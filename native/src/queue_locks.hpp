#pragma once

#include "snapshot_wire.hpp"
#include <jni.h>
#include <jvmti.h>
#include <stdexcept>

namespace gorrect {

// Temporary queue/lock locals only. No wrapper/referent value or persistent object registry.
struct QueueLockRole {
  jfieldID field;
  jint index;
};
struct QueueLockConfig {
  jweak roots = nullptr;
  jfieldID lock = nullptr;
  jint roots_id = 0, queue_id = 0, lock_id = 0, thread_id = 0;
  std::vector<QueueLockRole> fields;
};
struct QueueLockWitness {
  jint resource = 0, roots_class = 0, root_field = 0, kind = 1;
  jlong queue = 0, lock = 0, owner_before = 0, owner_after = 0;
  jint entries_before = 0, entries_after = 0;
};

/** Acquisition precedes observer serialization; every acquired monitor is unwound in reverse. */
class MonitorCustody {
  JNIEnv *env_;
  const std::vector<jobject> &locks_;
  std::vector<jint> &cleanup_;
  std::size_t acquired_ = 0;

public:
  MonitorCustody(JNIEnv *env, const std::vector<jobject> &locks, std::vector<jint> &cleanup)
      : env_(env), locks_(locks), cleanup_(cleanup) {}
  MonitorCustody(const MonitorCustody &) = delete;
  MonitorCustody &operator=(const MonitorCustody &) = delete;
  ~MonitorCustody() { release(); }

  void acquire() {
    for (auto lock : locks_) {
      auto status = env_->MonitorEnter(lock);
      if (status == JNI_OK)
        ++acquired_;
      if (status != JNI_OK || env_->ExceptionCheck())
        throw std::runtime_error("Queue monitor acquisition failed");
    }
  }

  // Preserve an original exception, but attempt all exits even if an individual exit fails.
  void release() noexcept {
    if (acquired_ == 0)
      return;
    auto original = env_->ExceptionOccurred();
    if (original)
      env_->ExceptionClear();
    while (acquired_ != 0) {
      --acquired_;
      auto status = env_->MonitorExit(locks_[acquired_]);
      if (env_->ExceptionCheck()) {
        status = JNI_ERR;
        env_->ExceptionClear();
      }
      cleanup_[acquired_] = status;
    }
    if (original) {
      env_->Throw(original);
      env_->DeleteLocalRef(original);
    }
  }
};

/** Actual custody certificate; all JNI/JVMTI queries occur outside heap callbacks. */
class QueueLocks {
  JNIEnv *env_;
  jvmtiEnv *ti_;
  const QueueLockConfig &config_;
  std::vector<jobject> queues_, locks_;
  std::vector<jint> cleanup_;
  std::vector<QueueLockWitness> rows_;
  MonitorCustody custody_;
  jthread current_ = nullptr;
  jlong thread_ = 0, thread_tag_ = 0;

  void check(jvmtiError error) {
    if (error != JVMTI_ERROR_NONE)
      throw std::runtime_error("Queue custody JVMTI error " + std::to_string(error));
  }
  jlong tag(jobject object, jlong &next) {
    if (!object)
      return 0;
    jlong value = 0;
    check(ti_->GetTag(object, &value));
    if (value == 0) {
      if (next >= (1LL << 62))
        throw std::runtime_error("Queue custody tag capacity exhausted");
      value = next++;
      check(ti_->SetTag(object, value));
    }
    if (value <= 0 || value >= (1LL << 62))
      throw std::runtime_error("Invalid queue custody object tag");
    return value;
  }
  void sample(bool after, jlong &next) {
    for (std::size_t i = 0; i < locks_.size(); ++i) {
      jvmtiMonitorUsage usage{};
      check(ti_->GetObjectMonitorUsage(locks_[i], &usage));
      // Keep actual JVMTI counts; never manufacture a JNI depth from a Java-frame metric.
      // Always dispose ownership/waiter results, including if tagging the owner fails.
      try {
        auto owner = tag(usage.owner, next);
        if (after) {
          rows_[i].owner_after = owner;
          rows_[i].entries_after = usage.entry_count;
        } else {
          rows_[i].owner_before = owner;
          rows_[i].entries_before = usage.entry_count;
        }
      } catch (...) {
        dispose(usage);
        throw;
      }
      dispose(usage);
    }
  }
  void dispose(jvmtiMonitorUsage &usage) noexcept {
    if (usage.owner)
      env_->DeleteLocalRef(usage.owner);
    for (jint i = 0; i < usage.waiter_count; ++i)
      env_->DeleteLocalRef(usage.waiters[i]);
    for (jint i = 0; i < usage.notify_waiter_count; ++i)
      env_->DeleteLocalRef(usage.notify_waiters[i]);
    if (usage.waiters)
      ti_->Deallocate(reinterpret_cast<unsigned char *>(usage.waiters));
    if (usage.notify_waiters)
      ti_->Deallocate(reinterpret_cast<unsigned char *>(usage.notify_waiters));
  }

public:
  QueueLocks(JNIEnv *env, jvmtiEnv *ti, const QueueLockConfig &config)
      : env_(env), ti_(ti), config_(config), custody_(env, locks_, cleanup_) {}

