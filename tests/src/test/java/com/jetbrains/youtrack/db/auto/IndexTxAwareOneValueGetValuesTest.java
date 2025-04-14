package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareOneValueGetValuesTest extends IndexTxAwareBaseTest {

  @Parameters(value = "remote")
  public IndexTxAwareOneValueGetValuesTest(@Optional Boolean remote) {
    super(remote != null && remote, true);
  }

  @Test
  public void testPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    var doc3 = newDoc(3);
    verifyTxIndexPut(Map.of(3, Set.of(doc3.getIdentity())));

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2, 3), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 3);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(resultThree.size(), 2);
  }

  @Test
  public void testRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    session.commit();

    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    Set<Identifiable> resultOne = new HashSet<>();
    streamToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    try (var rids = index.getRids(session, 1)) {
      rids.map(rid -> {
        var transaction = session.getActiveTransaction();
        return transaction.load(rid);
      }).forEach(record -> ((EntityImpl) record).delete());
    }

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    Set<Identifiable> resultTwo = new HashSet<>();
    streamToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 1);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultThree);
    Assert.assertEquals(resultThree.size(), 2);
  }

  @Test
  public void testRemoveAndPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    try (var ridStream = index.getRids(session, 1)) {
      ridStream.map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          })
          .forEach(record -> ((EntityImpl) record).delete());
    }
    var doc3 = newDoc(1);

    verifyTxIndexChanges(
        Map.of(1, Set.of(doc3.getIdentity())),
        Map.of(1, Set.of(doc1.getIdentity()))
    );

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 2);

    session.rollback();
  }

  @Test
  public void testMultiPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var doc1 = newDoc(1);
    doc1.setProperty(fieldName, 0);
    doc1.setProperty(fieldName, 1);

    final var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 2);
  }

  @Test
  public void testPutAfterTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.begin();
    newDoc(3);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2, 3), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 3);
  }

  @Test
  public void testRemoveOneWithinTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    try (var ridStream = index.getRids(session, 1)) {
      ridStream.map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          })
          .forEach(record -> ((EntityImpl) record).delete());
    }

    verifyTxIndexPut(Map.of(
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 1);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testRemoveAllWithinTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    try (var ridStream = index.getRids(session, 1)) {
      ridStream.map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          })
          .forEach(record -> ((EntityImpl) record).delete());
    }

    verifyTxIndexPut(Map.of(
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 1);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 1);
  }

  @Test
  public void testPutAfterRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    try (var ridStream = index.getRids(session, 1)) {
      ridStream.map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          })
          .forEach(record -> ((EntityImpl) record).delete());
    }
    var doc3 = newDoc(1);
    verifyTxIndexPut(Map.of(
        1, Set.of(doc3.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    stream = index.streamEntries(session, Arrays.asList(1, 2), true);
    streamToSet(stream, result);
    Assert.assertEquals(result.size(), 2);
  }

  private static void streamToSet(
      Stream<RawPair<Object, RID>> stream, Set<Identifiable> result) {
    result.clear();
    result.addAll(stream.map((entry) -> entry.second()).collect(Collectors.toSet()));
  }
}
