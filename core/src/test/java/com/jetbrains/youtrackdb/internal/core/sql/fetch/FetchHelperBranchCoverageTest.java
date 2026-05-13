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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
   * sendRecord call. The expected per-arm routing — verified empirically against the live
   * production code in {@link FetchHelper} — is:
   * <ul>
   *   <li>{@code linkSingleton} → {@link FetchHelper}'s {@code fetchEntity} →
   *       {@code RemoteFetchListener.fetchLinked} → {@code sendRecord}.
   *   <li>{@code linkList} member → {@code fetchCollection} →
   *       {@code RemoteFetchListener.fetchLinkedCollectionValue} → {@code sendRecord}.
   *   <li>{@code linkSet} member → {@code fetchCollection} →
   *       {@code RemoteFetchListener.fetchLinkedCollectionValue} → {@code sendRecord}.
   *   <li>{@code linkMap} entry → {@code fetchMap} → {@code parseLinked}; does NOT call
   *       {@code sendRecord}. (See FetchHelper.fetchMap line ~752: the else-branch of the
   *       {@code !validPosition || fieldDepthLevel == iLevelFromRoot} guard.)
   * </ul>
   *
   * <p>So three of the four populated multi-value arms route a distinct RID through
   * {@code sendRecord}, and the fourth (linkMap) is observable only by its <em>absence</em>
   * from {@code observed}. The falsifiable per-arm pin captured below combines (a) a size
   * pin (exactly 3 distinct sendRecord invocations) and (b) explicit
   * {@code observed.contains(armRid)} / {@code !observed.contains(armRid)} assertions for
   * each of the four arms. A regression that drops any of linkSingleton / linkList /
   * linkSet would flip the contains() pin for that arm; a regression that re-routed
   * fetchMap's else-branch through {@code sendRecord} would flip the linkMap doesNotContain
   * pin.
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

    // Carriers for the per-arm wired RIDs, populated inside executeInTx so the membership
    // assertions below can pin per-arm routing (not just the aggregate count).
    final AtomicReference<RID> singletonRid = new AtomicReference<>();
    final AtomicReference<RID> listMemberRid = new AtomicReference<>();
    final AtomicReference<RID> setMemberRid = new AtomicReference<>();
    final AtomicReference<RID> mapMemberRid = new AtomicReference<>();

    // Verified per-arm sendRecord routing (see Javadoc above): linkSingleton, linkList,
    // and linkSet each contribute one distinct RID to observed; linkMap routes through
    // parseLinked and contributes nothing. So the size pin is 3 — drop any of the first
    // three and size ≤ 2; flip the linkMap arm to sendRecord and size = 4.
    final int expectedDistinctSendRecord = 3;

    session.executeInTx(tx -> {
      EntityImpl root = tx.load(rootId);

      // Arm 1: Identifiable singleton — short-circuits the multi-value cascade. Verified
      // to drive fetchEntity → fetchLinked → sendRecord.
      final var singleton = (EntityImpl) session.newEntity("BranchCovTarget");
      root.setProperty("linkSingleton", singleton);
      singletonRid.set(singleton.getIdentity());

      // Arm 2: populated LinkList (Iterable + Collection of Identifiable). The
      // newLinkList/setProperty pathway wraps the target into EntityLinkListImpl so the
      // runtime type passed to processRecordRidMap is Iterable<Identifiable>. Verified to
      // drive fetchCollection → fetchLinkedCollectionValue → sendRecord.
      final var listMember = (EntityImpl) session.newEntity("BranchCovTarget");
      final var linkList = root.newLinkList("linkList");
      linkList.add(listMember);
      listMemberRid.set(listMember.getIdentity());

      // Arm 3: populated LinkSet — a different Iterable<Identifiable> implementation.
      // Verified to drive fetchCollection → fetchLinkedCollectionValue → sendRecord.
      final var setMember = (EntityImpl) session.newEntity("BranchCovTarget");
      final var linkSet = root.newLinkSet("linkSet");
      linkSet.add(setMember);
      setMemberRid.set(setMember.getIdentity());

      // Arm 4: populated LinkMap — Map<String, Identifiable>. Drives the Map arm
      // including the values().iterator().next() instanceof Identifiable check at the
      // processRecordRidMap pre-walk, but downstream fetchMap routes through parseLinked
      // (else branch of the fieldDepthLevel guard at FetchHelper.fetchMap ~line 752), so
      // this arm does NOT contribute to sendRecord.
      final var mapMember = (EntityImpl) session.newEntity("BranchCovTarget");
      final var linkMap = root.newLinkMap("linkMap");
      linkMap.put("k0", mapMember);
      mapMemberRid.set(mapMember.getIdentity());

      // Arm 5: empty LinkList — the isEmpty / hasNext branches of the Iterable + Collection
      // arms evaluate true, exercising the short-circuit paths.
      root.newLinkList("emptyLinkList");

      // Arm 6: empty LinkMap — similarly drives the Map.isEmpty short-circuit.
      root.newLinkMap("emptyLinkMap");

      // Arm 7: non-Identifiable EmbeddedMap. The first value is a String — the
      // Map.values().iterator().next() instanceof Identifiable check evaluates FALSE, so
      // the AND-cascade in processRecordRidMap's filter (FetchHelper.java line ~131–157)
      // proceeds to the trailing !containsIdentifiers(fieldValue) clause; containsIdentifiers
      // walks the Map values and finds no Identifiable, so the field is skipped (continue
      // branch). Pinned to drive the trailing-safety-net leg on a Map-of-strings.
      final var stringMap = root.newEmbeddedMap("stringMap");
      stringMap.put("k", "v");

      // Arm 8: populated EmbeddedList of strings — exercises the trailing
      // !containsIdentifiers(fieldValue) clause for an Iterable+Collection of non-
      // Identifiables (the Iterable arm's first-element check sees a String, so the cascade
      // continues; the Collection arm's first-element check also sees a String; finally
      // containsIdentifiers returns false and the field is skipped). The LinkList of
      // Identifiable (Arm 2) covered the recurse-on-match leg of the same surface — Arm 8
      // exercises its complement.
      final var stringList = root.newEmbeddedList("stringList");
      stringList.add("a");
      stringList.add("b");

      // Arm 9: populated EmbeddedSet of strings — same coverage as Arm 8 but through
      // the EmbeddedSet collection type so the dispatcher sees a different runtime class
      // along the same chain.
      final var stringSet = root.newEmbeddedSet("stringSet");
      stringSet.add("x");

      // Arm 10: EmbeddedList of 32-bit integer wrappers — a Collection of non-Identifiable
      // wrappers, complementary to the String EmbeddedList of Arm 8. Exercises the same
      // trailing-safety-net leg with a non-String element type so the false-leg evaluation
      // is exercised through more than one runtime element class.
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

    // Per-arm RID-membership pins for the non-shallow empty-format fetch. See the method
    // Javadoc for the verified routing — these contains/doesNotContain assertions falsify
    // (a) any arm-drop regression on linkSingleton / linkList / linkSet (their RID would
    // disappear from observed) and (b) any regression that re-routed fetchMap's else
    // branch through sendRecord (mapMember's RID would appear in observed). The size pin
    // below is retained as defence-in-depth — any new arm landing in sendRecord that was
    // not anticipated here would push size above expectedDistinctSendRecord.
    assertTrue(
        "linkSingleton must drive fetchEntity → fetchLinked → sendRecord: observed="
            + emptyFormatListener.observed,
        emptyFormatListener.observed.contains(singletonRid.get()));
    assertTrue(
        "linkList member must drive fetchCollection → fetchLinkedCollectionValue → "
            + "sendRecord: observed=" + emptyFormatListener.observed,
        emptyFormatListener.observed.contains(listMemberRid.get()));
    assertTrue(
        "linkSet member must drive fetchCollection → fetchLinkedCollectionValue → "
            + "sendRecord: observed=" + emptyFormatListener.observed,
        emptyFormatListener.observed.contains(setMemberRid.get()));
    assertFalse(
        "linkMap member must route through fetchMap → parseLinked, NOT sendRecord: "
            + "observed=" + emptyFormatListener.observed,
        emptyFormatListener.observed.contains(mapMemberRid.get()));
    assertEquals(
        "non-shallow empty-format fetch must dispatch sendRecord exactly once per "
            + "sendRecord-bound arm (linkSingleton/linkList/linkSet): observed="
            + emptyFormatListener.observed,
        (long) expectedDistinctSendRecord,
        (long) emptyFormatListener.observed.size());

    // Same per-arm pin under keepTypes — processFieldTypes runs but does not fan out
    // additional records, so the membership pattern is identical.
    assertTrue(
        "linkSingleton must drive sendRecord under keepTypes: observed="
            + keepTypesFormatListener.observed,
        keepTypesFormatListener.observed.contains(singletonRid.get()));
    assertTrue(
        "linkList member must drive sendRecord under keepTypes: observed="
            + keepTypesFormatListener.observed,
        keepTypesFormatListener.observed.contains(listMemberRid.get()));
    assertTrue(
        "linkSet member must drive sendRecord under keepTypes: observed="
            + keepTypesFormatListener.observed,
        keepTypesFormatListener.observed.contains(setMemberRid.get()));
    assertFalse(
        "linkMap member must route through parseLinked under keepTypes: observed="
            + keepTypesFormatListener.observed,
        keepTypesFormatListener.observed.contains(mapMemberRid.get()));
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
      final var list = new ArrayList<EntityImpl>();
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
   * Recording listener that captures the set of distinct RIDs dispatched through
   * {@code sendRecord} so per-arm fan-out can be pinned by RID-set membership. Required
   * to flip {@link RemoteFetchListener#requireFieldProcessing()} to true so the fast-path
   * in {@code processRecord} does NOT skip the whole fetch when the plan singleton
   * matches the default.
   */
  private static final class RecordingFetchListener extends RemoteFetchListener {

    final Set<RID> observed = new HashSet<>();

    @Override
    public boolean requireFieldProcessing() {
      return true;
    }

    @Override
    protected void sendRecord(RecordAbstract iLinked) {
      if (iLinked != null) {
        observed.add(iLinked.getIdentity());
      }
    }
  }
}
