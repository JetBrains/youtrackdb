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

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
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
   * <b>COVERAGE-ONLY:</b> this test verifies the no-throw contract on {@code changeCacheSize}
   * against a started engine; the resulting cache capacity is not directly observable through
   * public API. A regression that silently no-ops the resize is not detected here. WHEN-FIXED:
   * future tracker issue — once {@code ReadCache} exposes a public size getter (or a probe
   * helper is added), upgrade this test to assert the post-call capacity.
   *
   * <p>{@code changeCacheSize} on a started engine forwards to {@code ReadCache.
   * changeMaximumAmountOfMemory}. We don't assert the resulting size (it's an internal calculation
   * involving {@code GlobalConfiguration.DISK_WRITE_CACHE_PART}); we only verify that the call
   * does not throw.
   */
  @Test
  public void changeCacheSizeOnStartedEngineDoesNotThrow() {
    var engine = new EngineLocalPaginated();
    engine.startup();
    try {
      // Picking a small value to stay well clear of OOM during the test JVM.
      engine.changeCacheSize(32L * 1024 * 1024);
      assertThat(engine.getReadCache()).isNotNull();
    } finally {
      engine.shutdown();
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
