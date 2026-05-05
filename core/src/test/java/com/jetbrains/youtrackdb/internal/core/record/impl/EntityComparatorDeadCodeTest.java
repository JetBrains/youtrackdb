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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Shape pin for {@link EntityComparator}. PSI all-scope {@code ReferencesSearch} confirms the
 * class is <strong>chain-dead</strong>: its only production caller is
 * {@link EntityHelper#sort(java.util.List, java.util.List, CommandContext)}, which is itself
 * dead (zero production callers, pinned in {@link EntityHelperDeadCodeTest}). One additional
 * reference exists in the {@code tests} Maven module under
 * {@code CRUDDocumentValidationTest}, which constructs an {@link EntityComparator} directly
 * for a sort-stability assertion — that test does not lift the class to "live" status, only
 * to "test-only-reachable", because no production path can reach it once the dead
 * {@code sort} chain is removed.
 *
 * <p>Why a behavioral pin would over-reach: instantiating {@link EntityComparator} via its
 * single public constructor requires a {@link CommandContext} carrying a live
 * {@code DatabaseSessionEmbedded} (the ctor reads {@code LOCALE_COUNTRY} /
 * {@code LOCALE_LANGUAGE} attributes for {@link java.text.Collator} construction), and
 * {@link EntityComparator#compare(Identifiable, Identifiable)} calls
 * {@code transaction.load(...)} for both operands. A behavioral pin would either spin up a
 * full database (over-reaching the standalone discipline this dead-code pin family follows)
 * or mock the entire session graph (heavy and brittle). The reflective shape pin captures
 * the same deletion-detection signal at a fraction of the maintenance cost.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link EntityComparator} together with this
 * test file. Co-delete with {@link EntityHelper#sort} (the dead production call site).
 * Retargeting contingency: the single {@code tests}-module test reference
 * ({@code CRUDDocumentValidationTest}) either drops the comparator-stability assertion or
 * migrates to a {@link Comparator} built inline from the same field criteria.
 *
 * <p>Standalone — no database session needed; pure {@link Class}-level reflection.
 */
public class EntityComparatorDeadCodeTest {

  // The complete declared-method surface this chain-dead comparator offers, as a sorted set
  // of names. Pinning the set (rather than the count) catches both a method dropped silently
  // and a method renamed in place — either would shift the resulting set.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = Set.of(
      "compare",
      "factor");

  @Test
  public void classIsPublicConcreteAndImplementsComparatorIdentifiable() {
    var clazz = EntityComparator.class;
    int mods = clazz.getModifiers();
    assertTrue("must be public", Modifier.isPublic(mods));
    assertFalse("must NOT be abstract (concrete comparator)",
        Modifier.isAbstract(mods));
    assertSame("must extend Object — no abstract base contract to retarget",
        Object.class, clazz.getSuperclass());
    // Comparator<Identifiable> is the contract the dead sort chain depends on; pin the
    // generic supertype set rather than just Comparator.class so a refactor that drops the
    // type parameter (silently weakening the contract) is caught.
    var interfaces = clazz.getGenericInterfaces();
    assertEquals("must implement exactly one interface (Comparator<Identifiable>)",
        1, interfaces.length);
    assertTrue("the implemented interface must be Comparator<Identifiable>",
        interfaces[0].toString().contains("Comparator")
            && interfaces[0].toString().contains("Identifiable"));
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    // A future addition (new helper) or deletion (removed accessor) shifts the set and
    // fails — making the deletion / rename a deliberate change rather than a silent edit.
    var actual = new HashSet<String>();
    for (Method m : EntityComparator.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the chain-dead surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void declaresSinglePublicTwoArgConstructor() {
    Constructor<?>[] ctors = EntityComparator.class.getDeclaredConstructors();
    assertEquals("must declare exactly one constructor", 1, ctors.length);

    Constructor<?> ctor = ctors[0];
    assertTrue(
        "ctor must be public — the test-only caller in tests/ uses 'new EntityComparator(...)'",
        Modifier.isPublic(ctor.getModifiers()));
    assertArrayEquals(
        "ctor signature must remain (List<Pair<String,String>>, CommandContext)"
            + " — both the dead EntityHelper.sort caller and the test-only-reachable caller"
            + " in tests/ pin against this exact shape",
        new Class<?>[] {List.class, CommandContext.class},
        ctor.getParameterTypes());
  }

  @Test
  public void compareIsPublicIntTakingTwoIdentifiables() throws Exception {
    Method m = EntityComparator.class.getDeclaredMethod(
        "compare", Identifiable.class, Identifiable.class);
    int mods = m.getModifiers();
    assertTrue("compare must be public (Comparator contract)", Modifier.isPublic(mods));
    assertSame("compare must return int", int.class, m.getReturnType());
    assertEquals("compare must take exactly two parameters", 2, m.getParameterCount());
    assertSame("compare first parameter must be Identifiable",
        Identifiable.class, m.getParameterTypes()[0]);
    assertSame("compare second parameter must be Identifiable",
        Identifiable.class, m.getParameterTypes()[1]);
  }

  @Test
  public void factorIsPrivateIntInternalHelper() throws Exception {
    // factor() is the DESC ordering inversion helper — private, never overridden,
    // referenced only inside compare(). Pin its visibility so a refactor that opens it
    // (suggesting external callers) is recognised as a deliberate change.
    Method m = EntityComparator.class.getDeclaredMethod("factor", int.class, String.class);
    int mods = m.getModifiers();
    assertTrue("factor must be private (internal helper)", Modifier.isPrivate(mods));
    assertSame("factor must return int", int.class, m.getReturnType());
  }

  @Test
  public void declaresExpectedInstanceFieldShape() {
    // Pin the instance-field set — a future refactor that adds a new sort criterion
    // (e.g., a NULLS_LAST flag) would silently change comparator semantics; this assertion
    // forces the change to be acknowledged.
    Set<String> expectedFieldNames = Set.of("orderCriteria", "context", "collator");
    var actual = new HashSet<String>();
    for (Field f : EntityComparator.class.getDeclaredFields()) {
      if (f.isSynthetic()) {
        continue;
      }
      actual.add(f.getName());
    }
    assertEquals("declared instance-field name set must remain stable",
        expectedFieldNames, actual);

    // All three instance fields must remain private final — pin the immutability contract
    // so a refactor that opens up mutation (which would silently break sort stability across
    // a single sort() invocation) is recognised as a deliberate change.
    for (Field f : EntityComparator.class.getDeclaredFields()) {
      if (f.isSynthetic()) {
        continue;
      }
      assertTrue("instance field '" + f.getName() + "' must be private",
          Modifier.isPrivate(f.getModifiers()));
      assertTrue("instance field '" + f.getName() + "' must be final",
          Modifier.isFinal(f.getModifiers()));
    }
  }

  @Test
  public void chainDeadVia_EntityHelperSort_andTestOnlyReachableViaCRUDDocumentValidationTest()
      throws NoSuchMethodException {
    // Documentation-as-assertion: pin the deletion order:
    //
    //   1. delete EntityHelper.sort (dead per PSI; pinned in EntityHelperDeadCodeTest);
    //   2. delete this class (chain-dead once step 1 lands);
    //   3. drop or rewrite tests/CRUDDocumentValidationTest's comparator-stability check
    //      (the only test-only reference, identified by Phase A PSI audit).
    //
    // We pin the chain-source via a reference-accurate signature lookup so that renaming
    // EntityHelper.sort, Pair, or CommandContext fails the test rather than silently
    // breaking the deletion plan. Class-literal-only assertions would not be falsifiable
    // here — the imports above already keep the symbols load-bearing.
    var sort = EntityHelper.class.getDeclaredMethod(
        "sort", List.class, List.class, CommandContext.class);
    assertSame("EntityHelper.sort must take Pair<String,String> order entries",
        List.class, sort.getParameterTypes()[1]);
    assertSame("EntityHelper.sort must remain context-aware via CommandContext",
        CommandContext.class, sort.getParameterTypes()[2]);
  }
}
