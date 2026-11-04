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
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.WTinyLFUPolicy;
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

  @Before
  public void configureDeterministicCacheSize() {
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

  /**
   * {@link EngineLocalPaginated#changeCacheSize} on a not-yet-started engine (read cache still
   * null) is a documented no-op — the comment states: "otherwise memory size will be set during
   * cache initialization." Pin the no-op so a future refactor that throws NPE on null read cache
   * fails loudly.
   */
  @Test
  public void changeCacheSizeOnNotStartedEngineIsNoOp() {
    var engine = new EngineLocalPaginated();
    assertThat(engine.getReadCache()).isNull();

    // Must not throw — the early-return path simply defers cache sizing to startup.
    engine.changeCacheSize(64L * 1024 * 1024);

    // The read cache must still be null afterwards.
    assertThat(engine.getReadCache()).isNull();
  }

  /**
   * Halving the total cache leaves 384 read-cache pages, enough for the seven-pin budget fixed
   * at startup. The same cache must remain installed and its policy must expose the new capacity.
   */
  @Test
  public void changeCacheSizeWithValidFixedPinBudgetUpdatesCapacity() throws Exception {
    var engine = new EngineLocalPaginated();
    engine.startup();
    try {
      var cache = engine.getReadCache();
      assertThat(cache).isNotNull();
      var policy = readCachePolicy(cache);
      assertThat(policy.getMaxSize()).isEqualTo(768);
      long pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsLong() * 1024;

      engine.changeCacheSize(512L * pageSize);

      assertThat(engine.getReadCache()).isSameAs(cache);
      assertThat(policy.getMaxSize()).isEqualTo(384);
    } finally {
      engine.shutdown();
    }
  }

  /**
   * Shrinking to 48 read-cache pages cannot accommodate the fixed seven-pin budget. Rejection
   * must leave the same cache installed with its original capacity, not partially apply the resize.
   */
  @Test
  public void changeCacheSizeWithInvalidFixedPinBudgetPreservesCapacity() throws Exception {
    var engine = new EngineLocalPaginated();
    engine.startup();
    try {
      var cache = engine.getReadCache();
      assertThat(cache).isNotNull();
      var policy = readCachePolicy(cache);
      int capacityBefore = policy.getMaxSize();
      assertThat(capacityBefore).isEqualTo(768);
      long pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsLong() * 1024;

      assertThatThrownBy(() -> engine.changeCacheSize(64L * pageSize))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid commit pin budget 7 for cache size 48 pages");

      assertThat(engine.getReadCache()).isSameAs(cache);
      assertThat(policy.getMaxSize()).isEqualTo(capacityBefore);
    } finally {
      engine.shutdown();
    }
  }

  private static WTinyLFUPolicy readCachePolicy(ReadCache cache) throws Exception {
    // ReadCache exposes used memory, not capacity. Read the policy through its existing accessor.
    var field = LockFreeReadCache.class.getDeclaredField("policy");
    field.setAccessible(true);
    return (WTinyLFUPolicy) field.get(cache);
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
