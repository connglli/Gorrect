#pragma once

#include "snapshot_wire.hpp"
#include <stdexcept>

namespace gorrect {

// Explicitly selected resource policy; the inner snapshot format remains unchanged.
inline std::vector<std::uint8_t> encode_queue_body(const std::vector<std::uint8_t> &body) {
  if (body.size() > 128U * 1024 * 1024)
    throw std::length_error("Queue snapshot byte capacity exhausted");
  WireWriter out;
  out.integer(0x47514331);
  out.integer(1);
  out.integer(1);
  out.integer(static_cast<std::uint32_t>(body.size()));
  out.bytes(body);
  out.number(0x454e445143415031ULL);
  return out.finish();
}

inline std::vector<std::uint8_t> encode_queue(const Snapshot &snapshot) {
  return encode_queue_body(encode(snapshot));
}

} // namespace gorrect
