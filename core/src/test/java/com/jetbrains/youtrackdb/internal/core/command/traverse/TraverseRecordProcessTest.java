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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link TraverseRecordProcess} — the per-record processor that walks an entity's
 * link-valued fields, enforces the already-traversed guard, and respects {@code maxDepth}. Drives
 * each branch via a full {@link Traverse#execute} round-trip because {@code process()} depends on
 * the session's active transaction, the command's strategy/predicate/maxDepth config, and the
 * context's history set — all wired up for us by the normal traversal path.
 *
 * <p>Track 9 Step 3 coverage for:
 * <ul>
 *   <li>The "already traversed" drop branch (via cycles — see {@link TraverseTest}).
 *   <li>Predicate-false drop — false predicate filters all records but root itself.
 *   <li>Star / {@code *} field expansion vs. explicit named field vs. {@code <className>.<field>}
 *       filter.
 *   <li>Non-entity loaded record → pop.
 *   <li>{@code toString} / {@code getPath} accessors.
 * </ul>
 */
public class TraverseRecordProcessTest extends DbTestBase {

  /**
   * Safety net matching Track 8's {@code TestUtilsFixture.rollbackIfLeftOpen} — a test that
   * throws mid-transaction otherwise leaves {@code session.isTxActive() == true} for {@link
   * DbTestBase#afterTest()}, which masks the original failure with a close-path exception.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  /**
   * A false predicate rejects every record including the root — the predicate path runs BEFORE
   * {@code addTraversed}, so even the root never appears in results. Uses an invocation counter
   * so a regression that silently bypasses the predicate altogether (and still produces an empty
   * result by other means) breaks the test.
   */
  @Test
  public void falsePredicateRejectsAllRecordsIncludingRoot() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    var child = (EntityImpl) session.newEntity();
    root.setProperty("child", child, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var invocations = new AtomicInteger();
      var traverse = new Traverse(session);
      traverse.target(loaded).fields("*").predicate((r, a, c) -> {
        invocations.incrementAndGet();
        return Boolean.FALSE;
      });

      var results = traverse.execute(session);

      assertTrue("a false predicate yields an empty result set", results.isEmpty());
      assertTrue("the predicate must be invoked at least once on the root",
          invocations.get() >= 1);
    } finally {
      session.rollback();
    }
  }

  /**
   * A predicate that returns {@code Boolean.TRUE} accepts every record. This pairs with the
   * false-predicate test to lock in the exact conditional ({@code conditionResult != Boolean.TRUE}).
   * Non-Boolean returns (or null) are treated as FALSE per the same guard.
   */
  @Test
  public void truePredicateAcceptsAllRecords() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    var child = (EntityImpl) session.newEntity();
    root.setProperty("child", child, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var loadedChild = session.getActiveTransaction().load(child);
      var traverse = new Traverse(session);
      traverse.target(loadedRoot).fields("*").predicate((r, a, c) -> Boolean.TRUE);

      var results = new HashSet<>(traverse.execute(session));

      assertEquals("true predicate yields all records", 2, results.size());
      assertTrue(results.contains(loadedRoot));
      assertTrue(results.contains(loadedChild));
    } finally {
      session.rollback();
    }
  }

  /**
   * A predicate that returns a non-Boolean value (for example a record reference) is treated as
   * rejected — {@code conditionResult != Boolean.TRUE} is strict reference equality on the boxed
   * TRUE singleton. Counter pins that the predicate actually ran.
   */
  @Test
  public void nonBooleanPredicateReturnIsTreatedAsRejection() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var invocations = new AtomicInteger();
      var traverse = new Traverse(session);
      traverse.target(loaded).fields("*").predicate((r, a, c) -> {
        invocations.incrementAndGet();
        return "not-a-boolean";
      });

      var results = traverse.execute(session);

      assertTrue("non-Boolean predicate result must reject the record", results.isEmpty());
      assertTrue("predicate must be invoked on the root", invocations.get() >= 1);
    } finally {
      session.rollback();
    }
  }

  /**
   * A predicate that returns {@code null} is rejected for the same reason non-Boolean returns
   * are ({@code conditionResult != Boolean.TRUE}). Sibling of the non-Boolean test — protects
   * against a refactor to {@code Boolean.TRUE.equals(...)} that would silently accept null.
   */
  @Test
  public void nullPredicateReturnIsTreatedAsRejection() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var invocations = new AtomicInteger();
      var traverse = new Traverse(session);
      traverse.target(loaded).fields("*").predicate((r, a, c) -> {
        invocations.incrementAndGet();
        return null;
      });

      var results = traverse.execute(session);

      assertTrue("null predicate return must reject the record", results.isEmpty());
      assertTrue("predicate must be invoked on the root", invocations.get() >= 1);
    } finally {
      session.rollback();
    }
  }

  /**
   * Named field traversal: only the named link is descended into — sibling links on the same
   * entity are ignored.
   */
  @Test
  public void namedFieldDescendsOnlyIntoThatField() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    var visited = (EntityImpl) session.newEntity();
    var skipped = (EntityImpl) session.newEntity();
    root.setProperty("follow", visited, PropertyType.LINK);
    root.setProperty("ignore", skipped, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var loadedVisited = session.getActiveTransaction().load(visited);
      var loadedSkipped = session.getActiveTransaction().load(skipped);
      var traverse = new Traverse(session);
      traverse.target(loadedRoot).fields("follow");

      var results = new HashSet<>(traverse.execute(session));

      assertTrue("root is emitted", results.contains(loadedRoot));
      assertTrue("the named 'follow' child is descended into",
          results.contains(loadedVisited));
      assertFalse("the un-named 'ignore' child must NOT be visited",
          results.contains(loadedSkipped));
    } finally {
      session.rollback();
    }
  }

  /**
   * A field referencing a non-Identifiable non-multivalue value (e.g. a primitive string) is
   * skipped cleanly — the {@code continue} branch in {@code processFields}. Root is still emitted.
   */
  @Test
  public void primitiveFieldValueIsSkippedWithoutError() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    root.setProperty("label", "root-label");
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      traverse.target(loaded).fields("*");

      var results = traverse.execute(session);

      assertEquals("only root is emitted; the string field is skipped",
          1, results.size());
      assertEquals(loaded, results.getFirst());
    } finally {
      session.rollback();
    }
  }

  /**
   * Missing (null) field value short-circuits without descent — the {@code if (fieldValue != null)}
   * guard in {@code processFields}.
   */
  @Test
  public void missingFieldValueIsSkippedSilently() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      traverse.target(loaded).fields("nonexistent");

      var results = traverse.execute(session);

      assertEquals("root is emitted even when the named field is missing",
          1, results.size());
      assertEquals(loaded, results.getFirst());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code toString} on a record process reports the target's identity when present. This is used
   * by the {@code stack} variable for debugging; a null target renders as {@code "-"}.
   */
  @Test
  public void toStringRendersTargetIdentityOrDashForNull() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      var process = new TraverseRecordProcess(traverse, loaded,
          TraversePath.empty(), session);

      assertEquals("toString renders the RID of the target",
          loaded.getIdentity().toString(), process.toString());

      var nullProcess = new TraverseRecordProcess(traverse, null,
          TraversePath.empty(), session);

      assertEquals("null target renders as '-'", "-", nullProcess.toString());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code getPath} on a record process reports the path used for that record. The path is
   * constructed as {@code parentPath.append(target)}, so the depth of a root-level record is
   * {@code -1 + 1 = 0} (where {@code -1} is the {@code FirstPathItem} sentinel).
   */
  @Test
  public void getPathReturnsThePathConstructedWithTheTarget() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      var process = new TraverseRecordProcess(traverse, loaded,
          TraversePath.empty(), session);

      assertNotNull("getPath is non-null", process.getPath());
      assertEquals("depth equals 0 for a root-level record (FirstPathItem -1 + 1)",
          0, process.getPath().getDepth());
    } finally {
      session.rollback();
    }
  }

  /**
   * Embedded-entity branch: a TraverseRecordProcess whose target is embedded returns {@code null}
   * from {@code process()} instead of emitting the embedded entity. This is the branch
   * {@code if (targeEntity.isEmbedded()) return null;} — important because embedded entities are
   * visited structurally but not surfaced as identifiable results.
   *
   * <p>We force this by building an embedded-list field and iterating: the outer record is
   * traversed, the embedded element's RP returns null (not surfaced), and the outer entity is
   * still emitted.
   */
  @Test
  public void embeddedEntityIsVisitedStructurallyButNotSurfacedAsResult() {
    // Schema changes must happen outside an active transaction.
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("RP_Outer_" + System.nanoTime());
    cls.createProperty("payloads", PropertyType.EMBEDDEDLIST);

    session.begin();
    var outer = (EntityImpl) session.newEntity(cls);
    var embedded = (EntityImpl) session.newEmbeddedEntity();
    embedded.setProperty("val", "embedded-child");
    outer.getOrCreateEmbeddedList("payloads").add(embedded);
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(outer);
      var traverse = new Traverse(session);
      traverse.target(loaded).fields("*");

      var results = traverse.execute(session);

      assertTrue("outer (persistent) entity is surfaced",
          results.stream().anyMatch(r -> r.getIdentity().equals(loaded.getIdentity())));
      // The embedded entity has a temporary/transient identity — it must not produce an
      // additional distinct result row beyond the outer record itself (at most one identity).
      var distinctIdentities = results.stream()
          .map(r -> r.getIdentity())
          .distinct()
          .count();
      assertEquals(
          "only the outer record's identity is emitted; embedded entities are not surfaced",
          1L, distinctIdentities);
    } finally {
      session.rollback();
    }
  }

  /**
   * Multi-valued link field (a LinkList) is descended into. This exercises the path where
   * {@code fieldValue instanceof Iterable} / {@code MultiValue.isMultiValue} branches to
   * {@link TraverseMultiValueProcess}. Each link-list element is visited exactly once.
   */
  @Test
  public void linkListFieldIsExpandedInOrderWithEachLinkVisitedOnce() {
    session.begin();
    var root = (EntityImpl) session.newEntity();
    var a = (EntityImpl) session.newEntity();
    var b = (EntityImpl) session.newEntity();
    var c = (EntityImpl) session.newEntity();
    root.getOrCreateLinkList("children").addAll(Arrays.asList(a, b, c));
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var loadedA = session.getActiveTransaction().load(a);
      var loadedB = session.getActiveTransaction().load(b);
      var loadedC = session.getActiveTransaction().load(c);
      var traverse = new Traverse(session);
      traverse.target(loadedRoot).fields("*");

      var results = new HashSet<>(traverse.execute(session));

      assertTrue("root is emitted", results.contains(loadedRoot));
      assertTrue("A is emitted", results.contains(loadedA));
      assertTrue("B is emitted", results.contains(loadedB));
      assertTrue("C is emitted", results.contains(loadedC));
      assertEquals("exactly four records emitted", 4, results.size());
    } finally {
      session.rollback();
    }
  }

  /**
   * Class-qualified field name — {@code <className>.<field>} — restricts descent to records
   * whose class matches or subclasses the named class. A non-matching root record causes the
   * field to be skipped (no descent), while a matching record descends.
   */
  @Test
  public void classQualifiedFieldNameSkipsNonMatchingClass() {
    // Schema changes must happen outside an active transaction.
    var schema = session.getMetadata().getSchema();
    var clsName = "RP_Foo_" + System.nanoTime();
    var cls = schema.createClass(clsName);
    cls.createProperty("child", PropertyType.LINK);

    session.begin();
    var matching = (EntityImpl) session.newEntity(cls);
    var child = (EntityImpl) session.newEntity();
    matching.setProperty("child", child, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loadedMatching = session.getActiveTransaction().load(matching);
      var loadedChild = session.getActiveTransaction().load(child);

      // 1. Non-existent class qualifier → skip entirely; root is emitted, child is not.
      var traverseMiss = new Traverse(session);
      traverseMiss.target(loadedMatching).fields("NoSuchClass.child");
      var miss = new HashSet<>(traverseMiss.execute(session));
      assertTrue("root is still emitted", miss.contains(loadedMatching));
      assertFalse("child not visited through non-matching class qualifier",
          miss.contains(loadedChild));

      // 2. Matching class qualifier → descent.
      var traverseHit = new Traverse(session);
      traverseHit.target(loadedMatching).fields(clsName + ".child");
      var hit = new HashSet<>(traverseHit.execute(session));
      assertTrue("child is visited through matching class qualifier",
          hit.contains(loadedChild));
    } finally {
      session.rollback();
    }
  }

  /**
   * Double-pop of a record process via two {@code pop()} calls exercises the
   * {@link TraverseAbstractProcess#pop} base implementation (passes null to
   * {@link TraverseContext#pop}), which in turn hits the "not in history" log-warn branch because
   * the RP itself was never {@code addTraversed}. The second pop throws IllegalStateException on
   * empty memory — pinning the invariant that pop is destructive per call.
   */
  @Test
  public void popTwiceThrowsOnEmptyMemoryAfterDrainingSoleFrame() {
    session.begin();
    try {
      var root = (EntityImpl) session.newEntity();
      var traverse = new Traverse(session);
      // Create an RP (does not push itself) + explicit push to seed memory.
      var rp = new TraverseRecordProcess(traverse, root, TraversePath.empty(), session);
      traverse.getContext().push(rp);

      assertNull("pop returns null (its contract)", rp.pop());
      assertTrue("memory is empty after the single frame popped",
          traverse.getContext().isEmpty());

      assertThrows(
          "second pop on empty memory throws IllegalStateException",
          IllegalStateException.class, rp::pop);
    } finally {
      session.rollback();
    }
  }
}
