#include "openj9_stack.hpp"
#include "snapshot_wire.hpp"
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
constexpr jlong array_base = 1LL << 62;
constexpr std::size_t max_arrays = 4096;
constexpr jint max_records = 1'000'000;
jvmtiEnv *jvmti = nullptr;
jvmtiExtensionFunction extended_stacks = nullptr;
// Serializes registration, binding, and capture. Heap callbacks must never acquire this lock.
std::mutex control_mutex;
// Copied metadata and weak JVMTI tags retain no Java objects or classes.
std::vector<ClassInfo> classes;
std::vector<ArrayIdentity> arrays;
jlong next_object_tag = 1;
std::atomic<jlong> gc_starts{0}, gc_finishes{0};
jlong capabilities = 0;
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
std::int64_t method_token(jmethodID method) {
  return static_cast<std::int64_t>(reinterpret_cast<std::uintptr_t>(method));
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
bool is_array_tag(jlong tag) { return tag >= array_base; }
bool logical_type(const ClassInfo *type) {
  return type && (type->kind == ClassKind::object || type->kind == ClassKind::soft_reference ||
                  type->kind == ClassKind::weak_reference);
}

// Reserve all callback storage before FollowReferences. The callbacks neither allocate nor call
// JNI.
struct Capture {
  Snapshot snapshot;
  std::vector<bool> class_seen;
  std::vector<jlong> seen;
  std::size_t record_limit;
  bool full = false;
  Capture(jlong run, jlong checkpoint, jint capacity)
      : class_seen(classes.size(), false), record_limit(static_cast<std::size_t>(capacity)) {
    snapshot.run = run;
    snapshot.checkpoint = checkpoint;
    snapshot.records.reserve(static_cast<std::size_t>(capacity));
    // Reserve room for capacity objects plus the one that can trigger record exhaustion.
    std::size_t size = 1;
    while (size < static_cast<std::size_t>(capacity) * 2)
      size <<= 1;
    seen.resize(size, 0);
  }
  bool add(Record record) {
    if (snapshot.records.size() == record_limit) {
      full = true;
      return false;
    }
    snapshot.records.push_back(record);
    return true;
  }
  std::size_t position(jlong tag) const {
    auto index =
        static_cast<std::size_t>(static_cast<std::uint64_t>(tag) * 11400714819323198485ULL) &
        (seen.size() - 1);
    while (seen[index] != 0 && seen[index] != tag)
      index = (index + 1) & (seen.size() - 1);
    return index;
  }
  bool observed(jlong tag) const { return tag > 0 && seen[position(tag)] == tag; }
  jlong tag(jlong *pointer) {
    if (!pointer)
      return 0;
    // Keep tags stable across captures: some VMs reuse cached tags in primitive callbacks.
    if (*pointer != 0)
      return *pointer;
    if (next_object_tag >= array_base) {
      full = true;
      return 0;
    }
    *pointer = next_object_tag++;
    return *pointer;
  }
  void object(jlong *pointer, jlong class_tag, jlong size, jint length) {
    if (*pointer < 0) {
      auto index = static_cast<std::size_t>(-*pointer - 1);
      if (index < class_seen.size() && !class_seen[index]) {
        class_seen[index] = true;
        add({RecordKind::class_visit, static_cast<jlong>(index + 1), 0, 0, 0, 0, 0, 0});
      }
      return;
    }
    jlong physical = tag(pointer);
    if (physical == 0)
      return;
    auto index = position(physical);
    if (seen[index] == 0) {
      seen[index] = physical;
      auto type = type_for(class_tag);
      add({RecordKind::object, physical, type ? type->id : 0, size, length, 0, 0, 0});
    }
  }
};

jint JNICALL reference_callback(jvmtiHeapReferenceKind kind, const jvmtiHeapReferenceInfo *info,
                                jlong class_tag, jlong referrer_class_tag, jlong size,
                                jlong *target, jlong *source, jint length, void *user) {
  auto &capture = *static_cast<Capture *>(user);
  if (capture.full)
    return JVMTI_VISIT_ABORT;
  auto type = type_for(class_tag);
  auto owner = type_for(referrer_class_tag);
  auto root_class = source ? type_for(*source) : nullptr;
  bool target_workload = logical_type(type) || is_array_tag(*target);
  bool source_workload = logical_type(owner) || (source && is_array_tag(*source));
  bool root_field = kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD && root_class &&
                    root_class->kind == ClassKind::roots;
  if (*target < 0)
    capture.object(target, class_tag, size, length);
  // Skip irrelevant records, but keep traversing: foreign objects can lead back to the workload.
  if (!(target_workload || source_workload || root_field))
    return capture.full ? JVMTI_VISIT_ABORT : JVMTI_VISIT_OBJECTS;
  capture.object(target, class_tag, size, length);
  jlong target_tag = capture.tag(target);
  if (!source) {
    jlong thread = 0, depth = -1, slot = -1, method = 0, location = -1;
    if (kind == JVMTI_HEAP_REFERENCE_STACK_LOCAL && info) {
      thread = info->stack_local.thread_id;
      depth = info->stack_local.depth;
      slot = info->stack_local.slot;
      method = method_token(info->stack_local.method);
      location = info->stack_local.location;
    } else if (kind == JVMTI_HEAP_REFERENCE_JNI_LOCAL && info) {
      thread = info->jni_local.thread_id;
      depth = info->jni_local.depth;
      method = method_token(info->jni_local.method);
    }
    capture.add({RecordKind::root, target_tag, kind, thread, depth, slot, method, location});
  } else {
    jlong index = -1;
    if (info && (kind == JVMTI_HEAP_REFERENCE_FIELD || kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD))
      index = info->field.index;
    if (info && kind == JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT)
      index = info->array.index;
    capture.add({RecordKind::reference, capture.tag(source), target_tag, kind, index,
                 referrer_class_tag, 0, 0});
  }
  return capture.full ? JVMTI_VISIT_ABORT : JVMTI_VISIT_OBJECTS;
}
std::int64_t primitive_bits(jvalue value, jvmtiPrimitiveType type) {
  switch (type) {
  case JVMTI_PRIMITIVE_TYPE_BOOLEAN:
    return value.z;
  case JVMTI_PRIMITIVE_TYPE_BYTE:
    return value.b;
  case JVMTI_PRIMITIVE_TYPE_CHAR:
    return value.c;
  case JVMTI_PRIMITIVE_TYPE_SHORT:
    return value.s;
  case JVMTI_PRIMITIVE_TYPE_INT:
    return value.i;
  case JVMTI_PRIMITIVE_TYPE_LONG:
    return value.j;
  case JVMTI_PRIMITIVE_TYPE_FLOAT: {
    std::uint32_t bits;
    std::memcpy(&bits, &value.f, sizeof(bits));
    return bits;
  }
  case JVMTI_PRIMITIVE_TYPE_DOUBLE: {
    std::int64_t bits;
    std::memcpy(&bits, &value.d, sizeof(bits));
    return bits;
  }
  default:
    return 0;
  }
}
jint JNICALL primitive_callback(jvmtiHeapReferenceKind kind, const jvmtiHeapReferenceInfo *info,
                                jlong class_tag, jlong *object, jvalue value,
                                jvmtiPrimitiveType type, void *user) {
  auto &capture = *static_cast<Capture *>(user);
  if (capture.full)
    return JVMTI_VISIT_ABORT;
  auto metadata =
      kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD ? type_for(*object) : type_for(class_tag);
  // A primitive callback can precede the object's reference callback; assign its owner tag here.
  if (logical_type(metadata) || (metadata && metadata->kind == ClassKind::roots))
    capture.add({RecordKind::primitive_field, capture.tag(object), info->field.index, type,
                 primitive_bits(value, type), class_tag, 0, 0});
  return capture.full ? JVMTI_VISIT_ABORT : 0;
}
jint JNICALL array_callback(jlong class_tag, jlong, jlong *object, jint count,
                            jvmtiPrimitiveType type, const void *values, void *user) {
  auto &capture = *static_cast<Capture *>(user);
  if (capture.full)
    return JVMTI_VISIT_ABORT;
  if (!(capture.observed(*object) || is_array_tag(*object)))
    return 0;
  // The marker distinguishes a visited empty array from missing primitive-array evidence.
  capture.add({RecordKind::array_values, *object, count, type, 0, 0, 0, 0});
  if (type == JVMTI_PRIMITIVE_TYPE_LONG) {
    auto data = static_cast<const jlong *>(values);
    for (jint index = 0; index < count && !capture.full; ++index)
      capture.add({RecordKind::array_element, *object, index, type, data[index], class_tag, 0, 0});
  }
  return capture.full ? JVMTI_VISIT_ABORT : 0;
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
      result.push_back({token, signature(owner), ascii(name.data), ascii(descriptor.data)});
    } catch (...) {
      env->DeleteLocalRef(owner);
      throw;
    }
    env->DeleteLocalRef(owner);
  }
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
extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *, void *) {
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
    desired.can_generate_garbage_collection_events =
        available.can_generate_garbage_collection_events;
    desired.can_generate_compiled_method_load_events =
        available.can_generate_compiled_method_load_events;
    check(jvmti->AddCapabilities(&desired));
    capabilities =
        capability::tagging | capability::reference_fields |
        (desired.can_generate_garbage_collection_events ? capability::gc_events : 0) |
        (desired.can_generate_compiled_method_load_events ? capability::compilation_events : 0);
    jvmtiEventCallbacks callbacks{};
    callbacks.GarbageCollectionStart = gc_start;
    callbacks.GarbageCollectionFinish = gc_finish;
    callbacks.CompiledMethodLoad = compiled_load;
    callbacks.CompiledMethodUnload = compiled_unload;
    check(jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)));
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
    arrays.reserve(max_arrays);
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
      const auto &old = arrays.at(static_cast<std::size_t>(tag - array_base - 1));
      if (old.worker != worker || old.site != site || old.occurrence != occurrence ||
          old.shape != name)
        throw std::runtime_error("Array identity cannot change");
      return;
    }
    if (arrays.size() == max_arrays)
      throw std::runtime_error("Array identity capacity exhausted");
    for (const auto &old : arrays)
      if (old.worker == worker && old.site == site && old.occurrence == occurrence)
        throw std::runtime_error("Duplicate array identity");
    tag = array_base + static_cast<jlong>(arrays.size() + 1);
    // Store identity metadata only; keeping a JNI reference here would change array liveness.
    arrays.push_back({tag, std::move(name), worker, site, occurrence});
    auto error = jvmti->SetTag(array, tag);
    if (error != JVMTI_ERROR_NONE) {
      arrays.pop_back();
      check(error);
    }
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
    Capture capture(run, checkpoint, capacity);
    capture.snapshot.starts_before = gc_starts.load();
    capture.snapshot.finishes_before = gc_finishes.load();
    jvmtiHeapCallbacks callbacks{};
    callbacks.heap_reference_callback = reference_callback;
    callbacks.primitive_field_callback = primitive_callback;
    callbacks.array_primitive_value_callback = array_callback;
    // Filtering by target class would hide unexpected references to foreign objects.
    // No GC is requested here; the VM may still collect or materialize objects to perform the walk.
    auto error = jvmti->FollowReferences(0, nullptr, nullptr, &callbacks, &capture);
    capture.snapshot.starts_after = gc_starts.load();
    capture.snapshot.finishes_after = gc_finishes.load();
    capture.snapshot.status = error != JVMTI_ERROR_NONE ? CaptureStatus::jvmti_error
                              : capture.full            ? CaptureStatus::record_limit
                                                        : CaptureStatus::complete;
    capture.snapshot.error = error;
    capture.snapshot.capabilities =
        capabilities | (compilation_overflow ? capability::compilation_overflow : 0);
    // Allocate metadata, encoded bytes, and the Java result only after callbacks have finished.
    capture.snapshot.classes = classes;
    capture.snapshot.arrays = arrays;
    capture.snapshot.methods = methods(env, capture.snapshot);
    auto bytes = encode(capture.snapshot);
    if (bytes.size() > 128U * 1024 * 1024)
      throw std::runtime_error("Snapshot byte capacity exhausted");
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!result)
      return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte *>(bytes.data()));
    return result;
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
