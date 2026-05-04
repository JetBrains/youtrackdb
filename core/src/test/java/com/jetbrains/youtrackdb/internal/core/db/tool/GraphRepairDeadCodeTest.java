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

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageRecoverEventListener;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link GraphRepair}. PSI all-scope {@code ReferencesSearch} confirms zero
 * production references across all modules. The 3 references that exist all live in a
 * single test file: {@code GraphRecoveringTest} in
 * {@code core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/}. The class is
 * therefore <strong>test-only-reachable</strong> — its deletion in the deferred-cleanup
 * track is contingent on either deleting {@code GraphRecoveringTest} outright or
 * retargeting it (the recovery scenarios it exercises are storage-IT territory and may
 * have been superseded by the {@code LocalPaginatedStorageRestore*} family).
 *
 * <p>Two pinnable observables can be exercised <em>without</em> a live database:
 * (1) the no-arg constructor leaves {@code eventListener} null, and (2) the
 * {@link #setEventListener(StorageRecoverEventListener)} fluent setter returns {@code this}
 * so chained-builder usage compiles. The remaining methods ({@code repair}, {@code check},
 * {@code repairEdges}, {@code repairVertices}, {@code isEdgeBroken}) all need a live
 * {@code DatabaseSessionEmbedded} with vertex/edge classes, and exercising them here would
 * duplicate {@code GraphRecoveringTest}; pin them via reflection only.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@link GraphRepair} together with this
 * test file and {@code GraphRecoveringTest} (or retarget {@code GraphRecoveringTest} at
 * the storage-IT layer where the equivalent recovery is already exercised).
 *
 * <p>Standalone — no database session needed.
 */
public class GraphRepairDeadCodeTest {

  // The complete declared-method surface this test-only-reachable tool offers, as a sorted
  // set of names. Pinning the set (rather than the count) catches both a method dropped
  // silently and a method renamed in place — either would shift the resulting set.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "repair",
      "check",
      "repairEdges",
      "repairVertices",
      "onScannedLink",
      "onRemovedLink",
      "getEventListener",
      "setEventListener",
      "message",
      "isEdgeBroken",
      "getInverseConnectionFieldName"));

  @Test
  public void classIsPublicConcreteAndDoesNotExtendDatabaseTool() {
    // Unlike DatabaseRepair/CheckIndexTool/DatabaseCompare, GraphRepair stands alone — it
    // does NOT extend the DatabaseTool base. Pin the inheritance shape so a refactor
    // that retrofits it onto DatabaseTool is recognized as a deliberate change.
    var clazz = GraphRepair.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must NOT be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertSame("must directly extend Object (not DatabaseTool)",
        Object.class, clazz.getSuperclass());
  }

  @Test
  public void declaresExactlyTheExpectedDeclaredMethodNames() {
    var actual = new TreeSet<String>();
    for (Method m : GraphRepair.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the pinned test-only-reachable surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void declaresSingleNoArgConstructor() {
    Constructor<?>[] ctors = GraphRepair.class.getDeclaredConstructors();
    assertEquals("must declare exactly one constructor", 1, ctors.length);

    Constructor<?> ctor = ctors[0];
    assertTrue("ctor must be public (default)", Modifier.isPublic(ctor.getModifiers()));
    assertEquals("ctor must be no-arg", 0, ctor.getParameterCount());
  }

  @Test
  public void noArgConstructorLeavesEventListenerNull() throws Exception {
    // The single behaviorally-pinnable observation that does not require a live database:
    // the no-arg ctor must leave eventListener null. A future refactor that lazily
    // allocates a default listener (which would silently change recovery semantics) fails
    // this assertion.
    var probe = new GraphRepair();
    var listenerField = GraphRepair.class.getDeclaredField("eventListener");
    listenerField.setAccessible(true);
    assertNull("eventListener must remain null after no-arg construction",
        listenerField.get(probe));
    assertNull("getEventListener() must mirror the underlying field",
        probe.getEventListener());
  }

  @Test
  public void setEventListenerIsFluentAndReturnsThis() {
    // Pin the fluent-builder return-type contract. Tests using
    // `new GraphRepair().setEventListener(l).repair(...)` would silently break if the
    // setter's return type was changed to void.
    var probe = new GraphRepair();
    GraphRepair returned = probe.setEventListener(null);
    assertSame("setEventListener must return this for fluent chaining", probe, returned);
  }

  @Test
  public void getInverseConnectionFieldNameIsPublicStatic() throws Exception {
    // Companion utility used by GraphRecoveringTest scenarios that consult inverse-edge
    // field names. Pin the static + return-type contract — signature is
    // (fieldName, useVertexFieldsForEdgeLabels).
    Method m = GraphRepair.class.getDeclaredMethod(
        "getInverseConnectionFieldName", String.class, boolean.class);
    int mods = m.getModifiers();
    assertTrue("getInverseConnectionFieldName must be public", Modifier.isPublic(mods));
    assertTrue("getInverseConnectionFieldName must be static", Modifier.isStatic(mods));
    assertSame("must return String", String.class, m.getReturnType());
  }

  @Test
  public void repairAndCheckAreVoidPublicTwins() throws Exception {
    // The two main entry points share a parameter shape (database, listener, options). Pin
    // both as public + void so a refactor that returns a stats payload fails this
    // assertion (and forces an explicit decision about the new return value at the
    // GraphRecoveringTest sites).
    for (String name : new String[] {"repair", "check"}) {
      // Resolve by name+arity since the parameter list contains generics that can drift.
      Method m = null;
      for (Method candidate : GraphRepair.class.getDeclaredMethods()) {
        if (candidate.getName().equals(name) && candidate.getParameterCount() == 3) {
          m = candidate;
          break;
        }
      }
      assertSame(name + " must declare a 3-arg overload", true, m != null);
      assertTrue(name + " must be public", Modifier.isPublic(m.getModifiers()));
      assertSame(name + " must return void", void.class, m.getReturnType());
    }
  }

  @Test
  public void repairStatsInnerClassIsPublicStaticAndStateful() {
    // GraphRecoveringTest reads RepairStats fields. Pin its modifier shape so a refactor
    // that hides the class (e.g. nesting it in a private holder) fails loudly.
    var stats = GraphRepair.RepairStats.class;
    int mods = stats.getModifiers();
    assertTrue("RepairStats must be public", Modifier.isPublic(mods));
    assertTrue("RepairStats must be static (nested, not inner)", Modifier.isStatic(mods));
    assertFalse("RepairStats must NOT be abstract", Modifier.isAbstract(mods));
  }
}
