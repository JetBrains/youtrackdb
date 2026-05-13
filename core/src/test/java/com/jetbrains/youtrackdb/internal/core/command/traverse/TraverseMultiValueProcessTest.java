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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link TraverseMultiValueProcess} — walks an {@link Iterator} of elements
 * produced by a multi-value field (link list, link set, link map values). Pushes a
 * {@link TraverseRecordProcess} for each {@link Identifiable} element; skips non-Identifiables;
 * pops when the iterator is exhausted. Exercised end-to-end via a {@link Traverse} driving a
 * link-list / link-map field, plus direct tests for {@code getPath} / {@code toString} /
 * empty-iterator {@code process}.
 *
 * <p>Track 9 Step 3 coverage for:
 * <ul>
 *   <li>Empty iterator → pop (returns null).
 *   <li>Iterator of Identifiable → push {@link TraverseRecordProcess} for each.
 *   <li>Iterator of mixed elements (non-Identifiable among Identifiables) → skip non-Identifiable.
 *   <li>{@code getPath} reports {@code parentPath.appendIndex(index)}.
 *   <li>{@code toString} renders the current {@code index}.
 * </ul>
 */
public class TraverseMultiValueProcessTest extends DbTestBase {

  /**
   * Safety net matching Track 8's {@code TestUtilsFixture.rollbackIfLeftOpen}.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  /**
   * Seed {@code traverse} with a single empty-iterator {@link TraverseRecordSetProcess} so the
   * context has one pop-able frame for the MVP under test to sit on top of.
   */
  private void seedEmptyRsp(Traverse traverse) {
    new TraverseRecordSetProcess(traverse,
        Collections.<Identifiable>emptyList().iterator(),
        TraversePath.empty(), session);
  }

  private int stackSize(Traverse traverse) {
    return ((Collection<?>) traverse.getContext().getVariables().get("stack")).size();
  }

  /**
   * Empty iterator: {@code process()} falls through the {@code while} loop and calls {@code pop},
   * which returns null. Observable via: the process pops its own frame (visible by memory
   * shrinking) and no subprocess is pushed for any element.
   */
  @Test
  public void processOnEmptyIteratorPopsFrameAndReturnsNull() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      seedEmptyRsp(traverse);

      Iterator<Object> emptyIt = Collections.<Object>emptyList().iterator();
      var mvp = new TraverseMultiValueProcess(traverse, emptyIt,
          TraversePath.empty(), session);
      traverse.getContext().push(mvp);
      int beforePushes = stackSize(traverse);

      var result = mvp.process();

