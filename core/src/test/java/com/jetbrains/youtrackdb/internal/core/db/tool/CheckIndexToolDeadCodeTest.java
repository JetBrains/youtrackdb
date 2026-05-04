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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link CheckIndexTool}. PSI all-scope {@code ReferencesSearch} confirms zero
 * production references across all modules. The 2 references that exist all live in a
 * single test file: {@code CheckIndexToolTest} in
 * {@code core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/}. The class is
 * therefore <strong>test-only-reachable</strong> — its deletion in the deferred-cleanup
 * track is contingent on either deleting {@code CheckIndexToolTest} outright or migrating
 * the indexed-record check into a generic index-validation helper (the underlying logic
 * could be expressed as a query against the index iterator, which the index test
 * infrastructure already exercises).
 *
 * <p>The single behaviorally-pinnable observation that does not require a live database is
 * {@link CheckIndexTool#getTotalErrors()} returning 0 on a freshly constructed instance.
 * Constructing the tool is cheap (the no-arg ctor is implicit; {@code totalErrors} starts
 * at 0); driving {@code run()} requires a live session with indexes, which
 * {@code CheckIndexToolTest} already exercises.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link CheckIndexTool} together with this
 * test file and {@code CheckIndexToolTest} (or retarget {@code CheckIndexToolTest} at the
 * generic index-validation helper if such a helper is introduced).
 *
 * <p>Standalone — no database session needed.
 */
public class CheckIndexToolDeadCodeTest {

  // The complete declared-method surface this test-only-reachable tool offers, as a sorted
  // set of names. Pinning the set (rather than the count) catches both a method dropped
  // silently and a method renamed in place — either would shift the resulting set.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "parseSetting",
      "run",
      "canCheck",
      "checkIndex",
      "checkCollection",
      "printProgress",
      "checkThatRecordIsIndexed",
      "getTotalErrors"));

  @Test
  public void classIsPublicConcreteAndExtendsDatabaseTool() {
    var clazz = CheckIndexTool.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must NOT be abstract (concrete tool)", Modifier.isAbstract(clazz.getModifiers()));
    assertSame(
        "must extend DatabaseTool (the abstract base) — direct, not via DatabaseImpExpAbstract",
        DatabaseTool.class, clazz.getSuperclass());
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    var actual = new TreeSet<String>();
    for (Method m : CheckIndexTool.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the pinned test-only-reachable surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void declaresSingleDefaultConstructor() {
    Constructor<?>[] ctors = CheckIndexTool.class.getDeclaredConstructors();
    assertEquals("must declare exactly one constructor", 1, ctors.length);

    Constructor<?> ctor = ctors[0];
    assertEquals("ctor must be no-arg (default)", 0, ctor.getParameterCount());
    assertTrue("ctor must be public (default visibility from Java)",
        Modifier.isPublic(ctor.getModifiers()));
  }

  @Test
  public void parseSettingIsProtectedAndOverridesAbstractBase() throws Exception {
    Method m = CheckIndexTool.class.getDeclaredMethod(
        "parseSetting", String.class, java.util.List.class);
    int mods = m.getModifiers();
    assertTrue("parseSetting must be protected", Modifier.isProtected(mods));
    assertFalse("parseSetting must not be abstract", Modifier.isAbstract(mods));
    assertSame("parseSetting must return void", void.class, m.getReturnType());
  }

  @Test
  public void runIsPublicVoidNoArgRunnableImplementation() throws Exception {
    Method m = CheckIndexTool.class.getDeclaredMethod("run");
    int mods = m.getModifiers();
    assertTrue("run() must be public (Runnable contract)", Modifier.isPublic(mods));
    assertSame("run() must return void", void.class, m.getReturnType());
    assertEquals("run() must take no parameters", 0, m.getParameterCount());
  }

  @Test
  public void getTotalErrorsAccessorIsPublicLong() throws Exception {
    // The single accessor CheckIndexToolTest reads after running the tool. Pin the long
    // return type so a refactor to int (which would silently truncate the count) fails.
    Method m = CheckIndexTool.class.getDeclaredMethod("getTotalErrors");
    int mods = m.getModifiers();
    assertTrue("getTotalErrors must be public", Modifier.isPublic(mods));
    assertSame("getTotalErrors must return long", long.class, m.getReturnType());
  }

  @Test
  public void getTotalErrorsStartsAtZeroOnFreshInstance() {
    // Behavioral pin without a live database: the totalErrors counter starts at 0. A
    // refactor that initialized it to a sentinel would change the test-side baseline
    // and surface as a deliberate change here.
    var probe = new CheckIndexTool();
    assertEquals("freshly constructed tool must report 0 errors", 0L, probe.getTotalErrors());
  }

  @Test
  public void canCheckHelperIsPrivateStatic() throws Exception {
    Method m = CheckIndexTool.class.getDeclaredMethod(
        "canCheck", com.jetbrains.youtrackdb.internal.core.index.Index.class);
    int mods = m.getModifiers();
    assertTrue("canCheck must be private", Modifier.isPrivate(mods));
    assertTrue("canCheck must be static (no instance state)", Modifier.isStatic(mods));
    assertSame("canCheck must return boolean", boolean.class, m.getReturnType());
  }

  @Test
  public void totalErrorsIsTheSoleDeclaredInstanceField() {
    // Pin the field shape — adding a second instance field would shift the set and force
    // a deliberate decision (e.g. introducing per-index error breakdown).
    var actual = new HashSet<String>();
    for (Field f : CheckIndexTool.class.getDeclaredFields()) {
      if (f.isSynthetic()) {
        continue;
      }
      actual.add(f.getName());
    }
    assertArrayEquals("declared instance-field name set must remain {totalErrors}",
        new Object[] {"totalErrors"}, actual.toArray());
    Field totalErrors;
    try {
      totalErrors = CheckIndexTool.class.getDeclaredField("totalErrors");
    } catch (NoSuchFieldException e) {
      throw new AssertionError("totalErrors field must remain", e);
    }
    assertSame("totalErrors must remain long", long.class, totalErrors.getType());
  }
}
