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
package com.jetbrains.youtrackdb.internal.core.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link RecordListener}. PSI all-scope {@code ClassInheritorsSearch} returns
 * <strong>zero implementers</strong> across the full module graph (core, server, driver,
 * embedded, gremlin-annotations, docker-tests, tests). The single abstract method
 * {@link RecordListener#onEvent(DBRecord, RecordListener.EVENT)} has zero callers anywhere.
 * The interface is annotated {@link Deprecated}, signaling the same conclusion the PSI audit
 * confirms — it is a fully dead public API surface.
 *
 * <p>The accompanying {@link RecordListener.EVENT} enum is also pinned: it documents the six
 * record-lifecycle events the listener was meant to surface, and a future refactor that
 * either re-uses the listener (re-introducing implementers) or shifts the enum constants
 * silently would trip these reflective checks.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link RecordListener} (and its nested
 * {@link RecordListener.EVENT} enum) together with this test file. No retargeting needed:
 * zero implementers means no class will fail to compile when the interface goes away.
 *
 * <p>Standalone — no database session needed; pure {@link Class}-level reflection.
 */
public class RecordListenerDeadCodeTest {

  // The single abstract method this dead interface declares.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "onEvent"));

  // The six event constants the dead enum declares, sorted for stable comparison.
  private static final Set<String> EXPECTED_ENUM_CONSTANT_NAMES = new TreeSet<>(Set.of(
      "CLEAR",
      "RESET",
      "MARSHALL",
      "UNMARSHALL",
      "UNLOAD",
      "IDENTITY_CHANGED"));

  @Test
  public void typeIsPublicDeprecatedInterface() {
    var clazz = RecordListener.class;
    int mods = clazz.getModifiers();
    assertTrue("must be public", Modifier.isPublic(mods));
    assertTrue("must be an interface", clazz.isInterface());
    assertNotNull("must remain @Deprecated — the deprecation signals the dead status",
        clazz.getAnnotation(Deprecated.class));
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    var actual = new TreeSet<String>();
    for (Method m : RecordListener.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must remain {onEvent}",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void onEventIsAbstractVoidWithRecordAndEventParameters() throws Exception {
    Method m = RecordListener.class.getDeclaredMethod(
        "onEvent", DBRecord.class, RecordListener.EVENT.class);
    int mods = m.getModifiers();
    assertTrue("onEvent must be public (interface contract)", Modifier.isPublic(mods));
    assertTrue("onEvent must be abstract (interface contract)", Modifier.isAbstract(mods));
    assertSame("onEvent must return void", void.class, m.getReturnType());
    assertEquals("onEvent must take exactly two parameters", 2, m.getParameterCount());
    assertSame("onEvent first parameter must be DBRecord",
        DBRecord.class, m.getParameterTypes()[0]);
    assertSame("onEvent second parameter must be RecordListener.EVENT",
        RecordListener.EVENT.class, m.getParameterTypes()[1]);
  }

  @Test
  public void eventEnumDeclaresAllSixLifecycleConstants() {
    // Pin the enum's full constant set — a refactor that drops one (e.g. removing
    // IDENTITY_CHANGED because the embedded RID model changed) would silently shrink the
    // public surface; a refactor that adds one would silently extend it.
    var clazz = RecordListener.EVENT.class;
    assertTrue("EVENT must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("EVENT must be an enum", clazz.isEnum());

    var actual = new TreeSet<String>();
    for (var constant : clazz.getEnumConstants()) {
      actual.add(constant.name());
    }
    assertEquals("EVENT enum constant set must remain stable",
        EXPECTED_ENUM_CONSTANT_NAMES, actual);
    assertEquals("EVENT must declare exactly six constants",
        6, Arrays.stream(clazz.getEnumConstants()).count());
  }
}
