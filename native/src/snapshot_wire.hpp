#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace gorrect {

// Wire data owns copied values only: no JNI references or dereferenceable object addresses.
// Mirrors SnapshotCodec; the shared golden fixture checks the cross-language contract.
constexpr std::uint32_t snapshot_magic = 0x47525331;
constexpr std::uint32_t snapshot_version = 1;
constexpr std::uint64_t snapshot_end = 0x454e44534e415031ULL;

// Values match HeapSnapshot's enums and flags. Keep version 1 wire numbers unchanged.
enum class ClassKind : std::int32_t {
  object = 0,
  reference_array = 1,
  data_array = 2,
  roots = 3,
  soft_reference = 4,
  weak_reference = 5
};
enum class RecordKind : std::int64_t {
  object = 0,
  reference = 1,
  primitive_field = 2,
  root = 3,
  array_values = 4,
  array_element = 5,
  class_visit = 6
};
enum class CaptureStatus : std::int32_t { complete = 0, record_limit = 1, jvmti_error = 2 };
namespace capability {
constexpr std::int64_t tagging = 1, gc_events = 2, compilation_events = 4, compilation_overflow = 8,
                       reference_fields = 16, local_variables = 32;
}

struct FieldInfo {
  std::int32_t index, modifiers;
  std::string name, descriptor;
};

struct ClassInfo {
  std::int32_t id;
  ClassKind kind;
  std::string signature;
  std::vector<FieldInfo> fields;
};

struct ArrayIdentity {
  std::int64_t tag;
  std::string shape;
  std::int32_t worker, site;
  std::int64_t occurrence;
};

struct LocalVariable {
  std::int64_t start;
  std::int32_t length, slot;
  std::string name, descriptor;
};

struct MethodInfo {
  std::int64_t token;
  std::string owner, name, descriptor;
  std::vector<LocalVariable> locals{};
};

// Numeric kind and cell meanings are defined in docs/observation.md.
struct Record {
  RecordKind kind;
  std::int64_t a, b, c, d, e, f, g;
};

struct Snapshot {
  std::int64_t run = 0, checkpoint = 0;
  CaptureStatus status = CaptureStatus::complete;
  std::int32_t error = 0;
  std::int64_t capabilities = 0;
  std::int64_t starts_before = 0, finishes_before = 0, starts_after = 0, finishes_after = 0;
  std::vector<ClassInfo> classes;
  std::vector<ArrayIdentity> arrays;
  std::vector<MethodInfo> methods;
  std::vector<Record> records;
};

class WireWriter {
  std::vector<std::uint8_t> bytes_;

public:
  void integer(std::uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8)
      bytes_.push_back((value >> shift) & 0xff);
  }
  void number(std::uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8)
      bytes_.push_back((value >> shift) & 0xff);
  }
  void string(const std::string &value) {
    integer(static_cast<std::uint32_t>(value.size()));
    bytes_.insert(bytes_.end(), value.begin(), value.end());
  }
  std::vector<std::uint8_t> finish() { return std::move(bytes_); }
};

// Serialization allocates and must run outside the heap walk, including for partial captures.
inline std::vector<std::uint8_t> encode(const Snapshot &snapshot) {
  WireWriter out;
  out.integer(snapshot_magic);
  out.integer(snapshot_version);
  out.number(snapshot.run);
  out.number(snapshot.checkpoint);
  out.integer(static_cast<std::uint32_t>(snapshot.status));
  out.integer(snapshot.error);
  out.number(snapshot.capabilities);
  out.number(snapshot.starts_before);
  out.number(snapshot.finishes_before);
  out.number(snapshot.starts_after);
  out.number(snapshot.finishes_after);
  out.integer(static_cast<std::uint32_t>(snapshot.classes.size()));
  for (const auto &type : snapshot.classes) {
    out.integer(type.id);
    out.integer(static_cast<std::uint32_t>(type.kind));
    out.string(type.signature);
    out.integer(static_cast<std::uint32_t>(type.fields.size()));
    for (const auto &field : type.fields) {
      out.integer(field.index);
      out.integer(field.modifiers);
      out.string(field.name);
      out.string(field.descriptor);
    }
  }
  out.integer(static_cast<std::uint32_t>(snapshot.arrays.size()));
  for (const auto &array : snapshot.arrays) {
    out.number(array.tag);
    out.string(array.shape);
    out.integer(array.worker);
    out.integer(array.site);
    out.number(array.occurrence);
  }
  out.integer(static_cast<std::uint32_t>(snapshot.methods.size()));
  for (const auto &method : snapshot.methods) {
    out.number(method.token);
    out.string(method.owner);
    out.string(method.name);
    out.string(method.descriptor);
    if (snapshot.capabilities & capability::local_variables) {
      out.integer(static_cast<std::uint32_t>(method.locals.size()));
      for (const auto &local : method.locals) {
        out.number(local.start);
        out.integer(local.length);
        out.integer(local.slot);
        out.string(local.name);
        out.string(local.descriptor);
      }
    }
  }
  out.integer(static_cast<std::uint32_t>(snapshot.records.size()));
  for (const auto &record : snapshot.records) {
    out.number(static_cast<std::uint64_t>(record.kind));
    out.number(record.a);
    out.number(record.b);
    out.number(record.c);
    out.number(record.d);
    out.number(record.e);
    out.number(record.f);
    out.number(record.g);
  }
  out.number(snapshot_end);
  return out.finish();
}
} // namespace gorrect
