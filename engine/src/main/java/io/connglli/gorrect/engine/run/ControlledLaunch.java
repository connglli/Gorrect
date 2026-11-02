package io.connglli.gorrect.engine.run;

import com.google.gson.JsonObject;
import io.connglli.gorrect.engine.config.JsonFiles;
import io.connglli.gorrect.engine.config.TargetProfile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/** Restricted launch premise needed to treat the nontransforming load-hook bytes as final bytes. */
final class ControlledLaunch {
  static final Set<String> INJECTION_ENVIRONMENT =
      Set.of(
          "JDK_ALTERNATE_VM",
          "LD_PRELOAD",
          "LD_LIBRARY_PATH",
          "LD_AUDIT",
          "DYLD_INSERT_LIBRARIES",
          "DYLD_LIBRARY_PATH",
          "DYLD_FRAMEWORK_PATH",
          "LIBPATH");

  // OpenJ9 6e16eb1a4f runtime/options.default: platform lockwords and Class/Thread pre-hashing
  // only.
  private static final String OPENJ9_DEFAULTS =
      "bccd68e10c9a58f37227ddc267912b5dd1fc32fc86f6643f399b486be3813dad";

  private ControlledLaunch() {}

  /** Selects launch certification from retained metadata or generated resource plans. */
  static boolean selected(TargetProfile profile, Path directory) {
    return profile.retainedMetadata()
        || QueueResources.selected(directory)
        || NativeRoots.selected(directory);
  }

  /**
   * Profile-owned arguments admitted to the first retained provider; unknown options are
   * unsupported.
   */
  static boolean reviewedArgument(String argument) {
    return argument.matches("-X(ms|mx|ss)[1-9][0-9]*[kKmMgG]?")
        || Set.of(
                "-Xint",
                "-Xbatch",
                "-Xnojit",
                "-Xnoaot",
                "-Xshareclasses:none",
                "-Xshare:off",
                "-Djava.compiler=NONE")
            .contains(argument)
        || argument.matches(
            "-XX:[+-](DoEscapeAnalysis|TieredCompilation|UnlockDiagnosticVMOptions|UnlockExperimentalVMOptions|UseCompressedOops|UseCompressedClassPointers|UseTLAB|ResizeTLAB|AlwaysPreTouch|UseNUMA|UseStringDeduplication|DisableExplicitGC|ExplicitGCInvokesConcurrent|UseGCOverheadLimit)")
        || argument.matches(
            "-XX:(TieredStopAtLevel|CompileThreshold|MaxTenuringThreshold|InitialTenuringThreshold|SurvivorRatio|TargetSurvivorRatio|NewRatio|ParallelGCThreads|ConcGCThreads)=[0-9]+")
        || argument.matches("-XX:(NewSize|MaxNewSize|TLABSize)=[1-9][0-9]*[kKmMgG]?");
  }

  /** Rejects unsupported profile settings before compiler or target execution. */
  static List<String> configuration(TargetProfile profile) {
    return configuration(profile, false);
  }

  static List<String> configuration(TargetProfile profile, boolean queues) {
    if (!profile.retainedMetadata() && !queues) return List.of();
    var problems = new ArrayList<String>();
    if (profile.support().whitebox() != null || profile.usesWhiteBox())
      problems.add("Controlled observation does not admit external WhiteBox clients");
    for (String argument : profile.vmArgs())
      if (!reviewedArgument(argument))
        problems.add("Unreviewed controlled VM argument: " + argument);
    for (String name : profile.environment().keySet())
      if (injection(name)) problems.add("Native injection environment is unsupported: " + name);
    return List.copyOf(problems);
  }

