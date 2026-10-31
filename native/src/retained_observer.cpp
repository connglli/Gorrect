#include "retained_observer.hpp"
#include <array>
#include <cstring>
#include <mutex>
#include <new>
#include <stdexcept>

namespace gorrect::retained {
namespace {
constexpr std::size_t max_class_bytes = 16U * 1024 * 1024;
constexpr jint max_methods = 256, max_participants = 17, max_frames = 64;
jvmtiEnv *ti = nullptr;
// Class callbacks can occur independently of capture, so this is separate from the heap-walk lock.
std::mutex metadata_mutex;
struct Certificate {
  RetainedStatus status = RetainedStatus::unavailable;
  jint error = 0;
  jlong epoch = 0, run = 0;
  jint flags = 0;
  bool loaded = false, prepared = false, bind_attempted = false, bound = false, finished = false;
  jweak loader = nullptr, type = nullptr;
  std::vector<std::uint8_t> bytes;
  std::vector<CertifiedMethod> methods;
} certificate;

template <typename T> class Local {
  JNIEnv *env_;
  T reference_;

public:
  Local(JNIEnv *env, T reference) : env_(env), reference_(reference) {}
  Local(const Local &) = delete;
  Local &operator=(const Local &) = delete;
  ~Local() {
    if (reference_)
      env_->DeleteLocalRef(reference_);
  }
  T get() const { return reference_; }
};
template <typename T> struct Allocation {
  T *data = nullptr;
  Allocation() = default;
  Allocation(const Allocation &) = delete;
  Allocation &operator=(const Allocation &) = delete;
  ~Allocation() {
    if (data)
      ti->Deallocate(reinterpret_cast<unsigned char *>(data));
  }
};
struct JvmtiFailure : std::runtime_error {
  jvmtiError error;
  explicit JvmtiFailure(jvmtiError value)
      : std::runtime_error("Retained JVMTI error " + std::to_string(value)), error(value) {}
};
void check(jvmtiError error) {
  if (error != JVMTI_ERROR_NONE)
    throw JvmtiFailure(error);
}
std::int64_t token(jmethodID method) {
  return static_cast<std::int64_t>(reinterpret_cast<std::uintptr_t>(method));
}
bool same(JNIEnv *env, jobject first, jobject second) {
  return env->IsSameObject(first, second) == JNI_TRUE;
}
std::string ascii(const char *text) {
  if (!text)
    throw std::runtime_error("Missing retained method metadata");
  std::string result(text);
  if (result.size() > 65535)
    throw std::length_error("Retained method metadata limit");
  for (auto character : result)
    if (static_cast<unsigned char>(character) > 127)
      throw std::runtime_error("Non-ASCII retained method metadata");
  return result;
}
// Error 110 represents native allocation failure as well as JVMTI-reported out of memory.
// Explicit capacity bounds use LIMIT/error0, so consumers can distinguish resource failure.
void invalidate(RetainedStatus status, jint error = 0) {
  ++certificate.epoch;
  // Keep the first causal failure rather than replacing it with a consequence on later queries.
  if (certificate.status == RetainedStatus::valid ||
      certificate.status == RetainedStatus::unavailable) {
    certificate.status = status;
    certificate.error = error;
  }
}
bool target(jclass type) {
  Allocation<char> signature;
  check(ti->GetClassSignature(type, &signature.data, nullptr));
  return signature.data && std::strcmp(signature.data, "LCase;") == 0;
}
bool weak_live(JNIEnv *env, jweak reference) { return reference && !same(env, reference, nullptr); }

// Re-query method ownership without keeping any strong class/loader JNI reference in native state.
void validate(JNIEnv *env) {
  if (!certificate.bound || certificate.status != RetainedStatus::valid)
    return;
  Local<jclass> type(env, static_cast<jclass>(env->NewLocalRef(certificate.type)));
  Local<jobject> loader(env, env->NewLocalRef(certificate.loader));
  if (!type.get() || !loader.get()) {
    if (env->ExceptionCheck())
      throw std::bad_alloc();
    invalidate(RetainedStatus::invalidated);
    return;
  }
  jobject actual_loader = nullptr;
  auto error = ti->GetClassLoader(type.get(), &actual_loader);
  Local<jobject> actual(env, actual_loader);
  if (error != JVMTI_ERROR_NONE) {
    invalidate(RetainedStatus::jvmti_error, error);
    return;
  }
  if (!same(env, loader.get(), actual.get())) {
    invalidate(RetainedStatus::invalidated);
    return;
  }
  for (const auto &method : certificate.methods) {
    jclass actual_owner = nullptr;
    error = ti->GetMethodDeclaringClass(
        reinterpret_cast<jmethodID>(static_cast<std::uintptr_t>(method.token)), &actual_owner);
    Local<jclass> owner(env, actual_owner);
    if (error != JVMTI_ERROR_NONE) {
      invalidate(RetainedStatus::jvmti_error, error);
      return;
    }
    if (!same(env, owner.get(), type.get())) {
      invalidate(RetainedStatus::invalidated);
      return;
    }
  }
}
CodeEvidence evidence(bool final) {
  CodeEvidence result;
  result.run = certificate.run;
  result.final = final;
  result.status = certificate.status;
  result.error = certificate.error;
  result.epoch = certificate.epoch;
  result.class_token = certificate.prepared ? 1 : 0;
  result.loader_token =
      certificate.loaded && !(certificate.flags & code_flag::bootstrap_loader) ? 1 : 0;
  result.flags = certificate.flags;
  if (!final) {
    result.bytes = certificate.bytes;
    result.methods = certificate.methods;
  }
  return result;
}
void sample(JNIEnv *env, FrameEvidence &frames, jobjectArray threads, bool before) {
  for (jsize index = 0; index < static_cast<jsize>(frames.participants.size()); ++index) {
    Local<jthread> thread(env, static_cast<jthread>(env->GetObjectArrayElement(threads, index)));
    if (env->ExceptionCheck())
      throw std::bad_alloc();
    std::array<jvmtiFrameInfo, max_frames + 1> buffer{};
    jint count = 0;
    auto error =
        ti->GetStackTrace(thread.get(), 0, static_cast<jint>(buffer.size()), buffer.data(), &count);
    if (error != JVMTI_ERROR_NONE) {
      frames.status = RetainedStatus::jvmti_error;
      frames.error = error;
      continue;
    }
    if (count < 0 || count > max_frames) {
      frames.status = RetainedStatus::limit;
      continue;
    }
    auto &destination =
        before ? frames.participants[index].before : frames.participants[index].after;
    destination.reserve(static_cast<std::size_t>(count));
    for (jint depth = 0; depth < count; ++depth)
      destination.push_back({token(buffer[depth].method), depth, buffer[depth].location});
  }
}
} // namespace

void initialize(jvmtiEnv *environment, bool bytecode_locations) {
  ti = environment;
  if (bytecode_locations)
    certificate.flags |= code_flag::jvmbci;
}
bool enabled() { return ti != nullptr; }

void JNICALL loaded(jvmtiEnv *, JNIEnv *env, jclass redefining, jobject loader, const char *name,
                    jobject, jint length, const unsigned char *data, jint *, unsigned char **) {
  bool relevant = name && std::strcmp(name, "Case") == 0;
  // An unnamed initial Case is rejected later as an unpaired preparation. A modification has a
  // mirror already, so it must invalidate the certificate even when the hook omits its name.
  try {
    if (!relevant && !name && redefining)
      relevant = target(redefining);
    if (!relevant)
      return;
    std::lock_guard<std::mutex> guard(metadata_mutex);
    ++certificate.epoch;
    if (certificate.loaded || redefining || certificate.finished ||
        certificate.status != RetainedStatus::unavailable) {
      invalidate(RetainedStatus::invalidated);
      return;
    }
    certificate.loaded = true;
    if (!loader)
      certificate.flags |= code_flag::bootstrap_loader;
    else {
      certificate.loader = env->NewWeakGlobalRef(loader);
      if (!certificate.loader)
        throw std::bad_alloc();
    }
    if (length <= 0 || static_cast<std::size_t>(length) > max_class_bytes) {
      invalidate(RetainedStatus::limit);
      return;
    }
    certificate.bytes.assign(data, data + length);
    certificate.status = RetainedStatus::valid;
  } catch (const JvmtiFailure &failure) {
    std::lock_guard<std::mutex> guard(metadata_mutex);
    invalidate(RetainedStatus::jvmti_error, failure.error);
  } catch (const std::bad_alloc &) {
    std::lock_guard<std::mutex> guard(metadata_mutex);
    invalidate(RetainedStatus::jvmti_error, JVMTI_ERROR_OUT_OF_MEMORY);
  } catch (...) {
    std::lock_guard<std::mutex> guard(metadata_mutex);
    invalidate(RetainedStatus::invalidated);
  }
}

void JNICALL prepared(jvmtiEnv *, JNIEnv *env, jthread, jclass type) {
  try {
    if (!target(type))
      return;
    std::lock_guard<std::mutex> guard(metadata_mutex);
    ++certificate.epoch;
    if (!certificate.loaded || certificate.prepared ||
        certificate.status != RetainedStatus::valid) {
      invalidate(RetainedStatus::invalidated);
      return;
    }
    jobject actual_loader = nullptr;
    check(ti->GetClassLoader(type, &actual_loader));
    Local<jobject> loader(env, actual_loader);
    bool bootstrap = certificate.flags & code_flag::bootstrap_loader;
    if (bootstrap
            ? loader.get() != nullptr
            : !weak_live(env, certificate.loader) || !same(env, certificate.loader, loader.get())) {
      invalidate(RetainedStatus::invalidated);
      return;
    }
    certificate.type = env->NewWeakGlobalRef(type);
    if (!certificate.type)
      throw std::bad_alloc();
    Allocation<jmethodID> methods;
    jint count = 0;
    check(ti->GetClassMethods(type, &count, &methods.data));
    if (count < 1 || count > max_methods) {
      invalidate(RetainedStatus::limit);
      return;
    }
    std::vector<CertifiedMethod> copied;
    copied.reserve(static_cast<std::size_t>(count));
    for (jint index = 0; index < count; ++index) {
      Allocation<char> name, descriptor;
      check(ti->GetMethodName(methods.data[index], &name.data, &descriptor.data, nullptr));
      jclass actual_owner = nullptr;
      check(ti->GetMethodDeclaringClass(methods.data[index], &actual_owner));
      Local<jclass> owner(env, actual_owner);
      if (!same(env, owner.get(), type)) {
        invalidate(RetainedStatus::invalidated);
        return;
      }
      copied.push_back({token(methods.data[index]), ascii(name.data), ascii(descriptor.data)});
    }
    certificate.methods = std::move(copied);
    certificate.prepared = true;
    certificate.flags |= code_flag::prepared;
  } catch (const JvmtiFailure &failure) {
    std::lock_guard<std::mutex> guard(metadata_mutex);
    invalidate(RetainedStatus::jvmti_error, failure.error);
  } catch (const std::bad_alloc &) {
    std::lock_guard<std::mutex> guard(metadata_mutex);
    invalidate(RetainedStatus::jvmti_error, JVMTI_ERROR_OUT_OF_MEMORY);
  } catch (...) {
    std::lock_guard<std::mutex> guard(metadata_mutex);
    invalidate(RetainedStatus::invalidated);
  }
}

CodeEvidence bind(JNIEnv *env, jlong run, jclass intended) {
  std::lock_guard<std::mutex> guard(metadata_mutex);
  if (!enabled() || run <= 0 || !intended || certificate.bind_attempted || certificate.finished)
    throw std::runtime_error("Invalid retained startup binding");
  certificate.bind_attempted = true;
  certificate.run = run;
  if (!certificate.prepared || !weak_live(env, certificate.type) ||
      !same(env, certificate.type, intended) || !(certificate.flags & code_flag::jvmbci)) {
    invalidate(RetainedStatus::invalidated);
    return evidence(false);
  }
  Local<jclass> loader_type(env, env->FindClass("java/lang/ClassLoader"));
  if (!loader_type.get())
    throw std::runtime_error("System class loader lookup failed");
  auto get_system = env->GetStaticMethodID(loader_type.get(), "getSystemClassLoader",
                                           "()Ljava/lang/ClassLoader;");
  if (!get_system)
    throw std::runtime_error("System class loader method unavailable");
  Local<jobject> system(env, env->CallStaticObjectMethod(loader_type.get(), get_system));
  if (env->ExceptionCheck())
    throw std::runtime_error("System class loader lookup failed");
  if (!system.get() || !weak_live(env, certificate.loader) ||
      !same(env, certificate.loader, system.get())) {
    invalidate(RetainedStatus::invalidated);
    return evidence(false);
  }
  certificate.bound = true;
  certificate.flags |= code_flag::intended_bound | code_flag::system_loader;
  validate(env);
  return evidence(false);
}
CodeEvidence finish(JNIEnv *env, jlong run, bool complete) {
  std::lock_guard<std::mutex> guard(metadata_mutex);
  if (!enabled() || run <= 0 || run != certificate.run || certificate.finished)
    throw std::runtime_error("Invalid retained final status request");
  validate(env);
  if (!complete || !certificate.bound)
    invalidate(RetainedStatus::invalidated);
  certificate.finished = true;
  return evidence(true);
}
FrameEvidence before(JNIEnv *env, jlong run, jlong checkpoint, jobjectArray threads,
                     jintArray workers, bool terminal) {
  if (!enabled() || run <= 0 || checkpoint <= 0 || !threads || !workers)
    throw std::runtime_error("Invalid retained capture request");
  jsize count = env->GetArrayLength(threads);
  if (count < 1 || count > max_participants || env->GetArrayLength(workers) != count ||
      (terminal && count != 1))
    throw std::runtime_error("Invalid retained participant count");
  std::array<jint, max_participants> ids{};
  env->GetIntArrayRegion(workers, 0, count, ids.data());
  if (env->ExceptionCheck())
    throw std::runtime_error("Cannot read retained participant IDs");
  Local<jclass> thread_type(env, env->FindClass("java/lang/Thread"));
  if (!thread_type.get())
    throw std::runtime_error("Thread type unavailable");
  auto get_id = env->GetMethodID(thread_type.get(), "getId", "()J");
  if (!get_id)
    throw std::runtime_error("Thread identity unavailable");
  jthread actual_current = nullptr;
  check(ti->GetCurrentThread(&actual_current));
  Local<jthread> current(env, actual_current);
  FrameEvidence result;
  result.run = run;
  result.checkpoint = checkpoint;
  for (jsize index = 0; index < count; ++index) {
    Local<jthread> thread(env, static_cast<jthread>(env->GetObjectArrayElement(threads, index)));
    Local<jclass> actual_type(env, thread.get() ? env->GetObjectClass(thread.get()) : nullptr);
    // The first provider admits the launcher's platform caller and our own plain Thread workers.
    // Reject subclasses, including virtual threads, instead of guessing their stack conventions.
    if (!thread.get() || !same(env, actual_type.get(), thread_type.get()) ||
        (index == 0 && (ids[index] != 0 || !same(env, current.get(), thread.get()))) ||
        (index != 0 && (ids[index] <= 0 || ids[index] > 64)))
      throw std::runtime_error("Invalid retained participant identity");
    auto id = env->CallNonvirtualLongMethod(thread.get(), thread_type.get(), get_id);
    if (env->ExceptionCheck())
      throw std::runtime_error("Cannot query retained thread identity");
    jint state = 0;
    check(ti->GetThreadState(thread.get(), &state));
    if (id <= 0 || !(state & JVMTI_THREAD_STATE_ALIVE) || state & JVMTI_THREAD_STATE_TERMINATED)
      throw std::runtime_error("Retained participant is not alive");
    for (const auto &old : result.participants)
      if (old.worker == ids[index] || old.thread == id)
        throw std::runtime_error("Duplicate retained participant");
    result.participants.push_back({ids[index],
                                   id,
                                   ids[index] != 0 ? FrameRole::worker
                                   : terminal      ? FrameRole::main
                                                   : FrameRole::execute,
                                   {},
                                   {}});
  }
  {
    std::lock_guard<std::mutex> guard(metadata_mutex);
    if (run != certificate.run || !certificate.bound || certificate.finished)
      throw std::runtime_error("Retained capture requires a bound active certificate");
    validate(env);
    result.before_epoch = certificate.epoch;
    result.status = certificate.status;
    result.error = certificate.error;
  }
  sample(env, result, threads, true);
  return result;
}
void after(JNIEnv *env, FrameEvidence &frames, jobjectArray threads) {
  sample(env, frames, threads, false);
  std::lock_guard<std::mutex> guard(metadata_mutex);
  validate(env);
  frames.after_epoch = certificate.epoch;
  if (certificate.status != RetainedStatus::valid) {
    frames.status = certificate.status;
    frames.error = certificate.error;
  } else if (frames.before_epoch != frames.after_epoch) {
    frames.status = RetainedStatus::invalidated;
  }
}
} // namespace gorrect::retained
