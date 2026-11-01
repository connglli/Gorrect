#include "heap_capture.hpp"
#include "queue_wire.hpp"
#include <algorithm>
#include <fstream>
#include <iostream>
#include <stdexcept>

namespace {
using namespace gorrect;

void require(bool condition, const char *message) {
  if (!condition)
    throw std::runtime_error(message);
}

const std::vector<ClassInfo> graph_classes{{1, ClassKind::roots, "LGraph$Roots;", {}},
                                           {2, ClassKind::weak_reference, "LGraph$W;", {}}};
const std::vector<ClassInfo> queue_classes{
    {3, ClassKind::roots, "LCase$QueueRoots;", {{0, 8, "q1", "LCase$Queue;"}}},
    {4, ClassKind::object, "LCase$Queue;", {{0, 16, "idQueue", "I"}}},
    {5, ClassKind::object, "Ljava/lang/ref/ReferenceQueue;", {}},
    {6, ClassKind::reference_array, "[Ljava/lang/ref/Reference;", {}}};

const Record *object(const CaptureBuffer &buffer, jlong tag) {
  auto found =
      std::find_if(buffer.snapshot.records.begin(), buffer.snapshot.records.end(),
                   [tag](const Record &r) { return r.kind == RecordKind::object && r.a == tag; });
  return found == buffer.snapshot.records.end() ? nullptr : &*found;
}

void legacy_graph() {
  jlong next = 1, target = 0;
  HeapCapture capture(1, 2, 16, graph_classes, nullptr, next);
  jvmtiHeapReferenceInfo info{};
  info.stack_local.thread_id = 42;
  info.stack_local.depth = 4;
  info.stack_local.slot = 3;
  info.stack_local.method = reinterpret_cast<jmethodID>(std::uintptr_t{0x333});
  info.stack_local.location = 12;
  auto result = HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_STACK_LOCAL, &info, -2, 0, 32,
                                                &target, nullptr, -1, &capture);
  require(result == JVMTI_VISIT_OBJECTS && target == 1, "legacy graph tag/visit");
  require(capture.graph.snapshot.records.size() == 2 && !capture.queues, "legacy graph policy");
  const auto &root = capture.graph.snapshot.records.back();
  require(root.kind == RecordKind::root && root.a == 1 && root.c == 42 && root.d == 4 &&
              root.e == 3 && root.f == 0x333 && root.g == 12,
          "legacy root coordinates");
}

void helper_first_and_shared_tags() {
  jlong next = 1, helper = 0, wrapper = 0, alien = 0;
  HeapCapture capture(1, 2, 64, graph_classes, &queue_classes, next);
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_JNI_GLOBAL, nullptr, -6, 0, 40, &helper,
                                  nullptr, 3, &capture);
  auto owner = object(*capture.queues, helper);
  require(owner && owner->b == 6 && owner->d == 3, "helper metadata without queue discovery");
  require(capture.graph.snapshot.records.empty(), "helper remains opaque to graph");
  jvmtiHeapReferenceInfo info{};
  info.array.index = 1;
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT, &info, 0, -6, 32, &alien,
                                  &helper, -1, &capture);
  auto unknown = object(*capture.queues, alien);
  require(unknown && unknown->b == 0, "unknown active helper target remains visible");
  const auto &edge = capture.queues->snapshot.records.back();
  require(edge.kind == RecordKind::reference && edge.a == helper && edge.b == alien &&
              edge.d == 1 && edge.e == -6,
          "actual helper edge");
  info.array.index = 2;
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT, &info, -2, -6, 32, &wrapper,
                                  &helper, -1, &capture);
  require(object(capture.graph, wrapper) && object(*capture.queues, wrapper),
          "same physical wrapper joins both snapshots");
  require(capture.graph.snapshot.records.back().e == 0, "resource class opaque in graph edge");
  require(capture.queues->snapshot.records.back().e == -6, "resource keeps actual class tag");
}

