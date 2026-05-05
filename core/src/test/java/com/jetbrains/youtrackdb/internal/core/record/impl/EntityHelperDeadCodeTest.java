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
package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Per-method dead-code pin for {@link EntityHelper}. PSI all-scope {@code ReferencesSearch}
 * across all five Maven modules (core, server, driver, embedded, gremlin-annotations,
 * docker-tests, tests) shows that <strong>twelve of the seventeen public methods</strong>
 * have either zero production references or are reachable only through other already-dead
 * surfaces. The remaining five public methods are still wired into live SQL and serialization
 * paths and are deliberately <em>not</em> pinned here — this test is exclusively about the
 * dead surface so that a partial deletion (drop one dead method, keep the rest) leaves the
 * pin set valid.
 *
 * <p>Live (NOT pinned in this file): {@code getReservedAttributes}, the two
 * {@code getFieldValue} overloads, {@code getIdentifiableValue}, {@code getRecordAttribute}.
 * These have direct production callers (SQLJson, SQLMethodField, SQLHelper, TraverseContext,
 * SQLFilterItemFieldMultiAbstract, EntityImpl) and stay covered by their existing test
 * surface.
 *
 * <p>Dead (pinned individually below):
 * <ul>
 *   <li>{@code sort(List, List, CommandContext)} — chain-dead; only ref is via the live
 *       SQL surface tests, none in production. The chain {@code sort} -> {@link EntityComparator}
 *       both die together when the deferred-cleanup track lands.</li>
 *   <li>{@code getMapEntry(DatabaseSessionEmbedded, Map, Object)} — only intra-class refs.</li>
 *   <li>{@code getResultEntry(DatabaseSessionEmbedded, Result, Object)} — only intra-class.</li>
 *   <li>{@code evaluateFunction(Object, String, CommandContext)} — only intra-class.</li>
 *   <li>{@code hasSameContentItem(...)} — only intra-class.</li>
 *   <li>{@code hasSameContentOf(5-arg)} — chain-dead via {@link DatabaseCompare} (test-only-
 *       reachable, pinned in {@code DatabaseCompareDeadCodeTest}) and via the test-only
 *       {@code EntityImpl.hasSameContentOf(EntityImpl)} convenience overload.</li>
 *   <li>{@code hasSameContentOf(6-arg)} — only intra-class refs.</li>
 *   <li>{@code compareMaps(...)} — only intra-class.</li>
 *   <li>{@code compareCollections(...)} — only intra-class.</li>
 *   <li>{@code compareSets(...)} — only intra-class.</li>
 *   <li>{@code compareBags(...)} — only intra-class.</li>
 *   <li>{@code isEntity(byte)} — chain-dead via {@link DatabaseCompare}.</li>
 * </ul>
 *
 * <p>WHEN-FIXED: deferred-cleanup track — drop the twelve dead public methods listed above
 * (some are gated behind the {@code DatabaseCompare} / {@code EntityComparator} deletions
 * that the same track absorbs). Each pin is its own {@code @Test} method so a partial
 * landing — for example, deleting only {@code sort} while {@code DatabaseCompare} retargeting
 * is still pending — leaves the rest of the pin set valid and the build green.
 *
 * <p>The five live methods listed above are deliberately <em>not</em> pinned here; their
 * coverage stays with the existing live-surface tests. Touching the live methods would
 * entrench an over-broad shape pin and reduce future flexibility.
 *
 * <p>Standalone — no database session needed; pure {@link Class}-level reflection.
 */
public class EntityHelperDeadCodeTest {

