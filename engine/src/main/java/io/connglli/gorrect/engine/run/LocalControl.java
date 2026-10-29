package io.connglli.gorrect.engine.run;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/** One local connection, with absolute deadlines across partial frame reads and writes. */
final class LocalControl implements Closeable {
  private final Path directory;
  private final Path address;
  private ServerSocketChannel server;
  private Selector selector;
  private SocketChannel connection;
  private SelectionKey key;
  private long deadline;

  LocalControl() throws IOException {
    // A short temporary path keeps long run-directory names out of the OS socket-path limit.
    directory =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
            ? Files.createTempDirectory(
                "gorrect-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            : Files.createTempDirectory("gorrect-");
    address = directory.resolve("control");
    try {
      server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      server.configureBlocking(false);
      server.bind(UnixDomainSocketAddress.of(address));
      selector = Selector.open();
      key = server.register(selector, SelectionKey.OP_ACCEPT);
    } catch (IOException | RuntimeException | Error failure) {
      try {
        close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  Path address() {
    return address;
  }

  void deadline(long value) {
    deadline = value;
  }

  void accept(long limit) throws IOException {
    deadline(limit);
    while ((connection = server.accept()) == null) await(SelectionKey.OP_ACCEPT);
    key.cancel();
    selector.selectNow();
    selector.selectedKeys().clear();
    connection.configureBlocking(false);
    key = connection.register(selector, 0);
  }

  DataInputStream input() {
    return new DataInputStream(
        new InputStream() {
          private final byte[] single = new byte[1];

          @Override
          public int read() throws IOException {
            return read(single, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(single[0]);
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            var buffer = ByteBuffer.wrap(bytes, offset, length);
            while (true) {
              remaining();
              int count = connection.read(buffer);
              if (count != 0) return count;
              await(SelectionKey.OP_READ);
            }
          }
        });
  }

  DataOutputStream output() {
    return new DataOutputStream(
        new OutputStream() {
          private final byte[] single = new byte[1];

          @Override
          public void write(int value) throws IOException {
            single[0] = (byte) value;
            write(single, 0, 1);
          }

          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            var buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) {
              remaining();
              if (connection.write(buffer) == 0) await(SelectionKey.OP_WRITE);
            }
          }
        });
  }

  private void await(int operation) throws IOException {
    key.interestOps(operation);
    while (true) {
      int ready = selector.select(remaining());
      selector.selectedKeys().clear();
      if (ready > 0) return;
    }
  }

  private long remaining() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new ClosedByInterruptException();
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) throw new SocketTimeoutException("Control deadline exceeded");
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (var resource : new Closeable[] {connection, server, selector}) {
      if (resource != null)
        try {
          resource.close();
        } catch (IOException exception) {
          if (failure == null) failure = exception;
          else failure.addSuppressed(exception);
        }
    }
    // Closing a Unix domain channel does not unlink its filesystem entry.
    for (var path : new Path[] {address, directory})
      try {
        Files.deleteIfExists(path);
      } catch (IOException exception) {
        if (failure == null) failure = exception;
        else failure.addSuppressed(exception);
      }
    if (failure != null) throw failure;
  }
}
