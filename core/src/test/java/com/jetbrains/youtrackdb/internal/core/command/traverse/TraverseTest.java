package com.jetbrains.youtrackdb.internal.core.command.traverse;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end tests for {@link Traverse} — the Iterator/Iterable facade over the traversal state
 * machine. Exercise cases that require the whole stack (RSP → RP → MVP dispatch) rather than any
 * one process class in isolation. Companion direct-process unit tests live in
 * {@link TraverseContextTest}, {@link TraverseRecordProcessTest},
 * {@link TraverseMultiValueProcessTest}, {@link TraverseRecordSetProcessTest}, and the pure-data
 * {@link TraversePathTest}.
 */
public class TraverseTest extends DbTestBase {

  /**
   * Roll back any transaction left open by a failing test method before {@link
   * DbTestBase#afterTest()} drops the database. Mirrors the {@code TestUtilsFixture} safety net
   * from Track 8. JUnit 4 runs subclass {@code @After} methods before superclass ones, so this
   * safety net runs ahead of the database teardown.
   *
   * <p>WHEN-FIXED: Track 22 — hoist this idiom into {@code TestUtilsFixture} and switch this
   * class to extend it directly (plan §Track 22 absorption list). Extends {@code DbTestBase}
   * today to preserve existing test infrastructure.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  @Test
  public void testDepthTraverse() {
    EntityImpl rootDocument;
    Traverse traverse;

    session.begin();
    rootDocument = (EntityImpl) session.newEntity();

    final var aa = (EntityImpl) session.newEntity();
    final var ab = (EntityImpl) session.newEntity();
    final var ba = (EntityImpl) session.newEntity();
    final var bb = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    a.setProperty("aa", aa, PropertyType.LINK);
    a.setProperty("ab", ab, PropertyType.LINK);
    final var b = (EntityImpl) session.newEntity();
    b.setProperty("ba", ba, PropertyType.LINK);
    b.setProperty("bb", bb, PropertyType.LINK);

    rootDocument.setProperty("a", a, PropertyType.LINK);
    rootDocument.setProperty("b", b, PropertyType.LINK);

    final var c1 = (EntityImpl) session.newEntity();
    final var c1a = (EntityImpl) session.newEntity();
    c1.setProperty("c1a", c1a, PropertyType.LINK);
    final var c1b = (EntityImpl) session.newEntity();
    c1.setProperty("c1b", c1b, PropertyType.LINK);
    final var c2 = (EntityImpl) session.newEntity();
    final var c2a = (EntityImpl) session.newEntity();
    c2.setProperty("c2a", c2a, PropertyType.LINK);
    final var c2b = (EntityImpl) session.newEntity();
    c2.setProperty("c2b", c2b, PropertyType.LINK);
    final var c3 = (EntityImpl) session.newEntity();
    final var c3a = (EntityImpl) session.newEntity();
    c3.setProperty("c3a", c3a, PropertyType.LINK);
    final var c3b = (EntityImpl) session.newEntity();
    c3.setProperty("c3b", c3b, PropertyType.LINK);
    rootDocument.getOrCreateLinkList("c").addAll(new ArrayList<>(Arrays.asList(c1, c2, c3)));

    session.commit();

    session.begin();
    var activeTx15 = session.getActiveTransaction();
    rootDocument = activeTx15.load(rootDocument);
    var activeTx = session.getActiveTransaction();
    var activeTx1 = session.getActiveTransaction();
    var activeTx2 = session.getActiveTransaction();
    var activeTx3 = session.getActiveTransaction();
    var activeTx4 = session.getActiveTransaction();
    var activeTx5 = session.getActiveTransaction();
    var activeTx6 = session.getActiveTransaction();
    var activeTx7 = session.getActiveTransaction();
    var activeTx8 = session.getActiveTransaction();
    var activeTx9 = session.getActiveTransaction();
    var activeTx10 = session.getActiveTransaction();
    var activeTx11 = session.getActiveTransaction();
    var activeTx12 = session.getActiveTransaction();
    var activeTx13 = session.getActiveTransaction();
    var activeTx14 = session.getActiveTransaction();
    final var expectedResult =
        new HashSet<>(Arrays.asList(
            rootDocument,
            activeTx14.load(a),
            activeTx13.load(aa),
            activeTx12.load(ab),
            activeTx11.load(b),
            activeTx10.load(ba),
            activeTx9.load(bb),
            activeTx8.load(c1),
            activeTx7.load(c1a),
            activeTx6.load(c1b),
            activeTx5.load(c2),
            activeTx4.load(c2a),
            activeTx3.load(c2b),
            activeTx2.load(c3),
            activeTx1.load(c3a),
            activeTx.load(c3b)));

    traverse = new Traverse(session);
    traverse.target(rootDocument).fields("*");
    final var results = new HashSet<>(traverse.execute(session));

    Assert.assertEquals(expectedResult, results);
    session.commit();
  }

  @Test
  public void testBreadthTraverse() throws Exception {
    EntityImpl rootDocument;
    Traverse traverse;

    session.begin();
    rootDocument = (EntityImpl) session.newEntity();
    final var aa = (EntityImpl) session.newEntity();
    final var ab = (EntityImpl) session.newEntity();
    final var ba = (EntityImpl) session.newEntity();
    final var bb = (EntityImpl) session.newEntity();
    final var a = (EntityImpl) session.newEntity();
    a.setProperty("aa", aa, PropertyType.LINK);
    a.setProperty("ab", ab, PropertyType.LINK);
    final var b = (EntityImpl) session.newEntity();
    b.setProperty("ba", ba, PropertyType.LINK);
    b.setProperty("bb", bb, PropertyType.LINK);

    rootDocument.setProperty("a", a, PropertyType.LINK);
    rootDocument.setProperty("b", b, PropertyType.LINK);

    final var c1 = (EntityImpl) session.newEntity();
    final var c1a = (EntityImpl) session.newEntity();
    c1.setProperty("c1a", c1a, PropertyType.LINK);
    final var c1b = (EntityImpl) session.newEntity();
    c1.setProperty("c1b", c1b, PropertyType.LINK);
    final var c2 = (EntityImpl) session.newEntity();
    final var c2a = (EntityImpl) session.newEntity();
    c2.setProperty("c2a", c2a, PropertyType.LINK);
    final var c2b = (EntityImpl) session.newEntity();
    c2.setProperty("c2b", c2b, PropertyType.LINK);
    final var c3 = (EntityImpl) session.newEntity();
    final var c3a = (EntityImpl) session.newEntity();
    c3.setProperty("c3a", c3a, PropertyType.LINK);
    final var c3b = (EntityImpl) session.newEntity();
    c3.setProperty("c3b", c3b, PropertyType.LINK);
    rootDocument.getOrCreateLinkList("c").addAll(new ArrayList<>(Arrays.asList(c1, c2, c3)));
    session.commit();

    session.begin();
    var activeTx15 = session.getActiveTransaction();
    rootDocument = activeTx15.load(rootDocument);
    traverse = new Traverse(session);

    traverse.target(rootDocument).fields("*");
    traverse.setStrategy(Traverse.STRATEGY.BREADTH_FIRST);

    var activeTx = session.getActiveTransaction();
    var activeTx1 = session.getActiveTransaction();
    var activeTx2 = session.getActiveTransaction();
    var activeTx3 = session.getActiveTransaction();
    var activeTx4 = session.getActiveTransaction();
    var activeTx5 = session.getActiveTransaction();
    var activeTx6 = session.getActiveTransaction();
    var activeTx7 = session.getActiveTransaction();
    var activeTx8 = session.getActiveTransaction();
    var activeTx9 = session.getActiveTransaction();
    var activeTx10 = session.getActiveTransaction();
    var activeTx11 = session.getActiveTransaction();
    var activeTx12 = session.getActiveTransaction();
    var activeTx13 = session.getActiveTransaction();
    var activeTx14 = session.getActiveTransaction();
    final var expectedResult =
        new HashSet<>(Arrays.asList(
            rootDocument,
            activeTx14.load(a),
            activeTx13.load(b),
            activeTx12.load(aa),
            activeTx11.load(ab),
            activeTx10.load(ba),
            activeTx9.load(bb),
            activeTx8.load(c1),
            activeTx7.load(c2),
            activeTx6.load(c3),
            activeTx5.load(c1a),
            activeTx4.load(c1b),
            activeTx3.load(c2a),
            activeTx2.load(c2b),
            activeTx1.load(c3a),
            activeTx.load(c3b)));
    final var results = new HashSet<>(traverse.execute(session));
    Assert.assertEquals(expectedResult, results);
    session.rollback();
  }

  /**
   * Belt-and-suspenders: {@link Traverse#next()} consumes the interrupt flag via {@link
   * Thread#interrupted()} on the happy path, but a crash anywhere between {@code
   * Thread.currentThread().interrupt()} and {@code next()} would leak the flag. The per-test
   * {@code try { ... } finally { Thread.interrupted(); }} inside the interrupt test covers this,
   * and this {@code @After} is a second line of defence so a parallel-surefire worker thread
   * never returns to the pool with the flag set.
   */
  @After
  public void clearInterruptFlagAfterTest() {
    Thread.interrupted();
  }

