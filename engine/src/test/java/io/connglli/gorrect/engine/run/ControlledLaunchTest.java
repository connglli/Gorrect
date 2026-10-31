package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.ControlMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ControlledLaunchTest {
  @TempDir Path directory;

  @Test
  void profileDefaultsRemainNativeAndUnknownProviderCannotBecomeFallback() {
    var json = profile();
    assertEquals("jvmti", TargetProfile.parse(json, directory).bindingMetadata());
    json.addProperty("bindingMetadata", "retained-class");
    assertTrue(
        TargetProfile.parse(json, directory).withCollectorMode("default").retainedMetadata());
    json.addProperty("bindingMetadata", "fallback");
    assertThrows(IllegalArgumentException.class, () -> TargetProfile.parse(json, directory));
  }

  @Test
  void whiteBoxRemainsSupportedOnlyForTheNativeProvider() {
    var json = profile();
    json.getAsJsonObject("support").addProperty("whitebox", "whitebox.jar");
    assertTrue(VmDriver.unsupported(TargetProfile.parse(json, directory)).isEmpty());
    json.addProperty("bindingMetadata", "retained-class");
    assertTrue(
        VmDriver.unsupported(TargetProfile.parse(json, directory)).stream()
            .anyMatch(problem -> problem.contains("WhiteBox")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "-javaagent:other.jar",
        "-agentlib:jdwp",
        "-Djava.system.class.loader=Other",
        "@options",
        "--patch-module=java.base=classes",
        "-XX:CompileCommand=exclude,Case.*",
        "-Xjit:count=0"
      })
  void unknownOrLoadingSensitiveArgumentsAreNotControlled(String argument) {
    assertFalse(ControlledLaunch.reviewedArgument(argument));
  }

  @Test
  void rejectsArbitraryCompatibleSupportAndExecutableWrappers() throws IOException {
    var json = profile();
    json.addProperty("bindingMetadata", "retained-class");
    json.addProperty("java", Path.of(System.getProperty("java.home"), "bin/java").toString());
    var parsed = TargetProfile.parse(json, directory);
    Files.createDirectories(directory.resolve("support"));
    Files.writeString(directory.resolve("support/runtime.jar"), "unreviewed content");
    assertThrows(
        IllegalArgumentException.class, () -> ControlledLaunch.check(directory, parsed, null));
    Path wrapperHome = directory.resolve("wrapper");
    Files.createDirectories(wrapperHome.resolve("bin"));
    Files.writeString(wrapperHome.resolve("release"), "JAVA_VERSION=21");
    Files.writeString(wrapperHome.resolve("bin/java"), "#!/bin/sh\nexec java \"$@\"\n");
    json.addProperty("java", wrapperHome.resolve("bin/java").toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> ControlledLaunch.check(directory, TargetProfile.parse(json, directory), null));
  }

  @Test
  void installationOptionsCannotHideAnUncontrolledAgent() throws IOException {
    Path home = directory.resolve("installation");
    Files.createDirectories(home.resolve("bin"));
    Files.createDirectories(home.resolve("lib"));
    Files.copy(Path.of(System.getProperty("java.home"), "bin/java"), home.resolve("bin/java"));
    Files.writeString(home.resolve("release"), "JAVA_VERSION=21");
    Files.writeString(home.resolve("lib/options.default"), "-javaagent:hidden.jar\n");
    var json = profile();
    json.addProperty("java", home.resolve("bin/java").toString());
    json.addProperty("vm", "openj9");
    json.addProperty("collector", "gencon");
    json.addProperty("bindingMetadata", "retained-class");
    var problem =
        assertThrows(
            IllegalArgumentException.class,
            () -> ControlledLaunch.check(directory, TargetProfile.parse(json, directory), null));
    assertTrue(problem.getMessage().contains("option file"));
  }

  @Test
  void retainedEnvironmentCannotRestoreJavaOrNativeInjection() {
    var builder = new ProcessBuilder();
    builder.environment().put("LD_PRELOAD", "other.so");
    builder.environment().put("JAVA_TOOL_OPTIONS", "-javaagent:other.jar");
    ProcessSupport.cleanEnvironment(builder, Map.of("GORRECT_TEST", "value"), true);
    assertFalse(builder.environment().containsKey("LD_PRELOAD"));
    assertFalse(builder.environment().containsKey("JAVA_TOOL_OPTIONS"));
    assertEquals("value", builder.environment().get("GORRECT_TEST"));
    var json = profile();
    json.addProperty("bindingMetadata", "retained-class");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ControlledLaunch.check(
                directory, TargetProfile.parse(json, directory), Map.of("LD_PRELOAD", "other.so")));
    // The legacy provider retains its prior launch behavior.
    json.remove("bindingMetadata");
    assertEquals("jvmti", TargetProfile.parse(json, directory).bindingMetadata());
  }

  @Test
  void retainedPolicyFactsCanBeVerifiedWithoutTheInstallation() {
    var json = profile();
    json.addProperty("bindingMetadata", "retained-class");
    var selected = TargetProfile.parse(json, directory);
    var facts = new Properties();
    facts.setProperty("java.home", directory.resolve("jdk").toString());
    facts.setProperty("process.command", selected.java());
    facts.setProperty("flag.DisableAttachMechanism", "true");
    var unmet = new ArrayList<String>();
    ControlledLaunch.effective(selected, facts, unmet);
    assertEquals(List.of(), unmet);
    assertFalse(Files.exists(Path.of(selected.java())));
  }

  @Test
  void openJ9CoordinateRecoveryRequiresItsConformedBuild() {
    var facts = new Properties();
    facts.setProperty("java.feature", "21");
    facts.setProperty("java.vm.version", "master-6e16eb1a4f");
    facts.setProperty("os.name", "Linux");
    facts.setProperty("os.arch", "amd64");
    assertTrue(OpenJ9Driver.retainedCoordinates(facts));
    facts.setProperty("java.vm.version", "master-other");
    assertFalse(OpenJ9Driver.retainedCoordinates(facts));
    facts.setProperty("java.vm.version", "master-6e16eb1a4f");
    facts.setProperty("java.feature", "17");
    assertFalse(OpenJ9Driver.retainedCoordinates(facts));
  }

  @Test
  void initialResourceAndPersistenceFailuresAreNotUnsupportedFacilities() {
    for (String cause :
        List.of(
            "java.lang.OutOfMemoryError: allocation failed",
            "java.io.IOException: cannot persist certificate",
            "java.lang.UnsatisfiedLinkError: missing library",
            "java.lang.IllegalStateException: missing prepared Case")) {
      var result = TargetRunner.startupFailure(true, cause);
      assertEquals(RunReport.Outcome.STARTUP_FAILURE, result.execution());
      assertEquals(List.of(cause), result.problems());
    }
    String detail =
        ControlMessage.RETAINED_UNSUPPORTED
            + "java.lang.IllegalStateException: missing prepared Case";
    assertEquals(
        RunReport.Outcome.UNSUPPORTED, TargetRunner.startupFailure(true, detail).execution());
    assertEquals(
        RunReport.Outcome.STARTUP_FAILURE, TargetRunner.startupFailure(false, detail).execution());
  }

  @Test
  void unqualifiedOpenJ9PolicyDoesNotChangeNativeProviderCoverage() {
    var json = profile();
    json.addProperty("vm", "openj9");
    json.addProperty("collector", "balanced");
    assertTrue(VmDriver.unsupported(TargetProfile.parse(json, directory)).isEmpty());
    json.addProperty("bindingMetadata", "retained-class");
    assertTrue(
        VmDriver.unsupported(TargetProfile.parse(json, directory)).stream()
            .anyMatch(problem -> problem.contains("gencon only")));
  }

  @Test
  void unsupportedLaunchSettingsStopBeforeCompilation() throws IOException {
    var json = profile();
    json.addProperty("bindingMetadata", "retained-class");
    json.add("environment", JsonFiles.GSON.toJsonTree(Map.of("LD_PRELOAD", "unconfigured.so")));
    Path profile = directory.resolve("profile.json"),
        source = directory.resolve("case.gir"),
        run = directory.resolve("run");
    JsonFiles.write(profile, json);
    Files.writeString(source, "gir 1; program {}");
    assertEquals(RunReport.Outcome.UNSUPPORTED, RunEngine.run(source, profile, run).execution());
    assertFalse(Files.exists(run.resolve("logs/compiler-command.json")));
  }

  @Test
  void hotSpotCoordinateQualificationIsLimitedToTestedFeaturesAndPlatform() {
    var facts = new Properties();
    facts.setProperty("java.feature", "17");
    facts.setProperty("os.name", "Linux");
    facts.setProperty("os.arch", "amd64");
    assertTrue(HotSpotDriver.retainedCoordinates(facts));
    facts.setProperty("java.feature", "21");
    assertTrue(HotSpotDriver.retainedCoordinates(facts));
    facts.setProperty("java.feature", "22");
    assertFalse(HotSpotDriver.retainedCoordinates(facts));
    facts.setProperty("java.feature", "21");
    facts.setProperty("os.arch", "aarch64");
    assertFalse(HotSpotDriver.retainedCoordinates(facts));
  }

  @Test
  void realNativeFailureEnvelopesPreserveTheResourceCategoryEndToEnd()
      throws ReflectiveOperationException, IOException {
    var check =
        io.connglli.gorrect.runtime.TargetSession.class.getDeclaredMethod(
            "checkCodeStatus", CodeEvidence.class);
    var detail =
        io.connglli.gorrect.runtime.TargetSession.class.getDeclaredMethod(
            "startupFailureDetail", boolean.class, Throwable.class);
    check.setAccessible(true);
    detail.setAccessible(true);
    for (String name :
        List.of(
            "native-code-resource-v1.hex",
            "native-code-limit-v1.hex",
            "native-code-jvmti-resource-v1.hex",
            "native-code-jvmti-failure-v1.hex")) {
      try (var input = getClass().getResourceAsStream("/wire/" + name)) {
        if (input == null) throw new IOException("Missing native producer envelope " + name);
        byte[] bytes =
            java.util.HexFormat.of()
                .parseHex(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII)
                        .strip());
        var code = CodeEvidence.read(bytes, 17, CodeEvidence.Stage.STARTUP);
        var thrown =
            assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> check.invoke(null, code));
        String message = (String) detail.invoke(null, true, thrown.getCause());
        var result = TargetRunner.startupFailure(true, message);
        assertEquals(
            code.jvmtiError() == CodeEvidence.OUT_OF_MEMORY
                ? RunReport.Outcome.STARTUP_FAILURE
                : RunReport.Outcome.UNSUPPORTED,
            result.execution(),
            name);
        assertTrue(result.problems().getFirst().contains("error " + code.jvmtiError()));
      }
    }
  }

  private JsonObject profile() {
    return JsonFiles.GSON.fromJson(
        """
        {"version":1,"vm":"hotspot","java":"jdk/bin/java","collector":"serial",
         "observation":"roots-diagnostic","support":{"runtime":"runtime.jar",
         "protocol":"protocol.jar","observer":"observer.so"}}
        """,
        JsonObject.class);
  }
}
