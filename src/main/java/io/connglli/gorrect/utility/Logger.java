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

import java.io.PrintStream;
import java.util.logging.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class Logger {

  private static Level settingLevel = Level.INFO;
  private static boolean settingHeadless = true;
  private static PrintStream settingOutput = System.out;

  @Getter private final String name;

  // - ===========================================
  // - Log Settings
  // - ===========================================

  public static Logger getLogger(Class<?> clazz) {
    return new Logger(clazz.getName());
  }

  public static void setLevel(Level level) {
    settingLevel = level;
  }

  public static void enableHeadless() {
    settingHeadless = true;
  }

  public static void disableHeadless() {
    settingHeadless = false;
  }

  public static void setOutput(java.io.OutputStream out) {
    settingOutput = new PrintStream(out);
  }

  public void log(Level level, String message) {
    if (level.intValue() < settingLevel.intValue()) {
      return;
    }
    if (settingHeadless) {
      settingOutput.println(message);
    } else {
      settingOutput.printf(
          "[%s] [%s] [%s:%s]: %s%n",
          java.time.LocalDateTime.now(),
          Thread.currentThread().getName(),
          name,
          level.getName(),
          message);
    }
  }

  // - ===========================================
  // - Logging Methods
  // - ===========================================

  public void severe(String message) {
    log(Level.SEVERE, message);
  }

  public void warning(String message) {
    log(Level.WARNING, message);
  }

  public void debug(String message) {
    log(Level.FINE, message);
  }

  public void fine(String message) {
    log(Level.FINE, message);
  }

  public void info(String message) {
    log(Level.INFO, message);
  }
}
