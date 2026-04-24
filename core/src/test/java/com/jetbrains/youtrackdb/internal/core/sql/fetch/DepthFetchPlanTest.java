/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.fetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import org.junit.Test;

/**
 * End-to-end fetch-plan tests that drive {@link FetchHelper#fetch} against a live in-memory
 * database. The scenarios exercise the live branches reachable from the fetch entry point — a
 * subset of the full package, since most of {@code core/fetch/} is dead code (see
 * {@code FetchHelperDeadCodeTest} for the cross-module caller audit and WHEN-FIXED markers).
 *
 * <p>The class extends {@link TestUtilsFixture} so the parent's {@code @After
 * rollbackIfLeftOpen} guard runs before {@link
 * com.jetbrains.youtrackdb.internal.DbTestBase#afterTest()} tears down the database, catching
 * any transaction a failing test leaves open.
 *
 * <p>Transactions use the {@code executeInTx(tx -> ...)} / {@code computeInTx(tx -> ...)}
 * callback idiom so that a throwing test body is rolled back automatically instead of leaking
 * an active tx. Each entity creation is followed by setup in a separate tx to match the
 * cross-tx persistence semantics of the original tests.
 */
public class DepthFetchPlanTest extends TestUtilsFixture {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Create a new Test entity in its own tx and return its identity. */
  private RID newTestEntityId() {
    return session.computeInTx(tx -> ((EntityImpl) session.newEntity("Test")).getIdentity());
  }

