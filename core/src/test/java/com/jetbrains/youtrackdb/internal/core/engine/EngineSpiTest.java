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
import com.jetbrains.youtrackdb.internal.core.engine.local.EngineLocalPaginated;
import com.jetbrains.youtrackdb.internal.core.engine.memory.EngineMemory;
import java.util.HashSet;
import java.util.ServiceLoader;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verifies that the {@code core/engine} package is wired through the Java {@link ServiceLoader}
 * SPI and that the manifest in {@code META-INF/services/} declares both built-in engines.
 *
 * <p>The {@code YouTrackDBEnginesManager.registerEngines()} private method walks {@code
 * ServiceLoader.load(Engine.class)} and registers everything it finds; if the SPI manifest
 * regresses (typo, missing line, accidental duplicate) the engine map silently loses an entry and
 * every {@code memory:} or {@code disk:} URL fails at runtime with a confusing "engine not found"
 * error. This test runs straight from the SPI without going through the manager so the failure
 * mode is unambiguous.
 *
 * <p>Carries {@link SequentialTest} as a defensive marker — although {@code ServiceLoader.load}
 * is read-only, the project-wide convention tags every engine-package test sequential to avoid
 * accidental coupling to any future test that mutates the engine map.
 */
@Category(SequentialTest.class)
public class EngineSpiTest {

  /**
   * The {@code META-INF/services/com.jetbrains.youtrackdb.internal.core.engine.Engine} manifest
   * must list both {@link EngineMemory} and {@link EngineLocalPaginated}. A future regression that
   * deletes a line silently breaks the public API surface.
   */
  @Test
  public void serviceLoaderDiscoversBothBuiltInEngines() {
    var loader = ServiceLoader.load(Engine.class);
    var classes = new HashSet<Class<?>>();
    for (var engine : loader) {
      classes.add(engine.getClass());
    }

    assertThat(classes).contains(EngineMemory.class, EngineLocalPaginated.class);
  }

  /**
   * Each engine instance returned by the {@link ServiceLoader} carries its canonical name. Pin
   * the names here so that an accidental rename ({@code "memory"} → {@code "MEMORY"}) breaks
   * loudly instead of silently mismatching URL parsers.
   */
  @Test
  public void serviceLoaderEnginesExposeCanonicalNames() {
    var loader = ServiceLoader.load(Engine.class);
    var names = new HashSet<String>();
    for (var engine : loader) {
      names.add(engine.getName());
    }

    assertThat(names).contains(EngineMemory.NAME, EngineLocalPaginated.NAME);
    // The two built-in engine names must remain stable.
    assertThat(names).contains("memory", "disk");
  }

  /**
   * A {@link ServiceLoader}-instantiated engine must be in the not-running state until {@code
   * startup()} fires. The {@code YouTrackDBEnginesManager} relies on this — it registers engines
   * eagerly in the constructor and starts them lazily through {@code startEngine}.
   */
  @Test
  public void serviceLoaderEnginesAreNotRunningOnConstruction() {
    var loader = ServiceLoader.load(Engine.class);
    for (var engine : loader) {
      assertThat(engine.isRunning())
          .as("engine %s must not be running on construction", engine.getName())
          .isFalse();
    }
  }
}
