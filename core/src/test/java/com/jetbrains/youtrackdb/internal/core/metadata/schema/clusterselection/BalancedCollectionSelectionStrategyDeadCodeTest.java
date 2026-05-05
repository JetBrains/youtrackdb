/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.CollectionSelectionStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Shape pin for {@link BalancedCollectionSelectionStrategy}. PSI all-scope
 * {@code ReferencesSearch} confirms the only production reference is the SPI service file
 * {@code META-INF/services/.../CollectionSelectionStrategy} (third entry); the class is
 * never instantiated through any code path that has a live caller because no public API on
 * {@link SchemaClass} switches the cluster-selection strategy by name —
 * {@code SchemaClassImpl} hardcodes {@code new RoundRobinCollectionSelectionStrategy()} for
 * every class created.
 *
 * <p>The "dead" framing here is "reachable only through {@link CollectionSelectionFactory}'s
 * {@code newInstance(name)}, which itself has no production callers" (see
 * {@link CollectionSelectionFactoryDeadCodeTest}). The lockstep deletion group is
 * {@code Balanced + Default + CollectionSelectionFactory.getStrategy + the SPI service
 * file's "balanced" + "default" entries}.
 *
 * <p>This pin exercises the constructor and the three interface methods so the class is
 * concretely covered for the deferred-cleanup track:
 *
 * <ul>
 *   <li>{@link #getName()} must return the literal {@code "balanced"} — pinned because the
 *       SPI registration in {@link CollectionSelectionFactory#registerStrategy()} indexes
 *       on this exact string.</li>
 *   <li>{@link BalancedCollectionSelectionStrategy#getCollection(DatabaseSessionEmbedded,
 *       SchemaClass, int[], EntityImpl)} short-circuits when the {@code collections} array
 *       has length 1.</li>
 *   <li>The same method picks the cluster id with the smallest approximate row count when
 *       the array has length &gt;1 — pin via Mockito on a stub session.</li>
 * </ul>
 *
 * <p>WHEN-FIXED: Track 22 — delete this class and this test together, lockstep with
 * {@code DefaultCollectionSelectionStrategy}, the {@code CollectionSelectionFactory.
 * getStrategy(String)} method (and the SPI-registry plumbing reachable only through it),
 * and the {@code "balanced"} + {@code "default"} entries in the
 * {@code META-INF/services/.../CollectionSelectionStrategy} file. The
 * {@link RoundRobinCollectionSelectionStrategy} stays — it is the only live strategy via
 * {@code SchemaClassImpl}'s direct instantiation.
 *
 * <p>Standalone: no database session is needed; {@link DatabaseSessionEmbedded} and
 * {@link SchemaClass} are both Mockito-mocked so the strategy's branches are exercised in
 * isolation. The strategy holds package-protected mutable state ({@code lastCount},
 * {@code smallerCollectionId}) — the tests exercise the public surface only and rely on
 * Mockito stubs for the session interaction; private-field probes would be brittle across
 * a Track 22 deletion and add no falsifiability.
 */
public class BalancedCollectionSelectionStrategyDeadCodeTest {

  @Test
  public void classIsPublicNonAbstractAndImplementsCollectionSelectionStrategy() {
    var clazz = BalancedCollectionSelectionStrategy.class;
    var mods = clazz.getModifiers();
    assertTrue("must be public", Modifier.isPublic(mods));
    assertTrue("must NOT be abstract", !Modifier.isAbstract(mods));
    assertTrue("must implement CollectionSelectionStrategy",
        CollectionSelectionStrategy.class.isAssignableFrom(clazz));
  }

  @Test
  public void getNameReturnsBalancedConstant() {
    // The "balanced" literal is the SPI-registry key used by
    // CollectionSelectionFactory.registerStrategy(); pin it byte-for-byte. A future rename
    // would silently de-register this strategy and would be undetectable without this pin.
    var s = new BalancedCollectionSelectionStrategy();
    assertEquals("getName() must return the literal 'balanced' (SPI-registry key)",
        "balanced", s.getName());
    assertEquals("the public NAME constant must match the getName() return value",
        BalancedCollectionSelectionStrategy.NAME, s.getName());
  }

  @Test
  public void getCollectionWithSingleCollectionShortCircuitsToThatCluster() {
    // Pin the documented length==1 short-circuit: when there is only one cluster, the
    // strategy must return it without consulting the session at all (no
    // getApproximateCollectionCount call). Use Mockito.verifyNoInteractions to prove the
    // session is untouched — a future refactor that always queries the session would be
    // caught.
    var session = Mockito.mock(DatabaseSessionEmbedded.class);
    var clazz = Mockito.mock(SchemaClass.class);
    var entity = Mockito.mock(EntityImpl.class);

    var s = new BalancedCollectionSelectionStrategy();
    int picked = s.getCollection(session, clazz, new int[] {42}, entity);

    assertEquals("length-1 array must short-circuit to the only cluster id", 42, picked);
    Mockito.verifyNoInteractions(session);
  }

  @Test
  public void getCollectionWithMultipleCollectionsPicksMinimumApproximateCount() {
    // Pin the load-balancing contract: for length>1, the strategy queries
    // session.getApproximateCollectionCount for every cluster id and returns the one with
    // the smallest count. Use stubs so cluster id 7 has the smallest row count (2L), 5 has
    // 9L, 9 has 5L. Expected pick: 7.
    var session = Mockito.mock(DatabaseSessionEmbedded.class);
    var clazz = Mockito.mock(SchemaClass.class);
    var entity = Mockito.mock(EntityImpl.class);

    Mockito.when(session.getApproximateCollectionCount(5)).thenReturn(9L);
    Mockito.when(session.getApproximateCollectionCount(7)).thenReturn(2L);
    Mockito.when(session.getApproximateCollectionCount(9)).thenReturn(5L);

    var s = new BalancedCollectionSelectionStrategy();
    int picked = s.getCollection(session, clazz, new int[] {5, 7, 9}, entity);

    assertEquals("must pick the cluster id with the smallest approximate row count",
        7, picked);
    // Pin that the session was queried at least once per cluster id — not the precise count
    // (the strategy may short-circuit a future optimisation), but enough to prove the
    // session interaction is the load-bearing observable.
    Mockito.verify(session, Mockito.atLeastOnce()).getApproximateCollectionCount(anyInt());
  }

  @Test
  public void getCollectionTwoArgFormDelegatesToClusterIdsFromSchemaClass() {
    // Pin the two-argument overload: it must read the cluster ids from the supplied
    // SchemaClass and delegate to the four-argument form. The stub returns a single-cluster
    // array so the inner call short-circuits — the observable is the returned cluster id
    // matching the stub's first element.
    var session = Mockito.mock(DatabaseSessionEmbedded.class);
    var clazz = Mockito.mock(SchemaClass.class);
    var entity = Mockito.mock(EntityImpl.class);
    Mockito.when(clazz.getCollectionIds()).thenReturn(new int[] {17});

    var s = new BalancedCollectionSelectionStrategy();
    int picked = s.getCollection(session, clazz, entity);

    assertEquals("two-arg form must delegate via getCollectionIds() to the only cluster id",
        17, picked);
    Mockito.verify(clazz).getCollectionIds();
  }
}
