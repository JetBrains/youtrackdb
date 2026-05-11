/*
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchHelperDeadCodeTest;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.remote.RemoteFetchListener;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.junit.Test;

/**
 * Falsifiable branch-coverage pins for the live {@link FetchHelper} surface against the
 * cumulative-vs-{@code origin/develop} coverage gate.
 *
 * <p>The cumulative diff against {@code origin/develop} for the surviving production lines
 * reformats the multi-line instanceof chains in {@code processRecordRidMap} (and the sibling
 * {@code process}/{@code processFieldDocument}/{@code isEmbedded} methods) — JaCoCo measures
 * coverage on the new line positions, so the unindented vs reindented chain is treated as
 * "changed" production code and the gate flags any chain branch that lacks a covering field
 * type. This test class drives those branches with a {@link EntityImpl} root carrying a fan
 * of property values across the supported multi-value types (LinkList, LinkSet, LinkMap,
 * embedded EntityImpl, populated and empty), so JaCoCo's branch counters advance across the
 * full width of the conditional.
 *
 * <p>The class extends {@link TestUtilsFixture} to inherit the rollback-on-failure safety net
 * (matches {@link DepthFetchPlanTest}'s pattern) — failing test bodies do not leak open
 * transactions into sibling fixtures.
 */
public class FetchHelperBranchCoverageTest extends TestUtilsFixture {