  /** Resolve immutable roles and reserve storage before the first workload monitor is acquired. */
  void acquire() {
    auto roots = static_cast<jclass>(env_->NewLocalRef(config_.roots));
    if (!roots)
      throw std::runtime_error("Automatic queue roots are unavailable");
    jlong roots_tag = 0;
    check(ti_->GetTag(roots, &roots_tag));
    if (roots_tag != -config_.roots_id)
      throw std::runtime_error("Automatic queue roots class changed");
    queues_.reserve(config_.fields.size());
    locks_.reserve(config_.fields.size());
    rows_.resize(config_.fields.size());
    cleanup_.assign(config_.fields.size(), JNI_ERR);
    for (const auto &role : config_.fields) {
      auto queue = env_->GetStaticObjectField(roots, role.field);
      if (!queue || env_->ExceptionCheck())
        throw std::runtime_error("Automatic queue resource is unavailable");
      auto type = env_->GetObjectClass(queue);
      jlong type_tag = 0;
      check(ti_->GetTag(type, &type_tag));
      env_->DeleteLocalRef(type);
      if (type_tag != -config_.queue_id)
        throw std::runtime_error("Automatic queue resource has an unqualified class");
      auto lock = env_->GetObjectField(queue, config_.lock);
      if (!lock || env_->ExceptionCheck())
        throw std::runtime_error("Automatic queue mutation lock is unavailable");
      type = env_->GetObjectClass(lock);
      check(ti_->GetTag(type, &type_tag));
      env_->DeleteLocalRef(type);
      if (type_tag != -config_.lock_id)
        throw std::runtime_error("Automatic queue mutation lock has an unqualified class");
      queues_.push_back(queue);
      locks_.push_back(lock);
    }
    custody_.acquire();
  }

  /** Called only after all monitors and the observer state mutex have been acquired. */
  void before(jlong &next) {
    check(ti_->GetCurrentThread(&current_));
    auto thread_type = env_->FindClass("java/lang/Thread");
    auto actual_type = current_ ? env_->GetObjectClass(current_) : nullptr;
    if (!thread_type || !actual_type || !env_->IsSameObject(thread_type, actual_type))
      throw std::runtime_error("Automatic capture requires an actual platform Thread");
    jlong type_tag = 0;
    check(ti_->GetTag(actual_type, &type_tag));
    if (type_tag != -config_.thread_id)
      throw std::runtime_error("Automatic caller Thread class provenance changed");
    auto get_id = env_->GetMethodID(thread_type, "getId", "()J");
    if (!get_id)
      throw std::runtime_error("Automatic capture caller identity is unavailable");
    thread_ = env_->CallNonvirtualLongMethod(current_, thread_type, get_id);
    if (thread_ <= 0 || env_->ExceptionCheck())
      throw std::runtime_error("Automatic capture caller identity failed");
    thread_tag_ = tag(current_, next);
    for (std::size_t i = 0; i < rows_.size(); ++i) {
      rows_[i].resource = static_cast<jint>(i + 1);
      rows_[i].roots_class = config_.roots_id;
      rows_[i].root_field = config_.fields[i].index;
      rows_[i].queue = tag(queues_[i], next);
      rows_[i].lock = tag(locks_[i], next);
    }
    sample(false, next);
  }
  jlong thread_tag() const { return thread_tag_; }
  void after(jlong &next) { sample(true, next); }
  void release() noexcept { custody_.release(); }

  /** Encoding follows explicit reverse cleanup; failed outcomes remain diagnostic rows. */
  std::vector<std::uint8_t> encode(const Snapshot &graph) const {
    WireWriter out;
    out.integer(0x47514c31);
    out.integer(1);
    out.number(graph.run);
    out.number(graph.checkpoint);
    out.number(thread_);
    out.number(thread_tag_);
    out.number(graph.starts_before);
    out.number(graph.finishes_before);
    out.number(graph.starts_after);
    out.number(graph.finishes_after);
    out.integer(static_cast<std::uint32_t>(rows_.size()));
    for (std::size_t i = 0; i < rows_.size(); ++i) {
      const auto &row = rows_[i];
      out.integer(row.resource);
      out.integer(row.roots_class);
      out.integer(row.root_field);
      out.integer(row.kind);
      out.number(row.queue);
      out.number(row.lock);
      out.number(row.owner_before);
      out.number(row.owner_after);
      out.integer(row.entries_before);
      out.integer(row.entries_after);
      out.integer(cleanup_[i]);
    }
    out.number(0x454e44514c4f4331ULL);
    return out.finish();
  }
};
} // namespace gorrect
