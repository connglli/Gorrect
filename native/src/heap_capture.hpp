#pragma once

#include "snapshot_wire.hpp"
#include <cstring>
#include <jni.h>
#include <jvmti.h>
#include <optional>

namespace gorrect {

// Published array identities occupy a different range from ordinary physical tags.
constexpr jlong array_base = 1LL << 62;
inline bool is_array_tag(jlong tag) { return tag >= array_base; }
inline bool logical_type(const ClassInfo *type) {
  return type && (type->kind == ClassKind::object || type->kind == ClassKind::soft_reference ||
                  type->kind == ClassKind::weak_reference);
}
inline bool reference_type(const ClassInfo *type) {
  return type &&
         (type->kind == ClassKind::soft_reference || type->kind == ClassKind::weak_reference);
}

// Storage is reserved before FollowReferences. No callback allocates, locks or calls JNI.
struct CaptureBuffer {
  Snapshot snapshot;
  std::vector<bool> class_seen;
  std::vector<jlong> seen;
  std::size_t record_limit;
  bool full = false;

  CaptureBuffer(jlong run, jlong checkpoint, jint capacity, std::size_t class_count)
      : class_seen(class_count, false), record_limit(static_cast<std::size_t>(capacity)) {
    snapshot.run = run;
    snapshot.checkpoint = checkpoint;
    snapshot.records.reserve(record_limit);
    // Space for every record-producing object and the object that exhausts the record quota.
    std::size_t size = 1;
    while (size < record_limit * 2)
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

  jlong tag(jlong *pointer, jlong &next_tag) {
    if (!pointer)
      return 0;
    // Existing weak tags stay stable across capture and primitive callback ordering.
    if (*pointer != 0)
      return *pointer;
    if (next_tag >= array_base) {
      full = true;
      return 0;
    }
    *pointer = next_tag++;
    return *pointer;
  }

  void object(jlong *pointer, const ClassInfo *type, jlong size, jint length, jlong &next_tag) {
    if (*pointer < 0) {
      auto index = static_cast<std::uint64_t>(-(*pointer + 1));
      if (index < class_seen.size() && !class_seen[static_cast<std::size_t>(index)]) {
        class_seen[static_cast<std::size_t>(index)] = true;
        add({RecordKind::class_visit, static_cast<jlong>(index + 1), 0, 0, 0, 0, 0, 0});
      }
      return;
    }
    jlong physical = tag(pointer, next_tag);
    if (physical == 0)
      return;
    auto index = position(physical);
    if (seen[index] == 0) {
      seen[index] = physical;
      add({RecordKind::object, physical, type ? type->id : 0, size, length, 0, 0, 0});
    }
  }
};

inline std::int64_t primitive_bits(jvalue value, jvmtiPrimitiveType type) {
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

/** One traversal with independent graph/resource quotas and a shared physical tag namespace. */
class HeapCapture {
  enum class ResourceRole { none, roots, queue, bootstrap, helper, caller };
  const std::vector<ClassInfo> &classes_;
  const std::vector<ClassInfo> *resources_;
  jlong &next_tag_;
  jlong witness_thread_;

  const ClassInfo *type(jlong tag, bool resources) const {
    if (tag >= 0)
      return nullptr;
    auto index = static_cast<std::uint64_t>(-(tag + 1));
    if (index < classes_.size())
      return &classes_[static_cast<std::size_t>(index)];
    index -= classes_.size();
    if (resources && resources_ && index < resources_->size())
      return &(*resources_)[static_cast<std::size_t>(index)];
    return nullptr;
  }

  ResourceRole role(jlong tag) const {
    if (tag >= 0 || !resources_)
      return ResourceRole::none;
    auto index = static_cast<std::uint64_t>(-(tag + 1));
    if (index < classes_.size() || index - classes_.size() >= resources_->size())
      return ResourceRole::none;
    // The first four roles retain their wire order; automatic lock metadata appends helpers.
    auto resource = index - classes_.size();
    if ((*resources_)[resource].signature == "Ljava/lang/Thread;")
      return ResourceRole::caller;
    return resource >= 3 ? ResourceRole::helper : static_cast<ResourceRole>(resource + 1);
  }

  bool full_resource(jlong tag) const {
    auto value = role(tag);
    return value != ResourceRole::none && value != ResourceRole::caller;
  }

  void reference(CaptureBuffer &capture, bool resource, jvmtiHeapReferenceKind kind,
                 const jvmtiHeapReferenceInfo *info, jlong class_tag, jlong referrer_class_tag,
                 jlong size, jlong *target, jlong *source, jint length) {
    auto target_type = type(class_tag, resource);
    auto owner = type(referrer_class_tag, resource);
    auto root_class = source ? type(*source, resource) : nullptr;
    bool target_kept = logical_type(target_type) || is_array_tag(*target);
    bool source_kept = logical_type(owner) || (source && is_array_tag(*source));
    bool root_field = kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD && root_class &&
                      root_class->kind == ClassKind::roots;
    if (resource) {
      // Role admission applies to targets as well as outgoing edges: helper-first discovery must
      // retain object/class/length evidence before any convenient queue edge is encountered.
      bool caller_class = kind == JVMTI_HEAP_REFERENCE_CLASS && source &&
                          *source == witness_thread_ &&
                          role(referrer_class_tag) == ResourceRole::caller;
      // Only the actual caller contributes Thread class evidence. Preserve unexpected incoming
      // edges from selected owners, but never follow Thread fields/statics or retain other roots.
      if ((role(referrer_class_tag) == ResourceRole::caller ||
           (source && role(*source) == ResourceRole::caller)) &&
          !caller_class)
        return;
      target_kept = reference_type(target_type) || full_resource(class_tag) ||
                    (witness_thread_ > 0 && *target == witness_thread_);
      source_kept = reference_type(owner) || full_resource(referrer_class_tag) || caller_class;
      root_field = kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD && source && full_resource(*source);
    }
    if (*target < 0)
      capture.object(target, target_type, size, length, next_tag_);
    if (!(target_kept || source_kept || root_field))
      return;
    if (resource && root_field)
      capture.object(source, root_class, 0, -1, next_tag_);
    capture.object(target, target_type, size, length, next_tag_);
    jlong target_tag = capture.tag(target, next_tag_);
    if (!source) {
      jlong thread = 0, depth = -1, slot = -1, method = 0, location = -1;
      if (kind == JVMTI_HEAP_REFERENCE_STACK_LOCAL && info) {
        thread = info->stack_local.thread_id;
        depth = info->stack_local.depth;
        slot = info->stack_local.slot;
        method = static_cast<jlong>(reinterpret_cast<std::uintptr_t>(info->stack_local.method));
        location = info->stack_local.location;
      } else if (kind == JVMTI_HEAP_REFERENCE_JNI_LOCAL && info) {
        thread = info->jni_local.thread_id;
        depth = info->jni_local.depth;
        method = static_cast<jlong>(reinterpret_cast<std::uintptr_t>(info->jni_local.method));
      }
      capture.add({RecordKind::root, target_tag, kind, thread, depth, slot, method, location});
    } else {
      jlong index = -1;
      if (info && (kind == JVMTI_HEAP_REFERENCE_FIELD || kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD))
        index = info->field.index;
      if (info && kind == JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT)
        index = info->array.index;
      // Resource-only class tags are opaque in the ordinary graph dictionary.
      jlong owner_tag =
          !resource && role(referrer_class_tag) != ResourceRole::none ? 0 : referrer_class_tag;
      capture.add({RecordKind::reference, capture.tag(source, next_tag_), target_tag, kind, index,
                   owner_tag, 0, 0});
    }
  }

public:
  CaptureBuffer graph;
  std::optional<CaptureBuffer> queues;

  HeapCapture(jlong run, jlong checkpoint, jint capacity, const std::vector<ClassInfo> &classes,
              const std::vector<ClassInfo> *resources, jlong &next_tag, jlong witness_thread = 0)
      : classes_(classes), resources_(resources), next_tag_(next_tag),
        witness_thread_(witness_thread), graph(run, checkpoint, capacity, classes.size()) {
    if (resources_)
      queues.emplace(run, checkpoint, capacity, classes.size() + resources_->size());
  }

  /** A graph abort or global JVMTI failure invalidates both buffers; local quota affects one. */
  void finish(jvmtiError error, jlong capabilities) {
    graph.snapshot.status = error != JVMTI_ERROR_NONE ? CaptureStatus::jvmti_error
                            : graph.full              ? CaptureStatus::record_limit
                                                      : CaptureStatus::complete;
    graph.snapshot.error = error;
    graph.snapshot.capabilities = capabilities;
    if (queues) {
      auto &snapshot = queues->snapshot;
      snapshot.status = error != JVMTI_ERROR_NONE    ? CaptureStatus::jvmti_error
                        : graph.full || queues->full ? CaptureStatus::record_limit
                                                     : CaptureStatus::complete;
      snapshot.error = error;
      snapshot.capabilities = capabilities;
      snapshot.starts_before = graph.snapshot.starts_before;
      snapshot.finishes_before = graph.snapshot.finishes_before;
      snapshot.starts_after = graph.snapshot.starts_after;
      snapshot.finishes_after = graph.snapshot.finishes_after;
    }
  }

  static jint JNICALL reference_callback(jvmtiHeapReferenceKind kind,
                                         const jvmtiHeapReferenceInfo *info, jlong class_tag,
                                         jlong referrer_class_tag, jlong size, jlong *target,
                                         jlong *source, jint length, void *user) {
    auto &walk = *static_cast<HeapCapture *>(user);
    if (walk.graph.full)
      return JVMTI_VISIT_ABORT;
    walk.reference(walk.graph, false, kind, info, class_tag, referrer_class_tag, size, target,
                   source, length);
    if (walk.graph.full)
      return JVMTI_VISIT_ABORT;
    if (walk.queues && !walk.queues->full)
      walk.reference(*walk.queues, true, kind, info, class_tag, referrer_class_tag, size, target,
                     source, length);
    return JVMTI_VISIT_OBJECTS;
  }

  static jint JNICALL primitive_callback(jvmtiHeapReferenceKind kind,
                                         const jvmtiHeapReferenceInfo *info, jlong class_tag,
                                         jlong *object, jvalue value, jvmtiPrimitiveType type,
                                         void *user) {
    auto &walk = *static_cast<HeapCapture *>(user);
    if (walk.graph.full)
      return JVMTI_VISIT_ABORT;
    auto metadata =
        walk.type(kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD ? *object : class_tag, false);
    if (logical_type(metadata) || (metadata && metadata->kind == ClassKind::roots))
      walk.graph.add({RecordKind::primitive_field, walk.graph.tag(object, walk.next_tag_),
                      info->field.index, type, primitive_bits(value, type), class_tag, 0, 0});
    if (walk.graph.full)
      return JVMTI_VISIT_ABORT;
    if (walk.queues && !walk.queues->full) {
      jlong owner = kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD ? *object : class_tag;
      metadata = walk.type(owner, true);
      if (reference_type(metadata) || walk.full_resource(owner)) {
        if (*object < 0)
          walk.queues->object(object, metadata, 0, -1, walk.next_tag_);
        walk.queues->add({RecordKind::primitive_field, walk.queues->tag(object, walk.next_tag_),
                          info->field.index, type, primitive_bits(value, type), class_tag, 0, 0});
      }
    }
    return 0;
  }

  static jint JNICALL array_callback(jlong class_tag, jlong, jlong *object, jint count,
                                     jvmtiPrimitiveType type, const void *values, void *user) {
    auto &walk = *static_cast<HeapCapture *>(user);
    auto &capture = walk.graph;
    if (capture.full)
      return JVMTI_VISIT_ABORT;
    if (!(capture.observed(*object) || is_array_tag(*object)))
      return 0;
    capture.add({RecordKind::array_values, *object, count, type, 0, 0, 0, 0});
    if (type == JVMTI_PRIMITIVE_TYPE_LONG) {
      auto data = static_cast<const jlong *>(values);
      for (jint index = 0; index < count && !capture.full; ++index)
        capture.add(
            {RecordKind::array_element, *object, index, type, data[index], class_tag, 0, 0});
    }
    return capture.full ? JVMTI_VISIT_ABORT : 0;
  }
};

} // namespace gorrect
