package io.connglli.gorrect.engine.run;

import io.connglli.gorrect.protocol.ControlMessage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

/** Process fixture that injects failures after a structurally valid handshake. */
public final class FailureTarget {
  private FailureTarget() {}

  public static void main(String[] args) throws Exception {
    if (Arrays.asList(args).contains("io.connglli.gorrect.runtime.VmFacts")) {
      var command = new ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.addAll(Arrays.asList(args).subList(1, args.length));
      System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
    if (args[0].equals("startup-timeout")) Thread.sleep(30_000);
    int offset = Arrays.asList(args).indexOf("Case") + 1;
    long run = Long.parseLong(args[offset + 1]);
    Path directory = Path.of(args[offset + 2]);
    var target = new Properties();
    target.setProperty("java.vm.name", "OpenJDK test fixture");
    target.setProperty("java.feature", "21");
    target.setProperty("collectors", "Copy,MarkSweepCompact");
    for (String flag :
        new String[] {"UseSerialGC", "UseParallelGC", "UseG1GC", "UseZGC", "UseShenandoahGC"})
      target.setProperty("flag." + flag, Boolean.toString(flag.equals("UseSerialGC")));
    target.setProperty("gc.control", "system");
    target.setProperty("gc.problem", "");
    target.setProperty("gc.evidence", "false");
    target.setProperty("observer.capabilities", "1");
    if (args[0].equals("malformed")) target.setProperty("observer.capabilities", "invalid");
    try (var output = Files.newOutputStream(directory.resolve("target.properties"))) {
      target.store(output, "Failure fixture");
    }
    try (var socket = SocketChannel.open(UnixDomainSocketAddress.of(args[offset]));
        var in = new DataInputStream(Channels.newInputStream(socket));
        var out = new DataOutputStream(Channels.newOutputStream(socket))) {
      if (args[0].equals("silent-handshake")) Thread.sleep(30_000);
      new ControlMessage(ControlMessage.Kind.HELLO, run, 0, 0, 0, "").write(out);
      ControlMessage.read(in, run);
      switch (args[0]) {
        case "crash" -> Runtime.getRuntime().halt(71);
        case "hang" -> Thread.sleep(30_000);
        case "failure" ->
            new ControlMessage(
                    ControlMessage.Kind.FAILED, run, 0, 0, 0, "Injected workload failure")
                .write(out);
        case "protocol" ->
            new ControlMessage(ControlMessage.Kind.CHECKPOINT, run, 1, 999, 1, "").write(out);
        default -> throw new AssertionError("Unknown failure mode");
      }
    }
  }
}
