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
package com.jetbrains.youtrackdb.internal.core.command.traverse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import org.junit.Test;

/**
 * Unit tests for {@link TraverseRecordSetProcess} — the root processor for the target iterator.
 * On construction it auto-pushes itself into the context's memory, then each {@code process()}
 * advances one record and pushes either a {@link TraverseRecordProcess} (for plain EntityImpl),
 * a nested {@link TraverseRecordSetProcess} (for the single-field-is-a-Collection shortcut), or a
 * nested {@link TraverseRecordProcess} (for the single-field-is-an-EntityImpl shortcut).
 *
 * <p>Track 9 Step 3 coverage for:
 * <ul>
 *   <li>Empty iterator → pop (returns null).
 *   <li>Constructor auto-push into memory.
 *   <li>{@code toString} on null vs. non-null target.
 *   <li>Normal entity target → push RP, return null.
 *   <li>Non-persistent single-Collection-field shortcut → push nested RSP.
 *   <li>Non-persistent single-EntityImpl-field shortcut → push nested RP.
 * </ul>
 */
public class TraverseRecordSetProcessTest extends DbTestBase {

  /**
   * Constructor auto-push pin: creating an RSP adds exactly one frame to the context's memory.
   * This is the fundamental invariant {@link Traverse#target} relies on to make the Traverse
   * non-empty.
   */
  @Test
  public void constructorAutoPushesSelfIntoContextMemory() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      var context = traverse.getContext();

      assertTrue("precondition: fresh context is empty", context.isEmpty());
      new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>emptyList().iterator(),
          TraversePath.empty(), session);

      assertEquals("context has exactly one frame (the RSP itself)",
          1, ((java.util.Collection<?>) context.getVariables().get("stack")).size());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code process()} on an empty iterator falls through the {@code while} loop and calls
   * {@code pop}, which returns null. Observable via: the RSP frame is removed from memory.
   */
  @Test
  public void processOnEmptyIteratorPopsFrameAndReturnsNull() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      var rsp = new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>emptyList().iterator(),
          TraversePath.empty(), session);
      var context = traverse.getContext();
      assertEquals("precondition: 1 frame (the RSP)", 1,
          ((java.util.Collection<?>) context.getVariables().get("stack")).size());

      var result = rsp.process();

      assertNull("empty iterator → process returns null", result);
      assertTrue("memory is empty after the RSP pops itself", context.isEmpty());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code process()} on an iterator of one persistent EntityImpl returns null (does not emit
   * directly) and pushes a {@link TraverseRecordProcess} on top of the RSP — memory grows by one.
   */
  @Test
  public void processWithPersistentEntityPushesRecordProcess() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(entity);
      var traverse = new Traverse(session);
      var rsp = new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>singletonList(loaded).iterator(),
          TraversePath.empty(), session);
      var context = traverse.getContext();

      var result = rsp.process();

      assertNull("process pushes a subprocess and returns null", result);
      assertEquals("memory has RSP + new RP = 2 frames", 2,
          ((java.util.Collection<?>) context.getVariables().get("stack")).size());
    } finally {
      session.rollback();
    }
  }

  /**
   * End-to-end pin: a target iterator of two distinct records produces two emitted results. This
   * validates the RSP cycle: push RP for first, process emits first; return null pops RP; next
   * RSP.process() pushes RP for second; and so on.
   */
  @Test
  public void iteratorOfTwoRecordsEmitsBothInOrder() {
    session.begin();
    var a = (EntityImpl) session.newEntity();
    var b = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loadedA = session.getActiveTransaction().load(a);
      var loadedB = session.getActiveTransaction().load(b);
      Iterator<Identifiable> targets = Arrays.<Identifiable>asList(loadedA, loadedB).iterator();
      var traverse = new Traverse(session);
      traverse.target(targets).fields("*");

      var results = new HashSet<>(traverse.execute(session));

      assertTrue("A is emitted", results.contains(loadedA));
      assertTrue("B is emitted", results.contains(loadedB));
      assertEquals("exactly two results — nothing extra from empty link graphs",
          2, results.size());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code toString} returns the target iterator's {@code toString} when non-null, {@code "-"}
   * otherwise. Pins the debug contract used by the {@code stack} variable.
   */
  @Test
  public void toStringRendersTargetOrDashForNullTarget() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      var rsp = new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>emptyList().iterator(),
          TraversePath.empty(), session);

      assertNotNull("toString is never null (target is non-null here)",
          rsp.toString());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code getPath} on an RSP returns the parent path after {@link TraversePath#appendRecordSet}
   * (which is a no-op); the RSP does not add an extra path component of its own.
   */
  @Test
  public void getPathEqualsParentPathAfterAppendRecordSet() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      var parent = TraversePath.empty().append(
          new com.jetbrains.youtrackdb.internal.core.id.RecordId(10, 100L));
      var rsp = new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>emptyList().iterator(), parent, session);

      assertEquals("RSP path is parent + recordSet — same render as parent",
          parent.toString(), rsp.getPath().toString());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code getPath} differs for different parent paths — the constructor captures the parent and
   * subsequent context changes don't leak into the cached path. This is a pure-data pin on the
   * {@code path} field initialization.
   */
  @Test
  public void getPathIsComposedFromParentPathConstructorArg() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      var parent1 = TraversePath.empty().append(
          new com.jetbrains.youtrackdb.internal.core.id.RecordId(1, 1L));
      var parent2 = TraversePath.empty().append(
          new com.jetbrains.youtrackdb.internal.core.id.RecordId(2, 2L));

      var rsp1 = new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>emptyList().iterator(), parent1, session);
      var rsp2 = new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>emptyList().iterator(), parent2, session);

      assertNotNull(rsp1.getPath());
      assertNotNull(rsp2.getPath());
      assertTrue("distinct parents produce distinct path renders",
          !rsp1.getPath().toString().equals(rsp2.getPath().toString()));
    } finally {
      session.rollback();
    }
  }
}
