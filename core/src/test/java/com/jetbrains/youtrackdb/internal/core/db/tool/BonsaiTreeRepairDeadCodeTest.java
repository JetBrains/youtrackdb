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
package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link BonsaiTreeRepair}. PSI all-scope {@code ReferencesSearch} confirms zero
 * production references and zero test references across the full module graph. Unlike the
 * sibling {@link DatabaseRepair} which extends the {@link DatabaseTool} base, this class is a
 * static-method holder (no instance fields, no instance methods, no inheritance) so the
 * shape pin focuses on the single static entry point and the absence of any instance surface.
 *
 * <p>The single load-bearing observable that does not require a live database is the
 * private {@code message} static helper's null-listener short-circuit: when the
 * {@link CommandOutputListener} is null, no NPE is thrown. Pinning that branch behaviorally
 * via reflection guards against a refactor that drops the null guard before the listener's
 * {@code onMessage} call. The full {@code repairDatabaseRidbags} path requires a live
 * database with edges and is exercised only through deletion in the deferred-cleanup track,
 * so the rest of the pin is reflection-only.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link BonsaiTreeRepair} together with this
 * test file. No external surface needs retargeting because there are no callers anywhere.
 *
 * <p>Standalone — no database session needed.
 */
public class BonsaiTreeRepairDeadCodeTest {

  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "repairDatabaseRidbags",
      "message"));

  @Test
  public void classIsPublicConcreteAndDoesNotExtendDatabaseTool() {
    // Unlike DatabaseRepair / DatabaseCompare / GraphRepair / CheckIndexTool, this class is
    // a flat static-method holder — no inheritance from DatabaseTool. Pin to catch a
    // refactor that retrofits it onto the DatabaseTool base or makes it abstract.
    var clazz = BonsaiTreeRepair.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must NOT be abstract (concrete static-method holder)",
        Modifier.isAbstract(clazz.getModifiers()));
    assertSame("must directly extend Object (not DatabaseTool)",
        Object.class, clazz.getSuperclass());
  }

  @Test
  public void hasNoInstanceFields() {
    // Pure static-helper holder — pinning the empty-instance-field set catches a refactor
    // that introduces stateful operation (which would change the threading invariant).
    long instanceFields = java.util.Arrays.stream(BonsaiTreeRepair.class.getDeclaredFields())
        .filter(f -> !f.isSynthetic())
        .filter(f -> !Modifier.isStatic(f.getModifiers()))
        .count();
    assertEquals("BonsaiTreeRepair must remain a stateless static-method holder",
        0L, instanceFields);
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    var actual = new TreeSet<String>();
    for (Method m : BonsaiTreeRepair.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the pinned dead surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void allDeclaredMethodsAreStatic() {
    for (Method m : BonsaiTreeRepair.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      assertTrue(m.getName() + " must be static", Modifier.isStatic(m.getModifiers()));
    }
  }

  @Test
  public void repairDatabaseRidbagsHasExpectedSignature() throws Exception {
    Method m = BonsaiTreeRepair.class.getDeclaredMethod(
        "repairDatabaseRidbags", DatabaseSessionEmbedded.class, CommandOutputListener.class);
    int mods = m.getModifiers();
    assertTrue("repairDatabaseRidbags must be public", Modifier.isPublic(mods));
    assertTrue("repairDatabaseRidbags must be static", Modifier.isStatic(mods));
    assertSame("repairDatabaseRidbags must return void", void.class, m.getReturnType());
    // Pin parameter ordering — a swap between (db, listener) and (listener, db) would
    // compile silently for any caller passing nulls, so a deliberate guard at this layer.
    assertSame(DatabaseSessionEmbedded.class, m.getParameterTypes()[0]);
    assertSame(CommandOutputListener.class, m.getParameterTypes()[1]);
  }

  @Test
  public void messageHelperIsPrivateStaticAndShortCircuitsOnNullListener() throws Exception {
    // The single behaviorally-pinnable observation: when the listener is null,
    // message() must not throw. A future refactor that drops the null guard before
    // listener.onMessage(...) would NPE under this assertion.
    Method m = BonsaiTreeRepair.class.getDeclaredMethod(
        "message", CommandOutputListener.class, String.class);
    int mods = m.getModifiers();
    assertTrue("message helper must be private", Modifier.isPrivate(mods));
    assertTrue("message helper must be static", Modifier.isStatic(mods));
    m.setAccessible(true);
    // Reflection invocation of (null, "anything") — must not throw, return value is void/null.
    Object ret = m.invoke(null, null, "irrelevant content");
    assertNull("message returns void; reflective invoke yields null", ret);
  }

  @Test
  public void declaresSingleNoArgPublicConstructor() {
    // The default no-arg ctor exists for compatibility but is itself unused since
    // every method is static. Pin its existence so a future refactor that adds a private
    // ctor (intent: utility class) is recognized as a deliberate state change.
    Constructor<?>[] ctors = BonsaiTreeRepair.class.getDeclaredConstructors();
    assertEquals("BonsaiTreeRepair must keep exactly one (default) constructor",
        1, ctors.length);
    Constructor<?> ctor = ctors[0];
    assertEquals("default ctor must be no-arg", 0, ctor.getParameterCount());
    assertTrue("default ctor must be public (Java implicit)",
        Modifier.isPublic(ctor.getModifiers()));
  }
}
