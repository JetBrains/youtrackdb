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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for the abstract base {@link DatabaseTool}. The class is the parent of every
 * concrete tool in the package — {@link DatabaseImport}, {@link DatabaseExport},
 * {@link DatabaseRepair}, {@link BonsaiTreeRepair}, {@link DatabaseCompare},
 * {@link GraphRepair}, {@link CheckIndexTool} — and its public protocol is the
 * fluent-setter API plus the abstract {@code parseSetting} hook.
 *
 * <p>The pin is interface-level only (reflection only): the live behavioural coverage
 * sits in {@link DatabaseExportImportRoundTripTest} and the per-tool dead-code pins in
 * this package. This pin enforces the contract shape — modifier flags, field shape,
 * declared-method set, abstract-method set — so a refactor to the abstract base is forced
 * to touch this test in lockstep.
 *
 * <p>Standalone — no session needed; no behaviour exercised here.
 */
public class DatabaseToolDeadCodeTest {

  // The complete declared-method surface, names only. Pinning the set (rather than just
  // the count) catches both silent deletions (set shrinks) and silent renames (set shifts).
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "parseSetting",
      "message",
      "setOptions",
      "setOutputListener",
      "setDatabaseSession",
      "setVerbose"));

  // The complete declared-field surface. The base holds the `output` listener, the
  // `session` reference (typed via the type parameter S), and the `verbose` flag.
  private static final Set<String> EXPECTED_DECLARED_FIELD_NAMES = new TreeSet<>(Set.of(
      "output",
      "session",
      "verbose"));

  @Test
  public void classIsPublicAbstractAndImplementsRunnable() {
    var clazz = DatabaseTool.class;
    assertTrue("DatabaseTool must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue(
        "DatabaseTool must be abstract — concrete tools subclass it",
        Modifier.isAbstract(clazz.getModifiers()));
    // Implements Runnable — the run() method is the entry point a console wires to.
    var implementsRunnable = false;
    for (var iface : clazz.getInterfaces()) {
      if (iface == Runnable.class) {
        implementsRunnable = true;
        break;
      }
    }
    assertTrue(
        "DatabaseTool must implement Runnable so console / test harnesses can invoke it",
        implementsRunnable);
  }

  @Test
  public void hasExactlyOneTypeParameterBoundedToDatabaseSessionEmbedded() {
    var clazz = DatabaseTool.class;
    var typeParams = clazz.getTypeParameters();
    assertEquals(
        "DatabaseTool exposes exactly one type parameter — the session-subtype slot",
        1, typeParams.length);
    var sessionParam = typeParams[0];
    assertNotNull(sessionParam);
    // Lower-bound: the session slot must be at least DatabaseSessionEmbedded so concrete
    // tools can call session-specific APIs without unchecked casts.
    var bounds = sessionParam.getBounds();
    assertEquals(1, bounds.length);
    assertEquals(
        "type parameter must be bounded to DatabaseSessionEmbedded",
        "com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded",
        bounds[0].getTypeName());
  }

  @Test
  public void declaresExactlyTheExpectedMethodNames() {
    var actual = new TreeSet<String>();
    for (Method m : DatabaseTool.class.getDeclaredMethods()) {
      // Synthetic bridges (e.g. covariant returns from the fluent setters) would
      // otherwise inflate the count.
      if (!m.isSynthetic()) {
        actual.add(m.getName());
      }
    }
    assertEquals(
        "DatabaseTool's declared-method surface drifted — update this pin in lockstep",
        EXPECTED_DECLARED_METHOD_NAMES,
        actual);
  }

  @Test
  public void parseSettingIsTheOnlyAbstractMethod() {
    var abstractCount = 0;
    var foundParseSetting = false;
    for (Method m : DatabaseTool.class.getDeclaredMethods()) {
      if (Modifier.isAbstract(m.getModifiers())) {
        abstractCount++;
        if ("parseSetting".equals(m.getName())) {
          foundParseSetting = true;
        }
      }
    }
    assertEquals(
        "DatabaseTool exposes exactly one abstract method — parseSetting",
        1, abstractCount);
    assertTrue(
        "the abstract method must be parseSetting",
        foundParseSetting);
  }

  @Test
  public void declaresExactlyTheExpectedFieldNames() {
    var actual = new TreeSet<String>();
    for (Field f : DatabaseTool.class.getDeclaredFields()) {
      if (!f.isSynthetic()) {
        actual.add(f.getName());
      }
    }
    assertEquals(
        "DatabaseTool's declared-field surface drifted — update this pin in lockstep",
        EXPECTED_DECLARED_FIELD_NAMES,
        actual);
  }

  @Test
  public void allDeclaredFieldsAreProtected() {
    for (Field f : DatabaseTool.class.getDeclaredFields()) {
      if (f.isSynthetic()) {
        continue;
      }
      assertTrue(
          "field " + f.getName() + " must be protected so subclasses can access it",
          Modifier.isProtected(f.getModifiers()));
      assertFalse(
          "field " + f.getName() + " must NOT be static (per-instance state)",
          Modifier.isStatic(f.getModifiers()));
    }
  }

  @Test
  public void fluentSettersReturnDatabaseToolForChaining() throws Exception {
    // The setters must declare a return type assignable from DatabaseTool so the
    // existing `new DatabaseExport(...).setOptions(...).exportDatabase()` chains keep
    // type-checking even after a hypothetical signature relaxation.
    var setOptions = DatabaseTool.class.getDeclaredMethod("setOptions", String.class);
    var setOutputListener =
        DatabaseTool.class.getDeclaredMethod(
            "setOutputListener",
            com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener.class);
    var setVerbose = DatabaseTool.class.getDeclaredMethod("setVerbose", boolean.class);
    var setDatabaseSession =
        DatabaseTool.class.getDeclaredMethod(
            "setDatabaseSession",
            com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.class);
    assertTrue(DatabaseTool.class.isAssignableFrom(setOptions.getReturnType()));
    assertTrue(DatabaseTool.class.isAssignableFrom(setOutputListener.getReturnType()));
    assertTrue(DatabaseTool.class.isAssignableFrom(setVerbose.getReturnType()));
    assertTrue(DatabaseTool.class.isAssignableFrom(setDatabaseSession.getReturnType()));
  }

  @Test
  public void parseSettingHasExpectedSignature() throws Exception {
    var m =
        DatabaseTool.class.getDeclaredMethod(
            "parseSetting", String.class, java.util.List.class);
    assertTrue(
        "parseSetting must remain abstract — concrete tools own the dispatch table",
        Modifier.isAbstract(m.getModifiers()));
    assertTrue(
        "parseSetting must remain protected — it is a subclass-only hook",
        Modifier.isProtected(m.getModifiers()));
    assertEquals(void.class, m.getReturnType());
  }

  @Test
  public void messageHelperIsProtectedNonStaticAndVariadic() throws Exception {
    var m = DatabaseTool.class.getDeclaredMethod("message", String.class, Object[].class);
    assertTrue(
        "message helper must be protected (subclass-only)",
        Modifier.isProtected(m.getModifiers()));
    assertFalse(
        "message helper must not be static — the listener lives on the instance",
        Modifier.isStatic(m.getModifiers()));
    assertTrue("message helper must be varargs", m.isVarArgs());
    assertEquals(void.class, m.getReturnType());
  }
}
