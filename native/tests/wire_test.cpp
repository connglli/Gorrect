#include "snapshot_wire.hpp"
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

int main(int argc, char **argv) {
  if (argc != 2)
    return 2;
  gorrect::Snapshot snapshot;
  snapshot.run = 17;
  snapshot.checkpoint = 23;
  snapshot.capabilities = gorrect::capability::tagging;
  snapshot.starts_before = snapshot.finishes_before = 10;
  snapshot.starts_after = snapshot.finishes_after = 10;
  snapshot.records.push_back({gorrect::RecordKind::primitive_field, 7, 3, 74, -9, -1, 0, 0});
  auto actual = gorrect::encode(snapshot);
  std::ifstream input(argv[1]);
  std::string text, line;
  while (input >> line)
    text += line;
  if (text.size() != actual.size() * 2)
    return 3;
  for (std::size_t index = 0; index < actual.size(); ++index) {
    auto expected = std::stoul(text.substr(index * 2, 2), nullptr, 16);
    if (actual[index] != expected) {
      std::cerr << "Wire mismatch at " << index << '\n';
      return 1;
    }
  }
  return 0;
}