  /**
   * Checks current launch inputs against the engine build's own support identities before starting
   * a VM.
   */
  static void check(Path directory, TargetProfile profile, Map<String, String> savedEnvironment)
      throws IOException {
    boolean queues = QueueResources.selected(directory) || NativeRoots.selected(directory);
    if (!profile.retainedMetadata() && !queues) return;
    var problems = configuration(profile, queues);
    if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
    if (savedEnvironment != null)
      for (String name : savedEnvironment.keySet())
        if (injection(name)
            || TargetProfile.ENVIRONMENT_OPTIONS.contains(name.toUpperCase(Locale.ROOT)))
          throw new IllegalArgumentException(
              "Replay environment reintroduces launch injection: " + name);

    Path executable = Path.of(profile.java()).toRealPath();
    Path home = executable.getParent().getParent();
    if (!executable.getParent().getFileName().toString().equals("bin")
        || !executable.getFileName().toString().equals("java")
        || !Files.isRegularFile(home.resolve("release")))
      throw new IllegalArgumentException(
          "Controlled observation requires the installation's direct bin/java executable");
    byte[] header;
    try (var input = Files.newInputStream(executable)) {
      header = input.readNBytes(4);
    }
    // Unix-domain control currently targets Unix installations. Scripts cannot establish this
    // premise.
    if (header.length != 4
        || header[0] != 0x7f
        || header[1] != 'E'
        || header[2] != 'L'
        || header[3] != 'F')
      throw new IllegalArgumentException(
          "Controlled observation requires a direct ELF JVM launcher");

    // OpenJ9 reads installation defaults before explicit arguments; only reviewed content is
    // admitted.
    if (profile.vm().equals("openj9"))
      for (String relative :
          List.of(
              "lib/options.default",
              "lib/default/options.default",
              "lib/compressedrefs/options.default",
              "lib/j9vm/options.default",
              "bin/default/options.default",
              "bin/compressedrefs/options.default")) {
        Path options = home.resolve(relative);
        if (Files.exists(options)
            && Files.size(options) != 0
            && (Files.size(options) > 4096 || !Artifacts.hash(options).equals(OPENJ9_DEFAULTS)))
          throw new IllegalArgumentException("Unreviewed installation option file: " + relative);
      }
    if (profile.vm().equals("hotspot") && Files.exists(directory.resolve(".hotspotrc")))
      throw new IllegalArgumentException("Unreviewed HotSpot startup option file");

    var trusted = new Properties();
    try (var input = ControlledLaunch.class.getResourceAsStream("/trusted-support.properties")) {
      if (input == null) throw new IOException("Engine build has no trusted support identities");
      trusted.load(input);
    }
    for (String name : List.of("runtime.jar", "protocol.jar", Artifacts.OBSERVER)) {
      String expected = trusted.getProperty(name);
      if (expected == null
          || !expected.equals(Artifacts.hash(directory.resolve("support/" + name))))
        throw new IllegalArgumentException(
            "Controlled observation requires reviewed project support: " + name);
      if (name.endsWith(".jar")) inspectJar(directory.resolve("support/" + name));
    }
  }

  /** Checks selected-VM policy facts independently of the command retained in the manifest. */
  static void effective(TargetProfile profile, Properties facts, List<String> unmet) {
    effective(profile, facts, unmet, false);
  }

  static void effective(
      TargetProfile profile, Properties facts, List<String> unmet, boolean queues) {
    if (!profile.retainedMetadata() && !queues) return;
    try {
      Path executable = Path.of(profile.java()).toAbsolutePath().normalize();
      if (!Path.of(facts.getProperty("java.home", ""))
              .toAbsolutePath()
              .normalize()
              .equals(executable.getParent().getParent())
          || !Path.of(facts.getProperty("process.command", ""))
              .toAbsolutePath()
              .normalize()
              .equals(executable))
        unmet.add("retained-class direct launcher identity was not confirmed");
    } catch (RuntimeException failure) {
      unmet.add("retained-class launcher facts unavailable: " + failure);
    }
    if (profile.vm().equals("hotspot")) {
      if (!facts.getProperty("flag.DisableAttachMechanism", "").equals("true"))
        unmet.add("Controlled observation requires disabled attachment");
    } else {
      if (!facts.getProperty("attach.disabled", "").equals("true"))
        unmet.add("Controlled observation requires disabled attachment");
      if (!facts.getProperty("reuse.disabled", "").equals("true"))
        unmet.add("Controlled observation requires disabled OpenJ9 shared-cache/AOT reuse");
    }
  }