  // -------------------------------------------------------------------
  // Sub-task (a) — sort(List, List, CommandContext) is dead in production.
  // Phase A confirmed PSI ReferencesSearch returned zero callers. The only caller was the
  // SQL ORDER BY path, which has since been re-routed; the EntityComparator chain reachable
  // from this method is itself chain-dead (pinned in EntityComparatorDeadCodeTest).
  // -------------------------------------------------------------------
  @Test
  public void sortIsPublicStaticVoidWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "sort", List.class, List.class, CommandContext.class);
    int mods = m.getModifiers();
    assertTrue("sort must be public", Modifier.isPublic(mods));
    assertTrue("sort must be static", Modifier.isStatic(mods));
    assertSame("sort must return void", void.class, m.getReturnType());
    assertEquals("sort must take three parameters", 3, m.getParameterCount());
    assertSame("sort first parameter must be List",
        List.class, m.getParameterTypes()[0]);
    assertSame("sort second parameter must be List<Pair<String,String>> (raw List at runtime)",
        List.class, m.getParameterTypes()[1]);
    assertSame("sort third parameter must be CommandContext",
        CommandContext.class, m.getParameterTypes()[2]);
  }

  // -------------------------------------------------------------------
  // Sub-task (b) — getMapEntry has 5 intra-file refs and zero refs from any other source
  // file (production or test). It is reachable only through the dead chain getFieldValue
  // (which IS live but only invokes getMapEntry as an internal sub-routine; the public
  // signature is not part of any live external contract).
  // -------------------------------------------------------------------
  @Test
  public void getMapEntryIsPublicStaticObjectReturnTypeWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "getMapEntry", DatabaseSessionEmbedded.class, Map.class, Object.class);
    int mods = m.getModifiers();
    assertTrue("getMapEntry must be public", Modifier.isPublic(mods));
    assertTrue("getMapEntry must be static", Modifier.isStatic(mods));
    assertSame("getMapEntry must return Object", Object.class, m.getReturnType());
    assertSame("getMapEntry first parameter must be DatabaseSessionEmbedded",
        DatabaseSessionEmbedded.class, m.getParameterTypes()[0]);
    assertSame("getMapEntry second parameter must be Map",
        Map.class, m.getParameterTypes()[1]);
    assertSame("getMapEntry third parameter must be Object",
        Object.class, m.getParameterTypes()[2]);
  }

  // -------------------------------------------------------------------
  // Sub-task (c) — getResultEntry has 2 intra-file refs and zero external refs.
  // -------------------------------------------------------------------
  @Test
  public void getResultEntryIsPublicStaticObjectReturnTypeWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "getResultEntry", DatabaseSessionEmbedded.class, Result.class, Object.class);
    int mods = m.getModifiers();
    assertTrue("getResultEntry must be public", Modifier.isPublic(mods));
    assertTrue("getResultEntry must be static", Modifier.isStatic(mods));
    assertSame("getResultEntry must return Object", Object.class, m.getReturnType());
    assertSame("getResultEntry second parameter must be Result",
        Result.class, m.getParameterTypes()[1]);
  }

  // -------------------------------------------------------------------
  // Sub-task (d) — evaluateFunction has 1 intra-file ref (from getFieldValue's '(' branch)
  // and zero external refs.
  // -------------------------------------------------------------------
  @Test
  public void evaluateFunctionIsPublicStaticObjectWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "evaluateFunction", Object.class, String.class, CommandContext.class);
    int mods = m.getModifiers();
    assertTrue("evaluateFunction must be public", Modifier.isPublic(mods));
    assertTrue("evaluateFunction must be static", Modifier.isStatic(mods));
    assertSame("evaluateFunction must return Object", Object.class, m.getReturnType());
    assertEquals("evaluateFunction must take three parameters", 3, m.getParameterCount());
    assertSame("evaluateFunction first parameter must be Object",
        Object.class, m.getParameterTypes()[0]);
    assertSame("evaluateFunction second parameter must be String",
        String.class, m.getParameterTypes()[1]);
    assertSame("evaluateFunction third parameter must be CommandContext",
        CommandContext.class, m.getParameterTypes()[2]);
  }

  // -------------------------------------------------------------------
  // Sub-task (e) — hasSameContentItem has only intra-class refs (called from compareCollections
  // and compareSets, both also dead).
  // -------------------------------------------------------------------
  @Test
  public void hasSameContentItemIsPublicStaticBooleanWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "hasSameContentItem", Object.class, DatabaseSessionEmbedded.class, Object.class,
        DatabaseSessionEmbedded.class, EntityHelper.RIDMapper.class);
    int mods = m.getModifiers();
    assertTrue("hasSameContentItem must be public", Modifier.isPublic(mods));
    assertTrue("hasSameContentItem must be static", Modifier.isStatic(mods));
    assertSame("hasSameContentItem must return boolean", boolean.class, m.getReturnType());
    assertEquals("hasSameContentItem must take five parameters",
        5, m.getParameterCount());
  }

  // -------------------------------------------------------------------
  // Sub-task (f) — hasSameContentOf(5-arg) is chain-dead via DatabaseCompare (test-only-
  // reachable per Step-1 pin) and EntityImpl.hasSameContentOf (also test-only-reachable).
  // No live production callers reach this method.
  // -------------------------------------------------------------------
  @Test
  public void hasSameContentOfFiveArgIsPublicStaticBooleanWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "hasSameContentOf",
        EntityImpl.class,
        DatabaseSessionEmbedded.class,
        EntityImpl.class,
        DatabaseSessionEmbedded.class,
        EntityHelper.RIDMapper.class);
    int mods = m.getModifiers();
    assertTrue("hasSameContentOf(5-arg) must be public", Modifier.isPublic(mods));
    assertTrue("hasSameContentOf(5-arg) must be static", Modifier.isStatic(mods));
    assertSame("hasSameContentOf(5-arg) must return boolean", boolean.class, m.getReturnType());
    assertSame("hasSameContentOf(5-arg) first parameter must be EntityImpl",
        EntityImpl.class, m.getParameterTypes()[0]);
    assertSame("hasSameContentOf(5-arg) RIDMapper parameter must be EntityHelper.RIDMapper",
        EntityHelper.RIDMapper.class, m.getParameterTypes()[4]);
  }

  // -------------------------------------------------------------------
  // Sub-task (g) — hasSameContentOf(6-arg) has only intra-class refs.
  // The 5-arg overload delegates here; pinning both ensures the deletion order is explicit.
  // -------------------------------------------------------------------
  @Test
  public void hasSameContentOfSixArgIsPublicStaticBooleanWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "hasSameContentOf",
        EntityImpl.class,
        DatabaseSessionEmbedded.class,
        EntityImpl.class,
        DatabaseSessionEmbedded.class,
        EntityHelper.RIDMapper.class,
        boolean.class);
    int mods = m.getModifiers();
    assertTrue("hasSameContentOf(6-arg) must be public", Modifier.isPublic(mods));
    assertTrue("hasSameContentOf(6-arg) must be static", Modifier.isStatic(mods));
    assertSame("hasSameContentOf(6-arg) must return boolean", boolean.class, m.getReturnType());
    assertEquals("hasSameContentOf(6-arg) must take six parameters",
        6, m.getParameterCount());
    assertSame("hasSameContentOf(6-arg) sixth parameter must be primitive boolean",
        boolean.class, m.getParameterTypes()[5]);
  }

  // -------------------------------------------------------------------
  // Sub-task (h) — compareMaps has only intra-class refs (called from hasSameContentOf, dead).
  // -------------------------------------------------------------------
  @Test
  public void compareMapsIsPublicStaticBooleanWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "compareMaps",
        DatabaseSessionEmbedded.class, Map.class,
        DatabaseSessionEmbedded.class, Map.class,
        EntityHelper.RIDMapper.class);
    int mods = m.getModifiers();
    assertTrue("compareMaps must be public", Modifier.isPublic(mods));
    assertTrue("compareMaps must be static", Modifier.isStatic(mods));
    assertSame("compareMaps must return boolean", boolean.class, m.getReturnType());
    assertSame("compareMaps Map parameter type must be raw Map",
        Map.class, m.getParameterTypes()[1]);
  }

  // -------------------------------------------------------------------
  // Sub-task (i) — compareCollections has only intra-class refs.
  // -------------------------------------------------------------------
  @Test
  public void compareCollectionsIsPublicStaticBooleanWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "compareCollections",
        DatabaseSessionEmbedded.class, Collection.class,
        DatabaseSessionEmbedded.class, Collection.class,
        EntityHelper.RIDMapper.class);
    int mods = m.getModifiers();
    assertTrue("compareCollections must be public", Modifier.isPublic(mods));
    assertTrue("compareCollections must be static", Modifier.isStatic(mods));
    assertSame("compareCollections must return boolean", boolean.class, m.getReturnType());
    assertSame("compareCollections second parameter must be raw Collection",
        Collection.class, m.getParameterTypes()[1]);
  }

  // -------------------------------------------------------------------
  // Sub-task (j) — compareSets has only intra-class refs.
  // -------------------------------------------------------------------
  @Test
  public void compareSetsIsPublicStaticBooleanWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "compareSets",
        DatabaseSessionEmbedded.class, Set.class,
        DatabaseSessionEmbedded.class, Set.class,
        EntityHelper.RIDMapper.class);
    int mods = m.getModifiers();
    assertTrue("compareSets must be public", Modifier.isPublic(mods));
    assertTrue("compareSets must be static", Modifier.isStatic(mods));
    assertSame("compareSets must return boolean", boolean.class, m.getReturnType());
    assertSame("compareSets second parameter must be raw Set",
        Set.class, m.getParameterTypes()[1]);
  }

  // -------------------------------------------------------------------
  // Sub-task (k) — compareBags has only intra-class refs (called from hasSameContentOf, dead).
  // -------------------------------------------------------------------
  @Test
  public void compareBagsIsPublicStaticBooleanWithExpectedSignature() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod(
        "compareBags", LinkBag.class, LinkBag.class, EntityHelper.RIDMapper.class);
    int mods = m.getModifiers();
    assertTrue("compareBags must be public", Modifier.isPublic(mods));
    assertTrue("compareBags must be static", Modifier.isStatic(mods));
    assertSame("compareBags must return boolean", boolean.class, m.getReturnType());
    assertEquals("compareBags must take three parameters", 3, m.getParameterCount());
    assertSame("compareBags first parameter must be LinkBag",
        LinkBag.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // Sub-task (l) — isEntity(byte) is chain-dead. Its only callers are inside DatabaseCompare,
  // which is itself test-only-reachable (pinned in DatabaseCompareDeadCodeTest).
  // -------------------------------------------------------------------
  @Test
  public void isEntityIsPublicStaticBooleanTakingByte() throws Exception {
    Method m = EntityHelper.class.getDeclaredMethod("isEntity", byte.class);
    int mods = m.getModifiers();
    assertTrue("isEntity must be public", Modifier.isPublic(mods));
    assertTrue("isEntity must be static", Modifier.isStatic(mods));
    assertSame("isEntity must return boolean", boolean.class, m.getReturnType());
    assertSame("isEntity parameter type must be byte (record-type tag)",
        byte.class, m.getParameterTypes()[0]);
  }

  // -------------------------------------------------------------------
  // Class-shape pin: ensure the live methods we did NOT touch above remain on the surface.
  // If a refactor accidentally drops one, this assertion catches it without forcing us to
  // pin those methods individually here.
  // -------------------------------------------------------------------
  @Test
  public void liveSurfaceRetainsItsFiveExpectedMethods() throws Exception {
    // Each live method has direct production callers verified by Phase A's PSI audit
    // (re-confirmed during Step-2 implementation). The deferred-cleanup track must NOT touch
    // any of these — they stay covered by the existing live-surface tests.
    EntityHelper.class.getDeclaredMethod("getReservedAttributes");
    EntityHelper.class.getDeclaredMethod(
        "getFieldValue", DatabaseSessionEmbedded.class, Object.class, String.class);
    EntityHelper.class.getDeclaredMethod(
        "getFieldValue", DatabaseSessionEmbedded.class, Object.class, String.class,
        CommandContext.class);
    EntityHelper.class.getDeclaredMethod(
        "getIdentifiableValue", DatabaseSessionEmbedded.class, Identifiable.class, String.class);
    EntityHelper.class.getDeclaredMethod("getRecordAttribute", Identifiable.class, String.class);
  }

  // -------------------------------------------------------------------
  // RIDMapper is the inner functional interface used by every dead comparison method above.
  // It is NOT used anywhere outside EntityHelper itself plus DatabaseCompare (test-only-
  // reachable). It dies together with the dead methods. Pin its shape so a refactor that
  // splits it out (or renames its single map() abstract method) is recognised as a deliberate
  // change.
  // -------------------------------------------------------------------
  @Test
  public void ridMapperIsPublicNestedFunctionalInterface() throws Exception {
    var clazz = EntityHelper.RIDMapper.class;
    int mods = clazz.getModifiers();
    assertTrue("RIDMapper must be public", Modifier.isPublic(mods));
    assertTrue("RIDMapper must be an interface", clazz.isInterface());
    assertTrue("RIDMapper must be a static nested type",
        Modifier.isStatic(mods));
    Method[] methods = clazz.getDeclaredMethods();
    assertEquals("RIDMapper must declare exactly one abstract method (functional interface)",
        1, methods.length);
    assertEquals("RIDMapper's single method must be named 'map'", "map", methods[0].getName());
    assertEquals("RIDMapper.map must take one parameter",
        1, methods[0].getParameterCount());
  }

  // -------------------------------------------------------------------
  // Cross-check: confirm the 'sort' chain target — EntityComparator — is reachable only
  // through this dead surface plus its (test-only) PSI callers. The deletion order must
  // delete EntityComparator together with the EntityHelper.sort method.
  // -------------------------------------------------------------------
  @Test
  public void sortChainTargetEntityComparatorIsObservedInTheClasspath() throws Exception {
    // Smoke test — load the chain target. If the deferred-cleanup track lands a deletion
    // of EntityComparator without dropping sort, the static initialiser of this test class
    // would still load fine but the deletion would be incomplete; the per-method sort pin
    // above would still hold on the now-orphaned method. The reflective signature lookup
    // pins EntityComparator's ctor against the same parameter types EntityHelper.sort
    // produces, so renaming/replacing either side fails this test loudly.
    assertFalse("EntityComparator must remain a class until sort is deleted alongside it",
        EntityComparator.class.isInterface());
    var ctor = EntityComparator.class.getDeclaredConstructor(List.class, CommandContext.class);
    assertSame("EntityComparator's first ctor parameter must remain List<Pair<...>>",
        List.class, ctor.getParameterTypes()[0]);
    assertSame("EntityComparator's second ctor parameter must remain CommandContext",
        CommandContext.class, ctor.getParameterTypes()[1]);
  }
}
