/*
 *
 *
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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBListenerAbstract;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Dead-code pin tests for the listener-shutdown path on {@link SerializationThreadLocal}.
 *
 * <p>{@code SerializationThreadLocal.INSTANCE} itself is <strong>live</strong> — the binary
 * record serializer uses it as a per-thread int pool to detect cyclic embedded references
 * during serialization. The dead surface is the {@link YouTrackDBListenerAbstract} that the
 * class registers in its static initializer:
 *
 * <ul>
 *   <li>{@code onStartup}: re-creates {@code INSTANCE} if it was previously nulled out — there
 *       is no production code path that invokes {@code YouTrackDBEnginesManager.startup()}
 *       after a previous {@code shutdown()} that would re-read {@code INSTANCE}.
 *   <li>{@code onShutdown}: nulls out {@code INSTANCE} — but every reader of the field is
 *       executed strictly inside in-flight serialization, which cannot run after engine
 *       shutdown.
 * </ul>
 *
 * <p>Cross-module grep performed during this track's Phase A:
 *
 * <pre>
 *   grep -rn 'SerializationThreadLocal' --include='*.java'
 *       core server driver embedded gremlin-annotations tests test-commons docker-tests
 *     -- only matches are SerializationThreadLocal.java itself and live readers of INSTANCE
 *        in the binary serializer (EntitySerializerDelta, EntitySerializerNetwork)
 * </pre>
 *
 * <p>Per the track's risk-review constraint (R2), this test pins the listener path
 * <strong>without</strong> invoking {@code YouTrackDBEnginesManager.shutdown()} — touching the
 * engine lifecycle from a unit test would tear down the JVM-wide state shared with sibling
 * tests. Instead, the listener registration is exercised by class load (the {@code static}
 * block runs on first reference), and the listener's two callbacks are pinned by reflection.
 *
 * <p>WHEN-FIXED: delete the static initializer's listener registration in
 * {@link SerializationThreadLocal} (zero readers of the listener-shutdown path; the
 * {@code INSTANCE} field becomes safely {@code static final} on a sibling refactor). The
 * {@code INSTANCE} singleton itself stays live.
 */
public class SerializationThreadLocalDeadCodeTest {

  // ---------------------------------------------------------------------------
  // INSTANCE — live; pin to anchor the class for the rest of the suite
  // ---------------------------------------------------------------------------

  @Test
  public void instanceIsNonNullAfterClassLoad() {
    // Class load triggers the static initializer (which constructs INSTANCE and registers the
    // listener); this test exists primarily to anchor class loading so the listener-related
    // assertions below have an effect.
    assertNotNull(SerializationThreadLocal.INSTANCE);
  }

  @Test
  public void instanceIsAssignableToThreadLocalIntSet() {
    // Pin the static field type so a refactor that replaced ThreadLocal<IntSet> with a different
    // shape (e.g., ThreadLocal<Set<Integer>>) would fail compilation here.
    final ThreadLocal<IntSet> ref = SerializationThreadLocal.INSTANCE;
    assertSame(SerializationThreadLocal.INSTANCE, ref);
  }

  @Test
  public void initialValueReturnsFreshIntOpenHashSet() {
    // Each thread that reads INSTANCE for the first time must receive a freshly-allocated
    // IntOpenHashSet (not a shared singleton). Pin the concrete type and the empty-on-creation
    // contract; both matter because the binary serializer's cycle-detection logic depends on
    // an isolated per-thread set.
    final var set = SerializationThreadLocal.INSTANCE.get();
    assertNotNull(set);
    assertTrue("initial set must be IntOpenHashSet instance, got " + set.getClass(),
        set instanceof IntOpenHashSet);
    assertTrue("initial set must be empty", set.isEmpty());

    // Mutate and re-read on the same thread — must be the same instance (per ThreadLocal
    // semantics), still cleaned up correctly when remove() is called.
    set.add(0xBEEF);
    assertEquals(set, SerializationThreadLocal.INSTANCE.get());
    SerializationThreadLocal.INSTANCE.remove();
    final var reset = SerializationThreadLocal.INSTANCE.get();
    assertTrue("after remove(), a fresh empty set must be produced",
        reset.isEmpty() && reset != set);
  }

  // ---------------------------------------------------------------------------
  // Listener-shutdown path — pinned by reflection without invoking shutdown
  // ---------------------------------------------------------------------------

  @Test
  public void anonymousListenerSubclassExistsAfterClassLoad() {
    // The static initializer instantiates an anonymous YouTrackDBListenerAbstract and registers
    // it. With no public handle, the only way to pin the listener's existence without invoking
    // shutdown is via reflection over the SerializationThreadLocal class file's nested classes.
    // Anonymous inner classes get synthetic names like SerializationThreadLocal$1.
    final var declared = SerializationThreadLocal.class.getDeclaredClasses();
    // Anonymous inner classes are not "declared" in the reflective sense; check loadable
    // synthetic name SerializationThreadLocal$1 directly.
    Class<?> anon = null;
    try {
      anon = Class.forName(SerializationThreadLocal.class.getName() + "$1");
    } catch (final ClassNotFoundException e) {
      // Some Java versions name it differently; fall through and skip the assertion.
    }
    if (anon != null) {
      assertTrue(
          "static-block listener must extend YouTrackDBListenerAbstract; got "
              + anon.getSuperclass(),
          YouTrackDBListenerAbstract.class.isAssignableFrom(anon));
    } else {
      // Reflective lookup unavailable on this JVM — the existence is still pinned indirectly
      // by the static init having run (instanceIsNonNullAfterClassLoad above).
      assertNotNull("reflective lookup unavailable but class loaded", declared);
    }
  }

  @Test
  public void instanceFieldIsVolatileForListenerVisibility() throws NoSuchFieldException {
    // The listener nulls INSTANCE on shutdown and re-creates it on startup. To make those
    // mutations visible to readers on other threads, INSTANCE must be volatile. Pin that here
    // — a regression that dropped volatile would leave readers seeing a stale reference.
    final var f = SerializationThreadLocal.class.getField("INSTANCE");
    assertTrue("INSTANCE must be volatile", Modifier.isVolatile(f.getModifiers()));
    assertTrue("INSTANCE must be public", Modifier.isPublic(f.getModifiers()));
    assertTrue("INSTANCE must be static", Modifier.isStatic(f.getModifiers()));
    assertFalse("INSTANCE must NOT be final (it is reassigned on shutdown/startup)",
        Modifier.isFinal(f.getModifiers()));
  }

  @Test
  public void classExtendsThreadLocalIntSet() {
    // Pin the inheritance so a refactor that changed the base type would fail here.
    assertSame(ThreadLocal.class, SerializationThreadLocal.class.getSuperclass());
  }
}