  /**
   * Bounded duplicate-free target facts for retained certification; no installation is consulted.
   */
  static Properties targetFacts(Path directory) throws IOException {
    Path path = directory.resolve("target.properties");
    if (Files.size(path) > 256 * 1024)
      throw new IOException("Target facts exceed retained byte limit");
    var facts = new UniqueProperties();
    try (var input = Files.newInputStream(path)) {
      facts.load(input);
    } catch (IllegalArgumentException malformed) {
      throw new IOException("Invalid target facts", malformed);
    }
    return facts;
  }

  private static final class UniqueProperties extends Properties {
    private static final long serialVersionUID = 1;

    @Override
    public synchronized Object put(Object key, Object value) {
      if (containsKey(key)) throw new IllegalArgumentException("Duplicate target property " + key);
      return super.put(key, value);
    }
  }

  /**
   * Certifies recorded effective inputs before ACK and offline; matching files alone is
   * insufficient.
   */
  static void evidence(TargetProfile profile, JsonObject manifest, Properties facts)
      throws IOException {
    evidence(profile, manifest, facts, false);
  }

  static void evidence(TargetProfile profile, JsonObject manifest, Properties facts, boolean queues)
      throws IOException {
    if (!profile.retainedMetadata() && !queues) return;
    try {
      var configured = configuration(profile, queues);
      if (!configured.isEmpty()) throw new IOException(String.join("; ", configured));
      var environment = JsonFiles.object(JsonFiles.required(manifest, "environment"));
      if (environment.size() > 2048)
        throw new IOException("Resolved environment exceeds count limit");
      int characters = 0;
      for (var entry : environment.entrySet()) {
        String name = entry.getKey(), value = JsonFiles.string(entry.getValue());
        characters += name.length() + value.length();
        if (name.isEmpty()
            || name.length() > 512
            || value.length() > 65_536
            || characters > 262_144
            || name.indexOf('=') >= 0
            || name.indexOf('\0') >= 0
            || value.indexOf('\0') >= 0) throw new IOException("Malformed resolved environment");
        if (injection(name)
            || TargetProfile.ENVIRONMENT_OPTIONS.contains(name.toUpperCase(Locale.ROOT)))
          throw new IOException("Disqualifying resolved environment: " + name);
      }
      var command = new ArrayList<String>();
      characters = 0;
      for (var item : JsonFiles.array(JsonFiles.required(manifest, "command"))) {
        String value = JsonFiles.string(item);
        characters += value.length();
        if (command.size() == 512
            || value.length() > 16_384
            || characters > 131_072
            || value.indexOf('\0') >= 0)
          throw new IOException("Recorded command exceeds retained bounds");
        command.add(value);
      }
      int cp = command.indexOf("-cp");
      if (cp < 1
          || cp + 9 != command.size()
          || !command.getFirst().equals(profile.java())
          || !command.get(cp + 2).equals("Case"))
        throw new IOException("Malformed controlled command");
      Path launch = Path.of(command.get(cp + 5));
      if (!launch.isAbsolute() || !launch.normalize().equals(launch))
        throw new IOException("Recorded launch directory is not canonical");
      String classpath =
          String.join(
              File.pathSeparator,
              launch.resolve("generated/classes").toString(),
              launch.resolve("support/runtime.jar").toString(),
              launch.resolve("support/protocol.jar").toString());
      if (!command.get(cp + 1).equals(classpath)
          || !command
              .get(cp + 3)
              .equals(JsonFiles.string(JsonFiles.required(manifest, "controlSocket")))
          || !command
              .get(cp + 4)
              .equals(Long.toString(JsonFiles.number(JsonFiles.required(manifest, "run"))))
          || !command.get(cp + 6).equals(launch.resolve("support/" + Artifacts.OBSERVER).toString())
          || !command.get(cp + 7).equals(Integer.toString(profile.limits().records()))
          || !command.get(cp + 8).equals(Integer.toString(profile.limits().traceEvents())))
        throw new IOException("Recorded command differs from controlled entry point");
      var owned =
          new ArrayList<>(
              VmDriver.launchOptions(profile, launch, facts, profile.retainedMetadata() || queues));
      owned.add("-Dgorrect.observation=" + profile.observation());
      if (!owned.equals(command.subList(1, cp)))
        throw new IOException("Unreviewed recorded VM options");
      var actual = effectiveArguments(facts);
      var expected = new ArrayList<String>();
      if (profile.vm().equals("openj9")) {
        Path home = Path.of(profile.java()).getParent().getParent();
        String defaults =
            JsonFiles.string(
                JsonFiles.required(
                    JsonFiles.object(JsonFiles.required(manifest, "installationArtifacts")),
                    "target/lib/options.default"));
        if (!actual.isEmpty() && actual.getFirst().startsWith("-Xoptionsfile="))
          expected.add("-Xoptionsfile=" + home.resolve("lib/options.default"));
        if (defaults.equals(OPENJ9_DEFAULTS)) expected.addAll(reviewedDefaults());
        else if (!defaults.equals("missing"))
          throw new IOException("Unreviewed implicit option source");
        String libraries = home.resolve("lib/default") + File.pathSeparator + home.resolve("lib");
        expected.add("-Dcom.ibm.oti.vm.bootstrap.library.path=" + libraries);
        expected.add("-Dsun.boot.library.path=" + libraries);
        expected.add("-Djava.library.path=" + libraries + ":/usr/lib64:/usr/lib");
        expected.add("-Djava.home=" + home);
        expected.add("-Duser.dir=" + launch);
      }
      expected.addAll(owned);
      if (profile.vm().equals("openj9")) {
        expected.add("-Djava.class.path=" + classpath);
        expected.add(
            "-Dsun.java.command=" + String.join(" ", command.subList(cp + 2, command.size())));
        expected.add("-Dsun.java.launcher=SUN_STANDARD");
      }
      if (!actual.equals(expected))
        throw new IOException("Effective VM arguments differ from reviewed inputs");
    } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
      throw new IOException("Malformed retained launch evidence", malformed);
    }
  }

  private static List<String> effectiveArguments(Properties facts) throws IOException {
    String text = facts.getProperty("vm.arguments.count", "");
    if (!text.matches("[0-9]{1,3}"))
      throw new IOException("Missing or malformed VM argument count");
    int count = Integer.parseInt(text);
    if (count > 512) throw new IOException("VM argument count exceeds limit");
    var values = new ArrayList<String>();
    int characters = 0;
    for (int index = 0; index < count; index++) {
      String value = facts.getProperty("vm.argument." + index);
      if (value == null
          || value.indexOf('\0') >= 0
          || value.length() > 16_384
          || (characters += value.length()) > 131_072)
        throw new IOException("Missing or oversized VM argument");
      values.add(value);
    }
    for (String name : facts.stringPropertyNames())
      if (name.startsWith("vm.argument.")) {
        String index = name.substring("vm.argument.".length());
        if (!index.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(index) >= count)
          throw new IOException("Unexpected VM argument key");
      }
    return List.copyOf(values);
  }

  private static List<String> reviewedDefaults() throws IOException {
    try (var input =
        ControlledLaunch.class.getResourceAsStream("/reviewed-openj9-options.default")) {
      if (input == null) throw new IOException("Missing reviewed OpenJ9 defaults");
      byte[] bytes = input.readNBytes(4097);
      if (bytes.length > 4096) throw new IOException("Reviewed defaults exceed limit");
      var values = new ArrayList<String>();
      for (String line :
          new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).strip().split("\n")) {
        if (!line.startsWith("\"") || !line.endsWith("\""))
          throw new IOException("Malformed reviewed defaults");
        values.add(line.substring(1, line.length() - 1));
      }
      return List.copyOf(values);
    }
  }

  static boolean injection(String name) {
    return INJECTION_ENVIRONMENT.contains(name.toUpperCase(Locale.ROOT));
  }

  private static void inspectJar(Path path) throws IOException {
    try (var jar = new JarFile(path.toFile())) {
      var manifest = jar.getManifest();
      if (manifest != null)
        for (String name :
            List.of(
                "Premain-Class",
                "Agent-Class",
                "Launcher-Agent-Class",
                "Boot-Class-Path",
                "Class-Path"))
          if (manifest.getMainAttributes().getValue(name) != null)
            throw new IllegalArgumentException("Support manifest changes class loading: " + name);
      if (jar.stream()
          .anyMatch(
              entry ->
                  entry.getName().matches("(?:META-INF/versions/[0-9]+/)?Case(?:\\$.*)?\\.class")))
        throw new IllegalArgumentException("Support shadows generated Case classes");
    }
  }
}