  /**
   * Empty target → {@code hasNext()} returns false immediately. The invariant is important because
   * a traversal with no seed records must not throw or emit spurious results.
   */
  @Test
  public void hasNextReturnsFalseImmediatelyOnEmptyTarget() {
    session.begin();
    try {
      var traverse = new Traverse(session);
      traverse.target(Collections.<Identifiable>emptyList().iterator());

      Assert.assertFalse("empty target must yield no results", traverse.hasNext());
      Assert.assertTrue("execute on empty target returns an empty list",
          traverse.execute(session).isEmpty());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code setMaxDepth(0)} → emit only the root records and do not descend into their link fields.
   * Covers the {@code maxDepth > -1 && depth == maxDepth} branch in {@link TraverseRecordProcess}
   * at the shallowest setting.
   */
  @Test
  public void setMaxDepthZeroEmitsOnlyRoot() {
    session.begin();
    EntityImpl root = (EntityImpl) session.newEntity();
    EntityImpl child = (EntityImpl) session.newEntity();
    root.setProperty("child", child, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loaded = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      traverse.target(loaded).fields("*");
      traverse.setMaxDepth(0);

      var results = new HashSet<>(traverse.execute(session));

      Assert.assertEquals("exactly one result: the root itself", 1, results.size());
      Assert.assertTrue("the sole result is the root", results.contains(loaded));
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code setMaxDepth(1)} → emit root and its direct link children, but not grandchildren.
   * Covers the cut-off branch at a mid-depth value.
   */
  @Test
  public void setMaxDepthOneEmitsRootAndDirectChildrenButNotGrandchildren() {
    session.begin();
    EntityImpl root = (EntityImpl) session.newEntity();
    EntityImpl child = (EntityImpl) session.newEntity();
    EntityImpl grandchild = (EntityImpl) session.newEntity();
    child.setProperty("grand", grandchild, PropertyType.LINK);
    root.setProperty("child", child, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var loadedChild = session.getActiveTransaction().load(child);
      var loadedGrand = session.getActiveTransaction().load(grandchild);
      var traverse = new Traverse(session);
      traverse.target(loadedRoot).fields("*");
      traverse.setMaxDepth(1);

      var results = new HashSet<>(traverse.execute(session));

      Assert.assertTrue("root is included (depth 0)", results.contains(loadedRoot));
      Assert.assertTrue("direct child is included (depth 1)",
          results.contains(loadedChild));
      Assert.assertFalse("grandchild is NOT included (depth 2 > maxDepth=1)",
          results.contains(loadedGrand));
      Assert.assertEquals("only two records are emitted", 2, results.size());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code getMaxDepth}/{@code setMaxDepth} round-trip (and the {@code -1} default → no cut-off)
   * are the trivially observable accessors the traversal relies on. The default and negative
   * value are accepted by {@code setMaxDepth} and preserved.
   */
  @Test
  public void getMaxDepthDefaultIsNegativeOneAndSetMaxDepthRoundTrips() {
    var traverse = new Traverse(session);

    Assert.assertEquals("default maxDepth is -1 (no limit)", -1, traverse.getMaxDepth());

    traverse.setMaxDepth(3);
    Assert.assertEquals("setMaxDepth round-trips", 3, traverse.getMaxDepth());

    traverse.setMaxDepth(-1);
    Assert.assertEquals("setMaxDepth(-1) restores the 'no-limit' sentinel",
        -1, traverse.getMaxDepth());
  }

  /**
   * Cycle pin: A→B→A — the cycle-detection guard ({@code history.contains} in
   * {@link TraverseRecordProcess#process}) keeps each node visited exactly once. Covers R6 (the
   * already-traversed branch of {@link TraverseContext#isAlreadyTraversed}).
   *
   * <p>Bounded via {@code traverse.limit(...)}: if cycle detection silently breaks, the limit
   * caps output at a finite value so the failure surfaces as a size mismatch instead of an
   * infinite loop. {@code @Test(timeout=...)} can't be used here because surefire's timeout
   * runner runs the test on a worker thread, which breaks {@code DbTestBase}'s thread-bound
   * session.
   */
  @Test
  public void cyclicLinksVisitEachRecordExactlyOnce() {
    session.begin();
    EntityImpl a = (EntityImpl) session.newEntity();
    EntityImpl b = (EntityImpl) session.newEntity();
    a.setProperty("next", b, PropertyType.LINK);
    b.setProperty("next", a, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loadedA = session.getActiveTransaction().load(a);
      var loadedB = session.getActiveTransaction().load(b);
      var traverse = new Traverse(session);
      // Cap at 10 results — far above the expected 2 — so a cycle-detection regression fails
      // with a size mismatch rather than an infinite loop.
      traverse.target(loadedA).fields("*").limit(10);

      var results = traverse.execute(session);

      Assert.assertEquals("A and B each visited exactly once — no cycle loop",
          2, results.size());
      Assert.assertTrue("A is in the result set", results.contains(loadedA));
      Assert.assertTrue("B is in the result set", results.contains(loadedB));
    } finally {
      session.rollback();
    }
  }

  /**
   * {@link Traverse#next()} consumes the JVM thread interrupt flag via {@link Thread#interrupted()}
   * and wraps the condition in a {@link CommandExecutionException}. This pins ONE of the two
   * distinct interrupt paths in the Traverse state machine — the other is inside
   * {@link Traverse#hasNext()} via {@code CommandExecutorAbstract.checkInterruption}, which reads
   * {@code ExecutionThreadLocal.isInterruptCurrentOperation()} and throws a
   * {@code CommandInterruptedException}; that path is exercised by Track 8's executor suite.
   *
   * <p>Categorized {@link SequentialTest} because surefire's parallel-classes mode reuses worker
   * threads and the interrupt flag must not leak between tests. The test clears the flag in a
   * {@code finally} block even if an assertion fires before {@code next()} consumes it.
   */
  @Test
  @Category(SequentialTest.class)
  public void nextConsumesThreadInterruptFlagAndWrapsInCommandExecutionException() {
    session.begin();
    try {
      var traverse = new Traverse(session);

      Thread.currentThread().interrupt();
      try {
        var ex = Assert.assertThrows("interrupted thread must trigger next() to throw",
            CommandExecutionException.class, traverse::next);

        Assert.assertFalse("the interrupt flag is consumed by Thread.interrupted()",
            Thread.interrupted());
        Assert.assertTrue("the exception message names the traverse interrupt",
            ex.getMessage().contains("interrupted"));
      } finally {
        // Unconditionally clear the flag — `Thread.interrupted()` inside `next()` normally does
        // this, but a test-assertion failure could bypass it and leak the flag onto the pooled
        // surefire worker thread.
        Thread.interrupted();
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code setStrategy} is observable via {@link Traverse#getStrategy()} and survives repeated
   * calls. Pairs with {@link TraverseContextTest#setStrategyPreservesPendingProcessesAcrossStrategySwitches}
   * which validates the underlying memory reshape.
   */
  @Test
  public void setStrategyRoundTripsAndBreadthFirstTraversalProducesSameNodesAsDepthFirst() {
    session.begin();
    EntityImpl root = (EntityImpl) session.newEntity();
    EntityImpl child = (EntityImpl) session.newEntity();
    root.setProperty("child", child, PropertyType.LINK);
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);

      // DFS baseline
      var dfs = new Traverse(session);
      dfs.target(loadedRoot).fields("*");
      Assert.assertEquals("default strategy is DEPTH_FIRST",
          Traverse.STRATEGY.DEPTH_FIRST, dfs.getStrategy());
      var dfsResults = new HashSet<>(dfs.execute(session));

      // BFS
      var bfs = new Traverse(session);
      bfs.target(loadedRoot).fields("*");
      bfs.setStrategy(Traverse.STRATEGY.BREADTH_FIRST);
      Assert.assertEquals("setStrategy round-trips",
          Traverse.STRATEGY.BREADTH_FIRST, bfs.getStrategy());
      var bfsResults = new HashSet<>(bfs.execute(session));

      Assert.assertEquals("BFS and DFS visit the same record set, order aside",
          dfsResults, bfsResults);
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code limit(n)} stops traversal after {@code n} results have been returned, leaving remaining
   * context frames unvisited. {@code getResultCount} reports exactly {@code n}.
   */
  @Test
  public void limitStopsAtResultCountAndLeavesRemainingFramesUnvisited() {
    session.begin();
    EntityImpl root = (EntityImpl) session.newEntity();
    EntityImpl a = (EntityImpl) session.newEntity();
    EntityImpl b = (EntityImpl) session.newEntity();
    EntityImpl c = (EntityImpl) session.newEntity();
    root.getOrCreateLinkList("children").addAll(Arrays.asList(a, b, c));
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      traverse.target(loadedRoot).fields("*").limit(2);

      var results = traverse.execute(session);

      Assert.assertEquals("limit(2) caps the output at 2", 2, results.size());
      Assert.assertEquals("getResultCount matches the limit", 2, traverse.getResultCount());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code limit(n)} where {@code n < -1} → {@link IllegalArgumentException}. The range guard
   * rejects values below the {@code -1} "unlimited" sentinel.
   */
  @Test
  public void limitRejectsValuesBelowMinusOne() {
    var traverse = new Traverse(session);

    var ex = Assert.assertThrows(IllegalArgumentException.class,
        () -> traverse.limit(-2));

    Assert.assertTrue("exception message pins the observable contract",
        ex.getMessage().contains("Limit cannot be negative"));
  }

  /**
   * TC1 iter-2 boundary pin: {@code limit(1)} stops traversal after the FIRST result is
   * emitted. The production guard is {@code limit > 0 && resultCount >= limit}; a regression
   * that changed {@code >=} to {@code >} would let one extra result through. Pin the
   * narrowest positive boundary so off-by-one bugs are caught.
   */
  @Test
  public void limitOneStopsAfterFirstResult() {
    session.begin();
    EntityImpl root = (EntityImpl) session.newEntity();
    EntityImpl a = (EntityImpl) session.newEntity();
    EntityImpl b = (EntityImpl) session.newEntity();
    root.getOrCreateLinkList("children").addAll(Arrays.asList(a, b));
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      traverse.target(loadedRoot).fields("*").limit(1);

      var results = traverse.execute(session);

      Assert.assertEquals("limit(1) caps the output at exactly 1 result", 1, results.size());
      Assert.assertEquals(
          "getResultCount matches the limit boundary", 1, traverse.getResultCount());
    } finally {
      session.rollback();
    }
  }

  /**
   * TC1 iter-2 boundary pin: {@code limit(0)} is documented as the "infinite / sentinel"
   * default — traversal runs without a result cap. The production guard is
   * {@code limit > 0 && resultCount >= limit}, so {@code limit == 0} must fall through.
   * A regression that flipped the sentinel (e.g., {@code if (limit >= 0 && ...)}) would
   * emit zero results and be caught here.
   */
  @Test
  public void limitZeroIsSentinelAndEmitsAllResultsUnbounded() {
    session.begin();
    EntityImpl root = (EntityImpl) session.newEntity();
    EntityImpl a = (EntityImpl) session.newEntity();
    EntityImpl b = (EntityImpl) session.newEntity();
    root.getOrCreateLinkList("children").addAll(Arrays.asList(a, b));
    session.commit();

    session.begin();
    try {
      var loadedRoot = session.getActiveTransaction().load(root);
      var traverse = new Traverse(session);
      // Explicit limit(0) to exercise the setter's "0 passes the < -1 guard" branch, then
      // assert the getter round-trips and traversal is unbounded.
      traverse.target(loadedRoot).fields("*").limit(0);
      Assert.assertEquals("limit(0) is the unlimited sentinel", 0L, traverse.getLimit());

      var results = traverse.execute(session);

      Assert.assertEquals(
          "default limit(0) must emit root + 2 children unbounded",
          3,
          results.size());
    } finally {
      session.rollback();
    }
  }

  /**
   * TC1 iter-2 boundary pin: {@code limit(-1)} is accepted (the range guard is {@code < -1}, so
   * {@code -1} passes) and behaves as the same "unlimited" sentinel. Pin the guard boundary —
   * a regression that tightened the guard to {@code <= -1} or {@code < 0} would reject this
   * value and be caught.
   */
  @Test
  public void limitMinusOneIsAcceptedAsUnlimitedSentinel() {
    var traverse = new Traverse(session);
    // Must not throw — the guard is `< -1`, not `<= -1`.
    traverse.limit(-1);
    Assert.assertEquals(-1L, traverse.getLimit());
  }

  /**
   * {@code remove()} is not supported (the {@link Traverse} iterator is read-only); this pin
   * covers the {@link UnsupportedOperationException} branch. {@code toString} renders the four
   * factory fields (target, fields, limit, predicate) — the format is a loose contract used by
   * debugging / logging paths.
   */
  @Test
  public void removeThrowsAndToStringExposesCoreFactoryFields() {
    var traverse = new Traverse(session);
    traverse.fields("foo").limit(5);

    Assert.assertThrows(UnsupportedOperationException.class, traverse::remove);

    var rendered = traverse.toString();
    Assert.assertTrue("toString includes limit value", rendered.contains("5"));
    Assert.assertTrue("toString includes field name", rendered.contains("foo"));
  }

  /**
   * {@code field(x)} deduplicates on the list — adding the same field twice produces a single
   * entry. {@code fields(Collection)} and {@code fields(String...)} delegate to {@code field(x)}
   * and inherit the dedup.
   */
  @Test
  public void fieldDeduplicatesAndCollectionStringOverloadsDelegateToIt() {
    var traverse = new Traverse(session);

    traverse.field("x").field("x").field("y");
    Assert.assertEquals("dedup keeps only two fields after three adds",
        2, traverse.getFields().size());

    traverse.fields(Arrays.asList("y", "z"));
    Assert.assertEquals("Collection overload adds only the new field 'z'",
        3, traverse.getFields().size());

    traverse.fields("w", "x");
    Assert.assertEquals("String... overload dedups against existing fields too",
        4, traverse.getFields().size());
  }

  /**
   * {@link Traverse#iterator()} returns the traverse itself — this is a well-known contract for
   * the {@link Iterable}/{@link java.util.Iterator} self-implementation used by caller code.
   */
  @Test
  public void iteratorReturnsSelf() {
    var traverse = new Traverse(session);

    Assert.assertSame("iterator() returns the traverse itself",
        traverse, traverse.iterator());
  }

  /**
   * WHEN-FIXED: Track 22 — the defensive branch in {@link Traverse#hasNext} at lines 91-93
   * (throw when {@code next()} returns null while {@link TraverseContext#isEmpty()} is false) is
   * unreachable through normal traversal flow: {@link Traverse#next} loops while memory is
   * non-empty, so it only returns null when memory drains. This test forces the precondition by
   * overriding {@code next()} to always return null while leaving an RSP frame in the context —
   * locking in the observable message and exception type. Track 22 should either delete the
   * branch or introduce a call path that reaches it naturally.
   */
  @Test
  public void hasNextWhenNextReturnsNullButContextNonEmptyThrowsAbnormalTermination() {
    session.begin();
    try {
      var rootRef = (EntityImpl) session.newEntity();

      // Subclass overrides next() to short-circuit to null WITHOUT draining memory.
      var traverse = new Traverse(session) {
        @Override
        public Identifiable next() {
          return null;
        }
      };
      // Populate the context with an RSP (pushed by its constructor).
      new TraverseRecordSetProcess(traverse,
          Collections.<Identifiable>singletonList(rootRef).iterator(),
          TraversePath.empty(), session);

      Assert.assertFalse(
          "precondition of the defensive branch: context is non-empty at throw site",
          traverse.getContext().isEmpty());

      var ex = Assert.assertThrows(
          IllegalStateException.class, traverse::hasNext);
      Assert.assertEquals("Traverse ended abnormally", ex.getMessage());
    } finally {
      session.rollback();
    }
  }
}
