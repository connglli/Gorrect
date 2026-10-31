package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.CodeEvidence;
import io.connglli.gorrect.protocol.ControlMessage;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Startup failure must reach the host with its original class/message before construction. */
public final class RetainedSessionCheck {
  private RetainedSessionCheck() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("Expected library, output and overload");
    for (Throwable rejected :
        new Throwable[] {
          new IllegalStateException("same cause in either phase"),
          new IllegalArgumentException("same cause in either phase")
        }) {
      if (!TargetSession.startupFailureDetail(true, rejected)
              .startsWith(ControlMessage.RETAINED_UNSUPPORTED)
          || TargetSession.startupFailureDetail(false, rejected)
              .startsWith(ControlMessage.RETAINED_UNSUPPORTED))
        throw new AssertionError("Setup phase classification was lost");
    }
    for (Throwable failure :
        new Throwable[] {
          new java.io.IOException("persist"),
          new OutOfMemoryError("allocate"),
          new UnsatisfiedLinkError("library")
        })
      if (TargetSession.startupFailureDetail(true, failure)
              .startsWith(ControlMessage.RETAINED_UNSUPPORTED)
          || !TargetSession.startupFailureDetail(true, failure)
              .contains(failure.getClass().getName()))
        throw new AssertionError("Resource failure was classified as unsupported");
    for (String name :
        new String[] {
          "native-code-resource-v1.hex",
          "native-code-limit-v1.hex",
          "native-code-jvmti-resource-v1.hex",
          "native-code-jvmti-failure-v1.hex"
        }) {
      try (var input = RetainedSessionCheck.class.getResourceAsStream("/" + name)) {
        if (input == null) throw new AssertionError("Missing native producer envelope " + name);
        byte[] bytes =
            java.util.HexFormat.of()
                .parseHex(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII)
                        .strip());
        var code = CodeEvidence.read(bytes, 17, CodeEvidence.Stage.STARTUP);
        Throwable rejected;
        try {
          TargetSession.checkCodeStatus(code);
          throw new AssertionError("Failed native status was accepted");
        } catch (OutOfMemoryError | IllegalStateException failure) {
          rejected = failure;
        }
        boolean resource = code.jvmtiError() == CodeEvidence.OUT_OF_MEMORY;
        if ((rejected instanceof OutOfMemoryError) != resource
            || TargetSession.startupFailureDetail(true, rejected)
                    .startsWith(ControlMessage.RETAINED_UNSUPPORTED)
                == resource
            || !rejected.getMessage().contains("error " + code.jvmtiError()))
          throw new AssertionError("Native status classification was lost: " + name);
      }
    }
    Path directory = Path.of(args[1]);
    Files.createDirectories(directory.resolve("generated"));
    Files.createDirectories(directory.resolve("traces"));
    Files.writeString(
        directory.resolve("generated/observer.properties"),
        "version=1\nbindingMetadata=retained-class\n");
    Path endpoint = directory.resolve("session.sock").toAbsolutePath();
    Files.deleteIfExists(endpoint);
    var message = new AtomicReference<ControlMessage>();
    var serverFailure = new AtomicReference<Throwable>();
    try (var server = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(endpoint));
      var receiver =
          new Thread(
              () -> {
                try (var socket = server.accept();
                    var input = new java.io.DataInputStream(Channels.newInputStream(socket))) {
                  message.set(ControlMessage.read(input, 17));
                } catch (Throwable failure) {
                  serverFailure.set(failure);
                }
              });
      receiver.start();
      var launch =
          new String[] {endpoint.toString(), "17", directory.toString(), args[0], "10000", "16"};
      IllegalStateException original;
      try {
        var session =
            args[2].equals("legacy")
                ? TargetSession.open(launch)
                : TargetSession.open(launch, RetainedSessionCheck.class);
        session.close();
        throw new AssertionError("Uncertified startup succeeded");
      } catch (IllegalStateException failure) {
        original = failure;
      }
      receiver.join(5_000);
      if (receiver.isAlive() || serverFailure.get() != null)
        throw new AssertionError("Startup failure was not delivered", serverFailure.get());
      var received = message.get();
      String detail = original.getClass().getName() + ": " + original.getMessage();
      if (received == null
          || received.kind() != ControlMessage.Kind.FAILED
          || received.sequence() != 0
          || !(ControlMessage.RETAINED_UNSUPPORTED + detail).equals(received.detail()))
        throw new AssertionError("Startup cause was replaced: " + received);
    } finally {
      Files.deleteIfExists(endpoint);
    }
  }
}