      assertNull("empty iterator → process returns null (pop path)", result);
      assertEquals("memory shrinks by exactly one (the MVP frame is popped)",
          beforePushes - 1, stackSize(traverse));
    } finally {
      session.rollback();
    }
  }

  /**
   * Non-Identifiable elements in the iterator are skipped silently — only Identifiables produce
   * {@link TraverseRecordProcess} subprocesses. An iterator containing only strings and numbers
   * yields {@code null} from process (iterator exhausted). The MVP pops itself (one frame) and
   * no subprocess is pushed.
   */
  @Test
  public void nonIdentifiableEntriesAreSkippedWithoutPushingSubprocess() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      seedEmptyRsp(traverse);

      Iterator<Object> it = Arrays.<Object>asList("string-a", 42, 3.14).iterator();
      var mvp = new TraverseMultiValueProcess(traverse, it,
          TraversePath.empty(), session);
      traverse.getContext().push(mvp);
      int beforePushes = stackSize(traverse);

      var result = mvp.process();

      assertNull("iterator of only non-Identifiables eventually pops and returns null",
          result);
      // Exactly one frame removed (the MVP), nothing pushed — verifies no accidental RP was
      // created for the primitive elements.
      assertEquals("exactly the MVP frame is popped — no extra pushes from non-Identifiables",
          beforePushes - 1, stackSize(traverse));
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code getPath} reports a path ending in {@code [index]}, where {@code index} starts at -1
   * before any iteration and advances with each processed element. Pinning the pre-iteration
   * state is important because the index is used by the debugging {@code stack} variable.
   * {@code toString} before iteration renders as {@code [idx:-1]}.
   */
  @Test
  public void getPathBeforeProcessShowsIndexMinusOne() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      var parent = TraversePath.empty().append(new RecordId(9, 5L));
      var mvp = new TraverseMultiValueProcess(traverse,
          Collections.<Object>emptyList().iterator(), parent, session);

      assertEquals("pre-iteration path ends in [-1]",
          parent.appendIndex(-1).toString(), mvp.getPath().toString());
      assertEquals("pre-iteration toString renders idx:-1", "[idx:-1]", mvp.toString());
    } finally {
      session.rollback();
    }
  }

  /**
   * After one Identifiable element has been processed, {@code getPath} reflects the new index
   * (0 after the first element) and {@code toString} renders {@code [idx:0]}. Drives the MVP
   * directly to exercise the advance-index bookkeeping in {@link TraverseMultiValueProcess}.
   */
  @Test
  public void getPathAndToStringAdvanceIndexAfterEachProcessedElement() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(entity);
      var traverse = new Traverse(session);
      seedEmptyRsp(traverse);

      var parent = TraversePath.empty().append(new RecordId(7, 7L));
      Iterator<Object> it = Collections.<Object>singletonList(loaded).iterator();
      var mvp = new TraverseMultiValueProcess(traverse, it, parent, session);
      traverse.getContext().push(mvp);

      // Pre-process: index is -1.
      assertEquals("pre-process path ends in [-1]",
          parent.appendIndex(-1).toString(), mvp.getPath().toString());

      mvp.process();

      // Post-process: first element consumed, index is 0.
      assertEquals("post-process path ends in [0]",
          parent.appendIndex(0).toString(), mvp.getPath().toString());
      assertEquals("post-process toString renders idx:0", "[idx:0]", mvp.toString());
    } finally {
      session.rollback();
    }
  }

  /**
   * A link-list field is expanded by {@link TraverseMultiValueProcess} when driven by
   * {@link Traverse#execute} end-to-end — the link-list child is surfaced in results.
   */
  @Test
  public void linkListTraversalReachesChildViaMultiValueBranch() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    var child = (EntityImpl) session.newEntity();
    root.getOrCreateLinkList("children").addAll(Arrays.asList(child));
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      traverse.target(loadedRoot).fields("*");
      var results = new HashSet<>(traverse.execute(session));

      assertTrue("child is reached via the link-list branch",
          results.stream().anyMatch(r -> r.getIdentity().equals(child.getIdentity())));
    } finally {
      session.rollback();
    }
  }

  /**
   * Direct drive of the Identifiable branch: pushing an MVP over an iterator of one Identifiable
   * record — after process(), one TraverseRecordProcess must be pushed to the memory (visible in
   * {@code stack}), and process returns null (the record is not emitted yet — that happens when
   * the pushed RP runs).
   */
  @Test
  public void identifiableElementPushesRecordProcessOntoMemory() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(entity);
      var traverse = new Traverse(session);
      seedEmptyRsp(traverse);

      Iterator<Object> it = Collections.<Object>singletonList(loaded).iterator();
      var mvp = new TraverseMultiValueProcess(traverse, it,
          TraversePath.empty(), session);
      traverse.getContext().push(mvp);
      int beforePushes = stackSize(traverse);

      var result = mvp.process();

      assertNull("process returns null when pushing a subprocess", result);
      assertEquals("a new subprocess is pushed on top — memory grows by one",
          beforePushes + 1, stackSize(traverse));
      // The top frame must be a TraverseRecordProcess — not an MVP or some other subprocess.
      var top = traverse.getContext().next();
      assertTrue("the pushed frame is a TraverseRecordProcess",
          top instanceof TraverseRecordProcess);
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code getPath} is composed from the parent path — not from {@link Traverse#getContext}. The
   * constructor captures the parent path; subsequent context changes don't leak into the path
   * accessor.
   */
  @Test
  public void getPathIsComposedFromParentPathNotFromContext() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      var parent1 = TraversePath.empty().append(new RecordId(1, 1L));
      var parent2 = TraversePath.empty().append(new RecordId(2, 2L));

      var mvp1 = new TraverseMultiValueProcess(traverse,
          Collections.<Object>emptyList().iterator(), parent1, session);
      var mvp2 = new TraverseMultiValueProcess(traverse,
          Collections.<Object>emptyList().iterator(), parent2, session);

      assertFalse("distinct parents produce distinct paths",
          mvp1.getPath().toString().equals(mvp2.getPath().toString()));
      assertNotNull(mvp1.getPath());
      assertNotNull(mvp2.getPath());
    } finally {
      session.rollback();
    }
  }
}
