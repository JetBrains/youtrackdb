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

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Direct unit coverage for {@link EngineAbstract}: the running flag toggling, default false-on-
 * construction, idempotency, and the visibility contract documented on the {@code volatile running}
 * field. {@link EngineAbstract} is the SPI super-class for {@link
 * com.jetbrains.youtrackdb.internal.core.engine.memory.EngineMemory} /
 * {@link com.jetbrains.youtrackdb.internal.core.engine.local.EngineLocalPaginated} and the test
 * stub used by {@code PostponedEngineStartTest}; both production engines override {@code startup()}
 * to do other work before delegating to {@code super.startup()} — therefore the contract pinned
 * here (running flips on/off purely from the abstract base) is what every concrete engine relies on
 * for {@link Engine#isRunning()} to return a coherent value.
 *
 * <p>Carries {@link SequentialTest} because {@code Engine} state is conceptually process-wide:
 * even though this test instantiates its own subclass and never registers it with {@link
 * com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager}, the project-wide convention
 * tags every engine-lifecycle test sequential to prevent any future accidental coupling.
 */
@Category(SequentialTest.class)
public class EngineAbstractTest {

  /**
   * A concrete {@link EngineAbstract} subclass with no production behaviour. Used to exercise the
   * abstract base's {@code running} flag in isolation from any storage allocation, file-system
   * effect, or {@link com.jetbrains.youtrackdb.internal.core.engine.MemoryAndLocalPaginatedEnginesInitializer}
   * static state.
   */
  private static final class TestEngine extends EngineAbstract {

    @Override
    public String getName() {
      return "test-engine";
    }

    @Override
    public Storage createStorage(
        String iURL,
        long maxWalSegSize,
        long doubleWriteLogMaxSegSize,
        int storageId,
        YouTrackDBInternalEmbedded context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getNameFromPath(String dbPath) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A freshly constructed {@link EngineAbstract} subclass must report not-running. Engines must be
   * registrable in the manager's map before {@code startup()} fires (this is the precondition the
   * existing {@code YouTrackDBEnginesManagerStartUpTest#enginesAreRegisteredInConstructorBefore
   * Startup} test depends on).
   */
  @Test
  public void freshEngineIsNotRunning() {
    var engine = new TestEngine();
    assertThat(engine.isRunning()).isFalse();
  }

  /**
   * {@code startup()} must flip the {@code running} flag to true; {@code shutdown()} must flip it
   * back. This is the exclusive contract the manager relies on to decide whether an engine should
   * be lazy-started by {@code getRunningEngine}.
   */
  @Test
  public void startupAndShutdownToggleRunningFlag() {
    var engine = new TestEngine();

    engine.startup();
    assertThat(engine.isRunning()).isTrue();

    engine.shutdown();
    assertThat(engine.isRunning()).isFalse();
  }

  /**
   * {@code startup()} called twice on an engine that is already running must keep the flag at
   * true — i.e., it is idempotent at the abstract-base level. Concrete subclasses (notably
   * {@link com.jetbrains.youtrackdb.internal.core.engine.local.EngineLocalPaginated}) MAY perform
   * non-idempotent work (allocating buffer pools), but the abstract base contract is idempotent
   * and the manager relies on this when racing two threads through {@code startEngine}.
   */
  @Test
  public void startupIsIdempotentAtAbstractBaseLevel() {
    var engine = new TestEngine();
    engine.startup();
    assertThat(engine.isRunning()).isTrue();

    // Second startup — base-class flag must remain true.
    engine.startup();
    assertThat(engine.isRunning()).isTrue();
  }

  /**
   * {@code shutdown()} called twice on an already-stopped engine must keep the flag at false.
   * Idempotent shutdown is required because the manager's shutdown loop calls
   * {@code engine.shutdown()} on every registered engine, including ones that never started.
   */
  @Test
  public void shutdownIsIdempotentAtAbstractBaseLevel() {
    var engine = new TestEngine();
    assertThat(engine.isRunning()).isFalse();

    engine.shutdown();
    assertThat(engine.isRunning()).isFalse();

    // Start, then shutdown twice.
    engine.startup();
    engine.shutdown();
    engine.shutdown();
    assertThat(engine.isRunning()).isFalse();
  }

  /**
   * A start-stop-start cycle must restore the running flag. This is the precondition for the
   * {@code testEngineRestart} branch in {@code PostponedEngineStartTest} where an engine's lazy
   * re-acquisition through {@code getRunningEngine} restarts a previously-shut-down engine.
   */
  @Test
  public void startStopStartCycleRestoresRunningFlag() {
    var engine = new TestEngine();

    engine.startup();
    engine.shutdown();
    assertThat(engine.isRunning()).isFalse();

    engine.startup();
    assertThat(engine.isRunning()).isTrue();
  }
}
