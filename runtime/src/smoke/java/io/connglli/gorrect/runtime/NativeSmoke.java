package io.connglli.gorrect.runtime;

import java.nio.file.Path;

/** Loads the same library through the agent and JNI entry points. */
public final class NativeSmoke {
  private NativeSmoke() {}

  public static void main(String[] args) {
    if (args.length != 1) {
      throw new IllegalArgumentException("Expected the native library path");
    }
    System.load(Path.of(args[0]).toAbsolutePath().toString());
    System.out.println("Native loading passed on " + System.getProperty("java.runtime.version"));
  }
}
