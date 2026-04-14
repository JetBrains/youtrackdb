package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link PathNode}, the immutable cons-cell list used to track the
 * path of visited records during recursive MATCH WHILE traversals.
 */
public class PathNodeTest {

  // -- Construction --

  /** A single-node path (no predecessor) stores the value and depth correctly. */
  @Test
  public void singleNodePath() {
    var r = result("a");
    var path = new PathNode(r, null, 0);

    assertSame(r, path.value());
    assertNull(path.prev());
    assertEquals(0, path.depth());
  }

  /** A chain of three nodes preserves each value, prev pointer, and depth. */
  @Test
  public void chainOfThreeNodes() {
    var r0 = result("a");
    var r1 = result("b");
    var r2 = result("c");

    var p0 = new PathNode(r0, null, 0);
    var p1 = new PathNode(r1, p0, 1);
    var p2 = new PathNode(r2, p1, 2);

    assertEquals(2, p2.depth());
    assertSame(r2, p2.value());
    assertSame(p1, p2.prev());
    assertSame(p0, p1.prev());
  }

  // -- toList() --

  /** toList() on a single-node path returns a one-element list. */
  @Test
  public void toListSingleNode() {
    var r = result("x");
    var path = new PathNode(r, null, 0);

    List<Result> list = path.toList();

    assertEquals(1, list.size());
    assertSame(r, list.getFirst());
  }

  /**
   * toList() materializes a chain in traversal order (oldest first), matching the
   * behavior of the previous ArrayList-based path construction.
   */
  @Test
  public void toListPreservesTraversalOrder() {
    var r0 = result("first");
    var r1 = result("second");
    var r2 = result("third");

    var p0 = new PathNode(r0, null, 0);
    var p1 = new PathNode(r1, p0, 1);
    var p2 = new PathNode(r2, p1, 2);

    List<Result> list = p2.toList();

    assertEquals(3, list.size());
    assertSame(r0, list.get(0));
    assertSame(r1, list.get(1));
    assertSame(r2, list.get(2));
  }

  /**
   * Two paths sharing a common prefix produce independent lists with correct
   * contents. This verifies that structural sharing in the cons-cell chain does
   * not leak through toList().
   */
  @Test
  public void structuralSharingProducesIndependentLists() {
    var r0 = result("root");
    var rLeft = result("left");
    var rRight = result("right");

    var shared = new PathNode(r0, null, 0);
    var left = new PathNode(rLeft, shared, 1);
    var right = new PathNode(rRight, shared, 1);

    List<Result> leftList = left.toList();
    List<Result> rightList = right.toList();

    // Both start with the shared root
    assertSame(r0, leftList.get(0));
    assertSame(r0, rightList.get(0));

    // But diverge at index 1
    assertSame(rLeft, leftList.get(1));
    assertSame(rRight, rightList.get(1));

    // Lists are independent objects with correct sizes
    assertNotSame(leftList, rightList);
    assertEquals(2, leftList.size());
    assertEquals(2, rightList.size());
  }

  // -- emptyPath() --

  /** emptyPath() returns an empty immutable list. */
  @Test
  public void emptyPathReturnsEmptyList() {
    List<Result> empty = PathNode.emptyPath();
    assertTrue(empty.isEmpty());
  }

  /** emptyPath() returns the same instance on repeated calls (no allocation). */
  @Test
  public void emptyPathReturnsSameInstance() {
    assertSame(PathNode.emptyPath(), PathNode.emptyPath());
  }

  // -- helpers --

  private static ResultInternal result(String label) {
    var r = new ResultInternal(
        (com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded) null);
    r.setProperty("label", label);
    return r;
  }
}
