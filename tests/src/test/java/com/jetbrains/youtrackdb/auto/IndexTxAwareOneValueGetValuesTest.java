package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareOneValueGetValuesTest extends IndexTxAwareBaseTest {

  public IndexTxAwareOneValueGetValuesTest() {
    super(true);
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
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    var doc3 = newDoc(3);
    verifyTxIndexPut(Map.of(3, Set.of(doc3.getIdentity())));

    Set<Identifiable> resultTwo = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2, 3), true);
    iteratorToSet(iterator, resultTwo);
    Assert.assertEquals(resultTwo.size(), 3);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultThree);
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

    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    Set<Identifiable> resultOne = new HashSet<>();
    iteratorToSet(iterator, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    try (var rids = index.getRids(session, 1)) {
      YTDBIteratorUtils.forEachRemaining(YTDBIteratorUtils.map(rids, rid -> {
        var transaction = session.getActiveTransaction();
        return transaction.load(rid);
      }), record -> ((EntityImpl) record).delete());
    }

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));

    iterator = index.entries(session, Arrays.asList(1, 2), true);
    Set<Identifiable> resultTwo = new HashSet<>();
    iteratorToSet(iterator, resultTwo);
    Assert.assertEquals(resultTwo.size(), 1);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultThree);
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
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    try (var rids = index.getRids(session, 1)) {
      YTDBIteratorUtils.forEachRemaining(
          YTDBIteratorUtils.map(rids, rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }), record -> ((EntityImpl) record).delete());
    }
    var doc3 = newDoc(1);

    verifyTxIndexChanges(
        Map.of(1, Set.of(doc3.getIdentity())),
        Map.of(1, Set.of(doc1.getIdentity()))
    );

    Set<Identifiable> resultTwo = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultTwo);
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
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);
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
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);

    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.begin();
    newDoc(3);

    session.commit();

    iterator = index.entries(session, Arrays.asList(1, 2, 3), true);
    iteratorToSet(iterator, result);

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

    try (var rids = index.getRids(session, 1)) {
      YTDBIteratorUtils.forEachRemaining(YTDBIteratorUtils.map(rids,
          rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }), record -> ((EntityImpl) record).delete());
    }

    verifyTxIndexPut(Map.of(
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);
    Assert.assertEquals(result.size(), 1);

    session.commit();

    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);
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

    try (var rids = index.getRids(session, 1)) {
      YTDBIteratorUtils.forEachRemaining(YTDBIteratorUtils.map(rids,
          rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }), record -> ((EntityImpl) record).delete());
    }

    verifyTxIndexPut(Map.of(
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);
    Assert.assertEquals(result.size(), 1);

    session.commit();

    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);
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

    try (var rids = index.getRids(session, 1)) {
      YTDBIteratorUtils.forEachRemaining(YTDBIteratorUtils.map(rids,
              rid -> {
                var transaction = session.getActiveTransaction();
                return transaction.load(rid);
              })
          , record -> ((EntityImpl) record).delete());
    }
    var doc3 = newDoc(1);
    verifyTxIndexPut(Map.of(
        1, Set.of(doc3.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    Set<Identifiable> result = new HashSet<>();
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, result);
    Assert.assertEquals(result.size(), 2);
  }

  private static void iteratorToSet(
      Iterator<RawPair<Object, RID>> iterator, Set<Identifiable> result) {
    result.clear();
    result.addAll(YTDBIteratorUtils.set(YTDBIteratorUtils.map(iterator, RawPair::second)));
  }
}
