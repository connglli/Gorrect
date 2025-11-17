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

package io.connglli.gorrect.java.oracle;

import io.connglli.gorrect.utility.Assert;
import java.io.File;
import java.io.PrintStream;
import lombok.NonNull;
import org.openjdk.jol.vm.ModelVMExt;

public final class HostJVM {

  public static @NonNull JavaVM getInstance(long heapCapacity, String... defaultOptions) {
    return switch (getBrandName()) {
      case HotSpotVM.BRAND_NAME ->
          new HotSpotVM(new File(getHome()), heapCapacity, getJavaMajorVersion(), new ModelVMExt());

      case GraalVM.BRAND_NAME ->
          new GraalVM(new File(getHome()), heapCapacity, getJavaMajorVersion(), new ModelVMExt());

      case ZingVM.BRAND_NAME ->
          new ZingVM(new File(getHome()), heapCapacity, getJavaMajorVersion(), new ModelVMExt());

      default -> {
        Assert.panic("Not yet implemented for JVM brand: " + getBrandName());
        throw new RuntimeException(); // Cannot reach here, but needed for compilation
      }
    };
  }

  public static @NonNull String getHome() {
    return safeGetProperty("java.home");
  }

  public static @NonNull String getName() {
    return safeGetProperty("java.vm.name");
  }

  public static @NonNull String getBrandName() {
    String vmVendor = getVendor().toLowerCase();
    String vmName = getName().toLowerCase();
    if (vmName.contains("hotspot") || vmName.contains("openjdk")) {
      return HotSpotVM.BRAND_NAME;
    } else if (vmName.contains("j9") || vmName.contains("openj9")) {
      return OpenJ9VM.BRAND_NAME;
    } else if (vmName.contains("graalvm") || vmVendor.contains("graalvm")) {
      return GraalVM.BRAND_NAME;
    } else if (vmName.contains("azul") || vmName.contains("zing")) {
      return ZingVM.BRAND_NAME;
    }
    Assert.panic("Unsupported JVM brand: " + vmName);
    return null; // Cannot reach here, but needed for compilation
  }

  public static @NonNull String getVendor() {
    return safeGetProperty("java.vm.vendor");
  }

  public static @NonNull String getVersion() {
    return safeGetProperty("java.vm.version");
  }

  public static int getJavaMajorVersion() {
    String version = getJavaVersion();
    if (version.startsWith("1.")) {
      return Integer.parseInt(version.substring(2, 3)); // Java 1.x versions
    } else if (version.contains(".")) {
      return Integer.parseInt(version.split("\\.")[0]); // Java 9 and above
    } else if (version.contains("-internal")) {
      return Integer.parseInt(version.split("-")[0].split("\\.")[0]); // Trunk versions
    } else {
      Assert.panic("Unrecognized java.version format: " + version);
      return -1; // Cannot reach here, but needed for compilation
    }
  }

  public static int getJavaMinorVersion() {
    String version = getJavaVersion();
    if (version.startsWith("1.")) {
      return Integer.parseInt(version.substring(2, 3)); // Java 1.x versions
    } else if (version.contains(".")) {
      String[] parts = version.split("\\.");
      return parts.length > 1 ? Integer.parseInt(parts[1]) : 0; // Java 9 and above
    } else if (version.contains("-internal")) {
      return 0; // Trunk versions
    } else {
      Assert.panic("Unrecognized java.version format: " + version);
      return -1; // Cannot reach here, but needed for compilation
    }
  }

  public static int getJavaPatchVersion() {
    String version = getJavaVersion();
    if (version.startsWith("1.")) {
      return Integer.parseInt(version.substring(2, 3)); // Java 1.x versions
    } else {
      String[] parts = version.split("\\.");
      return parts.length > 2 ? Integer.parseInt(parts[2]) : 0; // Java 9 and above
    }
  }

  public static @NonNull String getJavaVersion() {
    return safeGetProperty("java.version");
  }

  public static @NonNull String getJavaVendor() {
    return safeGetProperty("java.vendor");
  }

  public static @NonNull String getJavaVendorURL() {
    return safeGetProperty("java.vendor.url");
  }

  public static @NonNull String getJavaRuntimeName() {
    return safeGetProperty("java.runtime.name");
  }

  public static @NonNull String getJavaRuntimeVersion() {
    return safeGetProperty("java.runtime.version");
  }

  public static void printInfo(PrintStream ps) {
    ps.println("Java Version: " + getJavaVersion());
    ps.println("Java Vendor: " + getJavaVendor());
    ps.println("Java Vendor URL: " + getJavaVendorURL());
    ps.println("Java Home: " + getHome());
    ps.println("Java Runtime Name: " + getJavaRuntimeName());
    ps.println("Java Runtime Version: " + getJavaRuntimeVersion());
    ps.println("JVM Name: " + getName());
    ps.println("JVM Brand: " + getBrandName());
    ps.println("JVM Vendor: " + getVendor());
    ps.println("JVM Version: " + getVersion());
    ps.println("JVM Specification: " + safeGetProperty("java.vm.specification.name"));
    ps.println("JVM Specification Version: " + safeGetProperty("java.vm.specification.version"));
  }

  private static @NonNull String safeGetProperty(String key) {
    String value = System.getProperty(key);
    Assert.set(value != null, "Property '" + key + "' is not set in the system properties.");
    return value;
  }

  private HostJVM() {}

  public static void main(String[] args) {
    printInfo(System.out);
    JavaVM jvm = getInstance(1024L);
    jvm.printInfo(System.out);
  }
}
