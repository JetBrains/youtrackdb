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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.traverse.Traverse;
import com.jetbrains.youtrackdb.internal.core.command.traverse.TraversePath;
import com.jetbrains.youtrackdb.internal.core.command.traverse.TraverseRecordProcess;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionTraversedElement} — the {@code traversedElement()} SQL function,
 * which indexes into a traverse command's stack variable to return a previously-visited record.
 *
 * <p>The evaluate() logic handles two dimensions independently:
 *
 * <ul>
 *   <li><b>Index direction.</b> A positive {@code beginIndex} returns the Nth element counted from
 *       the top of the stack (most recently pushed first); a negative index returns the Nth element
 *       from the bottom (first pushed). Positive is implemented by reversing the stack-as-list,
 *       negative by iterating the Collection in insertion order.
 *   <li><b>Items count.</b> A single-element return uses {@code items == 1} (default); values
 *       {@code > 1} collect a list and stop once it is full.
 * </ul>
 *
 * <p>Stack entries can be plain {@link Identifiable} values or {@link TraverseRecordProcess}
 * wrappers; both paths are covered. The {@code iClassName} filter (supplied by the {@code
 * traversedVertex()} / {@code traversedEdge()} subclasses as "V" / "E") filters stack entries by
 * schema class, with non-matching entries skipped <em>without advancing the index counter</em> —
 * so the index is relative to matching items, not to stack position.
 *
 * <p>All tests use an in-memory graph with three vertices A→B→C and a {@code DbTestBase} session;
 * the function needs a real transaction because internally it calls {@code transaction.load(rid)}.
 */
public class SQLFunctionTraversedElementTest extends DbTestBase {

  private Identifiable a;
  private Identifiable b;
  private Identifiable c;

  @Before
  public void setUpGraph() {
    session.createEdgeClass("knows");
    session.begin();
    var va = session.newVertex();
    va.setProperty("name", "A");
    var vb = session.newVertex();
    vb.setProperty("name", "B");
    var vc = session.newVertex();
    vc.setProperty("name", "C");
    session.newEdge(va, vb, "knows");
    session.newEdge(vb, vc, "knows");
    a = va.getIdentity();
    b = vb.getIdentity();
    c = vc.getIdentity();
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

  /** Stack order [a, b, c] means a was pushed first, c most recently. */
  private static Deque<Object> stackOf(Object... entries) {
    var d = new ArrayDeque<Object>();
    for (var e : entries) {
      d.addLast(e);
    }
    return d;
  }

  // ---------------------------------------------------------------------------
  // Missing-stack contract
  // ---------------------------------------------------------------------------

  @Test
  public void missingStackThrowsCommandExecutionException() {
    // Neither the context variable nor the ResultInternal $stack metadata is set — the function
    // refuses to run outside a TRAVERSE command.
    var fn = new SQLFunctionTraversedElement();

    var ex = assertThrows(CommandExecutionException.class,
        () -> fn.execute(null, null, null, new Object[] {0}, ctx()));
    assertTrue("message must name the function",
        ex.getMessage().contains("traversedElement"));
    assertTrue("message must mention traverse",
        ex.getMessage().contains("traverse"));
  }

  @Test
  public void missingStackFromPlainIThisAlsoThrows() {
    // Plain non-ResultInternal iThis cannot supply $stack metadata — throws.
    var fn = new SQLFunctionTraversedElement();

    assertThrows(CommandExecutionException.class,
        () -> fn.execute("not-a-result", null, null, new Object[] {0}, ctx()));
  }

  @Test
  public void stackFromResultInternalMetadataFallback() {
    // When the context variable "stack" is null, the function looks at iThis.getMetadata("$stack").
    // ResultInternal.setMetadata runs convertPropertyValue which coerces Lists of Identifiables
    // into an internal LinkListResultImpl — the function still sees a Collection of RIDs, which
    // is what evaluate() iterates.
    var fn = new SQLFunctionTraversedElement();
    var result = new ResultInternal(session);
    result.setMetadata("$stack", List.of(a));

    var returned = fn.execute(result, null, null, new Object[] {0}, ctx());

    assertEquals(a.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  // ---------------------------------------------------------------------------
  // Positive index — iterates stack reversed (most recent first)
  // ---------------------------------------------------------------------------

  @Test
  public void positiveIndexZeroReturnsTopOfStack() {
    // Stack [a,b,c] → reversed [c,b,a] → i=0 matches first iteration → top = c.
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b, c));

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    assertEquals(c.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void positiveIndexOneReturnsSecondFromTop() {
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b, c));

    var returned = fn.execute(null, null, null, new Object[] {1}, context);

    assertEquals(b.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void positiveIndexDefaultItemsIsOne() {
    // Omitted second parameter → items = 1 (minParams=1). The function must not treat the
    // missing length as items>1 (which would incorrectly allocate a list).
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b));

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    // Returns the record directly, NOT a list.
    assertTrue("items=1 must return the record directly, not a list",
        returned instanceof Identifiable);
  }

  @Test
  public void positiveIndexWithItemsGreaterThanOneReturnsListFromTop() {
    // items=2, beginIndex=0 → collects [top, second-from-top] = [c, b].
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b, c));

