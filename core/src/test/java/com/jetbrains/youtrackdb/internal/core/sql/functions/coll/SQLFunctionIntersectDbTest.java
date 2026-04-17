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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.IteratorResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Database-dependent tests for {@link SQLFunctionIntersect} — covers the
 * {@link SQLFunctionIntersect#intersectWith(java.util.Iterator, Object)} conversion paths that
 * require real {@link Identifiable}s, a {@link ResultInternal} factory, a {@link LinkBag}, and the
 * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet} produced when a
 * {@link com.jetbrains.youtrackdb.internal.core.query.ResultSet} is fully identifiable.
 *
 * <p>The standalone paths (null/empty short-circuit, inline/aggregation with scalar collections,
 * the Set variant of {@code intersectWith}) are exercised in
 * {@link SQLFunctionIntersectTest}. This class covers only the branches that must see actual
 * record identities to function correctly.
 */
public class SQLFunctionIntersectDbTest extends DbTestBase {

  private Identifiable identity1;
  private Identifiable identity2;
  private Identifiable identity3;

  @Before
  public void setUpEntities() {
    session.createClass("X");
    session.begin();
    identity1 = session.newEntity("X").getIdentity();
    identity2 = session.newEntity("X").getIdentity();
    identity3 = session.newEntity("X").getIdentity();
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

  // ---------------------------------------------------------------------------
  // ResultSet → RidSet conversion
  // ---------------------------------------------------------------------------

  @Test
  public void resultSetOfOnlyIdentifiablesConvertsToRidSet() {
    // All ResultSet rows are identifiable → the outer block builds a RidSet (ids branch, not
    // nonIds). Since RidSet IS a Set, the switch matches `case Collection` and the intersection
    // proceeds via collection.contains(curr.getIdentity()).
    var current = List.of((Object) identity1, identity2, identity3).iterator();
    var rs = new IteratorResultSet(session, List.of(
        new ResultInternal(session, identity1),
        new ResultInternal(session, identity2)).iterator());

    var result = SQLFunctionIntersect.intersectWith(current, rs);

    // Identifiable.getIdentity() normalisation + RidSet.contains → {identity1, identity2}.
    assertEquals(Set.of(identity1.getIdentity(), identity2.getIdentity()), new HashSet<>(result));
  }

  @Test
  public void resultSetOfMixedResultsMergesIdsIntoNonIdsBranch() {
    // Put the non-identifiable row FIRST (seeds nonIds) and then TWO identifiable rows. With
    // the merge-into-nonIds logic, the second identifiable is added to nonIds via
    // `result.getIdentity()` directly — and the "ids → nonIds" merge is not exercised here
    // because `ids.isEmpty()` is true at end-of-loop. Combined with the ids-only test above,
    // this probes the ids-empty / nonIds-non-empty domain row (value = nonIds, ids.addAll is
    // a no-op). A missing Identifiable on the right side must NOT be dropped from the result.
    var current = List.of((Object) identity1, identity2).iterator();
    var rs = new IteratorResultSet(session, List.of(
        (Object) resultWithProperty("plain-string"),
        new ResultInternal(session, identity1),
        new ResultInternal(session, identity2)).iterator());

    var result = SQLFunctionIntersect.intersectWith(current, rs);

    // Both identities must be in the intersection. Tight assertion pins both halves of the
    // normalisation (Identifiable → RID on the left, result.getIdentity() → RID on the right).
    assertEquals(Set.of(identity1.getIdentity(), identity2.getIdentity()), new HashSet<>(result));
  }

  @Test
  public void resultSetIdsThenNonIdsTriggersAddAllMerge() {
    // Inverse ordering: identifiable FIRST, then non-identifiable. Now `ids` is non-empty when
    // the non-identifiable appears, so the production code executes `nonIds.addAll(ids)` at
    // SQLFunctionIntersect.java:172 before assigning `value = nonIds`. Without that merge,
    // identity1 would be lost.
    var current = List.of((Object) identity1).iterator();
    var rs = new IteratorResultSet(session, List.of(
        (Object) new ResultInternal(session, identity1),
        resultWithProperty("plain-string")).iterator());

    var result = SQLFunctionIntersect.intersectWith(current, rs);

    // identity1 survived because ids.addAll happened before value was reassigned to nonIds.
    assertEquals(Set.of(identity1.getIdentity()), new HashSet<>(result));
  }

  @Test
  public void resultSetOfOnlyNonIdentifiableRowsYieldsEmptyIntersection() {
    // All ResultSet rows are non-identifiable — nonIds contains only Result wrappers (not RIDs).
    // The left iterator's Identifiable entries normalise to RIDs, which never appear in nonIds,
    // so the intersection is empty. Exercises the else-else branch at SQLFunctionIntersect.java
    // line 167 (nonIds.add(result) for non-identifiable rows).
    var current = List.of((Object) identity1).iterator();
    var rs = new IteratorResultSet(session, List.of(
        (Object) resultWithProperty("x"),
        resultWithProperty("y")).iterator());

    var result = SQLFunctionIntersect.intersectWith(current, rs);

    assertTrue("no RIDs on the right → empty intersection", result.isEmpty());
  }

  @Test
  public void resultNormalisationHitsResultIsIdentifiableBranch() {
    // The `else if (curr instanceof Result result && result.isIdentifiable())` branch fires when
    // the iterator yields a Result (not an Identifiable). Feed a list of ResultInternal rows.
    var leftResults = new ArrayList<Object>();
    leftResults.add(new ResultInternal(session, identity1));
    leftResults.add(new ResultInternal(session, identity2));

    var right = Set.of(identity1.getIdentity(), identity3.getIdentity());

    var result = SQLFunctionIntersect.intersectWith(leftResults.iterator(), right);

    // Only identity1 is in both — identity2 filtered out.
    assertEquals(Set.of(identity1.getIdentity()), new HashSet<>(result));
  }

  @Test
  public void aggregationResolvesIdentifiableEntriesAcrossCalls() {
    // Exercise the Iterator branch of the aggregation switch: first call seeds context with a
    // Collection; second call narrows with a Set containing identities.
    var fn = new SQLFunctionIntersect();
    var context = ctx();
    context.setVariable("aggregation", Boolean.TRUE);

    fn.execute(null, null, null, new Object[] {List.of(identity1, identity2, identity3)}, context);
    fn.execute(null, null, null, new Object[] {Set.of(identity2.getIdentity(),
        identity3.getIdentity())}, context);

    var narrowed = (Set<?>) fn.getResult();
    // Narrowed set should contain identity2 and identity3 (normalised identities) — not identity1.
    assertTrue(narrowed.contains(identity2.getIdentity()));
    assertTrue(narrowed.contains(identity3.getIdentity()));
    assertEquals(2, narrowed.size());
  }

  // ---------------------------------------------------------------------------
  // LinkBag path — the production code has a `case LinkBag` arm, but the outer conversion block
  // converts LinkBag (which is NOT a Set and NOT SupportsContains) into a HashSet<RidPair> via
  // MultiValue.toSet BEFORE reaching the switch. LinkBag iterates RidPair objects, which do not
  // equal bare RIDs, so collection.contains(curr.getIdentity()) always returns false — the
  // intersection is DETERMINISTICALLY EMPTY. The `case LinkBag rids` arm at
  // SQLFunctionIntersect.java:192 is unreachable with the current LinkBag class hierarchy.
  // ---------------------------------------------------------------------------

  @Test
  public void linkBagRightHandSideYieldsEmptyIntersectionBecauseCaseLinkBagIsUnreachable() {
    // WHEN-FIXED: SQLFunctionIntersect.intersectWith's outer conversion block converts LinkBag
    // to HashSet<RidPair> via MultiValue.toSet, so the `case LinkBag rids` arm is dead code. A
    // fix would either: (a) skip conversion when value instanceof LinkBag so the `case LinkBag`
    // arm fires (then this test's expected result becomes {identity1}), or (b) delete the dead
    // `case LinkBag` arm and document the Set-conversion path explicitly. Either way, update
    // this test when the asymmetry is resolved.
    var bag = new LinkBag(session);
    bag.add(identity1.getIdentity());
    bag.add(identity2.getIdentity());

    var current = List.of((Object) identity1, identity3).iterator();

    var result = SQLFunctionIntersect.intersectWith(current, bag);

    // RidPair.equals(RID) returns false for every entry in the converted set → empty result.
    assertTrue("LinkBag intersection is always empty with current LinkBag class hierarchy",
        result.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ResultInternal resultWithProperty(String value) {
    // Build a non-identifiable Result (no identity set) with a single property — its
    // isIdentifiable() returns false, which routes it into the nonIds HashSet.
    var r = new ResultInternal(session);
    r.setProperty("value", value);
    if (r.isIdentifiable()) {
      fail("Precondition: Result must not be identifiable for the nonIds branch");
    }
    return r;
  }
}
