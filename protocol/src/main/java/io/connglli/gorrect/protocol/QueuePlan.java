package io.connglli.gorrect.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Source-derived selection and static resource roles; it contains no expected queue members. */
public record QueuePlan(List<Resource> resources) {
  public static final int POLICY = 1;
  public static final int MAX_BYTES = 65_536;
  public static final String ROOTS = "LCase$QueueRoots;";
  public static final String TYPE = "LCase$Queue;";

  /** Nominal source type and the fixed generated field corresponding to a resource ID. */
  public record Resource(int id, String name, String shape) {
    public Resource {
      if (id <= 0 || id > QueueHistory.MAX_QUEUES || !identifier(name) || !identifier(shape))
        throw new IllegalArgumentException("Invalid queue resource");
    }

    public String field() {
      return "q" + id;
    }
  }

  public QueuePlan {
    resources = List.copyOf(resources);
    var names = new HashSet<String>();
    if (resources.size() > QueueHistory.MAX_QUEUES)
      throw new IllegalArgumentException("Queue resource limit exceeded");
    for (int i = 0; i < resources.size(); i++)
      if (resources.get(i).id() != i + 1 || !names.add(resources.get(i).name()))
        throw new IllegalArgumentException("Duplicate or unordered queue resource");
  }

  private static boolean identifier(String value) {
    return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*") && !value.equals("null");
  }

  private String text() {
    var out = new StringBuilder("version=1\ncount=" + resources.size() + "\n");
    for (var resource : resources)
      out.append("queue.")
          .append(resource.id())
          .append('=')
          .append(resource.name())
          .append(',')
          .append(resource.shape())
          .append('\n');
    return out.toString();
  }

  /** Writes canonical bounded selection, including count zero for enqueue-only cases. */
  public void write(Path path) throws IOException {
    String text = text();
    if (text.length() > MAX_BYTES) throw new IOException("Queue plan exceeds byte limit");
    Files.writeString(path, text, StandardCharsets.US_ASCII);
  }

  /** Strict canonical parsing rejects duplicate, unknown, missing and hidden selection fields. */
  public static QueuePlan read(Path path) throws IOException {
    if (Files.size(path) > MAX_BYTES) throw new IOException("Queue plan exceeds byte limit");
    String text = Files.readString(path, StandardCharsets.US_ASCII);
    try {
      String[] lines = text.split("\n", -1);
      if (lines.length < 3 || !lines[0].equals("version=1") || !lines[1].startsWith("count="))
        throw new IllegalArgumentException("Invalid queue plan header");
      int count = Integer.parseInt(lines[1].substring(6));
      if (count < 0 || count > QueueHistory.MAX_QUEUES || lines.length != count + 3)
        throw new IllegalArgumentException("Invalid queue plan count");
      var resources = new ArrayList<Resource>();
      for (int i = 1; i <= count; i++) {
        String prefix = "queue." + i + "=";
        if (!lines[i + 1].startsWith(prefix)) throw new IllegalArgumentException("Missing queue");
        String[] fields = lines[i + 1].substring(prefix.length()).split(",", -1);
        if (fields.length != 2) throw new IllegalArgumentException("Invalid queue resource");
        resources.add(new Resource(i, fields[0], fields[1]));
      }
      var plan = new QueuePlan(resources);
      if (!text.equals(plan.text())) throw new IllegalArgumentException("Noncanonical queue plan");
      return plan;
    } catch (IllegalArgumentException malformed) {
      throw new IOException(malformed.getMessage(), malformed);
    }
  }
}
