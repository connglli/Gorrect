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

package io.connglli.gorrect.java;

import io.connglli.gorrect.utility.Jcmd;
import io.connglli.gorrect.utility.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NonNull;

/// A heap dumper that dumps the heap of a process (set by PID) to the given hprof file when the
/// process is ready, i.e., when the process starts to read something from us.
public class HprofDumper extends InputStream {
  private final Logger log = Logger.getLogger(JavaExecutor.class);

  @NonNull private final String file;
  @NonNull private final AtomicLong pid;

  boolean succeeded = false;
  volatile boolean closed = false;

  public HprofDumper(@NonNull String file) {
    this.file = file;
    // Initialize the pid to -1L to indicate not started
    this.pid = new AtomicLong(-1L);
  }

  public long getPid() {
    return pid.get();
  }

  public void setPid(long pid) {
    this.pid.set(pid);
  }

  @Override
  public int read() throws IOException {
    if (closed) {
      return -1; // EOF
    }
    // Wait until the process is started
    while (pid.get() == -1L) {
      try {
        Thread.sleep(5L);
      } catch (InterruptedException e) {
        log.error(
            "Thread interrupted while waiting for the generated java process to start: %s", e);
        closed = true;
        return -1; // EOF if interrupted
      }
    }
    try {
      Jcmd.dumpHeap(pid.get(), file, /* dead= */ false);
      succeeded = true;
    } catch (Exception e) {
      succeeded = false;
    }
    closed = true;
    return '\n'; // Signal that we have finished dumping the heap
  }
}
