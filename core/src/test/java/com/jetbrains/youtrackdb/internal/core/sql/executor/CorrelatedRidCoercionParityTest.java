package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquals;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Table A and Table B coercion parity from {@code parity-spec.md}: every row compares
 * {@link FetchFromCorrelatedRidStep#equalityMatchSet} against the scan filter's
 * {@link QueryOperatorEquals#equals} over a small class fixture. Discharges review gate TQ-5 at
 * the coercion layer and covers TQ-7/TQ-8/TQ-9 branch rows.
 */
public class CorrelatedRidCoercionParityTest extends TestUtilsFixture {

  /**
   * Runs every Table A/B coercion row: fetch set must equal the identifiers scan+filter would
   * visit, in ascending order, with class membership applied.
   */
  @Test
  public void equalityMatchSet_matchesScanFilterForTableAAndB() {
    var className = createClassInstance().getName();
    var foreignName = createClassInstance().getName();
    session.begin();
    var live = session.newInstance(className);
    live.setProperty("tag", "live");
    var second = session.newInstance(className);
    second.setProperty("tag", "second");
    var doomed = session.newInstance(className);
    doomed.setProperty("tag", "doomed");
    var foreign = session.newInstance(foreignName);
    foreign.setProperty("tag", "foreign");
    session.commit();

    session.begin();
    session.execute("DELETE FROM " + className + " WHERE @rid = " + doomed.getIdentity()).close();
    session.commit();

    var liveRid = (RecordIdInternal) live.getIdentity();
    var secondRid = (RecordIdInternal) second.getIdentity();
    var foreignRid = (RecordIdInternal) foreign.getIdentity();
    var invalidPos = new RecordId(liveRid.getCollectionId(), -1);
    var classIds = ids(liveRid.getCollectionId());
    var scanPool = List.of(liveRid, secondRid);

    assertCoercionParity(null, classIds, scanPool);
    assertCoercionParity(liveRid, classIds, scanPool);
    assertCoercionParity(invalidPos, classIds, scanPool);
    session.begin();
    var liveRecord = session.load(liveRid).asEntity();
    assertCoercionParity(liveRecord, classIds, scanPool);
    session.commit();
    assertCoercionParity(liveRid.toString(), classIds, scanPool);
    assertCoercionParity("  " + liveRid + "  ", classIds, scanPool);
    assertCoercionParity(new RecordId(liveRid.getCollectionId(), -1).toString(), classIds,
        scanPool);
    assertCoercionParity("not-a-rid", classIds, scanPool);
    assertCoercionParity(42, classIds, scanPool);
    assertCoercionParity(true, classIds, scanPool);
    assertCoercionParity(new byte[] {1, 2}, classIds, scanPool);
    assertCoercionParity(Map.of("k", liveRid), classIds, scanPool);
    assertCoercionParity(new Object[] {liveRid, secondRid}, classIds, scanPool);
    assertCoercionParity(List.of(), classIds, scanPool);
    assertCoercionParity(List.of(liveRid), classIds, scanPool);
    assertCoercionParity(List.of(liveRid, secondRid), classIds, scanPool);
    assertCoercionParity(List.of(List.of(liveRid, secondRid)), classIds, scanPool);
    assertCoercionParity(List.of(List.of(liveRid)), classIds, scanPool);

    session.begin();
    var fresh = session.newInstance(className);
    fresh.setProperty("tag", "fresh");
    var freshRid = (RecordIdInternal) fresh.getIdentity();
    try {
      assertCoercionParity(fresh, classIds, List.of(liveRid, secondRid, freshRid));
    } finally {
      session.commit();
    }

    var identifiableResult = new ResultInternal(session);
    identifiableResult.setIdentity(liveRid);
    assertCoercionParity(identifiableResult, classIds, scanPool);

    var linkWrapper = new ResultInternal(session);
    linkWrapper.setProperty("ids", liveRid);
    assertCoercionParity(linkWrapper, classIds, scanPool);

    var multiLink = new ResultInternal(session);
    multiLink.setProperty("ids", List.of(secondRid, liveRid, liveRid));
    assertCoercionParity(multiLink, classIds, scanPool);

    var stringMulti = new ResultInternal(session);
    stringMulti.setProperty("ids", List.of(liveRid.toString()));
    assertCoercionParity(stringMulti, classIds, scanPool);

    var mapMulti = new ResultInternal(session);
    mapMulti.setProperty("ids", Map.of("a", liveRid, "b", secondRid));
    assertCoercionParity(mapMulti, classIds, scanPool);

    var arrayMulti = new ResultInternal(session);
    arrayMulti.setProperty("ids", new RecordIdInternal[] {secondRid, liveRid});
    assertCoercionParity(arrayMulti, classIds, scanPool);

    var nullElementMulti = new ResultInternal(session);
    nullElementMulti.setProperty("ids", Arrays.asList(null, liveRid));
    assertCoercionParity(nullElementMulti, classIds, scanPool);

    var nestedCollectionMulti = new ResultInternal(session);
    nestedCollectionMulti.setProperty("ids", List.of(List.of(liveRid)));
    assertCoercionParity(nestedCollectionMulti, classIds, scanPool);

    var twoProp = new ResultInternal(session);
    twoProp.setProperty("a", liveRid);
    twoProp.setProperty("b", secondRid);
    assertCoercionParity(twoProp, classIds, scanPool);

    var emptyResult = new ResultInternal(session);
    assertCoercionParity(emptyResult, classIds, scanPool);

    var nullProp = new ResultInternal(session);
    nullProp.setProperty("ids", null);
    assertCoercionParity(nullProp, classIds, scanPool);

    var emptyListProp = new ResultInternal(session);
    emptyListProp.setProperty("ids", List.of());
    assertCoercionParity(emptyListProp, classIds, scanPool);

    assertCoercionParity(foreignRid, classIds, scanPool);

    var foreignMulti = new ResultInternal(session);
    foreignMulti.setProperty("ids", List.of(liveRid, foreignRid));
    assertCoercionParity(foreignMulti, classIds, scanPool);
  }

  /** Nested {@code [[rid]]} collections collapse to the inner identifier — BG-3. */
  @Test
  public void equalityMatchSet_doubleNestedSizeOneCollection_unwrapsToInnerId() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    var rid = (RecordIdInternal) doc.getIdentity();
    session.commit();

    var nested = List.of(List.of(rid));
    var classIds = ids(rid.getCollectionId());
    assertCoercionParity(nested, classIds, List.of(rid));
  }

  private void assertCoercionParity(
      Object rhs, IntSet classIds, List<RecordIdInternal> scanPool) {
    var expected = scanOracle(rhs, classIds, scanPool);
    var fetch = FetchFromCorrelatedRidStep.equalityMatchSet(rhs, classIds);
    assertThat(fetch)
        .as("fetch must match scan oracle for rhs=%s", rhs)
        .isEqualTo(expected);
  }

  private List<RecordIdInternal> scanOracle(
      Object rhs, IntSet classIds, List<RecordIdInternal> scanPool) {
    var matched = new LinkedHashSet<RecordIdInternal>();
    var openedHere = session.getActiveTransactionOrNull() == null;
    if (openedHere) {
      session.begin();
    }
    try {
      for (var candidate : scanPool) {
        if (!classIds.contains(candidate.getCollectionId())) {
          continue;
        }
        if (QueryOperatorEquals.equals(session, candidate, rhs)) {
          matched.add(candidate);
        }
      }
    } finally {
      if (openedHere) {
        session.commit();
      }
    }
    var scanSorted = new ArrayList<>(matched);
    scanSorted.sort(RecordIdInternal::compareTo);
    return scanSorted;
  }

  private static IntSet ids(int... values) {
    var set = new IntOpenHashSet(values.length);
    for (var v : values) {
      set.add(v);
    }
    return set;
  }
}