  /** Count sendRecord invocations for the given plan driven from the provided root RID. */
  private int fetchAndCount(RID rootId, String fetchPlan, String format) {
    Integer value = session.computeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      var listener = new CountFetchListener();
      FetchContext context = new RemoteFetchContext();
      FetchHelper.fetch(session,
          root, root, FetchHelper.buildFetchPlan(fetchPlan), listener, context, format);
      return listener.count;
    });
    return value == null ? 0 : value;
  }

  // ---------------------------------------------------------------------------
  // Original scenarios — preserved for regression coverage of the main entry points,
  // ported from top-level begin/commit to executeInTx.
  // ---------------------------------------------------------------------------

  @Test
  public void testFetchPlanDepth() {
    // ref:1 *:-2 → follow `ref` for exactly one hop and skip every other field.
    // Expected count: 1 — the single linked doc reached via ref is fetched once.
    session.getMetadata().getSchema().createClass("Test");

    var docId = newTestEntityId();
    var doc1Id = newTestEntityId();
    var doc2Id = newTestEntityId();

    session.executeInTx(tx -> {
      EntityImpl d = tx.load(docId);
      d.setProperty("name", "name");
    });

    session.executeInTx(tx -> {
      EntityImpl d = tx.load(docId);
      EntityImpl d1 = tx.load(doc1Id);
      d1.setProperty("name", "name1");
      d1.setProperty("ref", d);
    });

    session.executeInTx(tx -> {
      EntityImpl d1 = tx.load(doc1Id);
      EntityImpl d2 = tx.load(doc2Id);
      d2.setProperty("name", "name2");
      d2.setProperty("ref", d1);
    });

    int count = fetchAndCount(doc2Id, "ref:1 *:-2", "");
    assertEquals("ref:1 *:-2 follows exactly one link", 1, count);
  }

  @Test
  public void testFullDepthFetchPlan() {
    // [*]ref:-1 → follow `ref` at every level, infinite depth. Four-doc chain
    // doc3 → doc2 → doc1 → doc. Expected count: 3 (doc3 is root, so only the three reachable
    // hops are counted).
    session.getMetadata().getSchema().createClass("Test");

    var docId = newTestEntityId();
    var doc1Id = newTestEntityId();
    var doc2Id = newTestEntityId();
    var doc3Id = newTestEntityId();

    session.executeInTx(tx -> {
      EntityImpl d = tx.load(docId);
      d.setProperty("name", "name");
    });

    session.executeInTx(tx -> {
      EntityImpl d = tx.load(docId);
      EntityImpl d1 = tx.load(doc1Id);
      d1.setProperty("name", "name1");
      d1.setProperty("ref", d);
    });

    session.executeInTx(tx -> {
      EntityImpl d1 = tx.load(doc1Id);
      EntityImpl d2 = tx.load(doc2Id);
      d2.setProperty("name", "name2");
      d2.setProperty("ref", d1);
    });

    session.executeInTx(tx -> {
      EntityImpl d2 = tx.load(doc2Id);
      EntityImpl d3 = tx.load(doc3Id);
      d3.setProperty("name", "name3");
      d3.setProperty("ref", d2);
    });

    int count = fetchAndCount(doc3Id, "[*]ref:-1", "");
    assertEquals("[*]ref:-1 follows three hops", 3, count);
  }

  // ---------------------------------------------------------------------------
  // New scenarios — added to pin additional reachable branches in FetchHelper
  // without expanding to the dead parts of the package.
  // ---------------------------------------------------------------------------

  @Test
  public void cycleTerminatesViaParsedRecordsDedup() {
    // A ↔ B with `ref` in both directions. Plan [*]ref:-1 would infinite-loop without the
    // parsedRecords cycle-break. A → B is fetched once; B.ref → A hits the already-visited
    // dedup branch which dispatches to parseLinked (no-op for CountFetchListener), so the
    // counter reflects only the first outbound hop.
    session.getMetadata().getSchema().createClass("Test");

    var aId = newTestEntityId();
    var bId = newTestEntityId();

    session.executeInTx(tx -> {
      EntityImpl a = tx.load(aId);
      EntityImpl b = tx.load(bId);
      a.setProperty("name", "A");
      b.setProperty("name", "B");
      a.setProperty("ref", b);
    });

    session.executeInTx(tx -> {
      EntityImpl a = tx.load(aId);
      EntityImpl b = tx.load(bId);
      b.setProperty("ref", a);
    });

    int count = fetchAndCount(aId, "[*]ref:-1", "");
    assertEquals("cycle detected after exactly one outbound hop", 1, count);
  }

  @Test
  public void defaultFetchPlanSuppressesLinkVisitation() {
    // The cached DEFAULT_FETCHPLAN singleton ("*:0") triggers an early-return inside
    // processRecordRidMap (reference-equality guard), so parsedRecords is never populated
    // beyond the root. Without pre-population, fetchEntity takes the "new link" else branch
    // which dispatches to parseLinked — a no-op on the listener — so sendRecord is never
    // called. This is the dead-code pinning contract: the default path intentionally bypasses
    // the listener's sendRecord, and any mutation that pre-populates parsedRecords for the
    // DEFAULT plan would produce a non-zero count here.
    session.getMetadata().getSchema().createClass("Test");

    var targetId = newTestEntityId();
    var srcId = newTestEntityId();

    session.executeInTx(tx -> {
      EntityImpl s = tx.load(srcId);
      EntityImpl t = tx.load(targetId);
      s.setProperty("ref", t);
    });

    int count = fetchAndCount(srcId, FetchHelper.DEFAULT, "");
    assertEquals("default fetch plan singleton bypasses sendRecord", 0, count);
  }

  @Test
  public void shallowFormatSuppressesLinkTraversal() {
    // "shallow" format string short-circuits processRecord's link traversal: even if the plan
    // says follow `ref`, the shallow format prevents any fetchLinked invocation. Expected: 0.
    session.getMetadata().getSchema().createClass("Test");

    var targetId = newTestEntityId();
    var srcId = newTestEntityId();

    session.executeInTx(tx -> {
      EntityImpl s = tx.load(srcId);
      EntityImpl t = tx.load(targetId);
      s.setProperty("ref", t);
    });

    int count = fetchAndCount(srcId, "[*]ref:-1", "shallow");
    assertEquals("shallow format suppresses all link traversal", 0, count);
  }

  @Test
  public void nullFetchPlanDoesNotTriggerRunawayTraversal() {
    // A null plan from buildFetchPlan means "no explicit fetch plan". With no plan, the
    // default behaviour does not recurse through `ref` in this configuration — the root is
    // processed but sendRecord is never invoked for this single-link chain.
    session.getMetadata().getSchema().createClass("Test");

    var targetId = newTestEntityId();
    var srcId = newTestEntityId();

    session.executeInTx(tx -> {
      EntityImpl s = tx.load(srcId);
      EntityImpl t = tx.load(targetId);
      s.setProperty("ref", t);
    });

    int count = fetchAndCount(srcId, null, "");
    assertTrue("null plan must not trigger runaway link traversal", count <= 1);
  }

  // ---------------------------------------------------------------------------
  // Listener
  // ---------------------------------------------------------------------------

  /**
   * Counts every {@code sendRecord} invocation. Required to flip
   * {@link RemoteFetchListener#requireFieldProcessing()} to true — otherwise the shallow-fetch
   * fast-path in {@code processRecord} skips the whole fetch when the plan is the cached
   * default singleton.
   */
  private static final class CountFetchListener extends RemoteFetchListener {

    int count;

    @Override
    public boolean requireFieldProcessing() {
      return true;
    }

    @Override
    protected void sendRecord(RecordAbstract iLinked) {
      count++;
    }
  }
}
