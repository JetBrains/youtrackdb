package com.jetbrains.youtrackdb.auto;

import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareMultiValueGetTest extends IndexTxAwareBaseTest {

  public IndexTxAwareMultiValueGetTest() {
    super(false);
  }

  @Test
  public void testPut() {
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

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.begin();

    var doc4 = newDoc(2);

    verifyTxIndexPut(Map.of(2, Set.of(doc4.getIdentity())));
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 2);
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }
  }

  @Test
  public void testRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var docOne = newDoc(1);
    var docTwo = newDoc(1);
    var docThree = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(docOne.getIdentity(), docTwo.getIdentity()),
        2, Set.of(docThree.getIdentity())
    ));

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    final var tx = session.begin();

    docOne = tx.load(docOne);
    docTwo = tx.load(docTwo);

    docOne.delete();
    docTwo.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(docOne.getIdentity(), docTwo.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }
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

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.rollback();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }
  }

  @Test
  public void testMultiPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var document = newDoc(1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }

    document.setProperty(fieldName, 0);
    verifyTxIndexPut(Map.of(0, Set.of(document.getIdentity())));
    document.setProperty(fieldName, 1);
    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
  }

  @Test
  public void testPutAfterTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var doc1 = newDoc(1);
    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
    session.commit();

    session.begin();
    newDoc(1);
    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
  }

  @Test
  public void testRemoveOneWithinTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var document = newDoc(1);
    document.delete();

    verifyTxIndexChanges(null, null);
    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 0);
    }
  }

  @Test
  public void testPutAfterRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var document = newDoc(1);
    document.removeProperty(fieldName);
    document.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
  }
}
