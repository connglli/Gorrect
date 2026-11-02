#include "heap_capture.hpp"
#include "native_roots.hpp"
#include "openj9_stack.hpp"
#include "queue_locks.hpp"
#include "queue_wire.hpp"
#include "retained_observer.hpp"
#include "snapshot_wire.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <jni.h>
#include <jvmti.h>
#include <mutex>
#include <set>
#include <stdexcept>
#include <type_traits>

namespace {
using namespace gorrect;
// Negative tags identify registered classes; ordinary objects use [1, array_base),
// and bound arrays use array_base + binding index + 1. These are not GIR object IDs.
constexpr std::size_t max_arrays = 4096;
constexpr jint max_records = 1'000'000;
jvmtiEnv *jvmti = nullptr;
jvmtiExtensionFunction extended_stacks = nullptr;
// Serializes registration, binding, and capture. Heap callbacks must never acquire this lock.
std::mutex control_mutex;
// Copied metadata and weak JVMTI tags retain no Java objects or classes.
std::vector<ClassInfo> classes;
// Resource-only classes follow the graph class-ID range without changing graph lookup.
std::vector<ClassInfo> queue_classes;
bool queue_setup_attempted = false;
// Immutable after release publication; acquisition reads no observer state before queue locks.
QueueLockConfig automatic_queue_config;
std::atomic<bool> automatic_queues{false};
// Published entries never move or change. Mutators can read primitive identity cells without
// taking the capture/registration lock or retaining a Java reference.
std::array<ArrayIdentity, max_arrays> arrays;
std::atomic<std::size_t> array_count{0};
jlong next_object_tag = 1;
std::atomic<jlong> gc_starts{0}, gc_finishes{0};
jlong capabilities = 0;
NativeRoots native_roots;
struct CompiledCode {
  jmethodID method = nullptr;
  const void *address = nullptr;
};
std::array<CompiledCode, 8192> compiled;
// VM compilation callbacks can run during capture, so they use a separate lock.
std::mutex compilation_mutex;
std::atomic<bool> compilation_overflow{false};

void check(jvmtiError error) {
  if (error != JVMTI_ERROR_NONE)
    throw std::runtime_error("JVMTI error " + std::to_string(error));
}
template <typename T> struct JvmtiMemory {
  T *data = nullptr;
  JvmtiMemory() = default;
  JvmtiMemory(const JvmtiMemory &) = delete;
  JvmtiMemory &operator=(const JvmtiMemory &) = delete;
  ~JvmtiMemory() {
    if (data)
      jvmti->Deallocate(reinterpret_cast<unsigned char *>(data));
  }
};
struct LocalTable {
  jint count = 0;
  jvmtiLocalVariableEntry *data = nullptr;
  LocalTable() = default;
  LocalTable(const LocalTable &) = delete;
  LocalTable &operator=(const LocalTable &) = delete;
  ~LocalTable() {
    if (!data)
      return;
    for (jint i = 0; i < count; ++i) {
      jvmti->Deallocate(reinterpret_cast<unsigned char *>(data[i].name));
      jvmti->Deallocate(reinterpret_cast<unsigned char *>(data[i].signature));
      if (data[i].generic_signature)
        jvmti->Deallocate(reinterpret_cast<unsigned char *>(data[i].generic_signature));
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char *>(data));
  }
};
std::string ascii(const char *value) {
  // ASCII avoids a modified-UTF-8 conversion between JVMTI metadata and standard UTF-8 wire
  // strings.
  std::string result = value ? value : "";
  for (unsigned char c : result)
    if (c > 127)
      throw std::runtime_error("Non-ASCII metadata is unsupported by this profile");
  return result;
}
std::string signature(jclass type) {
  JvmtiMemory<char> name;
  check(jvmti->GetClassSignature(type, &name.data, nullptr));
  return ascii(name.data);
}
void raise(JNIEnv *env, const char *type, const char *message) {
  if (env->ExceptionCheck())
    return;
  auto exception_class = env->FindClass(type);
  if (exception_class) {
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
  }
}
// No C++ exception may unwind through a JNI entry point.
template <typename Function> auto entry(JNIEnv *env, Function function) -> decltype(function()) {
  try {
    return function();
  } catch (const std::bad_alloc &) {
    raise(env, "java/lang/OutOfMemoryError", "Native observer allocation failed");
  } catch (const std::exception &error) {
    raise(env, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    raise(env, "java/lang/IllegalStateException", "Unknown native observer failure");
  }
  if constexpr (!std::is_void_v<decltype(function())>)
    return {};
}
void require_agent() {
  if (!jvmti)
    throw std::runtime_error("Observer requires -agentpath at JVM startup");
}
const ClassInfo *type_for(jlong tag) {
  if (tag >= 0 || tag < -static_cast<jlong>(classes.size()))
    return nullptr;
  return &classes[static_cast<std::size_t>(-tag - 1)];
}

// Resource setup distinguishes allocation failure from unsupported metadata/layout.
void queue_check(jvmtiError error) {
  if (error == JVMTI_ERROR_OUT_OF_MEMORY)
    throw std::bad_alloc();
  check(error);
}
std::string queue_signature(jclass type) {
  JvmtiMemory<char> name;
  queue_check(jvmti->GetClassSignature(type, &name.data, nullptr));
  return ascii(name.data);
}
jclass queue_class(JNIEnv *env, const char *name) {
  auto type = env->FindClass(name);
  if (!type)
    throw std::runtime_error("Queue metadata class is unavailable");
  return type;
}
struct LocalFrame {
  JNIEnv *env;
  explicit LocalFrame(JNIEnv *environment, jint capacity = 64) : env(environment) {
    if (env->PushLocalFrame(capacity) != JNI_OK)
      throw std::bad_alloc();
  }
  ~LocalFrame() { env->PopLocalFrame(nullptr); }
};

// Heap callback field indices concatenate declared fields from Object through the actual class.
// These roles deliberately reject interface contributions rather than guessing their indices.
ClassInfo queue_metadata(JNIEnv *env, jclass type, ClassKind kind, jint id) {
  ClassInfo metadata{id, kind, queue_signature(type), {}};
  jint status = 0;
  queue_check(jvmti->GetClassStatus(type, &status));
  if (!(status & (JVMTI_CLASS_STATUS_PREPARED | JVMTI_CLASS_STATUS_ARRAY)))
    throw std::runtime_error("Queue class is not prepared: " + metadata.signature);
  if (kind == ClassKind::reference_array)
    return metadata;
  std::vector<jclass> hierarchy;
  for (auto current = static_cast<jclass>(env->NewLocalRef(type)); current;
       current = env->GetSuperclass(current)) {
    hierarchy.push_back(current);
    if (hierarchy.size() > 4)
      throw std::runtime_error("Queue hierarchy is unsupported");
  }
  if (env->ExceptionCheck())
    throw std::bad_alloc();
  jint field_index = 0;
  for (auto level = hierarchy.rbegin(); level != hierarchy.rend(); ++level) {
    JvmtiMemory<jclass> interfaces;
    jint interface_count = 0;
    queue_check(jvmti->GetImplementedInterfaces(*level, &interface_count, &interfaces.data));
    if (interface_count != 0)
      throw std::runtime_error("Queue interfaces are unsupported");
    JvmtiMemory<jfieldID> fields;
    jint count = 0;
    queue_check(jvmti->GetClassFields(*level, &count, &fields.data));
    if (count < 0 || count > 1024 - field_index)
      throw std::runtime_error("Queue metadata capacity exhausted");
    for (jint i = 0; i < count; ++i) {
      JvmtiMemory<char> name, descriptor;
      jint modifiers = 0;
      queue_check(
          jvmti->GetFieldName(*level, fields.data[i], &name.data, &descriptor.data, nullptr));
      queue_check(jvmti->GetFieldModifiers(*level, fields.data[i], &modifiers));
      metadata.fields.push_back(
          {field_index++, modifiers, ascii(name.data), ascii(descriptor.data)});
    }
  }
  return metadata;
}

void JNICALL gc_start(jvmtiEnv *) { ++gc_starts; }
void JNICALL gc_finish(jvmtiEnv *) { ++gc_finishes; }
void JNICALL compiled_load(jvmtiEnv *, jmethodID method, jint, const void *address, jint,
                           const jvmtiAddrLocationMap *, const void *) {
  // Bounded storage avoids allocation in VM callbacks; overflow invalidates compilation evidence.
  std::lock_guard<std::mutex> lock(compilation_mutex);
  for (auto &code : compiled)
    if (code.address == nullptr || code.address == address) {
      code = {method, address};
      return;
    }
  compilation_overflow = true;
}
void JNICALL compiled_unload(jvmtiEnv *, jmethodID, const void *address) {
  std::lock_guard<std::mutex> lock(compilation_mutex);
  for (auto &code : compiled)
    if (code.address == address)
      code = {};
}
// Resolve names after the heap walk; these JVMTI queries and JNI locals are unsafe in heap
// callbacks.
std::vector<MethodInfo> methods(JNIEnv *env, const Snapshot &snapshot) {
  std::set<std::int64_t> tokens;
  for (const auto &record : snapshot.records)
    if (record.kind == RecordKind::root && record.f != 0)
      tokens.insert(record.f);
  std::vector<MethodInfo> result;
  for (auto token : tokens) {
    auto method = reinterpret_cast<jmethodID>(static_cast<std::uintptr_t>(token));
    JvmtiMemory<char> name, descriptor;
    jclass owner = nullptr;
    if (jvmti->GetMethodName(method, &name.data, &descriptor.data, nullptr) != JVMTI_ERROR_NONE)
      continue;
    if (jvmti->GetMethodDeclaringClass(method, &owner) != JVMTI_ERROR_NONE)
      continue;
    try {
      MethodInfo metadata{token, signature(owner), ascii(name.data), ascii(descriptor.data)};
      if ((capabilities & capability::local_variables) && metadata.owner == "LCase;") {
        LocalTable table;
        auto error = jvmti->GetLocalVariableTable(method, &table.count, &table.data);
        if (error == JVMTI_ERROR_NONE) {
          if (table.count < 0 || table.count > 65536)
            throw std::runtime_error("Local-variable metadata capacity exhausted");
          // Copy metadata only, outside callbacks; never acquire the locals themselves.
          for (jint i = 0; i < table.count; ++i) {
            auto &local = table.data[i];
            metadata.locals.push_back({local.start_location, local.length, local.slot,
                                       ascii(local.name), ascii(local.signature)});
          }
        }
      }
      result.push_back(std::move(metadata));
    } catch (...) {
      env->DeleteLocalRef(owner);
      throw;
    }
    env->DeleteLocalRef(owner);
  }
  return result;
}
// Frames bracket the heap walk itself. All metadata queries and byte encoding follow the walk.
Snapshot capture_snapshot(JNIEnv *env, jlong run, jlong checkpoint, jint capacity,
                          FrameEvidence *frames = nullptr, jobjectArray threads = nullptr,
                          Snapshot *resources = nullptr, jlong witness_thread = 0) {
  if (queue_setup_attempted && queue_classes.empty())
    throw std::runtime_error("Queue setup failed; observer cannot be reused");
  if (!queue_classes.empty() && !resources)
    throw std::runtime_error("Queue capture requires paired resource evidence");
  HeapCapture capture(run, checkpoint, capacity, classes,
                      queue_classes.empty() ? nullptr : &queue_classes, next_object_tag,
                      witness_thread);
  capture.graph.snapshot.starts_before = gc_starts.load();
  capture.graph.snapshot.finishes_before = gc_finishes.load();
  jvmtiHeapCallbacks callbacks{};
  callbacks.heap_reference_callback = HeapCapture::reference_callback;
  callbacks.primitive_field_callback = HeapCapture::primitive_callback;
  callbacks.array_primitive_value_callback = HeapCapture::array_callback;
  // Both policies see this same walk. Filtering by target would hide unexpected entries.
  auto error = jvmti->FollowReferences(0, nullptr, nullptr, &callbacks, &capture);
  capture.graph.snapshot.starts_after = gc_starts.load();
  capture.graph.snapshot.finishes_after = gc_finishes.load();
  if (frames)
    retained::after(env, *frames, threads);
  capture.finish(error,
                 capabilities | (compilation_overflow ? capability::compilation_overflow : 0));
  // Metadata copying, byte encoding and Java allocation stay outside heap callbacks.
  capture.graph.snapshot.classes = classes;
  capture.graph.snapshot.arrays.assign(
      arrays.begin(), arrays.begin() + array_count.load(std::memory_order_acquire));
  capture.graph.snapshot.methods = methods(env, capture.graph.snapshot);
  if (capture.queues) {
    capture.queues->snapshot.classes = classes;
    capture.queues->snapshot.classes.insert(capture.queues->snapshot.classes.end(),
                                            queue_classes.begin(), queue_classes.end());
    *resources = std::move(capture.queues->snapshot);
  }
  return std::move(capture.graph.snapshot);
}
jbyteArray java_bytes(JNIEnv *env, const std::vector<std::uint8_t> &bytes,
                      std::size_t limit = 128U * 1024 * 1024) {
  if (bytes.size() > limit)
    throw std::runtime_error("Observer byte capacity exhausted");
  auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (result)
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte *>(bytes.data()));
  return result;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) { return JNI_VERSION_1_8; }

extern "C" JNIEXPORT jlong JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_gcFinishes(JNIEnv *env, jclass) {
  return entry(env, []() -> jlong {
    require_agent();
    return (capabilities & capability::gc_events) ? gc_finishes.load() : -1;
  });
}
extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *) {
  try {
    void *environment = nullptr;
    if (vm->GetEnv(&environment, JVMTI_VERSION_1_2) != JNI_OK)
      return JNI_ERR;
    jvmti = static_cast<jvmtiEnv *>(environment);
    extended_stacks = openj9_stacks(jvmti);
    jvmtiCapabilities available{}, desired{};
    check(jvmti->GetPotentialCapabilities(&available));
    // Tagging is essential; missing GC or compilation events are reported as capability gaps.
    if (!available.can_tag_objects)
      return JNI_ERR;
    desired.can_tag_objects = 1;
    desired.can_get_monitor_info = available.can_get_monitor_info;
    std::string mode = options ? options : "";
    if (!mode.empty() && mode != "locals" && mode != "retained-class")
      return JNI_ERR;
    bool locals = mode == "locals";
    bool retained_mode = mode == "retained-class";
    jvmtiJlocationFormat location_format = JVMTI_JLOCATION_OTHER;
    bool bytecode_locations = jvmti->GetJLocationFormat(&location_format) == JVMTI_ERROR_NONE &&
                              location_format == JVMTI_JLOCATION_JVMBCI;
    if (retained_mode && !available.can_generate_all_class_hook_events)
      return JNI_ERR;
    desired.can_generate_all_class_hook_events = retained_mode;
    desired.can_access_local_variables =
        locals && bytecode_locations && available.can_access_local_variables;
    desired.can_generate_garbage_collection_events =
        available.can_generate_garbage_collection_events;
    desired.can_generate_compiled_method_load_events =
        available.can_generate_compiled_method_load_events;
    check(jvmti->AddCapabilities(&desired));
    if (retained_mode) {
      jvmtiCapabilities actual{};
      check(jvmti->GetCapabilities(&actual));
      if (!actual.can_generate_all_class_hook_events || actual.can_access_local_variables ||
          actual.can_get_bytecodes || actual.can_redefine_classes || actual.can_retransform_classes)
        return JNI_ERR;
      retained::initialize(jvmti, bytecode_locations);
    }
    capabilities =
        capability::tagging | capability::reference_fields |
        (desired.can_access_local_variables ? capability::local_variables : 0) |
        (desired.can_generate_garbage_collection_events ? capability::gc_events : 0) |
        (desired.can_generate_compiled_method_load_events ? capability::compilation_events : 0);
    jvmtiEventCallbacks callbacks{};
    callbacks.GarbageCollectionStart = gc_start;
    callbacks.GarbageCollectionFinish = gc_finish;
    callbacks.CompiledMethodLoad = compiled_load;
    callbacks.CompiledMethodUnload = compiled_unload;
    if (retained_mode) {
      callbacks.ClassFileLoadHook = retained::loaded;
      callbacks.ClassPrepare = retained::prepared;
    }
    check(jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)));
    if (retained_mode) {
      check(
          jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr));
      check(jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr));
    }
    if (desired.can_generate_garbage_collection_events) {
      check(jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START,
                                            nullptr));
      check(jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH,
                                            nullptr));
    }
    if (desired.can_generate_compiled_method_load_events) {
      check(
          jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_COMPILED_METHOD_LOAD, nullptr));
      check(jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_COMPILED_METHOD_UNLOAD,
                                            nullptr));
    }
    return JNI_OK;
  } catch (...) {
    return JNI_ERR;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_configure0(JNIEnv *env, jclass,
                                                                   jobjectArray types,
                                                                   jintArray kinds) {
  entry(env, [&] {
    require_agent();
    std::lock_guard<std::mutex> lock(control_mutex);
    if (!classes.empty())
      throw std::runtime_error("Observer is already configured");
    jsize count = env->GetArrayLength(types);
    if (count == 0 || count > 1024 || env->GetArrayLength(kinds) != count)
      throw std::runtime_error("Invalid class registration");
    std::vector<jint> values(static_cast<std::size_t>(count));
    env->GetIntArrayRegion(kinds, 0, count, values.data());
    std::vector<ClassInfo> pending;
    for (jsize index = 0; index < count; ++index) {
      auto type = static_cast<jclass>(env->GetObjectArrayElement(types, index));
      jint raw_kind = values[static_cast<std::size_t>(index)];
      if (raw_kind < static_cast<jint>(ClassKind::object) ||
          raw_kind > static_cast<jint>(ClassKind::weak_reference))
        throw std::runtime_error("Unknown class kind");
      jlong previous = 0;
      check(jvmti->GetTag(type, &previous));
      if (previous != 0)
        throw std::runtime_error("Class is already tagged");
      auto kind = static_cast<ClassKind>(raw_kind);
      ClassInfo metadata{index + 1, kind, signature(type), {}};
      for (const auto &old : pending)
        if (old.signature == metadata.signature)
          throw std::runtime_error("Duplicate registered class");
      if (kind == ClassKind::reference_array && metadata.signature != "[Ljava/lang/Object;")
        throw std::runtime_error("Reference arrays must use Object[]");
      if (kind == ClassKind::data_array && metadata.signature != "[J")
        throw std::runtime_error("Data arrays must use long[]");
      if (kind == ClassKind::soft_reference || kind == ClassKind::weak_reference) {
        auto parent = env->GetSuperclass(type);
        if (!parent || signature(parent) != (kind == ClassKind::soft_reference
                                                 ? "Ljava/lang/ref/SoftReference;"
                                                 : "Ljava/lang/ref/WeakReference;")) {
          env->DeleteLocalRef(parent);
          throw std::runtime_error(
              "Reference wrappers require direct SoftReference/WeakReference subclasses");
        }
        env->DeleteLocalRef(parent);
        std::vector<jclass> hierarchy;
        if (env->PushLocalFrame(32) != JNI_OK)
          throw std::bad_alloc();
        for (auto current = static_cast<jclass>(env->NewLocalRef(type)); current;
             current = env->GetSuperclass(current))
          hierarchy.push_back(current);
        // JVMTI indices include every declared field, starting at Object, even static fields.
        // The supported reference hierarchy has no interfaces contributing additional indices.
        jint field_index = 0;
        for (auto level = hierarchy.rbegin(); level != hierarchy.rend(); ++level) {
          JvmtiMemory<jclass> interfaces;
          jint interface_count = 0;
          check(jvmti->GetImplementedInterfaces(*level, &interface_count, &interfaces.data));
          if (interface_count != 0)
            throw std::runtime_error("Reference interfaces are unsupported");
          JvmtiMemory<jfieldID> fields;
          jint count_fields = 0;
          check(jvmti->GetClassFields(*level, &count_fields, &fields.data));
          for (jint i = 0; i < count_fields; ++i) {
            JvmtiMemory<char> name, descriptor;
            jint modifiers = 0;
            check(
                jvmti->GetFieldName(*level, fields.data[i], &name.data, &descriptor.data, nullptr));
            check(jvmti->GetFieldModifiers(*level, fields.data[i], &modifiers));
            metadata.fields.push_back(
                {field_index++, modifiers, ascii(name.data), ascii(descriptor.data)});
          }
        }
        env->PopLocalFrame(nullptr);
      }
      if (kind == ClassKind::object || kind == ClassKind::roots) {
        // This restriction makes GetClassFields order match heap callback field indices.
        // Supporting inheritance/interfaces requires resolving their JVMTI index contributions.
        auto parent = env->GetSuperclass(type);
        auto object = env->FindClass("java/lang/Object");
        bool direct = env->IsSameObject(parent, object);
        env->DeleteLocalRef(parent);
        env->DeleteLocalRef(object);
        JvmtiMemory<jclass> interfaces;
        jint interface_count = 0;
        check(jvmti->GetImplementedInterfaces(type, &interface_count, &interfaces.data));
        for (jint i = 0; i < interface_count; ++i)
          env->DeleteLocalRef(interfaces.data[i]);
        if (!direct || interface_count != 0)
          throw std::runtime_error(
              "This profile requires direct Object subclasses without interfaces");
        JvmtiMemory<jfieldID> fields;
        jint field_count = 0;
        check(jvmti->GetClassFields(type, &field_count, &fields.data));
        for (jint i = 0; i < field_count; ++i) {
          JvmtiMemory<char> name, descriptor;
          jint modifiers = 0;
          check(jvmti->GetFieldName(type, fields.data[i], &name.data, &descriptor.data, nullptr));
          check(jvmti->GetFieldModifiers(type, fields.data[i], &modifiers));
          metadata.fields.push_back({i, modifiers, ascii(name.data), ascii(descriptor.data)});
        }
      }
      pending.push_back(std::move(metadata));
      env->DeleteLocalRef(type);
    }
    // Tags are published only after the complete metadata set has been validated.
    for (jsize index = 0; index < count; ++index) {
      auto type = static_cast<jclass>(env->GetObjectArrayElement(types, index));
      check(jvmti->SetTag(type, -static_cast<jlong>(index + 1)));
      env->DeleteLocalRef(type);
    }
    classes = std::move(pending);
  });
}
extern "C" JNIEXPORT void JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_configureQueues0(JNIEnv *env, jclass,
                                                                         jclass roots,
                                                                         jclass queue_type,
                                                                         jboolean automatic) {
  entry(env, [&] {
    require_agent();
    std::lock_guard<std::mutex> lock(control_mutex);
    if (classes.empty() || queue_setup_attempted || !roots || !queue_type)
      throw std::runtime_error("Invalid or repeated queue registration");
    // A failed registration cannot reopen the graph-only path with partially tagged classes.
    queue_setup_attempted = true;
    LocalFrame locals(env);
    auto base = queue_class(env, "java/lang/ref/ReferenceQueue");
    auto helper = queue_class(env, "[Ljava/lang/ref/Reference;");
    auto object = queue_class(env, "java/lang/Object");
    auto parent = env->GetSuperclass(queue_type);
    if (env->ExceptionCheck())
      throw std::runtime_error("Queue parent is unavailable");
    auto roots_parent = env->GetSuperclass(roots);
    if (env->ExceptionCheck())
      throw std::runtime_error("Queue roots parent is unavailable");
    if (!env->IsSameObject(parent, base) || !env->IsSameObject(roots_parent, object))
      throw std::runtime_error("Queue resources require direct supported subclasses");
    std::vector<jclass> types{roots, queue_type, base, helper};
    std::vector<ClassKind> kinds{ClassKind::roots, ClassKind::object, ClassKind::object,
                                 ClassKind::reference_array};
    jclass mutation_lock = nullptr;
    if (automatic == JNI_TRUE) {
      jvmtiCapabilities actual{};
      queue_check(jvmti->GetCapabilities(&actual));
      if (!actual.can_get_monitor_info)
        throw std::runtime_error("Automatic queues require monitor ownership capability");
      mutation_lock = queue_class(env, "java/lang/ref/ReferenceQueue$Lock");
      if (!env->IsSameObject(env->GetSuperclass(mutation_lock), object))
        throw std::runtime_error("Automatic queue monitor class is unsupported");
      types.push_back(mutation_lock);
      kinds.push_back(ClassKind::object);
      auto thread = queue_class(env, "java/lang/Thread");
      jobject loader = nullptr;
      queue_check(jvmti->GetClassLoader(thread, &loader));
      if (loader) {
        env->DeleteLocalRef(loader);
        throw std::runtime_error("Automatic caller Thread class is not bootstrap-defined");
      }
      jint status = 0;
      queue_check(jvmti->GetClassStatus(thread, &status));
      if (!(status & JVMTI_CLASS_STATUS_PREPARED) ||
          !env->IsSameObject(env->GetSuperclass(thread), object))
        throw std::runtime_error("Automatic caller Thread class is unsupported");
      types.push_back(thread);
      kinds.push_back(ClassKind::object);
    }
    std::vector<ClassInfo> pending;
    pending.reserve(types.size());
    for (std::size_t i = 0; i < types.size(); ++i) {
      jlong previous = 0;
      queue_check(jvmti->GetTag(types[i], &previous));
      if (previous != 0)
        throw std::runtime_error("Queue resource class is already tagged");
      auto id = static_cast<jint>(classes.size() + i + 1);
      // The caller role supplies actual type/class evidence only, never Thread field coverage.
      auto metadata = automatic == JNI_TRUE && i == 5
                          ? ClassInfo{id, ClassKind::object, queue_signature(types[i]), {}}
                          : queue_metadata(env, types[i], kinds[i], id);
      for (const auto &old : classes)
        if (old.signature == metadata.signature)
          throw std::runtime_error("Queue class conflicts with graph registration");
      for (const auto &old : pending)
        if (old.signature == metadata.signature)
          throw std::runtime_error("Duplicate queue resource class");
      pending.push_back(std::move(metadata));
    }
    if (pending[0].fields.size() > 64)
      throw std::runtime_error("Queue resource capacity exhausted");
    auto identity = std::find_if(pending[1].fields.begin(), pending[1].fields.end(),
                                 [](const FieldInfo &field) {
                                   return field.name == "idQueue" && field.descriptor == "I" &&
                                          !(field.modifiers & 0x0008) && (field.modifiers & 0x0010);
                                 });
    if (identity == pending[1].fields.end())
      throw std::runtime_error("Queue identity field is unavailable");
    QueueLockConfig lock_config;
    if (automatic == JNI_TRUE) {
      auto role = std::find_if(pending[1].fields.begin(), pending[1].fields.end(),
                               [](const FieldInfo &field) {
                                 return field.name == "lock" &&
                                        field.descriptor == "Ljava/lang/ref/ReferenceQueue$Lock;" &&
                                        !(field.modifiers & 0x0008) && (field.modifiers & 0x0010);
                               });
      if (role == pending[1].fields.end() || !pending[4].fields.empty() ||
          pending[0].fields.empty())
        throw std::runtime_error("Automatic queue mutation-lock metadata is unsupported");
      lock_config.lock = env->GetFieldID(base, "lock", "Ljava/lang/ref/ReferenceQueue$Lock;");
      if (!lock_config.lock)
        throw std::runtime_error("Automatic queue mutation-lock field is unavailable");
      lock_config.roots_id = pending[0].id;
      lock_config.queue_id = pending[1].id;
      lock_config.lock_id = pending[4].id;
      lock_config.thread_id = pending[5].id;
      lock_config.fields.reserve(pending[0].fields.size());
      for (std::size_t i = 0; i < pending[0].fields.size(); ++i) {
        auto name = "q" + std::to_string(i + 1);
        auto field = std::find_if(
            pending[0].fields.begin(), pending[0].fields.end(), [&](const FieldInfo &candidate) {
              return candidate.name == name && candidate.descriptor == pending[1].signature &&
                     (candidate.modifiers & 0x0008);
            });
        if (field == pending[0].fields.end())
          throw std::runtime_error("Automatic queue root role is unavailable");
        auto id = env->GetStaticFieldID(roots, name.c_str(), pending[1].signature.c_str());
        if (!id)
          throw std::runtime_error("Automatic queue root field is unavailable");
        lock_config.fields.push_back({id, field->index});
      }
    }
    // Existing registered graph tags are never changed. Only these new role classes are tagged.
    for (std::size_t i = 0; i < types.size(); ++i)
      queue_check(jvmti->SetTag(types[i], -static_cast<jlong>(pending[i].id)));
    if (automatic == JNI_TRUE) {
      lock_config.roots = env->NewWeakGlobalRef(roots);
      if (!lock_config.roots)
        throw std::bad_alloc();
    }
    queue_classes = std::move(pending);
    if (automatic == JNI_TRUE) {
      automatic_queue_config = std::move(lock_config);
      automatic_queues.store(true, std::memory_order_release);
    }
  });
}

extern "C" JNIEXPORT void JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_bindArrayIdentity0(
    JNIEnv *env, jclass, jobject array, jstring shape, jint worker, jint site, jlong occurrence) {
  entry(env, [&] {
    require_agent();
    std::lock_guard<std::mutex> lock(control_mutex);
    if (worker < 0 || site < 0 || occurrence <= 0 || (site == 0 && worker != 0))
      throw std::runtime_error("Invalid array identity");
    auto type = env->GetObjectClass(array);
    jlong class_tag = 0;
    check(jvmti->GetTag(type, &class_tag));
    env->DeleteLocalRef(type);
    auto metadata = type_for(class_tag);
    if (!metadata ||
        (metadata->kind != ClassKind::reference_array && metadata->kind != ClassKind::data_array))
      throw std::runtime_error("Array class is not registered");
    const char *characters = env->GetStringUTFChars(shape, nullptr);
    if (!characters)
      throw std::bad_alloc();
    std::string name;
    try {
      name = ascii(characters);
    } catch (...) {
      env->ReleaseStringUTFChars(shape, characters);
      throw;
    }
    env->ReleaseStringUTFChars(shape, characters);
    jlong tag = 0;
    check(jvmti->GetTag(array, &tag));
    if (is_array_tag(tag)) {
      auto index = static_cast<std::size_t>(tag - array_base - 1);
      if (index >= array_count.load(std::memory_order_acquire))
        throw std::runtime_error("Unknown array identity tag");
      const auto &old = arrays[index];
      if (old.worker != worker || old.site != site || old.occurrence != occurrence ||
          old.shape != name)
        throw std::runtime_error("Array identity cannot change");
      return;
    }
    auto count = array_count.load(std::memory_order_relaxed);
    if (count == max_arrays)
      throw std::runtime_error("Array identity capacity exhausted");
    for (std::size_t index = 0; index < count; ++index) {
      const auto &old = arrays[index];
      if (old.worker == worker && old.site == site && old.occurrence == occurrence)
        throw std::runtime_error("Duplicate array identity");
    }
    tag = array_base + static_cast<jlong>(count + 1);
    // Store identity metadata only; keeping a JNI reference here would change array liveness.
    arrays[count] = {tag, std::move(name), worker, site, occurrence};
    check(jvmti->SetTag(array, tag));
    array_count.store(count + 1, std::memory_order_release);
  });
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_arrayIdentity0(JNIEnv *env, jclass,
                                                                       jobject array,
                                                                       jint component) {
  return entry(env, [&]() -> jlong {
    require_agent();
    if (!array || component < 0 || component > 2)
      throw std::runtime_error("Invalid array identity query");
    jlong tag = 0;
    check(jvmti->GetTag(array, &tag));
    if (tag <= array_base)
      throw std::runtime_error("Array has no diagnostic identity");
    auto index = static_cast<std::size_t>(tag - array_base - 1);
    if (index >= array_count.load(std::memory_order_acquire))
      throw std::runtime_error("Unknown array identity tag");
    const auto &identity = arrays[index];
    return component == 0 ? identity.worker : component == 1 ? identity.site : identity.occurrence;
  });
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_capture0(JNIEnv *env, jclass, jlong run,
                                                                 jlong checkpoint, jint capacity) {
  return entry(env, [&]() -> jbyteArray {
    require_agent();
    std::lock_guard<std::mutex> lock(control_mutex);
    if (classes.empty() || run <= 0 || checkpoint <= 0 || capacity <= 0 || capacity > max_records)
      throw std::runtime_error("Invalid capture request");
    if (retained::enabled())
      throw std::runtime_error("Retained capture requires participant frame evidence");
    return java_bytes(env, encode(capture_snapshot(env, run, checkpoint, capacity)));
  });
}
namespace {
struct QueueCaptureBundle {
  Snapshot graph, resources;
  FrameEvidence frames;
  std::vector<std::uint8_t> consistency;
};
QueueCaptureBundle capture_bundle(JNIEnv *env, jlong run, jlong checkpoint, jint capacity,
                                  jobjectArray threads = nullptr, jintArray workers = nullptr,
                                  bool terminal = false) {
  require_agent();
  QueueCaptureBundle result;
  // Temporary metadata/queue/lock/thread locals expire before returned Java arrays are allocated.
  LocalFrame locals(env, 2 * 64 + 16);
  std::optional<QueueLocks> custody;
  if (automatic_queues.load(std::memory_order_acquire)) {
    custody.emplace(env, jvmti, automatic_queue_config);
    custody->acquire();
  }
  {
    std::lock_guard<std::mutex> state(control_mutex);
    if (automatic_queues.load(std::memory_order_acquire) != custody.has_value())
      throw std::runtime_error("Queue capture policy changed before custody");
    if (classes.empty() || run <= 0 || checkpoint <= 0 || capacity <= 0 || capacity > max_records ||
        (!threads && (queue_classes.empty() || retained::enabled())))
      throw std::runtime_error("Invalid paired queue capture request");
    if (threads)
      result.frames = retained::before(env, run, checkpoint, threads, workers, terminal);
    if (custody)
      custody->before(next_object_tag);
    result.graph = capture_snapshot(
        env, run, checkpoint, capacity, threads ? &result.frames : nullptr, threads,
        queue_classes.empty() ? nullptr : &result.resources, custody ? custody->thread_tag() : 0);
    if (custody)
      custody->after(next_object_tag);
  }
  if (custody) {
    custody->release();
    result.consistency = custody->encode(result.graph);
  }
  return result;
}
} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_captureQueues0(JNIEnv *env, jclass,
                                                                       jlong run, jlong checkpoint,
                                                                       jint capacity) {
  return entry(env, [&]() -> jobjectArray {
    auto bundle = capture_bundle(env, run, checkpoint, capacity);
    auto byte_array_type = env->FindClass("[B");
    if (!byte_array_type)
      return nullptr;
    auto result = env->NewObjectArray(bundle.consistency.empty() ? 2 : 3, byte_array_type, nullptr);
    env->DeleteLocalRef(byte_array_type);
    if (!result)
      return nullptr;
    auto raw = java_bytes(env, encode(bundle.graph));
    if (!raw)
      return nullptr;
    env->SetObjectArrayElement(result, 0, raw);
    env->DeleteLocalRef(raw);
    if (env->ExceptionCheck())
      return nullptr;
    auto queue = java_bytes(env, encode_queue(bundle.resources), 128U * 1024 * 1024 + 24);
    if (!queue)
      return nullptr;
    env->SetObjectArrayElement(result, 1, queue);
    env->DeleteLocalRef(queue);
    if (!bundle.consistency.empty() && !env->ExceptionCheck()) {
      auto proof = java_bytes(env, bundle.consistency);
      if (!proof)
        return nullptr;
      env->SetObjectArrayElement(result, 2, proof);
      env->DeleteLocalRef(proof);
    }
    return env->ExceptionCheck() ? nullptr : result;
  });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_retainedMode0(JNIEnv *env, jclass) {
  return entry(env, []() -> jboolean {
    require_agent();
    return retained::enabled() ? JNI_TRUE : JNI_FALSE;
  });
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_bindCode0(JNIEnv *env, jclass, jlong run,
                                                                  jclass intended) {
  return entry(env, [&]() -> jbyteArray {
    require_agent();
    return java_bytes(env, encode(retained::bind(env, run, intended)));
  });
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_codeStatus0(JNIEnv *env, jclass, jlong run,
                                                                    jboolean complete) {
  return entry(env, [&]() -> jbyteArray {
    require_agent();
    return java_bytes(env, encode(retained::finish(env, run, complete == JNI_TRUE)));
  });
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_captureRetained0(
    JNIEnv *env, jclass, jlong run, jlong checkpoint, jint capacity, jobjectArray threads,
    jintArray workers, jboolean terminal) {
  return entry(env, [&]() -> jobjectArray {
    auto bundle =
        capture_bundle(env, run, checkpoint, capacity, threads, workers, terminal == JNI_TRUE);
    auto byte_array_type = env->FindClass("[B");
    if (!byte_array_type)
      return nullptr;
    auto result = env->NewObjectArray(queue_classes.empty()        ? 2
                                      : bundle.consistency.empty() ? 3
                                                                   : 4,
                                      byte_array_type, nullptr);
    env->DeleteLocalRef(byte_array_type);
    if (!result)
      return nullptr;
    auto raw = java_bytes(env, encode(bundle.graph));
    if (!raw)
      return nullptr;
    env->SetObjectArrayElement(result, 0, raw);
    env->DeleteLocalRef(raw);
    if (env->ExceptionCheck())
      return nullptr;
    auto sample = java_bytes(env, encode(bundle.frames));
    if (!sample)
      return nullptr;
    env->SetObjectArrayElement(result, 1, sample);
    env->DeleteLocalRef(sample);
    if (!queue_classes.empty() && !env->ExceptionCheck()) {
      auto queue = java_bytes(env, encode_queue(bundle.resources), 128U * 1024 * 1024 + 24);
      if (!queue)
        return nullptr;
      env->SetObjectArrayElement(result, 2, queue);
      env->DeleteLocalRef(queue);
    }
    if (!bundle.consistency.empty() && !env->ExceptionCheck()) {
      auto proof = java_bytes(env, bundle.consistency);
      if (!proof)
        return nullptr;
      env->SetObjectArrayElement(result, 3, proof);
      env->DeleteLocalRef(proof);
    }
    return env->ExceptionCheck() ? nullptr : result;
  });
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_capabilities0(JNIEnv *env, jclass) {
  return entry(env, [&]() -> jlong {
    require_agent();
    return capabilities | (compilation_overflow ? capability::compilation_overflow : 0);
  });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_wasCompiled0(JNIEnv *env, jclass,
                                                                     jobject method) {
  return entry(env, [&]() -> jboolean {
    require_agent();
    auto id = env->FromReflectedMethod(method);
    if (!id || compilation_overflow)
      return JNI_FALSE;
    std::lock_guard<std::mutex> lock(compilation_mutex);
    for (const auto &code : compiled)
      if (code.address && code.method == id)
        return JNI_TRUE;
    return JNI_FALSE;
  });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_openJ9Stack(JNIEnv *env, jclass) {
  return entry(env, [&]() -> jobjectArray {
    require_agent();
    if (!extended_stacks)
      return nullptr;
    std::array<OpenJ9Frame, 64> frames{};
    jint count = 0;
    // EXTRA_FRAME_INFO | MARK_INLINED_FRAMES; a null thread selects this JNI caller.
    check(extended_stacks(jvmti, jint{12}, static_cast<jthread>(nullptr), jint{0},
                          static_cast<jint>(frames.size()), frames.data(), &count));
    if (count < 0 || count > static_cast<jint>(frames.size()))
      throw std::runtime_error("Invalid OpenJ9 stack frame count");
    auto string_class = env->FindClass("java/lang/String");
    if (!string_class)
      return nullptr;
    auto result = env->NewObjectArray(count * 4, string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (!result)
      return nullptr;
    for (jint index = 0; index < count; ++index) {
      JvmtiMemory<char> name, descriptor;
      check(jvmti->GetMethodName(frames[index].method, &name.data, &descriptor.data, nullptr));
      jclass owner = nullptr;
      check(jvmti->GetMethodDeclaringClass(frames[index].method, &owner));
      std::string owner_signature;
      try {
        owner_signature = signature(owner);
      } catch (...) {
        env->DeleteLocalRef(owner);
        throw;
      }
      env->DeleteLocalRef(owner);
      std::array<std::string, 4> fields{owner_signature, ascii(name.data), ascii(descriptor.data),
                                        std::to_string(frames[index].type)};
      for (jint field = 0; field < 4; ++field) {
        auto value = env->NewStringUTF(fields[field].c_str());
        if (!value)
          return nullptr;
        env->SetObjectArrayElement(result, index * 4 + field, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck())
          return nullptr;
      }
    }
    return result;
  });
}

namespace {
void supported_native_operand(JNIEnv *env, jobject value, jclass expected = nullptr,
                              jstring nominal = nullptr) {
  if (!value)
    return;
  auto actual = env->GetObjectClass(value);
  if (!actual)
    throw std::runtime_error("Native root operand class unavailable");
  jlong class_tag = 0;
  auto error = jvmti->GetTag(actual, &class_tag);
  bool same = !expected || env->IsSameObject(actual, expected);
  env->DeleteLocalRef(actual);
  native_root_check(error);
  auto type = type_for(class_tag);
  if (!same || !type ||
      (type->kind != ClassKind::object && type->kind != ClassKind::reference_array &&
       type->kind != ClassKind::data_array))
    throw std::runtime_error("Unsupported native root operand shape");
  if (type->kind == ClassKind::reference_array || type->kind == ClassKind::data_array) {
    jlong tag = 0;
    native_root_check(jvmti->GetTag(value, &tag));
    if (!is_array_tag(tag) || static_cast<std::uint64_t>(tag - array_base - 1) >=
                                  array_count.load(std::memory_order_acquire))
      throw std::runtime_error("Native root array has no published nominal identity");
    if (nominal) {
      auto text = env->GetStringUTFChars(nominal, nullptr);
      if (!text)
        throw std::runtime_error("Native root nominal type unavailable");
      bool matches = arrays[static_cast<std::size_t>(tag - array_base - 1)].shape == text;
      env->ReleaseStringUTFChars(nominal, text);
      if (!matches)
        throw std::runtime_error("Native root array nominal shape differs");
    }
  }
}

struct NativeProbe {
  jlong tag;
  jint count = 0;
  static jint JNICALL reference(jvmtiHeapReferenceKind kind, const jvmtiHeapReferenceInfo *, jlong,
                                jlong, jlong, jlong *tag, jlong *, jint, void *data) {
    auto &probe = *static_cast<NativeProbe *>(data);
    if (kind == JVMTI_HEAP_REFERENCE_JNI_GLOBAL && tag && *tag == probe.tag)
      ++probe.count;
    return 0;
  }
};

void qualify_native_roots(JNIEnv *env) {
  LocalFrame locals(env);
  auto type = env->FindClass("java/lang/Object");
  if (!type)
    throw std::runtime_error("Native root qualification class unavailable");
  auto constructor = env->GetMethodID(type, "<init>", "()V");
  auto object = constructor ? env->NewObject(type, constructor) : nullptr;
  if (!object)
    throw std::bad_alloc();
  constexpr jlong probe_tag = (1LL << 61) - 1;
  native_root_check(jvmti->SetTag(object, probe_tag));
  jobject left = nullptr, right = nullptr;
  try {
    left = env->NewGlobalRef(object);
    if (!left || env->ExceptionCheck())
      throw std::bad_alloc();
    right = env->NewGlobalRef(object);
    if (!right || env->ExceptionCheck())
      throw std::bad_alloc();
    env->DeleteLocalRef(object);
    object = nullptr;
    for (int expected : {2, 1, 0}) {
      NativeProbe probe{probe_tag};
      jvmtiHeapCallbacks callbacks{};
      callbacks.heap_reference_callback = NativeProbe::reference;
      native_root_check(jvmti->FollowReferences(0, nullptr, nullptr, &callbacks, &probe));
      if (probe.count != expected)
        throw std::runtime_error("Native root callback multiplicity is unsupported");
      if (expected != 0) {
        auto current = expected == 2 ? left : right;
        jlong tag = 0;
        if (env->GetObjectRefType(current) != JNIGlobalRefType)
          throw std::runtime_error("Native root reference kind unavailable");
        native_root_check(jvmti->GetTag(current, &tag));
        if (tag != probe_tag)
          throw std::runtime_error("Native root target attribution unavailable");
        env->DeleteGlobalRef(current);
        if (expected == 2)
          left = nullptr;
        else
          right = nullptr;
      }
    }
  } catch (...) {
    if (left)
      env->DeleteGlobalRef(left);
    if (right)
      env->DeleteGlobalRef(right);
    throw;
  }
}

jobjectArray native_bundle_bytes(JNIEnv *env, const Snapshot &graph,
                                 const std::vector<std::uint8_t> &resources,
                                 const FrameEvidence *frames) {
  auto byte_array_type = env->FindClass("[B");
  if (!byte_array_type)
    return nullptr;
  auto result = env->NewObjectArray(frames ? 3 : 2, byte_array_type, nullptr);
  env->DeleteLocalRef(byte_array_type);
  if (!result)
    return nullptr;
  auto put = [&](jint index, const std::vector<std::uint8_t> &bytes) {
    auto value = java_bytes(env, bytes);
    if (!value)
      throw std::runtime_error("Native root result allocation failed");
    env->SetObjectArrayElement(result, index, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck())
      throw std::runtime_error("Native root result publication failed");
  };
  put(0, encode(graph));
  put(1, resources);
  if (frames)
    put(2, encode(*frames));
  return result;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_configureNativeRoots0(JNIEnv *env, jclass,
                                                                              jlong run, jint slots,
                                                                              jlong generations,
                                                                              jint records) {
  entry(env, [&]() {
    std::lock_guard<std::mutex> lock(control_mutex);
    require_agent();
    if (classes.empty() || queue_setup_attempted || records <= 0 || records > max_records)
      throw std::runtime_error(
          "Native roots require configured graph metadata and bounded records");
    native_roots.configure(run, slots, generations, records);
    try {
      qualify_native_roots(env);
    } catch (...) {
      native_roots.close(env, run);
      throw;
    }
  });
}

extern "C" JNIEXPORT void JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_writeNativeRoot0(JNIEnv *env, jclass,
                                                                         jlong run, jint slot,
                                                                         jobject value) {
  entry(env, [&]() {
    std::lock_guard<std::mutex> lock(control_mutex);
    native_roots.borrowed(run, slot);
    supported_native_operand(env, value);
    native_roots.write(env, run, slot, value);
  });
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_readNativeRoot0(JNIEnv *env, jclass,
                                                                        jlong run, jint slot,
                                                                        jclass type,
                                                                        jstring nominal) {
  return entry(env, [&]() -> jobject {
    std::lock_guard<std::mutex> lock(control_mutex);
    auto value = native_roots.borrowed(run, slot);
    if (!type || !nominal)
      throw std::runtime_error("Missing native root asserted shape");
    supported_native_operand(env, value, type, nominal);
    return native_roots.read(env, run, slot);
  });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_connglli_gorrect_runtime_observe_NativeObserver_captureNativeRoots0(
    JNIEnv *env, jclass, jlong run, jlong checkpoint, jint records, jint site, jlong visit,
    jobjectArray threads, jintArray workers, jboolean terminal, jboolean cleanup) {
  return entry(env, [&]() -> jobjectArray {
    Snapshot graph;
    FrameEvidence frames;
    std::vector<NativeRootRow> before, after;
    {
      std::lock_guard<std::mutex> lock(control_mutex);
      require_agent();
      if (checkpoint <= 0 || records <= 0 || records > max_records || site < 0 || visit <= 0 ||
          (cleanup == JNI_TRUE && (threads || workers)) ||
          ((threads == nullptr) != (workers == nullptr)) ||
          (cleanup == JNI_FALSE && retained::enabled() != (threads != nullptr)))
        throw std::runtime_error("Invalid native root capture request");
      native_roots.require(run);
      if (records < 2 * static_cast<jint>(native_roots.count()))
        throw std::runtime_error("Native root bracket record capacity exhausted");
      if (cleanup == JNI_TRUE)
        native_roots.close(env, run);
      before = native_roots.rows(env, jvmti, run, next_object_tag, records, cleanup == JNI_TRUE);
      if (threads)
        frames = retained::before(env, run, checkpoint, threads, workers, terminal == JNI_TRUE);
      graph = capture_snapshot(env, run, checkpoint, records, threads ? &frames : nullptr, threads);
      after = native_roots.rows(env, jvmti, run, next_object_tag, records, cleanup == JNI_TRUE);
    }
    auto resources = encode_native_roots(graph, site, visit, cleanup == JNI_TRUE,
                                         native_roots.limit(), before, after);
    return native_bundle_bytes(env, graph, resources, threads ? &frames : nullptr);
  });
}
