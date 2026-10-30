package io.connglli.gorrect.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalControlTest {
  @Test
  void boundsAcceptAndRemovesThePrivateSocketDirectory() throws Exception {
    Path address;
    try (var control = new LocalControl()) {
      address = control.address();
      assertTrue(Files.exists(address));
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(address.getParent()));
      assertThrows(
          SocketTimeoutException.class,
          () -> control.accept(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25)));
    }
    assertFalse(Files.exists(address));
    assertFalse(Files.exists(address.getParent()));
  }

  @Test
  void partialFramesCannotRestartTheDeadline() throws Exception {
    try (var control = new LocalControl();
        var peer = SocketChannel.open(UnixDomainSocketAddress.of(control.address()))) {
      control.accept(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
      peer.write(ByteBuffer.wrap(new byte[] {1}));
      control.deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25));
      assertThrows(SocketTimeoutException.class, () -> control.input().readInt());
      peer.write(ByteBuffer.wrap(new byte[] {2}));
      control.deadline(System.nanoTime() - 1);
      assertThrows(SocketTimeoutException.class, () -> control.input().read());
    }
  }

  @Test
  void interruptionStopsWaiting() throws Exception {
    try (var control = new LocalControl()) {
      Thread.currentThread().interrupt();
      try {
        assertThrows(
            ClosedByInterruptException.class,
            () -> control.accept(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
      } finally {
        Thread.interrupted();
      }
    }
  }
}
