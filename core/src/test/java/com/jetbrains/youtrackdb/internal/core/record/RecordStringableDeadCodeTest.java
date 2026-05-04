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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link RecordStringable}. PSI all-scope {@code ClassInheritorsSearch} returns
 * <strong>zero implementers</strong> across the full module graph (core, server, driver,
 * embedded, gremlin-annotations, docker-tests, tests). Both abstract methods —
 * {@link RecordStringable#value()} and {@link RecordStringable#value(String)} — are unreferenced
 * outside the interface declaration itself. The interface is therefore a fully dead public API.
 *
 * <p>Each abstract method is pinned individually so that a partial deletion (drop one method,
 * keep the other) still leaves the pin set valid. A rename or signature drift on either
 * surviving method fails loudly.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link RecordStringable} together with this
 * test file. No retargeting needed: zero implementers means no class will fail to compile when
 * the interface goes away.
 *
 * <p>Standalone — no database session needed; pure {@link Class}-level reflection.
 */
public class RecordStringableDeadCodeTest {

  // The two abstract method names this dead interface declares. Pinning the set (rather than
  // the count) catches both a method dropped silently and a method renamed in place.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "value"));

  @Test
  public void typeIsPublicInterface() {
    var clazz = RecordStringable.class;
    int mods = clazz.getModifiers();
    assertTrue("must be public", Modifier.isPublic(mods));
    assertTrue("must be an interface", clazz.isInterface());
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNameSet() {
    // Both abstract methods share the name "value" (overload by parameter list), so the
    // name-set is a single entry. The count assertion below distinguishes overload-loss
    // from rename.
    var actual = new TreeSet<String>();
    for (Method m : RecordStringable.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must remain {value} (both overloads share name)",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void declaresExactlyTwoOverloadsOfValue() {
    // Two abstract methods (getter + fluent setter). A future refactor that drops either
    // overload shifts the count; a refactor that adds a third method also shifts it.
    int valueMethodCount = 0;
    for (Method m : RecordStringable.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      if (m.getName().equals("value")) {
        valueMethodCount++;
      }
    }
    assertEquals("must declare exactly two value() overloads (getter + fluent setter)",
        2, valueMethodCount);
  }

  // --- Per-method pins. Each isolated so that partial deletion stays valid. ---

  @Test
  public void valueGetterIsAbstractStringNoArg() throws Exception {
    Method m = RecordStringable.class.getDeclaredMethod("value");
    int mods = m.getModifiers();
    assertTrue("value() must be public (interface contract)", Modifier.isPublic(mods));
    assertTrue("value() must be abstract (interface contract)", Modifier.isAbstract(mods));
    assertSame("value() must return String", String.class, m.getReturnType());
    assertEquals("value() must take zero parameters", 0, m.getParameterCount());
  }

  @Test
  public void valueFluentSetterIsAbstractRecordStringableTakingString() throws Exception {
    Method m = RecordStringable.class.getDeclaredMethod("value", String.class);
    int mods = m.getModifiers();
    assertTrue("value(String) must be public (interface contract)", Modifier.isPublic(mods));
    assertTrue("value(String) must be abstract (interface contract)", Modifier.isAbstract(mods));
    assertSame("value(String) must return RecordStringable (fluent setter contract)",
        RecordStringable.class, m.getReturnType());
    assertSame("value(String) parameter type must be String",
        String.class, m.getParameterTypes()[0]);
  }
}