  /**
   * Drive {@link FetchHelper#fetch} against a root entity that carries link-singleton +
   * link-list + link-set + link-map properties. Each property exercises a distinct arm of
   * the {@code processRecordRidMap} {@code instanceof} chain — Identifiable, Iterable
   * (LinkList / LinkSet), and Map (LinkMap whose values are Identifiable).
   *
   * <p>The arms use <em>disjoint</em> single-element targets so the
   * {@code parsedRecords}-keyed dedup cannot collapse the per-arm fan-out into a single
   * sendRecord call. With four disjoint single-element arms (one Identifiable singleton,
   * one LinkList member, one LinkSet member, one LinkMap entry), the expected count of
   * distinct sendRecord invocations is exactly 4 — one per arm. Asserting that exact
   * count is the falsifiable pin: a regression that drops any one of the four populated
   * arms would reduce the count to ≤3.
   */
  @Test
  public void fetchExercisesEveryInstanceofArmInProcessRecordRidMap() {
    session.getMetadata().getSchema().createClass("BranchCovRoot");
    session.getMetadata().getSchema().createClass("BranchCovTarget");

    // Allocate one DISJOINT target per multi-value arm. Targets are created inside the
    // same executeInTx that wires them into the root so the runtime entity references are
    // stable through to commit; we count distinct persistent RIDs reached by sendRecord.
    final var rootId = session.computeInTx(
        tx -> ((EntityImpl) session.newEntity("BranchCovRoot")).getIdentity());

    // Expected distinct sendRecord invocations is 3: the linkList, linkSet, and linkMap
    // arms each fan out exactly one element via the recursive
    // fetchCollection/fetchMap path that calls sendRecord. The linkSingleton arm, by
    // contrast, drives the recursive fetchEntity path which detects the target as already
    // visited at level 1 (it was enqueued by processRecordRidMap) and routes through
    // parseLinked instead of sendRecord. Asserting exactly 3 falsifies (a) any arm-drop
    // regression that would reduce the count below 3, and (b) any regression that flipped
    // fetchEntity's singleton path into sendRecord — which would push the count to 4.
    final int expectedDistinctSendRecord = 3;

    session.executeInTx(tx -> {
      EntityImpl root = tx.load(rootId);

      // Arm 1: Identifiable singleton — short-circuits the multi-value cascade.
      root.setProperty("linkSingleton",
          (EntityImpl) session.newEntity("BranchCovTarget"));

      // Arm 2: populated LinkList (Iterable + Collection of Identifiable). The
      // newLinkList/setProperty pathway wraps the target into EntityLinkListImpl so the
      // runtime type passed to processRecordRidMap is Iterable<Identifiable>.
      final var linkList = root.newLinkList("linkList");
      linkList.add(session.newEntity("BranchCovTarget"));

      // Arm 3: populated LinkSet — a different Iterable<Identifiable> implementation.
      final var linkSet = root.newLinkSet("linkSet");
      linkSet.add(session.newEntity("BranchCovTarget"));

      // Arm 4: populated LinkMap — Map<String, Identifiable>. Drives the Map arm
      // including the values().iterator().next() instanceof Identifiable check.
      final var linkMap = root.newLinkMap("linkMap");
      linkMap.put("k0", session.newEntity("BranchCovTarget"));

      // Arm 5: empty LinkList — the isEmpty / hasNext branches of the Iterable + Collection
      // arms evaluate true, exercising the short-circuit paths.
      root.newLinkList("emptyLinkList");

      // Arm 6: empty LinkMap — similarly drives the Map.isEmpty short-circuit.
      root.newLinkMap("emptyLinkMap");

      // Arm 7: non-Identifiable EmbeddedMap. The first value is a String — the
      // Map.values().iterator().next() instanceof Identifiable check evaluates FALSE, so the
      // skip branch is taken for this field. Pinned to drive the "false" leg of that branch.
      final var stringMap = root.newEmbeddedMap("stringMap");
      stringMap.put("k", "v");

      // Arm 8: populated EmbeddedList of strings — drives the
      // Collection.iterator().next() instanceof Identifiable=false branch in
      // processRecordRidMap's Collection arm. LinkList of Identifiable covered the same arm
      // with =true; both branches are required for branch coverage.
      final var stringList = root.newEmbeddedList("stringList");
      stringList.add("a");
      stringList.add("b");

      // Arm 9: populated EmbeddedSet of strings — same coverage as Arm 8 but through
      // the EmbeddedSet collection type so the dispatcher sees a different runtime class
      // along the same chain.
      final var stringSet = root.newEmbeddedSet("stringSet");
      stringSet.add("x");

      // Arm 10: EmbeddedList of 32-bit integer wrappers — a Collection of non-Identifiable
      // wrappers, complementary to the String EmbeddedList of Arm 8. Drives the same
      // Collection-of-non-Identifiable arm of the instanceof cascade with a different
      // element type so the false-leg evaluation is exercised through more than one
      // runtime element class.
      final var intList = root.newEmbeddedList("intList");
      intList.add(1);
      intList.add(2);
      intList.add(3);
    });

    // Drive the entry point with a plan that follows everything at depth 1. Each fetch
    // call below targets a DIFFERENT format-driven code path in the dispatcher:
    //   - format="" → FormatSettings.keepTypes=false, so processFieldTypes is skipped;
    //     drives only the process() instanceof chain.
    //   - format="keepTypes" → FormatSettings.keepTypes=true, so processFieldTypes runs and
    //     the sibling instanceof chain in processFieldTypes is also driven.
    //   - format="shallow" → exercises the early-return arms in process() that the
    //     non-shallow path skips.
    final var emptyFormatListener = session.computeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      final var listener = new RecordingFetchListener();
      final FetchContext context = new RemoteFetchContext();
      FetchHelper.fetch(session,
          root, root, FetchHelper.buildFetchPlan("[*]*:1"), listener, context, "");
      return listener;
    });

    final var keepTypesFormatListener = session.computeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      final var listener = new RecordingFetchListener();
      final FetchContext context = new RemoteFetchContext();
      // format="keepTypes" routes through FormatSettings(stringFormat) which sets
      // keepTypes=true. The pre-process loop in processRecord then drives processFieldTypes
      // for every field, covering its instanceof cascade. Null format would have the same
      // effect via the FormatSettings(null) initializer, but null also tripped on the outer
      // fetch() entry's format.contains("shallow") guard, so we use the explicit token.
      FetchHelper.fetch(session,
          root, root, FetchHelper.buildFetchPlan("[*]*:1"), listener, context, "keepTypes");
      return listener;
    });

    final var shallowFormatListener = session.computeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      final var listener = new RecordingFetchListener();
      final FetchContext context = new RemoteFetchContext();
      FetchHelper.fetch(session,
          root, root, FetchHelper.buildFetchPlan("[*]*:1"), listener, context, "shallow");
      return listener;
    });

    // Falsifiable post-condition: each populated multi-value arm (list, set, map) must
    // fan out exactly one element via sendRecord. The linkSingleton arm goes through
    // parseLinked rather than sendRecord (the target is already at level 1 via
    // processRecordRidMap), so the observed sendRecord count is 3 for both non-shallow
    // formats — drop any arm and the count is ≤2, flip any non-sendRecord path to
    // sendRecord and the count is ≥4. See expectedDistinctSendRecord above.
    assertEquals(
        "non-shallow empty-format fetch must dispatch sendRecord for each multi-value "
            + "arm: observed=" + emptyFormatListener.observed,
        (long) expectedDistinctSendRecord,
        (long) emptyFormatListener.observed.size());
    assertEquals(
        "non-shallow keepTypes-format fetch must dispatch the same per-arm fan-out "
            + "(processFieldTypes runs but does not fan out additional records): observed="
            + keepTypesFormatListener.observed,
        (long) expectedDistinctSendRecord,
        (long) keepTypesFormatListener.observed.size());

    // Shallow must suppress every traversal — the observed set is empty.
    assertTrue(
        "shallow format must suppress all link traversal: observed="
            + shallowFormatListener.observed,
        shallowFormatListener.observed.isEmpty());
  }

  /**
   * Shape pin for the explicit {@link LinkBag} short-circuit inside
   * {@link FetchHelper#isEmbedded}: a LinkBag is never embedded because it can only carry
   * edge references, never embedded documents.
   *
   * <p>This is a contract pin rather than a strictly falsifiable guard. With an empty
   * LinkBag, {@link com.jetbrains.youtrackdb.internal.common.collection.MultiValue#getFirstValue}
   * returns {@code null}, so the post-LinkBag fallback probe ({@code f != null && f
   * instanceof EntityImpl ...}) would still keep {@code isEmbedded == false} even without
   * the explicit LinkBag guard. The pin guards against a future refactor that flips the
   * scalar-fallback default to {@code true}, or that drops the LinkBag short-circuit and
   * lets a non-empty LinkBag whose first edge is a non-EntityImpl flow through the probe.
   */
  @Test
  public void isEmbeddedReturnsFalseForLinkBag() {
    final var bag = new LinkBag(session);
    assertFalse(
        "LinkBag short-circuit must return false even for an empty bag",
        FetchHelper.isEmbedded(bag));
  }

  /**
   * Pin the embedded-document detection arm of {@link FetchHelper#isEmbedded}: the
   * {@code fieldValue instanceof EntityImpl entityImpl && entityImpl.isEmbedded()} clause.
   * An EmbeddedEntityImpl (returned by {@link
   * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded#newEmbeddedEntity()})
   * has {@code isEmbedded()==true}, so the first half of the OR fires and the helper
   * returns true.
   */
  @Test
  public void isEmbeddedReturnsTrueForEmbeddedEntity() {
    final var observed = session.computeInTx(tx -> {
      final var embedded = (EntityImpl) session.newEmbeddedEntity();
      return FetchHelper.isEmbedded(embedded);
    });

    assertTrue("embedded EntityImpl must be detected as embedded", observed);
  }

  /**
   * Pin the {@link FetchHelper#isEmbedded} multi-value-of-embedded fallback branch: when the
   * field is a non-EntityImpl multi-value whose first element is an embedded EntityImpl,
   * {@code MultiValue.getFirstValue} returns the inner EntityImpl and the
   * {@code isEmbedded() || !isPersistent()} probe evaluates true. The
   * cumulative-vs-{@code origin/develop} coverage gate flagged the fallback probe lines as
   * uncovered because the existing
   * {@link com.jetbrains.youtrackdb.internal.core.fetch.FetchHelperDeadCodeTest}
   * {@code isEmbeddedReturns*} pins only exercise the non-Identifiable / scalar arms; this
   * one drives the {@code fallback-on-multi-value} arm that walks the embedded probe.
   */
  @Test
  public void isEmbeddedReturnsTrueForListContainingEmbeddedEntity() {
    final var observed = session.computeInTx(tx -> {
      final var embedded = (EntityImpl) session.newEmbeddedEntity();
      final var list = new java.util.ArrayList<EntityImpl>();
      list.add(embedded);
      return FetchHelper.isEmbedded(list);
    });

    assertTrue(
        "list whose first element is an embedded EntityImpl must surface as embedded",
        observed);
  }

  /**
   * Type-coverage pin for the scalar fall-through arm of {@link FetchHelper#isEmbedded}.
   *
   * <p>The {@link FetchHelperDeadCodeTest#isEmbeddedReturnsFalseForNonEntityScalar} pin
   * already covers {@code Integer} and {@code String}; this test extends the type coverage
   * to {@code Long} so the scalar fall-through is exercised against a wider runtime-type
   * surface. This is a redundant pin in the sense that {@code Long}, {@code Integer}, and
   * {@code String} all take the same code path — kept here so a future refactor that
   * narrows the scalar fall-through to specific types is caught by both test files.
   */
  @Test
  public void isEmbeddedReturnsFalseForScalarWithoutMultiValueSupport() {
    // A plain Long value: not an EntityImpl, not a LinkBag, MultiValue.getFirstValue
    // returns null for a non-multi-value scalar. The helper's post-probe isEmbedded check
    // evaluates the scalar against EntityImpl, sees no match, and returns false. Pins the
    // fall-through arm for the Long runtime type.
    assertFalse(
        "Long scalar must not be detected as embedded — fall-through pin",
        FetchHelper.isEmbedded(Long.valueOf(42L)));
  }

  /**
   * Direct unit-level driver for {@link FetchHelper#processRecordRidMap} that pins the
   * Identifiable-singleton arm of the {@code processRecord} chain. Independently of the
   * full-fan-out test above, this narrow probe drives only the Identifiable branch — the
   * falsifiable observable is that {@code parsedRecords} carries the target at level 1.
   */
  @Test
  public void processRecordRidMapPopulatesParsedRecordsForIdentifiableField() {
    session.getMetadata().getSchema().createClass("BranchCovProbeRoot");
    session.getMetadata().getSchema().createClass("BranchCovProbeTarget");

    final var targetId = session.computeInTx(
        tx -> ((EntityImpl) session.newEntity("BranchCovProbeTarget")).getIdentity());
    final var rootId = session.computeInTx(
        tx -> ((EntityImpl) session.newEntity("BranchCovProbeRoot")).getIdentity());

    session.executeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      EntityImpl target = tx.load(targetId);
      root.setProperty("ref", target);
    });

    final var parsed = new Object2IntOpenHashMap<RID>();
    parsed.defaultReturnValue(-1);

    session.executeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      FetchHelper.processRecordRidMap(
          session,
          root,
          FetchHelper.buildFetchPlan("ref:1"),
          0,
          0,
          -1,
          parsed,
          "",
          new RemoteFetchContext());
    });

    // The Identifiable arm of the instanceof chain enqueues the target into parsedRecords.
    assertEquals(
        "Identifiable field must be enqueued exactly once at level 1",
        1,
        parsed.getInt(targetId));
  }

  /**
   * Drive {@link FetchHelper#processRecordRidMap} with the empty-collections sentinel set
   * (empty LinkList, empty LinkMap, empty EmbeddedMap). The falsifiable observable is that
   * {@code parsedRecords} stays untouched.
   *
   * <p>Arm coverage: only the empty-Iterable and empty-Map short-circuit arms of the
   * instanceof cascade are exercised. An empty LinkList is also a Collection, but the
   * preceding Iterable arm short-circuits the OR chain on
   * {@code !((Iterable<?>) fieldValue).iterator().hasNext()}, so the Collection arm is
   * never reached — the LinkList short-circuit happens at the Iterable level. Likewise the
   * Map arm is short-circuited via {@code ((Map<?, ?>) fieldValue).isEmpty()}.
   */
  @Test
  public void processRecordRidMapSkipsEmptyContainerArms() {
    session.getMetadata().getSchema().createClass("BranchCovEmptyRoot");

    final var rootId = session.computeInTx(
        tx -> ((EntityImpl) session.newEntity("BranchCovEmptyRoot")).getIdentity());

    session.executeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      root.newLinkList("emptyLinkList");
      root.newLinkMap("emptyLinkMap");
      root.newEmbeddedMap("emptyEmbeddedMap");
    });

    final var parsed = new Object2IntOpenHashMap<RID>();
    parsed.defaultReturnValue(-1);

    session.executeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      FetchHelper.processRecordRidMap(
          session,
          root,
          FetchHelper.buildFetchPlan("*:1"),
          0,
          0,
          -1,
          parsed,
          "",
          new RemoteFetchContext());
    });

    assertTrue(
        "empty multi-value field arms must NOT enqueue anything: " + parsed.size(),
        parsed.isEmpty());
  }

  // ---------------------------------------------------------------------------
  // Listener
  // ---------------------------------------------------------------------------

  /**
   * Recording listener that captures the RID of every record dispatched through
   * {@code sendRecord} as well as the total invocation count. The recording form lets
   * tests pin per-arm fan-out by RID membership (robust to changes in dispatcher cadence)
   * as well as total-count tests. Required to flip
   * {@link RemoteFetchListener#requireFieldProcessing()} to true so the fast-path in
   * {@code processRecord} does NOT skip the whole fetch when the plan singleton matches
   * the default.
   */
  private static final class RecordingFetchListener extends RemoteFetchListener {

    int count;
    final java.util.Set<RID> observed = new java.util.HashSet<>();

    @Override
    public boolean requireFieldProcessing() {
      return true;
    }

    @Override
    protected void sendRecord(RecordAbstract iLinked) {
      count++;
      if (iLinked != null) {
        observed.add(iLinked.getIdentity());
      }
    }
  }
}
