/*
 * MIT License
 *
 * Copyright (c) 2025 Cong Li (cong.li@inf.ethz.ch)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.connglli.gorrect.utility;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class Logger {
  public enum AnsiColor {
    RESET("\u001B[0m"),
    BLACK("\u001B[30m"),
    RED("\u001B[31m"),
    GREEN("\u001B[32m"),
    YELLOW("\u001B[33m"),
    BLUE("\u001B[34m"),
    MAGENTA("\u001B[35m"),
    CYAN("\u001B[36m"),
    WHITE("\u001B[37m"),
    BRIGHT_BLACK("\u001B[90m"),
    BRIGHT_RED("\u001B[91m"),
    BRIGHT_GREEN("\u001B[92m"),
    BRIGHT_YELLOW("\u001B[93m"),
    BRIGHT_BLUE("\u001B[94m"),
    BRIGHT_MAGENTA("\u001B[95m"),
    BRIGHT_CYAN("\u001B[96m"),
    BRIGHT_WHITE("\u001B[97m");

    final String code;

    AnsiColor(String code) {
      this.code = code;
    }
  }

  private static Level settingLevel = Level.INFO;
  private static final ThreadLocal<Boolean> settingHeadless = ThreadLocal.withInitial(() -> true);
  private static final ThreadLocal<PrintStream> settingOutput =
      ThreadLocal.withInitial(() -> System.out);

  @Getter private final String name;

  // - ===========================================
  // - Log Settings
  // - ===========================================

  public static Logger getLogger(Class<?> clazz) {
    return new Logger(clazz.getSimpleName());
  }

  public static OutputStream getOutput() {
    return settingOutput.get();
  }

  public static Level getLevel() {
    return settingLevel;
  }

  public static boolean isHeadless() {
    return settingHeadless.get();
  }

  public static void setLevel(Level level) {
    settingLevel = level;
  }

  public static void enableHeadless() {
    settingHeadless.set(true);
  }

  public static void disableHeadless() {
    settingHeadless.set(false);
  }

  public static void setOutput(OutputStream out) {
    settingOutput.set(new PrintStream(out));
  }

  public void log(Level level, String message, Object... args) {
    log(level, null, message, args);
  }

  public void log(Level level, AnsiColor color, String message, Object... args) {
    if (level.intValue() < settingLevel.intValue()) {
      return;
    }
    String fullMessage = safeFormat(message, args);
    if (!settingHeadless.get()) {
      fullMessage =
          "[%s::%s::%s] [%s] %s"
              .formatted(
                  java.time.LocalDateTime.now()
                      .format(DateTimeFormatter.ofPattern("yyMMdd-HH:mm:ss")),
                  Thread.currentThread().getName(),
                  name,
                  level.getName(),
                  fullMessage);
    }
    if (color == null) {
      if (level.equals(Level.SEVERE)) {
        color = AnsiColor.RED;
      } else if (level.equals(Level.WARNING)) {
        color = AnsiColor.YELLOW;
      }
    }
    if (color != null && color != AnsiColor.RESET) {
      fullMessage = color.code + fullMessage + AnsiColor.RESET.code;
    }
    settingOutput.get().println(fullMessage);
  }

  // - ===========================================
  // - Logging Methods
  // - ===========================================

  public void error(Throwable t) {
    log(Level.SEVERE, "%s: %s", t.getClass().getCanonicalName(), t.getMessage());
    for (StackTraceElement e : t.getStackTrace()) {
      log(Level.SEVERE, "    at " + e.toString());
    }
    for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
      log(Level.SEVERE, "  Caused by %s: %s", c.getClass().getCanonicalName(), c.getMessage());
      for (StackTraceElement e : c.getStackTrace()) {
        log(Level.SEVERE, "    at " + e.toString());
      }
    }
    Assert.panic("");
  }

  public void error(String message, Object... args) {
    log(Level.SEVERE, safeFormat(message, args));
    Assert.panic(safeFormat(message, args));
  }

  public void warning(String message, Object... args) {
    log(Level.WARNING, safeFormat(message, args));
  }

  public void debug(AnsiColor color, String message, Object... args) {
    log(Level.FINE, color, message, args);
  }

  public void debug(String message, Object... args) {
    log(Level.FINE, message, args);
  }

  public void info(AnsiColor color, String message, Object... args) {
    log(Level.INFO, color, message, args);
  }

  public void info(String message, Object... args) {
    log(Level.INFO, message, args);
  }

  private static String safeFormat(String fmt, Object... args) {
    if (args.length == 0) {
      return fmt;
    } else {
      return fmt.formatted(args);
    }
  }
}
