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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

  /**
   * Reads one bounded JSON object, rejecting duplicate keys, excessive nesting, and trailing data.
   */
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

  /**
   * Replaces a JSON file through a sibling temporary file, using atomic replacement when supported.
   * Parent directories must exist. I/O failures propagate and the temporary file is cleaned up.
   */
  public static void write(Path path, Object value) throws IOException {
    // Atomic replacement keeps interrupted verification from destroying an earlier report.
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".json-", ".tmp");
    try {
      Files.writeString(temporary, GSON.toJson(value) + "\n");
      try {
        Files.move(
            temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Rejects unknown keys without requiring every allowed field to be present. */
  public static void fields(JsonObject object, String... allowed) {
    var names = Set.of(allowed);
    for (String name : object.keySet())
      if (!names.contains(name)) throw new IllegalArgumentException("Unknown JSON field " + name);
  }

  /** Returns a present, non-null field; absence and explicit JSON null are input errors. */
  public static JsonElement required(JsonObject object, String name) {
    var value = object.get(name);
    if (value == null || value.isJsonNull())
      throw new IllegalArgumentException("Missing JSON field " + name);
    return value;
  }

  /** Requires a JSON string without coercing numeric or boolean values. */
  public static String string(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected JSON string");
    return value.getAsString();
  }

  /** Requires an object value; a missing, null, or differently typed value is an input error. */
  public static JsonObject object(JsonElement value) {
    if (value == null || !value.isJsonObject())
      throw new IllegalArgumentException("Expected JSON object");
    return value.getAsJsonObject();
  }

  /** Requires an array value; a missing, null, or differently typed value is an input error. */
  public static JsonArray array(JsonElement value) {
    if (value == null || !value.isJsonArray())
      throw new IllegalArgumentException("Expected JSON array");
    return value.getAsJsonArray();
  }

  /**
   * Requires a JSON number exactly representable as a signed 64-bit integer, without truncation.
   */
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
