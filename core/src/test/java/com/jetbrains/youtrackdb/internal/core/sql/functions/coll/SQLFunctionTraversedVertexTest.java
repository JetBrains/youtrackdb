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
 * Tests for {@link SQLFunctionTraversedVertex} — the {@code traversedVertex()} SQL function, a thin
 * subclass of {@link SQLFunctionTraversedElement} whose only responsibility is to pass "V" as the
 * {@code iClassName} filter to {@link SQLFunctionTraversedElement#evaluate}. The shared evaluate()
 * logic (indexing, items count, TraverseRecordProcess handling) is covered in
 * {@link SQLFunctionTraversedElementTest}; this class pins the "V" filter contract.
 */
public class SQLFunctionTraversedVertexTest extends DbTestBase {

  private Identifiable vertex1;
  private Identifiable vertex2;
  private Identifiable edgeRid;

  @Before
  public void setUpGraph() {
    session.createEdgeClass("knows");
    session.begin();
    var a = session.newVertex();
    a.setProperty("name", "A");
    var b = session.newVertex();
    b.setProperty("name", "B");
    var ab = session.newEdge(a, b, "knows");
    vertex1 = a.getIdentity();
    vertex2 = b.getIdentity();
    edgeRid = ab.getIdentity();
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
  public void filterSkipsEdgesAndReturnsVertexFromTop() {
    // Stack [vertex1, edge, vertex2] (bottom-up). Reversed for positive index → top = vertex2.
    var fn = new SQLFunctionTraversedVertex();
    var context = ctx();
    context.setVariable("stack", stackOf(vertex1, edgeRid, vertex2));

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    assertEquals(vertex2.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void filterSkipsEdgesFromBottomWithNegativeIndex() {
    var fn = new SQLFunctionTraversedVertex();
    var context = ctx();
    context.setVariable("stack", stackOf(vertex1, edgeRid, vertex2));

    var returned = fn.execute(null, null, null, new Object[] {-1}, context);

    assertEquals(vertex1.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void filterWithItemsGreaterThanOneCollectsOnlyVertices() {
    var fn = new SQLFunctionTraversedVertex();
    var context = ctx();
    context.setVariable("stack", stackOf(vertex1, edgeRid, vertex2));

    @SuppressWarnings("unchecked")
    var returned = (List<Identifiable>) fn.execute(null, null, null,
        new Object[] {0, 5}, context);

    assertEquals(2, returned.size());
    assertEquals(vertex2.getIdentity(), returned.get(0).getIdentity());
    assertEquals(vertex1.getIdentity(), returned.get(1).getIdentity());
  }

  @Test
  public void stackContainingOnlyEdgesReturnsNullForVertexFilter() {
    var fn = new SQLFunctionTraversedVertex();
    var context = ctx();
    context.setVariable("stack", stackOf(edgeRid));

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    assertNull(returned);
  }

  @Test
  public void nameIsTraversedVertex() {
    var fn = new SQLFunctionTraversedVertex();
    assertEquals("traversedVertex", SQLFunctionTraversedVertex.NAME);
    assertEquals("traversedVertex", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
  }
}
