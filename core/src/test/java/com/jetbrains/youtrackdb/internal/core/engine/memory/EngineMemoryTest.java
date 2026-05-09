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
package com.jetbrains.youtrackdb.internal.core.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.engine.MemoryAndLocalPaginatedEnginesInitializer;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryStorage;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Unit coverage for {@link EngineMemory} — the in-memory storage SPI engine. Pins the engine name,
 * the path-to-name conversion, the lifecycle delegation to {@link
 * MemoryAndLocalPaginatedEnginesInitializer}, and the success path of {@link
 * EngineMemory#createStorage}. The error path of {@code createStorage} (which wraps thrown
 * exceptions through {@code BaseException.wrapException}) is exercised indirectly by the higher-
 * level {@code YouTrackDBEnginesManagerStartUpTest} suite; reproducing it here would require
 * reflectively breaking {@link DirectMemoryStorage}'s constructor, which would couple this test to
 * private implementation details of an unrelated class.
 *
 * <p>Carries {@link SequentialTest} because {@code MemoryAndLocalPaginatedEnginesInitializer.
 * INSTANCE} mutates process-wide {@code GlobalConfiguration} state on first call.
 */
@Category(SequentialTest.class)
public class EngineMemoryTest {

  /**
   * The {@link EngineMemory#NAME} constant is what the {@code YouTrackDBEnginesManager} uses to
   * look up the engine via {@code getEngine("memory")}. A silent rename would break URL parsing
   * for every {@code memory:} database URL the public API exposes.
   */
  @Test
  public void engineNameIsMemory() {
    assertThat(EngineMemory.NAME).isEqualTo("memory");
    assertThat(new EngineMemory().getName()).isEqualTo("memory");
  }

  /**
   * {@code getNameFromPath} delegates to {@code IOUtils.getRelativePathIfAny(dbPath, null)}. For
   * an in-memory engine the input is the database name as embedded in the URL after the {@code
   * memory:} prefix; with no relative-path baseline the call is effectively the identity on
   * already-relative names. Pin the identity case as a stable observable.
   */
  @Test
  public void getNameFromPathReturnsRelativePath() {
    var engine = new EngineMemory();
    assertThat(engine.getNameFromPath("simpleName")).isEqualTo("simpleName");
  }

  /**
   * {@code startup()} eagerly initialises the shared {@link
   * MemoryAndLocalPaginatedEnginesInitializer} (which is idempotent — see {@code
   * MemoryAndLocalPaginatedEnginesInitializerTest}) and flips the inherited running flag to true.
   * The combined contract is what the manager's lazy {@code startEngine} relies on.
   */
  @Test
  public void startupInitializesSharedInitializerAndFlipsRunningFlag() {
    var engine = new EngineMemory();
    assertThat(engine.isRunning()).isFalse();

    engine.startup();
    try {
      assertThat(engine.isRunning()).isTrue();
    } finally {
      engine.shutdown();
    }
  }

  /**
   * {@link EngineMemory#createStorage} returns a {@link DirectMemoryStorage} on the success path,
   * keyed by the URL. The two-argument internal constructor of {@link DirectMemoryStorage}
   * receives the URL as both the canonical name and the underlying path; pin the storage instance
   * type so a future refactor that swaps the storage implementation breaks loudly.
   */
  @Test
  public void createStorageReturnsDirectMemoryStorage() {
    var engine = new EngineMemory();
    engine.startup();
    try {
      var storage =
          engine.createStorage(
              "engineMemoryTestDb-" + java.util.UUID.randomUUID(),
              125 * 1024 * 1024,
              25 * 1024 * 1024,
              Integer.MAX_VALUE,
              null);
      assertThat(storage).isInstanceOf(DirectMemoryStorage.class);
      // The storage's name is set from the URL.
      assertThat(storage.getName()).startsWith("engineMemoryTestDb-");
    } finally {
      engine.shutdown();
    }
  }
}
