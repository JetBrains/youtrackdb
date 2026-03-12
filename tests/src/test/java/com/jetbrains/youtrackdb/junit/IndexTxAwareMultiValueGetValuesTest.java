package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class IndexTxAwareMultiValueGetValuesTest
    extends IndexTxAwareBaseJUnit5Test {

  public IndexTxAwareMultiValueGetValuesTest() {
    super(false);
  }

  @Test
  void testPut() {
    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())));
    session.commit();

    session.begin();
    Set<Identifiable> resultOne = new HashSet<>();
    var stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    assertEquals(3, resultOne.size());
    session.rollback();

    session.begin();

    var doc4 = newDoc(2);

    verifyTxIndexPut(Map.of(2, Set.of(doc4.getIdentity())));
    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    assertEquals(4, resultTwo.size());

    session.rollback();

    session.begin();
    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    assertEquals(3, resultThree.size());
    session.rollback();
  }

  @Test
  void testRemove() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();
    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())));

    session.commit();

    session.begin();
    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    assertEquals(3, resultOne.size());
    session.rollback();

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc2 = tx.load(doc2);

    doc1.delete();
    doc2.delete();

    verifyTxIndexRemove(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity())));

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    assertEquals(1, resultTwo.size());

    session.rollback();

    session.begin();
    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    assertEquals(3, resultThree.size());
    session.rollback();
  }

  @Test
  void testRemoveOne() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();
    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())));
    session.commit();

    session.begin();
    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    assertEquals(3, resultOne.size());
    session.rollback();

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(
        1, Set.of(doc1.getIdentity())));

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    assertEquals(2, resultTwo.size());

    session.rollback();

    session.begin();
    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    assertEquals(3, resultThree.size());
    session.rollback();
  }

  @Test
  void testMultiPut() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    final var doc1 = newDoc(1);

    doc1.setProperty(fieldName, 0);
    doc1.setProperty(fieldName, 1);

    final var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(2, result.size());

    session.commit();

    session.begin();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(2, result.size());
    session.rollback();
  }

  @Test
  void testPutAfterTransaction() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);
    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(2, result.size());
    session.commit();

    session.begin();
    newDoc(1);

    session.commit();

    session.begin();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(3, result.size());
    session.rollback();
  }

  @Test
  void testRemoveOneWithinTransaction() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);
    doc1.delete();
    verifyTxIndexPut(Map.of(2, Set.of(doc2.getIdentity())));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(1, result.size());

    session.commit();

    session.begin();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(1, result.size());
    session.rollback();
  }

  @Test
  void testRemoveAllWithinTransaction() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);
    doc1.delete();
    verifyTxIndexPut(Map.of(2, Set.of(doc2.getIdentity())));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(1, result.size());

    session.commit();

    session.begin();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(1, result.size());
    session.rollback();
  }

  @Test
  void testPutAfterRemove() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    assertEquals(2, result.size());

    session.commit();

    session.begin();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    assertEquals(2, result.size());
    session.rollback();
  }

  private static void streamToSet(
      Stream<RawPair<Object, RID>> stream, Set<Identifiable> result) {
    result.clear();
    result.addAll(
        stream.map((entry) -> entry.second()).collect(Collectors.toSet()));
  }
}
