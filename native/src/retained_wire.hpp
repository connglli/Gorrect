#pragma once

#include <cstdint>
#include <initializer_list>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace gorrect {

// These sidecars leave snapshot version 1 and its native capability bits unchanged.
enum class RetainedStatus : std::int32_t {
  valid = 0,
  unavailable = 1,
  invalidated = 2,
  limit = 3,
  jvmti_error = 4
};
namespace code_flag {
constexpr std::int32_t prepared = 1, intended_bound = 2, system_loader = 4, jvmbci = 8,
                       bootstrap_loader = 16;
}
struct CertifiedMethod {
  std::int64_t token;
  std::string name, descriptor;
};
struct CodeEvidence {
  std::int64_t run = 0;
  bool final = false;
  RetainedStatus status = RetainedStatus::unavailable;
  std::int32_t error = 0;
  std::int64_t epoch = 0, class_token = 0, loader_token = 0;
  std::int32_t flags = 0;
  std::vector<std::uint8_t> bytes;
  std::vector<CertifiedMethod> methods;
};
enum class FrameRole : std::int32_t { execute = 0, worker = 1, main = 2 };
struct RetainedFrame {
  std::int64_t method;
  std::int32_t depth;
  std::int64_t location;
};
struct FrameParticipant {
  std::int32_t worker;
  std::int64_t thread;
  FrameRole role;
  std::vector<RetainedFrame> before, after;
};
struct FrameEvidence {
  std::int64_t run = 0, checkpoint = 0;
  RetainedStatus status = RetainedStatus::valid;
  std::int32_t error = 0;
  std::int64_t before_epoch = 0, after_epoch = 0;
  std::vector<FrameParticipant> participants;
};

// Metadata is restricted to ASCII by the observer. This writer uses the unsigned two-byte
// string lengths of DataOutputStream.writeUTF, while snapshots keep their original framing.
class RetainedWriter {
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
  void utf(const std::string &text) {
    if (text.size() > 65535)
      throw std::runtime_error("Retained metadata string capacity exhausted");
    for (auto character : text)
      if (character <= 0 || static_cast<unsigned char>(character) > 127)
        throw std::runtime_error("Retained metadata is not non-NUL ASCII");
    bytes_.push_back((text.size() >> 8) & 0xff);
    bytes_.push_back(text.size() & 0xff);
    bytes_.insert(bytes_.end(), text.begin(), text.end());
  }
  void blob(const std::vector<std::uint8_t> &bytes) {
    integer(static_cast<std::uint32_t>(bytes.size()));
    bytes_.insert(bytes_.end(), bytes.begin(), bytes.end());
  }
  std::vector<std::uint8_t> finish() { return std::move(bytes_); }
};

inline std::vector<std::uint8_t> encode(const CodeEvidence &code) {
  RetainedWriter out;
  out.integer(0x47434431);
  out.integer(1);
  out.number(code.run);
  out.integer(code.final ? 1 : 0);
  out.integer(static_cast<std::uint32_t>(code.status));
  out.integer(code.error);
  out.number(code.epoch);
  out.number(code.class_token);
  out.number(code.loader_token);
  out.integer(code.flags);
  out.blob(code.bytes);
  out.integer(static_cast<std::uint32_t>(code.methods.size()));
  for (const auto &method : code.methods) {
    out.number(method.token);
    out.utf(method.name);
    out.utf(method.descriptor);
  }
  out.number(0x454e44434f444531ULL);
  return out.finish();
}
inline std::vector<std::uint8_t> encode(const FrameEvidence &frames) {
  RetainedWriter out;
  out.integer(0x47465231);
  out.integer(1);
  out.number(frames.run);
  out.number(frames.checkpoint);
  out.integer(static_cast<std::uint32_t>(frames.status));
  out.integer(frames.error);
  out.number(frames.before_epoch);
  out.number(frames.after_epoch);
  out.integer(static_cast<std::uint32_t>(frames.participants.size()));
  for (const auto &participant : frames.participants) {
    out.integer(participant.worker);
    out.number(participant.thread);
    out.integer(static_cast<std::uint32_t>(participant.role));
    for (const auto *sample : {&participant.before, &participant.after}) {
      out.integer(static_cast<std::uint32_t>(sample->size()));
      for (const auto &frame : *sample) {
        out.number(frame.method);
        out.integer(frame.depth);
        out.number(frame.location);
      }
    }
  }
  out.number(0x454e444652414d31ULL);
  return out.finish();
}
} // namespace gorrect
