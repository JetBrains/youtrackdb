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
package com.jetbrains.youtrackdb.internal.core.engine.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Targeted unit coverage for {@link EngineLocalPaginated} that complements the existing
 * {@code YouTrackDBEnginesManagerStartUpTest} suite (which already covers shutdown null-cache
 * guards, shutdown's catch-Exception clause, and the createStorage null-readCache fail path). This
 * class pins the simpler accessors and the lifecycle-flag observables — exposing the entry points
 * directly so a coverage shortfall would be unambiguous to the next reviewer.
 *
 * <p>Carries {@link SequentialTest} because {@code startup()} mutates shared {@code
 * GlobalConfiguration} state via {@link
 * com.jetbrains.youtrackdb.internal.core.engine.MemoryAndLocalPaginatedEnginesInitializer} and
 * allocates a process-wide {@code ByteBufferPool} read cache.
 */
@Category(SequentialTest.class)
public class EngineLocalPaginatedTest {

  private Object originalCacheSize;
  private boolean cacheSizeChanged;
  private Object originalWriteCachePart;
  private boolean writeCachePartChanged;

  private Object originalLimit;
  private boolean limitChanged;
  private Object originalPercent;
  private boolean percentChanged;

  @Before
  public void configureDeterministicCacheSize() {
    originalLimit = GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.getValue();
    limitChanged = GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.isChanged();
    originalPercent = GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.getValue();
    percentChanged = GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.isChanged();
    GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.setValue(1024);
    GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.setValue(1.0);
    originalCacheSize = GlobalConfiguration.DISK_CACHE_SIZE.getValue();
    cacheSizeChanged = GlobalConfiguration.DISK_CACHE_SIZE.isChanged();
    originalWriteCachePart = GlobalConfiguration.DISK_WRITE_CACHE_PART.getValue();
    writeCachePartChanged = GlobalConfiguration.DISK_WRITE_CACHE_PART.isChanged();

    // A total of 1024 pages leaves 768 read-cache pages and a fixed budget of seven pins.
    GlobalConfiguration.DISK_CACHE_SIZE.setValue(
        GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger());
    GlobalConfiguration.DISK_WRITE_CACHE_PART.setValue(25);
  }

  @After
  public void restoreCacheConfiguration() {
    restore(GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT, originalLimit, limitChanged);
    restore(GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT, originalPercent,
        percentChanged);
    if (cacheSizeChanged) {
      GlobalConfiguration.DISK_CACHE_SIZE.setValue(originalCacheSize);
    } else {
      GlobalConfiguration.DISK_CACHE_SIZE.resetToDefault();
    }
    if (writeCachePartChanged) {
      GlobalConfiguration.DISK_WRITE_CACHE_PART.setValue(originalWriteCachePart);
    } else {
      GlobalConfiguration.DISK_WRITE_CACHE_PART.resetToDefault();
    }
  }

  /**
   * The {@link EngineLocalPaginated#NAME} constant is what {@code YouTrackDBEnginesManager} uses
   * to look up the engine via {@code getEngine("disk")}. A silent rename would break URL parsing
   * for every {@code plocal:} / {@code disk:} database URL.
   */
  @Test
  public void engineNameIsDisk() {
    assertThat(EngineLocalPaginated.NAME).isEqualTo("disk");
    assertThat(new EngineLocalPaginated().getName()).isEqualTo("disk");
  }

  /**
   * {@code getNameFromPath} delegates to {@code IOUtils.getRelativePathIfAny(dbPath, null)}. With
   * no relative-path baseline the call is effectively the identity on already-relative database
   * names — pin the identity case so an accidental change to {@code FileUtils.getCanonicalPath}
   * (which would absolute-ise the path) would fail loudly.
   */
  @Test
  public void getNameFromPathReturnsInputForRelativeNames() {
    var engine = new EngineLocalPaginated();
    assertThat(engine.getNameFromPath("relativeName")).isEqualTo("relativeName");
  }

  /**
   * Before {@code startup()} is called {@link EngineLocalPaginated#getReadCache()} must return
   * null — the read cache is created lazily inside {@code startup()} and exposed only afterwards.
   * This is the pre-condition that the {@code YouTrackDBEnginesManagerStartUpTest#
   * createStorageBeforeStartupThrowsDatabaseException} test depends on.
   */
  @Test
  public void readCacheIsNullBeforeStartup() {
    var engine = new EngineLocalPaginated();
    assertThat(engine.getReadCache()).isNull();
    assertThat(engine.isRunning()).isFalse();
  }

  /**
   * After {@code startup()} the read cache must be initialised AND the running flag must be true.
   * The {@code volatile readCache} field carries a happens-before guarantee with respect to
   * {@code running=true} — without a non-null read cache after startup, concurrent callers
   * checking {@code isRunning()} could observe inconsistent state and call {@code createStorage}
   * with a null cache.
   */
  @Test
  public void startupInitializesReadCacheBeforeFlippingRunningFlag() {
    var engine = new EngineLocalPaginated();

    engine.startup();
    try {
      var cache = engine.getReadCache();
      assertThat(cache).isInstanceOf(ReadCache.class);
      assertThat(engine.isRunning()).isTrue();
    } finally {
      engine.shutdown();
    }
  }

  private static void restore(GlobalConfiguration setting, Object value, boolean changed) {
    if (changed) {
      setting.setValue(value);
    } else {
      setting.resetToDefault();
    }
  }

  // Each setting rejects its own out-of-range values before the engine starts.
  @Test
  public void invalidCommitSettingsStopStartupWithKeyValueAndRange() {
    var limit = GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT;
    for (int value : new int[] {0, -1}) {
      limit.setValue(value);
      var engine = new EngineLocalPaginated();
      assertThatThrownBy(engine::startup).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(limit.getKey()).hasMessageContaining("=" + value)
          .hasMessageContaining("1 to 2147483647");
      assertThat(engine.isRunning()).isFalse();
      assertThat(engine.getReadCache()).isNull();
    }
    limit.setValue(1024);
    var percent = GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT;
    for (double value : new double[] {-0.01, 15.01, Double.NaN,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      percent.setValue(value);
      var engine = new EngineLocalPaginated();
      assertThatThrownBy(engine::startup).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(percent.getKey()).hasMessageContaining("=" + value)
          .hasMessageContaining("0 to 15");
      assertThat(engine.isRunning()).isFalse();
      assertThat(engine.getReadCache()).isNull();
    }
  }

  // Both valid bounds are independent. Changing globals after startup cannot change this cache.
  @Test
  public void startupCapturesValidBoundsWithoutCrossValidationOrRuntimeChanges() {
    for (int limit : new int[] {1, Integer.MAX_VALUE}) {
      for (double percent : new double[] {0, 15}) {
        GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.setValue(limit);
        GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.setValue(percent);
        var engine = new EngineLocalPaginated();
        engine.startup();
        try {
          assertThat(engine.getReadCache().commitPageLimit()).isEqualTo(limit);
          assertThat(engine.getReadCache().writePinBudget()).isEqualTo(percent == 0 ? 0 : 115);
          GlobalConfiguration.DISK_CACHE_COMMIT_PAGE_LIMIT.setValue(42);
          GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.setValue(2.0);
          assertThat(engine.getReadCache().commitPageLimit()).isEqualTo(limit);
          assertThat(engine.getReadCache().writePinBudget()).isEqualTo(percent == 0 ? 0 : 115);
        } finally {
          engine.shutdown();
        }
      }
    }
  }

  // Exact decimal multiplication must not lose a page at integer boundaries.
  @Test
  public void pinBudgetRoundsDownWithoutBinaryFloatingPointLoss() {
    assertThat(EngineLocalPaginated.commitPinBudgetPages(100, 1.0)).isEqualTo(1);
    assertThat(EngineLocalPaginated.commitPinBudgetPages(99, 1.0)).isZero();
    assertThat(EngineLocalPaginated.commitPinBudgetPages(100000, 14.99)).isEqualTo(14990);
    assertThat(EngineLocalPaginated.commitPinBudgetPages(100000, 1.0)).isEqualTo(1000);
    assertThat(EngineLocalPaginated.commitPinBudgetPages(768, 1.0)).isEqualTo(7);
  }

  // A positive percent below one page disables the protocol and reports the exact inputs.
  @Test
  public void startupWarnsWhenPositivePercentRoundsToZero() {
    GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.setValue(0.01);
    var logger = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.common.log.LogManager.class);
    try (var logs = org.mockito.Mockito.mockStatic(
        com.jetbrains.youtrackdb.internal.common.log.LogManager.class)) {
      logs.when(com.jetbrains.youtrackdb.internal.common.log.LogManager::instance)
          .thenReturn(logger);
      var engine = new EngineLocalPaginated();
      engine.startup();
      try {
        assertThat(engine.getReadCache().writePinBudget()).isZero();
        org.mockito.Mockito.verify(logger).warn(engine,
            "%s=%s rounds down to zero pages for a read cache of %d pages."
                + " The page-set protocol is off.",
            GlobalConfiguration.DISK_CACHE_COMMIT_PIN_BUDGET_PERCENT.getKey(), 0.01, 768);
      } finally {
        engine.shutdown();
      }
    }
  }

  /**
   * Startup followed by shutdown must end with running=false. This pin combines the {@code
   * MemoryAndLocalPaginatedEnginesInitializer} side-effect, read-cache allocation, and the
   * shutdown's super-call sequence into a single observable.
   */
  @Test
  public void startupShutdownCycleEndsWithRunningFalse() {
    var engine = new EngineLocalPaginated();
    engine.startup();
    assertThat(engine.isRunning()).isTrue();
    engine.shutdown();
    assertThat(engine.isRunning()).isFalse();
  }
}
