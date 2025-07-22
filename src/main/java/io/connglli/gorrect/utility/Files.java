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

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import lombok.NonNull;

public final class Files {

  public static boolean deleteFileTreeIfExists(@NonNull Path path) {
    File file = path.toFile();
    if (!file.exists()) {
      return true;
    }
    if (file.isFile()) {
      try {
        java.nio.file.Files.delete(path);
        return true;
      } catch (NoSuchFileException e) {
        Assert.panic("Cannot reach here, file should exist: " + path);
        return false;
      } catch (DirectoryNotEmptyException e) {
        Assert.panic("Cannot reach here, file should not be a directory: " + path);
        return false;
      } catch (SecurityException | IOException e) {
        /* pass */
        return false;
      }
    }
    try {
      java.nio.file.Files.walkFileTree(
          path,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              java.nio.file.Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              java.nio.file.Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
      return true;
    } catch (SecurityException | IOException e) {
      return false;
    }
  }
}
