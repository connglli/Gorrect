#include "retained_wire.hpp"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
bool matches(const std::vector<std::uint8_t> &bytes, const char *path) {
  std::ifstream input(path);
  std::string text, word;
  while (input >> word)
    text += word;
  if (text.size() != bytes.size() * 2)
    return false;
  for (std::size_t index = 0; index < bytes.size(); ++index)
    if (bytes[index] != std::stoul(text.substr(index * 2, 2), nullptr, 16)) {
      std::cerr << "Wire mismatch at " << index << '\n';
      return false;
    }
  return true;
}
} // namespace

int main(int argc, char **argv) {
  if (argc != 3)
    return 2;
  gorrect::CodeEvidence code;
  code.run = 17;
  code.status = gorrect::RetainedStatus::valid;
  code.epoch = 2;
  code.class_token = code.loader_token = 1;
  code.flags = 15;
  code.bytes = {0xca, 0xfe, 0xba, 0xbe};
  code.methods.push_back({23, "execute", "()V"});
  auto bytes = gorrect::encode(code);
  if (!matches(bytes, argv[1]))
    return 1;

  gorrect::FrameEvidence frames;
  frames.run = 17;
  frames.checkpoint = 3;
  frames.before_epoch = frames.after_epoch = 2;
  frames.participants.push_back({0, 7, gorrect::FrameRole::execute, {{23, 0, 5}}, {{23, 0, 5}}});
  bytes = gorrect::encode(frames);
  if (!matches(bytes, argv[2]))
    return 1;

  try {
    gorrect::RetainedWriter invalid;
    invalid.utf(std::string(65536, 'a'));
    return 3;
  } catch (const std::runtime_error &) {
  }
  try {
    gorrect::RetainedWriter invalid;
    invalid.utf(std::string(1, '\0'));
    return 4;
  } catch (const std::runtime_error &) {
  }
  return 0;
}
