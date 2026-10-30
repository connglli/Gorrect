package io.connglli.gorrect.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  void openJ9ControlsDoNotAssumeWhiteBoxOrHotSpotTiers() throws IOException {
    Path path = directory.resolve("openj9.json");
    Files.writeString(path, PROFILE);
    var raw = JsonFiles.read(path);
    raw.addProperty("vm", "openj9");
    raw.addProperty("collector", "gencon");
    var jit = new JsonObject();
    raw.add("jit", jit);
    JsonFiles.write(path, raw);
    var profile = TargetProfile.read(path);
    assertEquals(1, profile.jit().level());
    assertFalse(profile.usesWhiteBox());
    assertEquals("system", profile.gc().control());
    assertEquals(
        profile,
        TargetProfile.parse(JsonFiles.GSON.toJsonTree(profile).getAsJsonObject(), directory));
    jit.addProperty("level", 4);
    JsonFiles.write(path, raw);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
    jit.remove("level");
    for (String option : List.of("-Xjit:count=0", "-Xaot", "-Xshareclasses:name=test")) {
      raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of(option)));
      JsonFiles.write(path, raw);
      assertThrows(IOException.class, () -> TargetProfile.read(path));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"OPENJ9_JAVA_OPTIONS", "IBM_JAVA_OPTIONS"})
  void rejectsAmbientOpenJ9OptionsInProfileEnvironment(String name) throws IOException {
    Path path = directory.resolve("environment.json");
    Files.writeString(path, PROFILE);
    var raw = JsonFiles.read(path);
    var environment = new JsonObject();
    environment.addProperty(name, "-Xgcpolicy:gencon");
    raw.add("environment", environment);
    JsonFiles.write(path, raw);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
    assertTrue(TargetProfile.ENVIRONMENT_OPTIONS.contains(name));
  }

  @Test
  void jitControlsRequireAnExplicitWhiteBoxClientAndStayOutOfVmArgs() throws IOException {
    Path path = directory.resolve("profile.json");
    Files.writeString(path, PROFILE);
    var object = JsonFiles.read(path);
    object.add("jit", new JsonObject());
    JsonFiles.write(path, object);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
    object.getAsJsonObject("support").addProperty("whitebox", "whitebox.jar");
    JsonFiles.write(path, object);
    var profile = TargetProfile.read(path);
    assertEquals(4, profile.jit().level());
    assertEquals(1, profile.jit().warmupRounds());
    assertEquals(directory.resolve("whitebox.jar").toString(), profile.support().whitebox());
    object.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-XX:+WhiteBoxAPI")));
    JsonFiles.write(path, object);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"level\":0}",
        "{\"level\":5}",
        "{\"warmupRounds\":-1}",
        "{\"warmupRounds\":1.5}",
        "{\"deadlineMillis\":0}",
        "{\"deadlineMillis\":60001}",
        "{\"unknown\":1}",
        "true"
      })
  void rejectsInvalidJitSettings(String settings) throws IOException {
    Path path = directory.resolve("profile.json");
    Files.writeString(path, PROFILE);
    var object = JsonFiles.read(path);
    object.getAsJsonObject("support").addProperty("whitebox", "client.jar");
    Files.writeString(path, "{\"jit\":" + settings + "}");
    object.add("jit", JsonFiles.read(path).get("jit"));
    JsonFiles.write(path, object);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
  }

  @Test
  void gcControlAndModesAreExplicitProfileSettings() throws IOException {
    var path = directory.resolve("gc.json");
    Files.writeString(path, PROFILE);
    var raw = JsonFiles.read(path);
    raw.addProperty("collector", "zgc");
    raw.addProperty("collectorMode", "generational");
    JsonFiles.write(path, raw);
    assertEquals("generational", TargetProfile.read(path).collectorMode());
    var gc = new JsonObject();
    gc.addProperty("control", "whitebox-full");
    raw.add("gc", gc);
    JsonFiles.write(path, raw);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
    raw.getAsJsonObject("support").addProperty("whitebox", "client.jar");
    JsonFiles.write(path, raw);
    assertTrue(TargetProfile.read(path).usesWhiteBox());
    assertNull(TargetProfile.read(path).jit());
    raw.add("vmArgs", JsonFiles.GSON.toJsonTree(List.of("-XX:+ZGenerational")));
    JsonFiles.write(path, raw);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
    raw.remove("vmArgs");
    raw.addProperty("collectorMode", "iu");
    JsonFiles.write(path, raw);
    assertThrows(IOException.class, () -> TargetProfile.read(path));
  }

  @Test
  void resolvesPathsWithoutTouchingInstallations() throws IOException {
    var path = directory.resolve("profile.json");
    Files.writeString(path, PROFILE);
    var profile = TargetProfile.read(path);
    assertEquals(directory.resolve("jdk/bin/java").toString(), profile.java());
    assertEquals(100_000, profile.limits().records());
    assertEquals(List.of("tagging", "named-roots"), profile.requirements());
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
