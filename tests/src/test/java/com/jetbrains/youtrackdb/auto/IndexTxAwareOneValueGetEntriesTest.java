package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareOneValueGetEntriesTest extends IndexTxAwareBaseTest {

  public IndexTxAwareOneValueGetEntriesTest() {
    super(true);
  }

  @Test
  public void testPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var doc1 = newDoc(1);
    final var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    var doc3 = newDoc(3);

    verifyTxIndexPut(Map.of(3, Set.of(doc3.getIdentity())));

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.entries(session, Arrays.asList(1, 2, 3), true);
    iteratorToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 3);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, resultThree);

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

    Set<Identifiable> resultOne = new HashSet<>();
    var stream =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc1 = activeTx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 1);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    stream = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, resultThree);
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
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, resultOne);
    Assert.assertEquals(resultOne.size(), 2);

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc1 = activeTx.load(doc1);
    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexChanges(
        Map.of(1, Set.of(doc1.getIdentity())),
        Map.of(1, Set.of(doc1.getIdentity()))
    );

    Set<Identifiable> resultTwo = new HashSet<>();
    stream = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, resultTwo);
    Assert.assertEquals(resultTwo.size(), 2);

    session.rollback();
  }

  @Test
  public void testMultiPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);

    doc1.setProperty(fieldName, 0);
    doc1.setProperty(fieldName, 1);

    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    stream = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, result);
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
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, result);

    Assert.assertEquals(result.size(), 2);
    session.commit();

    session.begin();
    newDoc(3);

    session.commit();

    stream = index.entries(session, Arrays.asList(1, 2, 3), true);
    iteratorToSet(stream, result);
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

    doc1.delete();

    verifyTxIndexPut(Map.of(
        2, Set.of(doc2.getIdentity())
    ));

    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, result);
    Assert.assertEquals(result.size(), 1);

    session.commit();

    stream = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, result);
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

    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())
    ));
    Set<Identifiable> result = new HashSet<>();
    var stream =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, result);

    Assert.assertEquals(result.size(), 2);

    session.commit();

    stream = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(stream, result);
    Assert.assertEquals(result.size(), 2);
  }

  private static void iteratorToSet(
      CloseableIterator<RawPair<Object, RID>> iterator, Set<Identifiable> result) {
    result.clear();
    result.addAll(YTDBIteratorUtils.set(YTDBIteratorUtils.map(iterator, RawPair::second)));
  }
}