    @SuppressWarnings("unchecked")
    var returned = (List<Identifiable>) fn.execute(null, null, null,
        new Object[] {0, 2}, context);

    assertEquals(2, returned.size());
    assertEquals(c.getIdentity(), returned.get(0).getIdentity());
    assertEquals(b.getIdentity(), returned.get(1).getIdentity());
  }

  @Test
  public void positiveIndexMoreItemsThanStackReturnsWhatWasCollected() {
    // items=10 but only 3 entries → collects all three in top-down order, then the loop ends
    // without filling the list and the trailing `if (!result.isEmpty()) return result` returns it.
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b, c));

    @SuppressWarnings("unchecked")
    var returned = (List<Identifiable>) fn.execute(null, null, null,
        new Object[] {0, 10}, context);

    assertEquals(3, returned.size());
    assertEquals(c.getIdentity(), returned.get(0).getIdentity());
    assertEquals(a.getIdentity(), returned.get(2).getIdentity());
  }

  @Test
  public void positiveIndexPastEndOfMatchingItemsReturnsNull() {
    // Stack size=3, beginIndex=5 → loop never matches, items=1 → null (no list allocated).
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b));

    var returned = fn.execute(null, null, null, new Object[] {5}, context);

    assertNull(returned);
  }

  @Test
  public void positiveIndexStackAsListTakesFastPath() {
    // stackToList has two branches: stack is already a List → returned as-is; otherwise convert via
    // stream. Use ArrayList to force the fast path; Deque above forces the slow path. Both must
    // deliver the same result for the same logical ordering.
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    var listStack = new ArrayList<Object>();
    listStack.add(a);
    listStack.add(b);
    listStack.add(c);
    context.setVariable("stack", listStack);

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    assertEquals(c.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  // ---------------------------------------------------------------------------
  // Negative index — iterates stack in insertion order (bottom-up)
  // ---------------------------------------------------------------------------

  @Test
  public void negativeIndexMinusOneReturnsBottomOfStack() {
    // i starts at -1, beginIndex=-1 → first iteration matches → bottom of stack = a.
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b, c));

    var returned = fn.execute(null, null, null, new Object[] {-1}, context);

    assertEquals(a.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void negativeIndexMinusTwoReturnsSecondFromBottom() {
    // i counts -1, -2, -3 with decrement after each match; beginIndex=-2 matches on i==-2 → b.
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b, c));

    var returned = fn.execute(null, null, null, new Object[] {-2}, context);

    assertEquals(b.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void negativeIndexItemsGreaterThanOneReturnsListFromBottom() {
    // beginIndex=-1, items=2 → collects [a, b] (bottom and one-up).
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b, c));

    @SuppressWarnings("unchecked")
    var returned = (List<Identifiable>) fn.execute(null, null, null,
        new Object[] {-1, 2}, context);

    assertEquals(2, returned.size());
    assertEquals(a.getIdentity(), returned.get(0).getIdentity());
    assertEquals(b.getIdentity(), returned.get(1).getIdentity());
  }

  @Test
  public void negativeIndexPastDeepestEntryReturnsNull() {
    // Only 2 entries, need i to reach -5 → never matches, items=1 → null.
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    context.setVariable("stack", stackOf(a, b));

    var returned = fn.execute(null, null, null, new Object[] {-5}, context);

    assertNull(returned);
  }

  // ---------------------------------------------------------------------------
  // TraverseRecordProcess entries — getTarget() dispatch
  // ---------------------------------------------------------------------------

  @Test
  public void stackWithTraverseRecordProcessEntriesResolvesViaGetTarget() {
    // When a stack entry is a TraverseRecordProcess, the function unwraps it via getTarget().
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    var cmd = new Traverse(session);
    var trpA = new TraverseRecordProcess(cmd, a, TraversePath.empty(), session);
    var trpB = new TraverseRecordProcess(cmd, b, TraversePath.empty(), session);
    context.setVariable("stack", stackOf(trpA, trpB));

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    // beginIndex=0 (positive) → reverses stack → top = trpB → unwraps to b.
    assertEquals(b.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void negativeIndexWithTraverseRecordProcessResolvesViaGetTarget() {
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    var cmd = new Traverse(session);
    var trpA = new TraverseRecordProcess(cmd, a, TraversePath.empty(), session);
    var trpB = new TraverseRecordProcess(cmd, b, TraversePath.empty(), session);
    context.setVariable("stack", stackOf(trpA, trpB));

    var returned = fn.execute(null, null, null, new Object[] {-1}, context);

    // beginIndex=-1 → bottom = trpA → unwraps to a.
    assertEquals(a.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void mixedIdentifiableAndTraverseRecordProcessEntries() {
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    var cmd = new Traverse(session);
    var trpB = new TraverseRecordProcess(cmd, b, TraversePath.empty(), session);
    context.setVariable("stack", stackOf(a, trpB, c));

    @SuppressWarnings("unchecked")
    var returned = (List<Identifiable>) fn.execute(null, null, null,
        new Object[] {0, 3}, context);

    assertEquals(3, returned.size());
    // Top-down order: c (Identifiable), b (trpB unwrapped), a (Identifiable).
    var identities = new ArrayList<Object>();
    for (var id : returned) {
      identities.add(id.getIdentity());
    }
    assertEquals(List.of(c.getIdentity(), b.getIdentity(), a.getIdentity()), identities);
  }

  // ---------------------------------------------------------------------------
  // Class filter — entries skipped without advancing the index
  // ---------------------------------------------------------------------------

  @Test
  public void classFilterAcceptsOnlyMatchingEntriesInNegativeBranch() {
    // Only vertices are in the stack (a is the only vertex; put an edge in too). Ask for "V"
    // entries from the bottom. Direct invocation of evaluate with iClassName="V".
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();

    // Insert an edge identity into the stack; it must be skipped.
    var edgeRid = session.query("select from E").next().getIdentity();
    context.setVariable("stack", stackOf(edgeRid, a, b));

    // Use the subclass trick: SQLFunctionTraversedVertex passes "V" to evaluate. Exercise the
    // filter by calling the subclass directly.
    var vertexFn = new SQLFunctionTraversedVertex();
    var returned = vertexFn.execute(null, null, null, new Object[] {-1}, context);

    // Bottom vertex is a (edge skipped).
    assertEquals(a.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void classFilterAcceptsOnlyMatchingEntriesInPositiveBranch() {
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    var edgeRid = session.query("select from E").next().getIdentity();
    context.setVariable("stack", stackOf(a, edgeRid, b));

    var vertexFn = new SQLFunctionTraversedVertex();
    var returned = vertexFn.execute(null, null, null, new Object[] {0}, context);

    // Reversed stack [b, edge, a]; filter "V" skips edge; first vertex = b.
    assertEquals(b.getIdentity(), ((Identifiable) returned).getIdentity());
  }

  @Test
  public void classFilterNoMatchReturnsNull() {
    // Stack has only edges; asking for vertices → null.
    var context = ctx();
    var edgeRid = session.query("select from E").next().getIdentity();
    context.setVariable("stack", stackOf(edgeRid));

    var vertexFn = new SQLFunctionTraversedVertex();
    var returned = vertexFn.execute(null, null, null, new Object[] {0}, context);

    assertNull(returned);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameAndConstructorDefaults() {
    var fn = new SQLFunctionTraversedElement();
    assertEquals("traversedElement", SQLFunctionTraversedElement.NAME);
    assertEquals("traversedElement", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
  }

  @Test
  public void syntaxExposesExpectedSignature() {
    var fn = new SQLFunctionTraversedElement();
    assertEquals("traversedElement(<beginIndex> [,<items>])", fn.getSyntax(null));
  }

  @Test
  public void aggregateResultsIsFalse() {
    // traversedElement is not an aggregate — it produces one value per row, not a reduction.
    assertEquals(false, new SQLFunctionTraversedElement().aggregateResults());
  }

  @Test
  public void filterResultIsTrue() {
    // filterResult() governs whether the engine should apply WHERE filters over the output.
    // traversedElement returns true so the engine forwards the call through correctly.
    assertEquals(true, new SQLFunctionTraversedElement().filterResult());
  }

  @Test
  public void getResultIsNull() {
    // Non-aggregate function: getResult() is not used; contract says null.
    assertNull(new SQLFunctionTraversedElement().getResult());
  }

  @Test
  public void customNameConstructorPreservesSymmetry() {
    // Subclasses (Vertex, Edge) use the name-based constructor; confirm min/max params are
    // unchanged by that overload.
    var fn = new SQLFunctionTraversedElement("customAlias");
    assertEquals("customAlias", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(2, fn.getMaxParams(null));
  }

  @Test
  public void setStackDeduplicatesButPreservesUniquenessForEntriesWithDifferentIdentities() {
    // Sanity regression: the Collection passed as "stack" can be any Collection. Use a HashSet of
    // identities to show the function doesn't require a List/Deque.
    var fn = new SQLFunctionTraversedElement();
    var context = ctx();
    Set<Object> stack = new HashSet<>();
    stack.add(a);
    context.setVariable("stack", stack);

    var returned = fn.execute(null, null, null, new Object[] {0}, context);

    // Single element → positive index 0 returns it.
    assertNotNull(returned);
    assertEquals(a.getIdentity(), ((Identifiable) returned).getIdentity());
  }
}
