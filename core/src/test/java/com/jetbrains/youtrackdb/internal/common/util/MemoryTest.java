package com.jetbrains.youtrackdb.internal.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import org.junit.Test;

public class MemoryTest {

  @Test
  public void testGetCappedRuntimeMaxMemoryReturnsActualWhenNotUnlimited() {
    // When JVM reports a finite max memory (typical case), the method should
    // return Runtime.maxMemory() regardless of the cap argument.
    var jvmMax = Runtime.getRuntime().maxMemory();
    // In a normal test JVM, maxMemory() is not Long.MAX_VALUE.
    assertTrue(jvmMax != Long.MAX_VALUE);
    assertEquals(jvmMax, Memory.getCappedRuntimeMaxMemory(1024));
  }

  @Test
  public void testGetCappedRuntimeMaxMemoryCapIgnoredWhenFinite() {
    // Verify that the cap parameter does not affect the result when
    // JVM memory is finite — it is only used when memory is unlimited.
    var jvmMax = Runtime.getRuntime().maxMemory();
    assertEquals(jvmMax, Memory.getCappedRuntimeMaxMemory(1));
    assertEquals(jvmMax, Memory.getCappedRuntimeMaxMemory(Long.MAX_VALUE));
  }

  @Test
  public void testFixCommonConfigurationProblemsDoesNotModifyOn64Bit() {
    // On a 64-bit JVM (the standard test environment), this method should
    // execute without errors and without modifying the disk cache size.
    var diskCacheSizeBefore =
        GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong();
    Memory.fixCommonConfigurationProblems();
    assertEquals(
        diskCacheSizeBefore,
        GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong());
  }
}
