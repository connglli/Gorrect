package io.connglli.gorrect.engine.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetProfileTest {
  @TempDir Path directory;
  private static final String PROFILE =
      """
      {"version":1,"vm":"hotspot","java":"jdk/bin/java","collector":"serial",
       "observation":"named-roots-diagnostic",
       "support":{"runtime":"runtime.jar","protocol":"protocol.jar","observer":"observer.so"}}
      """;

  @Test
  void resolvesPathsWithoutTouchingInstallations() throws IOException {
    var path = directory.resolve("profile.json");
    Files.writeString(path, PROFILE);
    var profile = TargetProfile.read(path);
    assertEquals(directory.resolve("jdk/bin/java").toString(), profile.java());
    assertEquals(100_000, profile.limits().records());
    assertEquals(java.util.List.of("tagging", "named-roots"), profile.requirements());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"version\":2",
        "\"version\":1.2",
        "\"version\":\"1\"",
        "\"version\":9223372036854775808",
        "\"unknown\":true",
        "\"limits\":{\"other\":1}",
        "\"limits\":{\"records\":0}",
        "\"limits\":{\"startupSeconds\":31}",
        "\"limits\":{\"records\":1000001}",
        "\"environment\":{\"JAVA_TOOL_OPTIONS\":\"-Xint\"}",
        "\"environment\":{\"NAME\":null}",
        "\"vmArgs\":[\"-XX:+UseG1GC\"]",
        "\"vmArgs\":[\"-XX:+UseEpsilonGC\"]",
        "\"vmArgs\":[\"-cp\"]",
        "\"vmArgs\":[\"-agentpath:observer.so\"]",
        "\"vmArgs\":[\"@options\"]",
        "\"vmArgs\":true",
        "\"limits\":true",
        "\"requirements\":[\"unknown\"]",
        "\"requirements\":[\"tagging\",\"tagging\"]",
        "\"java\":null",
        "\"collector\":\"gencon\"",
        "\"support\":{}"
      })
  void rejectsInvalidAndConflictingFields(String replacement) throws IOException {
    var path = directory.resolve("profile.json");
    Files.writeString(path, PROFILE);
    var object = JsonFiles.read(path);
    Files.writeString(path, "{" + replacement + "}");
    var changes = JsonFiles.read(path);
    for (var entry : changes.entrySet()) object.add(entry.getKey(), entry.getValue());
    JsonFiles.write(path, object);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"version\":null,\"version\":1}",
        "{\"x\":1} {}",
        "{\"x\":NaN}",
        "{\"x\":1,}",
        "{/*comment*/\"x\":1}"
      })
  void rejectsMalformedJson(String text) throws IOException {
    Path path = directory.resolve("bad.json");
    Files.writeString(path, text);
    assertThrows(IOException.class, () -> JsonFiles.read(path));
  }
}
