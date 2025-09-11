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

import io.connglli.gorrect.java.oracle.JavaVM;
import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Logger;
import io.connglli.gorrect.utility.MemorySize;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.listener.ProcessListener;

/// JavaExecutor execute the generated Java code and dump the heap if necessary.
@RequiredArgsConstructor
public class JavaExecutor {
  private final Logger log = Logger.getLogger(JavaExecutor.class);

  @Getter
  @NonNull private final JavaVM jvm;

  public enum ExitCode {
    SUCCESS,
    COMPILATION_TIMEOUT,
    COMPILATION_FAILURE,
    HEAP_DUMP_FAILURE,
    EXECUTION_FAILURE,
    EXECUTION_ASSERTION,
    EXECUTION_TIMEOUT,
    UNKNOWN_FAILURE
  }

  public record Result(@NonNull ExitCode exitCode, String errorMessage, Throwable exception) {
    public static Result success() {
      return new Result(ExitCode.SUCCESS, null, null);
    }

    public static Result failure(@NonNull ExitCode code, @NonNull String errorMessage) {
      return new Result(code, errorMessage, null);
    }

    public static Result failure(
        @NonNull ExitCode code, @NonNull String errorMessage, @NonNull Throwable exception) {
      return new Result(code, errorMessage, exception);
    }
  }

  /// Execute the Java file in the given workdir, dump the heap, and validate the result.
  /// If dumpFile is provided to true, a heap dump will be created within the workdir.
  public Result execute(
      @NonNull String javaFile,
      String dumpFile,
      @NonNull String workdir,
      String... extraExecOptions) {
    String className = // Convert the file name to a full-qualified class name
        javaFile.substring(0, javaFile.indexOf(".java")).replace(File.separator, ".");

    // Compile the generated Java code; will throw an exception if the compilation fails
    ByteArrayOutputStream oss = new ByteArrayOutputStream();
    try {
      new ProcessExecutor()
          .command(jvm.getJavaCompilerPath(), "-cp", workdir, workdir + File.separator + javaFile)
          .redirectOutput(oss)
          .redirectErrorStream(true)
          .timeout(30, TimeUnit.SECONDS) // A simple compilation should finish within 30 seconds
          .exitValue(0) // Throw an exception if the exit value is not 0
          .execute();
    } catch (InvalidExitValueException e) {
      return Result.failure(ExitCode.COMPILATION_FAILURE, oss.toString().strip(), e);
    } catch (TimeoutException e) {
      return Result.failure(ExitCode.COMPILATION_TIMEOUT, oss.toString().strip(), e);
    } catch (Exception e) {
      return Result.failure(ExitCode.UNKNOWN_FAILURE, oss.toString().strip(), e);
    }

    // Prepare arguments for executing the generated Java code
    List<String> command = new ArrayList<>();
    command.add(jvm.getJavaExecutablePath());
    command.add("-ea"); // Enable assertion checks
    command.add("-cp");
    command.add(workdir);
    command.add("-Xmx" + MemorySize.toString(jvm.getHeapCapacity(), 0).replace("B", ""));
    command.add("-Xms" + MemorySize.toString(jvm.getHeapCapacity(), 0).replace("B", ""));
    for (String opt : extraExecOptions) {
      if (opt != null && !opt.isEmpty()) {
        command.add(opt);
      }
    }
    command.add(className);

    // Prepare a heap dumper if necessary
    HprofDumper dumper = dumpFile == null ? null : heapDumper(workdir + File.separator + dumpFile);

    // Execute the Java code
    log.debug("Executing the Java class by command: %s", String.join(" ", command));
    ProcessExecutor executor =
        new ProcessExecutor(command)
            .timeout(1, TimeUnit.MINUTES)
            .redirectErrorStream(true) // Redirect stderr to stdout
            .exitValue(0); // Throw an exception if the exit value is not 0
    if (dumper != null) {
      oss = dumper.getOut();
      executor
          .listener(
              new ProcessListener() {
                @Override
                public void afterStart(Process process, ProcessExecutor executor) {
                  dumper.setPid(process.pid());
                }
              })
          .redirectInput(dumper);
    } else {
      oss = new ByteArrayOutputStream();
    }
    executor.redirectOutput(oss);
    try {
      executor.execute();
    } catch (InvalidExitValueException e) {
      String errMsg = oss.toString().strip();
      if (errMsg.contains("AssertionError") && errMsg.contains("oracle state")) {
        return Result.failure(ExitCode.EXECUTION_ASSERTION, errMsg, e);
      } else {
        return Result.failure(ExitCode.EXECUTION_FAILURE, errMsg, e);
      }
    } catch (TimeoutException e) {
      return Result.failure(ExitCode.EXECUTION_TIMEOUT, oss.toString().strip(), e);
    } catch (Exception e) {
      return Result.failure(ExitCode.UNKNOWN_FAILURE, oss.toString().strip(), e);
    }

    // Check if the heap dump succeeded
    if (dumper != null && !dumper.isSucceeded()) {
      return Result.failure(
          ExitCode.HEAP_DUMP_FAILURE, oss.toString().strip(), dumper.getException());
    }

    return Result.success();
  }

  /// Create a heap dumper that dumps the heap of a process (set by PID) to the given hprof file.
  public HprofDumper heapDumper(@NonNull String hprof) {
    Assert.set(!(new File(hprof).exists()), "The hprof file %s already exists", hprof);
    return new HprofDumper(jvm, hprof);
  }
}
