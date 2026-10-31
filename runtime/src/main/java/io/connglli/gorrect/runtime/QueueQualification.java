package io.connglli.gorrect.runtime;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** Pre-workload API exercise and image facts; admission remains an independent host decision. */
final class QueueQualification {
  private QueueQualification() {}

  static Properties exercise() throws IOException {
    if (Reference.class.getClassLoader() != null || ReferenceQueue.class.getClassLoader() != null)
      throw new IllegalStateException("Queue implementation is not bootstrap-defined");
    for (boolean soft : new boolean[] {false, true}) {
      Object target = new Object();
      Reference<Object> noQueue = soft ? new SoftReference<>(target) : new WeakReference<>(target);
      boolean returned, cleared;
      try {
        returned = noQueue.enqueue();
        cleared = noQueue.refersTo(null);
      } finally {
        // The null assertion must exercise enqueue clearing, not accidental prior GC processing.
        Reference.reachabilityFence(target);
      }
      if (returned || !cleared)
        throw new IllegalStateException("Queue API does not clear on failed no-queue enqueue");

      var queue = new ReferenceQueue<Object>();
      var other = new ReferenceQueue<Object>();
      Reference<Object> registered =
          soft ? new SoftReference<>(null, queue) : new WeakReference<>(null, queue);
      registered.clear();
      if (queue.poll() != null
          || other.poll() != null
          || !registered.enqueue()
          || registered.enqueue()
          || other.poll() != null
          || queue.poll() != registered
          || registered.enqueue()
          || queue.poll() != null)
        throw new IllegalStateException("Queue API insertion/consumption qualification failed");
    }
    var facts = new Properties();
    facts.setProperty("observer.queues.ready", "true");
    facts.setProperty("observer.queues.bootstrap", "true");
    for (String name : new String[] {"Reference", "ReferenceQueue", "ReferenceQueue$Null"}) {
      String digest = "missing";
      try (var in = ReferenceQueue.class.getResourceAsStream(name + ".class")) {
        if (in != null) {
          byte[] bytes = in.readNBytes(1024 * 1024 + 1);
          if (bytes.length > 1024 * 1024) throw new IOException("Queue library exceeds byte limit");
          digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
      } catch (NoSuchAlgorithmException unavailable) {
        throw new IllegalStateException("SHA-256 is unavailable", unavailable);
      }
      facts.setProperty("queue.library." + name, digest);
    }
    return facts;
  }
}
