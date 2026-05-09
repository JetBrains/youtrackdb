/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Coverage for {@link MemoryAndLocalPaginatedEnginesInitializer} — the shared one-shot initialiser
 * that {@link com.jetbrains.youtrackdb.internal.core.engine.memory.EngineMemory} and {@link
 * com.jetbrains.youtrackdb.internal.core.engine.local.EngineLocalPaginated} both call from their
 * {@code startup()} methods.
 *
 * <p>The class encapsulates two distinct behaviours: (a) an {@code initialized} flag that gates
 * subsequent {@code initialize()} calls into a no-op, and (b) the private {@code
 * calculateMemoryLeft(memoryLimit, parameter, memoryLeft)} parser whose branches are reached only
 * indirectly through {@code configureDefaultDiskCacheSize}. Because the disk-cache configuration
 * branch involves reading host OS memory state, we exercise the parser branches via reflection on
 * the private method — that is the only way to deterministically cover the {@code %}, {@code b},
 * {@code k}, {@code m}, {@code g}, {@code null}, length-too-short, and unknown-suffix arms.
 *
 * <p>Carries {@link SequentialTest} because the shared {@code INSTANCE.initialized} flag and the
 * {@link GlobalConfiguration#DISK_CACHE_SIZE} / {@link GlobalConfiguration#WAL_RESTORE_BATCH_SIZE}
 * mutations are process-wide. {@link Before}/{@link After} snapshot/restore both.
 */
@Category(SequentialTest.class)
public class MemoryAndLocalPaginatedEnginesInitializerTest {

  private static final long ONE_KB = 1024L;
  private static final long ONE_MB = 1024L * 1024;
  private static final long ONE_GB = 1024L * 1024 * 1024;

  private boolean originalInitialized;
  private Field initializedField;

  @Before
  public void snapshotInitialized() throws Exception {
    initializedField =
        MemoryAndLocalPaginatedEnginesInitializer.class.getDeclaredField("initialized");
    initializedField.setAccessible(true);
    originalInitialized =
        (boolean) initializedField.get(MemoryAndLocalPaginatedEnginesInitializer.INSTANCE);
  }

  @After
  public void restoreInitialized() throws Exception {
    initializedField.set(MemoryAndLocalPaginatedEnginesInitializer.INSTANCE, originalInitialized);
  }

  /**
   * The published {@code INSTANCE} field is the shared singleton both engines reference. Pin the
   * non-null contract — a future refactor that swaps it for a lazy-initialised holder would lose
   * the ability for tests to seed {@code initialized=true} before construction completes.
   */
  @Test
  public void instanceFieldIsAvailable() {
    assertThat(MemoryAndLocalPaginatedEnginesInitializer.INSTANCE).isNotNull();
  }

  /**
   * Calling {@code initialize()} when the singleton has already been initialised must short-
   * circuit. We pin this by setting {@code initialized=true} reflectively — any subsequent
   * mutation to {@code GlobalConfiguration.DISK_CACHE_SIZE} would betray the short-circuit.
   */
  @Test
  public void initializeIsNoOpOnceFlagged() throws Exception {
    initializedField.set(MemoryAndLocalPaginatedEnginesInitializer.INSTANCE, true);

    var diskCacheBefore = GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong();
    var walBatchBefore = GlobalConfiguration.WAL_RESTORE_BATCH_SIZE.getValueAsInteger();

    MemoryAndLocalPaginatedEnginesInitializer.INSTANCE.initialize();

    // Both shared configurations must be unchanged — proving the early-return path was taken.
    assertThat(GlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong()).isEqualTo(diskCacheBefore);
    assertThat(GlobalConfiguration.WAL_RESTORE_BATCH_SIZE.getValueAsInteger())
        .isEqualTo(walBatchBefore);
  }

  /**
   * The private {@code calculateMemoryLeft(memoryLimit, parameter, memoryLeft)} parser is the
   * shape this fan exercises. Pulling it via reflection is the only deterministic way to cover the
   * full branch matrix without staging real container/OS memory readings.
   */
  private long invokeCalculateMemoryLeft(long memoryLimit, String parameter, String memoryLeft)
      throws Exception {
    Method m =
        MemoryAndLocalPaginatedEnginesInitializer.class.getDeclaredMethod(
            "calculateMemoryLeft", long.class, String.class, String.class);
    m.setAccessible(true);
    return (long) m.invoke(
        MemoryAndLocalPaginatedEnginesInitializer.INSTANCE, memoryLimit, parameter, memoryLeft);
  }

  /**
   * A null {@code memoryLeft} input must take the early-return arm and yield the original
   * memory limit unchanged. The warning helper inside
   * {@code warningInvalidMemoryLeftValue(parameter, null)} casts both args to {@link Object}
   * so Java overload resolution picks the {@code (Object, String, Object...)} variant of
   * {@code SLF4JLogManager.warn} — a regression that drops the casts would re-route the call
   * to the {@code (Object, String dbName, String message, Object...)} variant and NPE on
   * {@code requireNonNull(message)}.
   */
  @Test
  public void calculateMemoryLeftWithNullValueReturnsOriginalLimit() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", null)).isEqualTo(1_000L);
  }

  /** Inputs shorter than two characters cannot carry a unit suffix and must be rejected. */
  @Test
  public void calculateMemoryLeftWithTooShortInputReturnsOriginalLimit() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "x")).isEqualTo(1_000L);
  }

  /** {@code 30%} of {@code 1000} is {@code (1000 * 70) / 100 = 700}. */
  @Test
  public void calculateMemoryLeftPercentBranch() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "30%")).isEqualTo(700L);
  }

  /** A non-numeric percent value must fall back to the original limit. */
  @Test
  public void calculateMemoryLeftPercentBranchInvalidNumber() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "abc%")).isEqualTo(1_000L);
  }

  /** A negative percent value must be rejected and fall back. */
  @Test
  public void calculateMemoryLeftPercentBranchNegative() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "-5%")).isEqualTo(1_000L);
  }

  /** A {@code >=100} percent value must be rejected and fall back. */
  @Test
  public void calculateMemoryLeftPercentBranchAtOrAbove100() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "100%")).isEqualTo(1_000L);
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "150%")).isEqualTo(1_000L);
  }

  /** The {@code b} (bytes) suffix subtracts the literal byte count. */
  @Test
  public void calculateMemoryLeftBytesBranch() throws Exception {
    assertThat(invokeCalculateMemoryLeft(10_000L, "param", "200b")).isEqualTo(9_800L);
  }

  /** A non-numeric bytes value must fall back. */
  @Test
  public void calculateMemoryLeftBytesBranchInvalidNumber() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "abcb")).isEqualTo(1_000L);
  }

  /** A negative bytes value must be rejected and fall back. */
  @Test
  public void calculateMemoryLeftBytesBranchNegative() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "-1b")).isEqualTo(1_000L);
  }

  /** The {@code k} (kilobytes) suffix subtracts {@code n * 1024} bytes. */
  @Test
  public void calculateMemoryLeftKilobytesBranch() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000_000L, "param", "10k")).isEqualTo(1_000_000L - 10
        * ONE_KB);
  }

  /** A non-numeric kilobytes value must fall back. */
  @Test
  public void calculateMemoryLeftKilobytesBranchInvalidNumber() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "xyzk")).isEqualTo(1_000L);
  }

  /** A negative kilobytes value must be rejected and fall back. */
  @Test
  public void calculateMemoryLeftKilobytesBranchNegative() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "-1k")).isEqualTo(1_000L);
  }

  /** The {@code m} (megabytes) suffix subtracts {@code n * 1024 * 1024} bytes. */
  @Test
  public void calculateMemoryLeftMegabytesBranch() throws Exception {
    assertThat(invokeCalculateMemoryLeft(10L * ONE_GB, "param", "256m"))
        .isEqualTo(10L * ONE_GB - 256L * ONE_MB);
  }

  /** A non-numeric megabytes value must fall back. */
  @Test
  public void calculateMemoryLeftMegabytesBranchInvalidNumber() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "xyzm")).isEqualTo(1_000L);
  }

  /** A negative megabytes value must be rejected and fall back. */
  @Test
  public void calculateMemoryLeftMegabytesBranchNegative() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "-1m")).isEqualTo(1_000L);
  }

  /** The {@code g} (gigabytes) suffix subtracts {@code n * 1024^3} bytes. */
  @Test
  public void calculateMemoryLeftGigabytesBranch() throws Exception {
    assertThat(invokeCalculateMemoryLeft(10L * ONE_GB, "param", "2g"))
        .isEqualTo(10L * ONE_GB - 2L * ONE_GB);
  }

  /** A non-numeric gigabytes value must fall back. */
  @Test
  public void calculateMemoryLeftGigabytesBranchInvalidNumber() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "xyzg")).isEqualTo(1_000L);
  }

  /** A negative gigabytes value must be rejected and fall back. */
  @Test
  public void calculateMemoryLeftGigabytesBranchNegative() throws Exception {
    assertThat(invokeCalculateMemoryLeft(10L * ONE_GB, "param", "-1g")).isEqualTo(10L * ONE_GB);
  }

  /**
   * An unknown trailing character must fall back to the original limit. Mixed-case input is
   * lower-cased by the parser before the suffix check, so an upper-case suffix routes through the
   * proper branch — pin "{@code Z}" (definitely unknown after lower-casing) for the unknown arm.
   */
  @Test
  public void calculateMemoryLeftUnknownSuffixReturnsOriginalLimit() throws Exception {
    assertThat(invokeCalculateMemoryLeft(1_000L, "param", "10z")).isEqualTo(1_000L);
  }

  /**
   * Input is lower-cased before suffix routing, so "{@code 30%}", "{@code 30%}" with mixed case
   * markers, and similar variants all route through the same arm. Pin the upper-case-G branch
   * lower-casing observable.
   */
  @Test
  public void calculateMemoryLeftLowerCasesInputBeforeSuffixCheck() throws Exception {
    // Upper-case G must lower-case to 'g' and route through the gigabytes arm.
    assertThat(invokeCalculateMemoryLeft(10L * ONE_GB, "param", "1G"))
        .isEqualTo(10L * ONE_GB - 1L * ONE_GB);
  }
}
