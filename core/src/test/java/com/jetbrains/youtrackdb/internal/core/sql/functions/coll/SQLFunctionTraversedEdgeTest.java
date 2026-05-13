/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.coll;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionTraversedEdge} — the {@code traversedEdge()} SQL function, a thin
 * subclass of {@link SQLFunctionTraversedElement} whose only responsibility is to pass "E" as the
 * {@code iClassName} filter to {@link SQLFunctionTraversedElement#evaluate}. The shared evaluate()
 * logic (indexing, items count, TraverseRecordProcess handling) is covered in
 * {@link SQLFunctionTraversedElementTest}; this class pins the "E" filter contract.
 */
public class SQLFunctionTraversedEdgeTest extends DbTestBase {

  private Identifiable vertexRid;
  private Identifiable edge1;
  private Identifiable edge2;

  @Before
  public void setUpGraph() {
    session.createEdgeClass("knows");
    session.begin();
    var a = session.newVertex();
    var b = session.newVertex();
    var c = session.newVertex();
    var ab = session.newEdge(a, b, "knows");
    var bc = session.newEdge(b, c, "knows");
    vertexRid = a.getIdentity();
    edge1 = ab.getIdentity();
    edge2 = bc.getIdentity();
    session.commit();
    session.begin();
  }

  @After
  public void rollbackIfLeftOpen() {
    if (session.getActiveTransaction().isActive()) {
      session.rollback();
    }
  }

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  private static Deque<Object> stackOf(Object... entries) {
    var d = new ArrayDeque<Object>();
    for (var e : entries) {
      d.addLast(e);
    }
    return d;
  }

  @Test
  public void filterSkipsVerticesAndReturnsEdgeFromTop() {
    // Stack [vertex, edge1, edge2] (bottom-up). Reversed for positive index → [edge2, edge1,
    // vertex]. Filter "E" passes edge2 first.
    var fn = new SQLFunctionTraversedEdge();
    var context = ctx();
    context.setVariable("stack", stackOf(vertexRid, edge1, edge2));

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    assertEquals(edge2.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void filterSkipsVerticesFromBottomWithNegativeIndex() {
    var fn = new SQLFunctionTraversedEdge();
    var context = ctx();
    context.setVariable("stack", stackOf(vertexRid, edge1, edge2));

    var returned = fn.execute(null, null, null, new Object[] {-1}, context);

    // Bottom-up iteration, filter "E" → first edge is edge1.
    assertEquals(edge1.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void filterWithItemsGreaterThanOneCollectsOnlyEdges() {
    var fn = new SQLFunctionTraversedEdge();
    var context = ctx();
    context.setVariable("stack", stackOf(vertexRid, edge1, edge2));

    @SuppressWarnings("unchecked")
    var returned = (List<Identifiable>) fn.execute(null, null, null,
        new Object[] {0, 5}, context);

    // Only two edges in the stack — vertex is skipped, list has 2.
    assertEquals(2, returned.size());
    assertEquals(edge2.getIdentity(), returned.get(0).getIdentity());
    assertEquals(edge1.getIdentity(), returned.get(1).getIdentity());
  }

  @Test
  public void stackContainingOnlyVerticesReturnsNullForEdgeFilter() {
    var fn = new SQLFunctionTraversedEdge();
    var context = ctx();
    context.setVariable("stack", stackOf(vertexRid));

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    assertNull(returned);
  }

  @Test
  public void nameIsTraversedEdge() {
    var fn = new SQLFunctionTraversedEdge();
    assertEquals("traversedEdge", SQLFunctionTraversedEdge.NAME);
    assertEquals("traversedEdge", fn.getName(null));
    // Subclass inherits min/max from parent.
    assertEquals(1, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
  }
}
