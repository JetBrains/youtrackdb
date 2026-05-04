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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link DatabaseRepair}. PSI all-scope {@code ReferencesSearch} confirms zero
 * references in any production source <em>and</em> zero references in any test source across
 * the full module graph (core, server, driver, embedded, tests, gremlin-annotations,
 * docker-tests). The class is therefore <strong>fully dead</strong> — neither the runtime
 * `DatabaseTool` registration surface nor any console command refers to it.
 *
 * <p>The pin is reflection-only on purpose: instantiating the tool requires a live
 * {@link DatabaseSessionEmbedded} and {@link #run()} would iterate every collection in the
 * database. Since no production caller will ever drive that loop, a behavioral pin would
 * exercise code that the rest of the module already covers via {@code DatabaseTool}'s
 * supertype contract. Reflection on the declared methods, fields, and constructor catches
 * deletion / rename / signature drift cheaply.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link DatabaseRepair} together with this
 * test file. No external surface needs retargeting because there are no callers anywhere.
 *
 * <p>Standalone — no database session needed.
 */
public class DatabaseRepairDeadCodeTest {

  // The complete declared-method surface this dead tool offers, as a sorted set of names.
  // Pinning the set (rather than the count) catches both a method dropped silently and a
  // method renamed in place — either would shift the resulting set.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "parseSetting",
      "run",
      "removeBrokenLinks",
      "fixLink"));

  @Test
  public void classIsPublicConcreteAndExtendsDatabaseTool() {
    var clazz = DatabaseRepair.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must NOT be abstract (concrete tool)", Modifier.isAbstract(clazz.getModifiers()));
    assertSame("must extend DatabaseTool (the abstract base)",
        DatabaseTool.class, clazz.getSuperclass());
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    // Pin the full set of declared method names. A future addition (new helper) or
    // deletion (removed accessor) shifts the set and fails — making the deletion / rename
    // a deliberate change rather than a silent edit.
    var actual = new TreeSet<String>();
    for (Method m : DatabaseRepair.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the pinned dead surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void declaresSingleSessionConstructor() throws Exception {
    var ctors = DatabaseRepair.class.getDeclaredConstructors();
    assertEquals("must declare exactly one constructor", 1, ctors.length);

    Constructor<?> ctor = ctors[0];
    assertTrue("ctor must be public", Modifier.isPublic(ctor.getModifiers()));
    assertArrayEquals(
        "ctor signature must remain (DatabaseSessionEmbedded)",
        new Class<?>[] {DatabaseSessionEmbedded.class},
        ctor.getParameterTypes());
  }

  @Test
  public void parseSettingIsProtectedAndOverridesAbstractBase() throws Exception {
    Method m = DatabaseRepair.class.getDeclaredMethod(
        "parseSetting", String.class, java.util.List.class);
    int mods = m.getModifiers();
    assertTrue("parseSetting must be protected", Modifier.isProtected(mods));
    assertFalse("parseSetting must not be abstract", Modifier.isAbstract(mods));
    assertSame("parseSetting must return void", void.class, m.getReturnType());
  }

  @Test
  public void runIsPublicVoidNoArgRunnableImplementation() throws Exception {
    Method m = DatabaseRepair.class.getDeclaredMethod("run");
    int mods = m.getModifiers();
    assertTrue("run() must be public (Runnable contract)", Modifier.isPublic(mods));
    assertSame("run() must return void", void.class, m.getReturnType());
    assertEquals("run() must take no parameters", 0, m.getParameterCount());
  }

  @Test
  public void removeBrokenLinksHelperIsProtectedAndReturnsLong() throws Exception {
    Method m = DatabaseRepair.class.getDeclaredMethod("removeBrokenLinks");
    assertTrue("removeBrokenLinks must be protected (subclass-extension hook)",
        Modifier.isProtected(m.getModifiers()));
    assertSame("removeBrokenLinks must return long (error count)", long.class, m.getReturnType());
  }

  @Test
  public void fixLinkHelperIsProtectedAndReturnsBoolean() throws Exception {
    Method m = DatabaseRepair.class.getDeclaredMethod("fixLink", Object.class);
    assertTrue("fixLink must be protected (subclass-extension hook)",
        Modifier.isProtected(m.getModifiers()));
    assertSame("fixLink must return boolean (true to remove the link)",
        boolean.class, m.getReturnType());
  }

  @Test
  public void declaresSingleBooleanRemoveBrokenLinksFlag() {
    // Pin the single instance field — a future addition would shift this set and force the
    // pin author to acknowledge the new option (or the field's removal).
    var fieldNames = new TreeSet<String>();
    for (Field f : DatabaseRepair.class.getDeclaredFields()) {
      if (f.isSynthetic()) {
        continue;
      }
      fieldNames.add(f.getName());
    }
    assertEquals("field set must remain {removeBrokenLinks}", Set.of("removeBrokenLinks"),
        fieldNames);
  }
}
