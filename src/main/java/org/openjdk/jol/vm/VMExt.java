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

package org.openjdk.jol.vm;

import io.connglli.gorrect.utility.Assert;
import io.connglli.gorrect.utility.Logger;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import org.openjdk.jol.vm.sa.ServiceabilityAgentSupport;
import org.openjdk.jol.vm.sa.UniverseData;
import sun.misc.Unsafe;

/** VM2 reimplements VM to be thread-safe and compatible to all HotSpot-series JVMs. */
public class VMExt {

  private static volatile VirtualMachine INSTANCE;
  private static volatile Logger LOGGER;

  public static VirtualMachine current() {
    if (INSTANCE == null) {
      synchronized (VMExt.class) {
        if (INSTANCE == null) {
          LOGGER = Logger.getLogger(VMExt.class);

          String name = System.getProperty("java.vm.name");

          // We support only HotSpot/OpenJDK-series VMs
          if (!name.contains("HotSpot")
              && !name.contains("OpenJDK")
              && !name.contains("Zing")
              && !name.contains("Graal")) {
            Assert.panic(
                "Only HotSpot/OpenJDK VMs are supported while the current JVM is %s", name);
          }

          Unsafe u = tryLoadUnsafe();
          if (u == null) {
            Assert.panic("Unsafe was failed to be loaded.");
          }

          Instrumentation inst = null;
          try {
            inst = InstrumentationSupport.instance();
          } catch (Exception e) {
            LOGGER.warning("Unable to get Instrumentation. " + e.getMessage());
          }

          try {
            UniverseData saDetails = ServiceabilityAgentSupport.instance().getUniverseData();
            INSTANCE = new HotspotUnsafe(u, inst, saDetails);
          } catch (Exception e) {
            LOGGER.warning("Unable to attach Serviceability Agent. " + e.getMessage());
            INSTANCE = new HotspotUnsafe(u, inst);
          }
        }
      }
    }

    return INSTANCE;
  }

  private static Unsafe tryLoadUnsafe() {
    try {
      Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
      unsafe.setAccessible(true);
      return (Unsafe) unsafe.get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      LOGGER.warning("Unable to get Unsafe: " + e.getMessage());
      return null;
    }
  }
}
