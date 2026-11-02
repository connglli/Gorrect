#ifndef GORRECT_NATIVE_ROOTS_HPP
#define GORRECT_NATIVE_ROOTS_HPP

#include "heap_capture.hpp"
#include <array>
#include <cstdint>
#include <jni.h>
#include <jvmti.h>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace gorrect {
inline void native_root_check(jvmtiError error) {
  if (error == JVMTI_ERROR_OUT_OF_MEMORY)
    throw std::bad_alloc();
  if (error != JVMTI_ERROR_NONE)
    throw std::runtime_error("Native root JVMTI failure " + std::to_string(error));
}

constexpr std::size_t max_native_slots = 64;
constexpr jlong max_native_generations = 100'000;

struct NativeRootRow {
  jint slot;
  jlong generation;
  std::uintptr_t handle;
  jint kind;
  jlong tag;
};

// The caller serializes the table. Its slots alone retain workload objects.
class NativeRoots {
  struct Slot {
    jobject reference = nullptr;
    jlong generation = 0;
  };
  std::array<Slot, max_native_slots> slots_{};
  jlong run_ = 0, generation_ = 0, limit_ = 0;
  std::size_t count_ = 0;
  bool attempted_ = false, closed_ = false;

  Slot &slot(jlong run, jint index) {
    require(run);
    if (index < 0 || static_cast<std::size_t>(index) >= count_)
      throw std::runtime_error("Invalid native root slot");
    return slots_[static_cast<std::size_t>(index)];
  }

public:
  void configure(jlong run, jint count, jlong limit, jint records) {
    if (attempted_)
      throw std::runtime_error("Native root setup cannot be repeated");
    attempted_ = true;
    if (run <= 0 || count <= 0 || static_cast<std::size_t>(count) > max_native_slots ||
        limit <= 0 || limit > max_native_generations || records < 2 * count)
      throw std::runtime_error("Invalid native root resource bound");
    run_ = run;
    count_ = static_cast<std::size_t>(count);
    limit_ = limit;
  }

  bool selected() const { return run_ != 0; }
  jlong limit() const { return limit_; }
  std::size_t count() const { return count_; }

  void require(jlong run) const {
    if (!selected() || closed_ || run != run_)
      throw std::runtime_error("Stale or cross-run native root access");
  }

  jobject borrowed(jlong run, jint index) { return slot(run, index).reference; }

  void write(JNIEnv *env, jlong run, jint index, jobject value) {
    auto &destination = slot(run, index);
    if (value && generation_ == limit_)
      throw std::runtime_error("Native root acquisition bound exhausted");
    // Acquire before publishing. A null return is failure even if the VM set no exception.
    auto replacement = value ? env->NewGlobalRef(value) : nullptr;
    if (value && (!replacement || env->ExceptionCheck())) {
      if (replacement)
        env->DeleteGlobalRef(replacement);
      throw std::bad_alloc();
    }
    auto previous = destination.reference;
    destination.reference = replacement;
    if (replacement)
      destination.generation = ++generation_;
    if (previous)
      env->DeleteGlobalRef(previous);
  }

  jobject read(JNIEnv *env, jlong run, jint index) {
    auto value = slot(run, index).reference;
    auto result = value ? env->NewLocalRef(value) : nullptr;
    if (value && (!result || env->ExceptionCheck())) {
      if (result)
        env->DeleteLocalRef(result);
      throw std::bad_alloc();
    }
    return result;
  }

  std::vector<NativeRootRow> rows(JNIEnv *env, jvmtiEnv *jvmti, jlong run, jlong &next_tag,
                                  jint records, bool closed = false) const {
    if (run != run_ || !selected() || closed_ != closed || records < 2 * static_cast<jint>(count_))
      throw std::runtime_error("Invalid native root capture ownership or capacity");
    std::vector<NativeRootRow> result;
    result.reserve(count_);
    for (std::size_t i = 0; i < count_; ++i) {
      const auto &source = slots_[i];
      jint kind = source.reference ? env->GetObjectRefType(source.reference) : JNIInvalidRefType;
      jlong tag = 0;
      if (source.reference) {
        if (kind != JNIGlobalRefType)
          throw std::runtime_error("Native root reference kind unavailable");
        native_root_check(jvmti->GetTag(source.reference, &tag));
        if (!tag) {
          if (next_tag <= 0 || next_tag >= array_base)
            throw std::runtime_error("Native root target tag bound exhausted");
          tag = next_tag++;
          native_root_check(jvmti->SetTag(source.reference, tag));
        }
        if (tag <= 0)
          throw std::runtime_error("Invalid native root target tag");
      }
      result.push_back({static_cast<jint>(i), source.generation,
                        reinterpret_cast<std::uintptr_t>(source.reference), kind, tag});
    }
    return result;
  }

  void close(JNIEnv *env, jlong run) {
    require(run);
    // Mark closed first so a failed post-delete walk cannot reopen or reuse this run.
    closed_ = true;
    for (std::size_t i = 0; i < count_; ++i) {
      if (slots_[i].reference)
        env->DeleteGlobalRef(slots_[i].reference);
      slots_[i].reference = nullptr;
    }
  }
};

inline std::vector<std::uint8_t> encode_native_roots(const Snapshot &graph, jint site, jlong visit,
                                                     bool cleanup, jlong limit,
                                                     const std::vector<NativeRootRow> &before,
                                                     const std::vector<NativeRootRow> &after) {
  WireWriter out;
  out.integer(0x474e5231);
  out.integer(1);
  out.number(graph.run);
  out.number(graph.checkpoint);
  out.integer(site);
  out.number(visit);
  out.bytes({static_cast<std::uint8_t>(cleanup)});
  out.number(limit);
  out.number(graph.starts_before);
  out.number(graph.finishes_before);
  out.number(graph.starts_after);
  out.number(graph.finishes_after);
  out.integer(static_cast<std::uint32_t>(before.size()));
  for (const auto *rows : {&before, &after})
    for (const auto &row : *rows) {
      out.integer(row.slot);
      out.number(row.generation);
      out.number(row.handle);
      out.integer(row.kind);
      out.number(row.tag);
    }
  out.number(0x454e444e524f4f31ULL);
  return out.finish();
}
} // namespace gorrect
#endif
