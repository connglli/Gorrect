package io.connglli.gorrect.engine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Strict JSON input with duplicate detection before schema validation can lose a field. */
public final class JsonFiles {
  public static final Gson GSON =
      new GsonBuilder()
          .setStrictness(Strictness.STRICT)
          .setPrettyPrinting()
          .serializeNulls()
          .create();

  private JsonFiles() {}

  public static JsonObject read(Path path) throws IOException {
    if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("JSON exceeds byte limit");
    try (var reader = new JsonReader(Files.newBufferedReader(path))) {
      reader.setStrictness(Strictness.STRICT);
      var element = read(reader, 0);
      if (reader.peek() != JsonToken.END_DOCUMENT || !element.isJsonObject())
        throw new IOException("Expected one JSON object");
      return element.getAsJsonObject();
    } catch (IllegalStateException | NumberFormatException exception) {
      throw new IOException("Invalid JSON: " + exception.getMessage(), exception);
    }
  }

  private static JsonElement read(JsonReader in, int depth) throws IOException {
    if (depth > 64) throw new IOException("JSON nesting limit exceeded");
    return switch (in.peek()) {
      case BEGIN_OBJECT -> {
        var object = new JsonObject();
        in.beginObject();
        while (in.hasNext()) {
          String name = in.nextName();
          if (object.has(name)) throw new IOException("Duplicate JSON field " + name);
          object.add(name, read(in, depth + 1));
        }
        in.endObject();
        yield object;
      }
      case BEGIN_ARRAY -> {
        var array = new JsonArray();
        in.beginArray();
        while (in.hasNext()) array.add(read(in, depth + 1));
        in.endArray();
        yield array;
      }
      case STRING -> new JsonPrimitive(in.nextString());
      case NUMBER -> new JsonPrimitive(new BigDecimal(in.nextString()));
      case BOOLEAN -> new JsonPrimitive(in.nextBoolean());
      case NULL -> {
        in.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new IOException("Invalid JSON value");
    };
  }

  public static void write(Path path, Object value) throws IOException {
    // Atomic replacement keeps interrupted verification from destroying an earlier report.
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".json-", ".tmp");
    try {
      Files.writeString(temporary, GSON.toJson(value) + "\n");
      try {
        Files.move(
            temporary,
            path,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static void fields(JsonObject object, String... allowed) {
    var names = Set.of(allowed);
    for (String name : object.keySet())
      if (!names.contains(name)) throw new IllegalArgumentException("Unknown JSON field " + name);
  }

  public static JsonElement required(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || value.isJsonNull())
      throw new IllegalArgumentException("Missing JSON field " + name);
    return value;
  }

  public static String string(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected JSON string");
    return value.getAsString();
  }

  public static long number(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected JSON integer");
    try {
      return value.getAsBigDecimal().longValueExact();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("JSON integer outside range", exception);
    }
  }
}
