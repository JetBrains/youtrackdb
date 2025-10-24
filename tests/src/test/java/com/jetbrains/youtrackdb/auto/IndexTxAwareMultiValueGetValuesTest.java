package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareMultiValueGetValuesTest extends IndexTxAwareBaseTest {

  public IndexTxAwareMultiValueGetValuesTest() {
    super(false);
  }

  @Test
  public void testPut() {
    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())
    ));
    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultOne);
    Assert.assertEquals(resultOne.size(), 3);

    session.begin();

    var doc4 = newDoc(2);

    verifyTxIndexPut(Map.of(2, Set.of(doc4.getIdentity())));
    Set<Identifiable> resultTwo = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultTwo);
    Assert.assertEquals(resultTwo.size(), 4);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultThree);
    Assert.assertEquals(resultThree.size(), 3);
  }

  @Test
  public void testRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())
    ));

    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultOne);
    Assert.assertEquals(resultOne.size(), 3);

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc2 = tx.load(doc2);

    doc1.delete();
    doc2.delete();

    verifyTxIndexRemove(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity())
    ));

    Set<Identifiable> resultTwo = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultTwo);
    Assert.assertEquals(resultTwo.size(), 1);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultThree);
    Assert.assertEquals(resultThree.size(), 3);
  }

  @Test
  public void testRemoveOne() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    var doc1 = newDoc(1);
    var doc2 = newDoc(1);
    var doc3 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity(), doc2.getIdentity()),
        2, Set.of(doc3.getIdentity())
    ));
    session.commit();

    Set<Identifiable> resultOne = new HashSet<>();
    var iterator =
        index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultOne);
    Assert.assertEquals(resultOne.size(), 3);

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(
        1, Set.of(doc1.getIdentity())
    ));

    Set<Identifiable> resultTwo = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultTwo);
    Assert.assertEquals(resultTwo.size(), 2);

    session.rollback();

    Set<Identifiable> resultThree = new HashSet<>();
    iterator = index.entries(session, Arrays.asList(1, 2), true);
    iteratorToSet(iterator, resultThree);
    Assert.assertEquals(resultThree.size(), 3);
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
    newDoc(1);

    session.commit();

    iterator = index.entries(session, Arrays.asList(1, 2), true);
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
    doc1.delete();
    verifyTxIndexPut(Map.of(2, Set.of(doc2.getIdentity())));

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
    doc1.delete();
    verifyTxIndexPut(Map.of(2, Set.of(doc2.getIdentity())));

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

    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

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

  private static void iteratorToSet(
      Iterator<RawPair<Object, RID>> iterator, Set<Identifiable> result) {
    result.clear();
    result.addAll(YTDBIteratorUtils.list(YTDBIteratorUtils.map(iterator, RawPair::second)));
  }
}
