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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchContext;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchHelper;
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
 * <p>The cumulative diff against {@code origin/develop} for the surviving Track 22b production
 * lines reformats the multi-line instanceof chains in {@code processRecordRidMap} (and the
 * sibling {@code process}/{@code processFieldDocument}/{@code isEmbedded} methods) — JaCoCo
 * measures coverage on the new line positions, so the unindented vs reindented chain is
 * treated as "changed" production code and the gate flags any chain branch that lacks a
 * covering field type. This test class drives those branches with a {@link EntityImpl}
 * root carrying a fan of property values across the supported multi-value types
 * (LinkList, LinkSet, LinkMap, embedded EntityImpl, populated and empty), so JaCoCo's branch
 * counters advance across the full width of the conditional.
 *
 * <p>The class extends {@link TestUtilsFixture} to inherit the rollback-on-failure safety net
 * (matches {@link DepthFetchPlanTest}'s pattern) — failing test bodies do not leak open
 * transactions into sibling fixtures.
 */
public class FetchHelperBranchCoverageTest extends TestUtilsFixture {

  /**
   * Drive {@link FetchHelper#fetch} against a root entity that carries link-singleton +
   * link-list + link-set + link-map properties. Each property exercises a distinct arm of the
   * {@code processRecordRidMap} {@code instanceof} chain — Identifiable, Iterable
   * (LinkList / LinkSet), and Map (LinkMap whose values are Identifiable). The falsifiable
   * post-condition is that {@code sendRecord} fires at least once: a regression that mis-
   * routed the multi-value field types into the skip branch would drop the count to zero
   * because the linked entities would never be enqueued into {@code parsedRecords}.
   */
  @Test
  public void fetchExercisesEveryInstanceofArmInProcessRecordRidMap() {
    session.getMetadata().getSchema().createClass("BranchCovRoot");
    session.getMetadata().getSchema().createClass("BranchCovTarget");

    // Pre-create 3 target entities so the link-list / link-set / link-map fields each carry
    // at least one populated Identifiable member to traverse.
    final var targetIds = new RID[3];
    for (var i = 0; i < targetIds.length; i++) {
      final var idx = i;
      targetIds[i] = session.computeInTx(
          tx -> ((EntityImpl) session.newEntity("BranchCovTarget")).getIdentity());
      session.executeInTx(tx -> {
        EntityImpl t = tx.load(targetIds[idx]);
        t.setProperty("idx", idx);
      });
    }

    final var rootId = session.computeInTx(
        tx -> ((EntityImpl) session.newEntity("BranchCovRoot")).getIdentity());

    session.executeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      EntityImpl singleton = tx.load(targetIds[0]);

      // Arm 1: Identifiable singleton — short-circuits the multi-value cascade.
      root.setProperty("linkSingleton", singleton);

      // Arm 2: populated LinkList (Iterable + Collection of Identifiable). The
      // newLinkList/setProperty pathway wraps the targets into EntityLinkListImpl so the
      // runtime type passed to processRecordRidMap is Iterable<Identifiable>.
      final var linkList = root.newLinkList("linkList");
      for (var id : targetIds) {
        linkList.add(tx.load(id));
      }

      // Arm 3: populated LinkSet — a different Iterable<Identifiable> implementation.
      final var linkSet = root.newLinkSet("linkSet");
      for (var id : targetIds) {
        linkSet.add(tx.load(id));
      }

      // Arm 4: populated LinkMap — Map<String, Identifiable>. Drives the Map arm including
      // the values().iterator().next() instanceof Identifiable check.
      final var linkMap = root.newLinkMap("linkMap");
      for (var i = 0; i < targetIds.length; i++) {
        linkMap.put("k" + i, tx.load(targetIds[i]));
      }

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
    });

    // Drive the entry point with a plan that follows everything at depth 1. Each fetch
    // call below targets a DIFFERENT format-driven code path in the dispatcher:
    //   - format="" → FormatSettings.keepTypes=false, so processFieldTypes is skipped;
    //     drives the process() branch chain at lines 537/551-554 only.
    //   - format=null → FormatSettings.keepTypes=true, so processFieldTypes runs and the
    //     branch chain at lines 476/488-491 is driven too.
    //   - format="shallow" → exercises the early-return arms in process() that the
    //     non-shallow path skips.
    final var emptyFormatCount = session.computeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      final var listener = new CountFetchListener();
      final FetchContext context = new RemoteFetchContext();
      FetchHelper.fetch(session,
          root, root, FetchHelper.buildFetchPlan("[*]*:1"), listener, context, "");
      return listener.count;
    });

    final var keepTypesFormatCount = session.computeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      final var listener = new CountFetchListener();
      final FetchContext context = new RemoteFetchContext();
      // format="keepTypes" routes through FormatSettings(stringFormat) which sets
      // keepTypes=true (FormatSettings:1360). The pre-process loop in processRecord
      // then drives processFieldTypes for every field, covering the chain at
      // FetchHelper:472-491. Null format would have the same effect via the
      // FormatSettings(null) initializer, but null also tripped on the outer fetch()
      // entry's format.contains("shallow") guard, so we use the explicit token.
      FetchHelper.fetch(session,
          root, root, FetchHelper.buildFetchPlan("[*]*:1"), listener, context, "keepTypes");
      return listener.count;
    });

    final var shallowFormatCount = session.computeInTx(tx -> {
      EntityImpl root = tx.load(rootId);
      final var listener = new CountFetchListener();
      final FetchContext context = new RemoteFetchContext();
      FetchHelper.fetch(session,
          root, root, FetchHelper.buildFetchPlan("[*]*:1"), listener, context, "shallow");
      return listener.count;
    });

    // Falsifiable post-conditions: every Identifiable-bearing field fans out at least
    // once into sendRecord under the non-shallow plans, and shallow plans suppress the
    // fan-out entirely.
    assertTrue(
        "non-shallow fetch must dispatch sendRecord at least once for empty-format: "
            + emptyFormatCount,
        emptyFormatCount >= 1);
    assertTrue(
        "non-shallow fetch with keepTypes format must also dispatch: "
            + keepTypesFormatCount,
        keepTypesFormatCount >= 1);
    assertEquals(
        "shallow format must suppress all link traversal: " + shallowFormatCount,
        0L,
        (long) shallowFormatCount);
  }

  /**
   * Pin the explicit {@link LinkBag} short-circuit inside {@link FetchHelper#isEmbedded}
   * (production line 610-612: a LinkBag is never embedded because it can only carry edge
   * references, never embedded documents). The guard short-circuits before the
   * {@code MultiValue.getFirstValue} probe fires, so a regression that dropped the guard
   * would still observably differ from the current shape under a LinkBag whose first edge
   * was a non-EntityImpl (the fallback probe would NPE on Iterator.next() being null).
   */
  @Test
  public void isEmbeddedReturnsFalseForLinkBag() {
    final var bag = new LinkBag(session);
    assertFalse(
        "LinkBag short-circuit must return false even for an empty bag",
        FetchHelper.isEmbedded(bag));
  }

  /**
   * Pin the embedded-document detection arm of {@link FetchHelper#isEmbedded} (production
   * line 605-607: {@code fieldValue instanceof EntityImpl entityImpl && entityImpl.isEmbedded()}).
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
   * Pin the {@link FetchHelper#isEmbedded} multi-value-of-embedded fallback branch
   * (production line 615-620: when the field is a non-EntityImpl multi-value whose first
   * element is an embedded EntityImpl, {@code MultiValue.getFirstValue} returns the inner
   * EntityImpl and the {@code isEmbedded() || !isPersistent()} probe evaluates true). The
   * cumulative-vs-{@code origin/develop} coverage gate flagged lines 619-620 as uncovered
   * because the existing {@link FetchHelperDeadCodeTest} {@code isEmbeddedReturns*} pins
   * only exercise the non-Identifiable / scalar arms; this one drives the
   * {@code fallback-on-multi-value} arm that walks the embedded probe.
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
   * Pin the {@link FetchHelper#isEmbedded} swallow-exception arm (production line
   * 614-624: the {@code try / catch (Exception e)} around {@code MultiValue.getFirstValue}).
   * If MultiValue handling throws (e.g. for an unsupported field-value shape), the helper
   * logs and returns the {@code isEmbedded} flag computed before the try — which stays
   * false because the field is neither an EntityImpl nor a LinkBag. The pin is best-effort:
   * MultiValue is conservative, so most non-multi-value shapes return false from
   * getFirstValue without throwing. Drives the fall-through case for completeness.
   */
  @Test
  public void isEmbeddedReturnsFalseForScalarWithoutMultiValueSupport() {
    // A plain Long value: not an EntityImpl, not a LinkBag, MultiValue.getFirstValue
    // returns the value itself (or null for a non-multi-value scalar). The helper's
    // post-probe isEmbedded check evaluates the scalar against EntityImpl, sees no match,
    // and returns false. Pins the fall-through arm.
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
   * (empty LinkList, empty LinkMap, empty EmbeddedMap). Every "is empty" / "first element
   * is null" arm of the instanceof cascade evaluates without enqueueing anything — the
   * falsifiable observable is that {@code parsedRecords} stays untouched.
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
   * Counter listener identical in shape to {@code DepthFetchPlanTest.CountFetchListener} —
   * duplicated here so this test class does not depend on a private fixture in a sibling
   * test file. Required to flip {@link RemoteFetchListener#requireFieldProcessing()} to
   * true so the fast-path in {@code processRecord} does NOT skip the whole fetch when the
   * plan singleton matches the default.
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