void primitive_and_static_owners() {
  jlong next = 1, queue = 0, roots = -3, base = -5, sentinel = 0;
  HeapCapture capture(1, 2, 64, graph_classes, &queue_classes, next);
  jvmtiHeapReferenceInfo info{};
  info.field.index = 0;
  jvalue id{};
  id.i = 1;
  HeapCapture::primitive_callback(JVMTI_HEAP_REFERENCE_FIELD, &info, -4, &queue, id,
                                  JVMTI_PRIMITIVE_TYPE_INT, &capture);
  require(queue == 1 && capture.graph.snapshot.records.empty(), "resource primitive-first tag");
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_STATIC_FIELD, &info, -4, 0, 40, &queue,
                                  &roots, -1, &capture);
  require(object(*capture.queues, queue)->b == 4, "resource metadata joins primitive owner");
  require(std::any_of(capture.queues->snapshot.records.begin(),
                      capture.queues->snapshot.records.end(),
                      [](const Record &record) {
                        return record.kind == RecordKind::class_visit && record.a == 3;
                      }),
          "actual static field proves root owner visit");
  info.field.index = 1;
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_STATIC_FIELD, &info, 0, 0, 32, &sentinel,
                                  &base, -1, &capture);
  const auto &record = capture.queues->snapshot.records.back();
  require(record.kind == RecordKind::reference && record.a == -5 && record.b == sentinel &&
              record.d == 1,
          "actual bootstrap sentinel edge");
  require(capture.graph.snapshot.records.empty(), "resource statics remain opaque");
}

void caller_type_is_target_only() {
  auto metadata = queue_classes;
  metadata.push_back({7, ClassKind::object, "Ljava/lang/ref/ReferenceQueue$Lock;", {}});
  metadata.push_back({8, ClassKind::object, "Ljava/lang/Thread;", {}});
  jlong next = 1000, caller = 500, other = 501, wrapper = 20, mirror = -8;
  HeapCapture capture(1, 2, 64, graph_classes, &metadata, next, caller);
  jvmtiHeapReferenceInfo info{};
  info.field.index = 0;
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_FIELD, &info, -8, -2, 64, &other, &wrapper,
                                  -1, &capture);
  require(std::any_of(capture.queues->snapshot.records.begin(),
                      capture.queues->snapshot.records.end(),
                      [](const Record &r) {
                        return r.kind == RecordKind::reference && r.a == 20 && r.b == 501;
                      }),
          "unexpected incoming Thread target was suppressed");
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_FIELD, &info, -2, -8, 32, &wrapper, &other,
                                  -1, &capture);
  jvalue value{};
  value.i = 7;
  HeapCapture::primitive_callback(JVMTI_HEAP_REFERENCE_FIELD, &info, -8, &other, value,
                                  JVMTI_PRIMITIVE_TYPE_INT, &capture);
  info.jni_local.thread_id = 42;
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_JNI_LOCAL, &info, -8, 0, 64, &other, nullptr,
                                  -1, &capture);
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_CLASS, nullptr, 0, -8, 0, &mirror, &other,
                                  -1, &capture);
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_JNI_LOCAL, &info, -8, 0, 64, &caller,
                                  nullptr, -1, &capture);
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_CLASS, nullptr, 0, -8, 0, &mirror, &caller,
                                  -1, &capture);
  require(object(*capture.queues, caller) && object(*capture.queues, caller)->b == 8,
          "actual caller Thread type is unavailable");
  require(std::none_of(capture.queues->snapshot.records.begin(),
                       capture.queues->snapshot.records.end(),
                       [](const Record &r) {
                         return (r.a == 501 &&
                                 (r.kind == RecordKind::root || r.kind == RecordKind::reference ||
                                  r.kind == RecordKind::primitive_field));
                       }),
          "noncaller Thread supplied witness roots, class edges or outgoing fields");
  require(std::any_of(capture.queues->snapshot.records.begin(),
                      capture.queues->snapshot.records.end(),
                      [](const Record &r) {
                        return r.kind == RecordKind::reference && r.a == 500 && r.b == -8 &&
                               r.c == JVMTI_HEAP_REFERENCE_CLASS;
                      }),
          "actual caller lacks its specific class edge");
}

