package io.connglli.gorrect.runtime;

import io.connglli.gorrect.protocol.ControlMessage;
import io.connglli.gorrect.protocol.ExecutionTrace;
import io.connglli.gorrect.runtime.observe.NativeObserver;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** One target process's endpoint and bounded scalar recorder. It owns no workload references. */
public final class TargetSession implements AutoCloseable {
  private final SocketChannel socket;
  private final DataInputStream in;
  private final DataOutputStream out;
  private final long run;
  private final Path directory;
  private final int records;
  private final int[] operations;
  private final byte[] kinds;
  private final long[] values;
  private final Map<Integer, Long> visits = new HashMap<>();
  private int count;
  private long checkpoint;
  private boolean complete;

  private TargetSession(String[] args) throws IOException {
    if (args.length != 6) throw new IllegalArgumentException("Invalid target launch arguments");
    run = Long.parseLong(args[1]);
    directory = Path.of(args[2]);
    records = Integer.parseInt(args[4]);
    int capacity = Integer.parseInt(args[5]);
    if (run <= 0 || capacity <= 0 || capacity > ExecutionTrace.MAX_EVENTS)
      throw new IllegalArgumentException("Invalid trace capacity or run token");
    operations = new int[capacity];
    kinds = new byte[capacity];
    values = new long[capacity];
    socket = SocketChannel.open(UnixDomainSocketAddress.of(args[0]));
    in = new DataInputStream(Channels.newInputStream(socket));
    out = new DataOutputStream(Channels.newOutputStream(socket));
    try {
      System.load(args[3]);
      var properties = new Properties();
      for (String name :
          new String[] {
            "java.version", "java.vm.name", "java.vm.version", "java.vendor", "os.name", "os.arch"
          }) properties.setProperty(name, System.getProperty(name));
      properties.setProperty("java.feature", Integer.toString(Runtime.version().feature()));
      properties.setProperty("observer.capabilities", Long.toString(NativeObserver.capabilities()));
      properties.setProperty(
          "collectors",
          String.join(
              ",",
              ManagementFactory.getGarbageCollectorMXBeans().stream()
                  .map(bean -> bean.getName())
                  .toList()));
      try (var stream = Files.newOutputStream(directory.resolve("target.properties"))) {
        properties.store(stream, "Target identity");
      }
      send(ControlMessage.Kind.HELLO, 0, 0, "");
      acknowledge(0);
    } catch (Throwable failure) {
      socket.close();
      throw failure;
    }
  }

  public static TargetSession open(String[] args) throws IOException {
    return new TargetSession(args);
  }

  /**
   * Records without allocation or serialization. Kind is an ExecutionTrace.Kind ordinal; exhaustion
   * stops the case before it can claim a complete trace.
   */
  public void record(int operation, int kind, long value) {
    if (count == operations.length) throw new IllegalStateException("Trace capacity exhausted");
    operations[count] = operation;
    kinds[count] = (byte) kind;
    values[count++] = value;
  }

  /** Call at a named-root checkpoint; generated code must have no live GIR reference bindings. */
  public void checkpoint(int operation) throws IOException {
    long occurrence = visits.merge(operation, 1L, Long::sum);
    checkpoint++;
    byte[] bytes = NativeObserver.capture(run, checkpoint, records);
    Files.write(directory.resolve("snapshots").resolve(checkpoint + ".gsnap"), bytes);
    send(ControlMessage.Kind.CHECKPOINT, operation, occurrence, "");
    acknowledge(checkpoint);
  }

  public void complete() throws IOException {
    writeTrace(true);
    send(ControlMessage.Kind.COMPLETE, 0, 0, "");
    complete = true;
  }

  public void fail(Throwable failure) throws IOException {
    writeTrace(false);
    // Bound failure text to the modified-UTF length used by control frames.
    String detail = failure.getClass().getName() + ": " + failure.getMessage();
    send(ControlMessage.Kind.FAILED, 0, 0, detail.substring(0, Math.min(detail.length(), 4000)));
  }

  private void writeTrace(boolean finished) throws IOException {
    var events = new ArrayList<ExecutionTrace.Event>(count);
    var eventKinds = ExecutionTrace.Kind.values();
    for (int i = 0; i < count; i++)
      events.add(new ExecutionTrace.Event(operations[i], eventKinds[kinds[i]], values[i]));
    new ExecutionTrace(run, finished, events).write(directory.resolve("traces/execution.gtrace"));
  }

  private void send(ControlMessage.Kind kind, int operation, long occurrence, String detail)
      throws IOException {
    new ControlMessage(kind, run, checkpoint, operation, occurrence, detail).write(out);
  }

  private void acknowledge(long sequence) throws IOException {
    var message = ControlMessage.read(in, run);
    if (message.kind() != ControlMessage.Kind.ACK || message.sequence() != sequence)
      throw new IOException("Unexpected engine acknowledgement");
  }

  @Override
  public void close() throws IOException {
    try {
      if (!complete) writeTrace(false);
    } finally {
      socket.close();
    }
  }
}