void independent_quota() {
  jlong next = 1, helper = 0;
  HeapCapture capture(1, 2, 8, graph_classes, &queue_classes, next);
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_JNI_GLOBAL, nullptr, -6, 0, 80, &helper,
                                  nullptr, 10, &capture);
  jvmtiHeapReferenceInfo info{};
  for (int i = 0; i < 10; ++i) {
    jlong target = 0;
    info.array.index = i;
    require(HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT, &info, 0, -6, 32,
                                            &target, &helper, -1, &capture) == JVMTI_VISIT_OBJECTS,
            "resource quota must continue traversal");
  }
  jlong wrapper = 0;
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_JNI_GLOBAL, nullptr, -2, 0, 32, &wrapper,
                                  nullptr, -1, &capture);
  capture.graph.snapshot.starts_before = 7;
  capture.graph.snapshot.finishes_before = 6;
  capture.graph.snapshot.starts_after = 8;
  capture.graph.snapshot.finishes_after = 8;
  capture.finish(JVMTI_ERROR_NONE, capability::tagging);
  require(capture.graph.snapshot.status == CaptureStatus::complete &&
              object(capture.graph, wrapper),
          "resource quota preserves graph capture");
  require(capture.queues->snapshot.status == CaptureStatus::record_limit &&
              capture.queues->snapshot.starts_before == 7 &&
              capture.queues->snapshot.finishes_before == 6 &&
              capture.queues->snapshot.starts_after == 8 &&
              capture.queues->snapshot.finishes_after == 8,
          "resource quota and same-walk counters");
}

void global_abort_and_resource_error() {
  jlong next = 1, first = 0, second = 0;
  HeapCapture capture(1, 2, 2, graph_classes, &queue_classes, next);
  HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_JNI_GLOBAL, nullptr, -2, 0, 32, &first,
                                  nullptr, -1, &capture);
  require(HeapCapture::reference_callback(JVMTI_HEAP_REFERENCE_JNI_GLOBAL, nullptr, -2, 0, 32,
                                          &second, nullptr, -1, &capture) == JVMTI_VISIT_ABORT,
          "graph quota aborts global traversal");
  capture.finish(JVMTI_ERROR_NONE, capability::tagging);
  require(!capture.queues->full && capture.graph.snapshot.status == CaptureStatus::record_limit &&
              capture.queues->snapshot.status == CaptureStatus::record_limit,
          "global abort invalidates unexhausted resource buffer");
  capture.finish(JVMTI_ERROR_OUT_OF_MEMORY, capability::tagging);
  require(capture.graph.snapshot.status == CaptureStatus::jvmti_error &&
              capture.queues->snapshot.status == CaptureStatus::jvmti_error &&
              capture.queues->snapshot.error == 110,
          "resource allocation error is not a quota");
}

void golden(const char *path) {
  std::ifstream input(path);
  std::string hex, token;
  while (input >> token)
    hex += token;
  require(hex.size() > 48 && hex.size() % 2 == 0, "queue golden fixture missing");
  std::vector<std::uint8_t> bytes;
  for (std::size_t i = 0; i < hex.size(); i += 2)
    bytes.push_back(static_cast<std::uint8_t>(std::stoul(hex.substr(i, 2), nullptr, 16)));
  std::vector<std::uint8_t> body(bytes.begin() + 16, bytes.end() - 8);
  require(encode_queue_body(body) == bytes, "Java/native queue envelope mismatch");
  Snapshot sample;
  sample.run = 1;
  sample.checkpoint = 2;
  auto normal = encode(sample);
  auto resources = encode_queue(sample);
  require(std::vector<std::uint8_t>(resources.begin() + 16, resources.end() - 8) == normal,
          "queue preserves inner snapshot encoding");
}
} // namespace

int main(int argc, char **argv) {
  try {
    require(argc == 2, "queue fixture argument");
    legacy_graph();
    helper_first_and_shared_tags();
    primitive_and_static_owners();
    caller_type_is_target_only();
    independent_quota();
    global_abort_and_resource_error();
    golden(argv[1]);
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
