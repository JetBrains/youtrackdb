package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class IndexTxAwareMultiValueGetTest
    extends IndexTxAwareBaseJUnit5Test {

  public IndexTxAwareMultiValueGetTest() {
    super(false);
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest#testPut
  void testPut() {
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
    try (var stream = index.getRids(session, 1)) {
      assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }
    session.rollback();

    session.begin();

    var doc4 = newDoc(2);

    verifyTxIndexPut(Map.of(2, Set.of(doc4.getIdentity())));
    try (var stream = index.getRids(session, 2)) {
      assertEquals(2, stream.count());
    }

    session.rollback();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest#testRemove
  void testRemove() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var docOne = newDoc(1);
    var docTwo = newDoc(1);
    var docThree = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(docOne.getIdentity(), docTwo.getIdentity()),
        2, Set.of(docThree.getIdentity())));

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }
    session.rollback();

    final var tx = session.begin();

    docOne = tx.load(docOne);
    docTwo = tx.load(docTwo);

    docOne.delete();
    docTwo.delete();

    verifyTxIndexRemove(
        Map.of(1, Set.of(docOne.getIdentity(), docTwo.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      assertFalse(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }

    session.rollback();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest#testRemoveOne
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
    try (var stream = index.getRids(session, 1)) {
      assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }
    session.rollback();

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      assertEquals(1, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }

    session.rollback();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(2, stream.count());
    }
    try (var stream = index.getRids(session, 2)) {
      assertEquals(1, stream.count());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest#testMultiPut
  void testMultiPut() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    final var document = newDoc(1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      assertEquals(1, stream.count());
    }

    document.setProperty(fieldName, 0);
    verifyTxIndexPut(Map.of(0, Set.of(document.getIdentity())));
    document.setProperty(fieldName, 1);
    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      assertEquals(1, stream.count());
    }
    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(1, stream.count());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest#testPutAfterTransaction
  void testPutAfterTransaction() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      assertEquals(1, stream.count());
    }
    session.commit();

    session.begin();
    newDoc(1);
    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(2, stream.count());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest#testRemoveOneWithinTransaction
  void testRemoveOneWithinTransaction() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    final var document = newDoc(1);
    document.delete();

    verifyTxIndexChanges(null, null);
    try (var stream = index.getRids(session, 1)) {
      assertFalse(stream.findAny().isPresent());
    }

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(0, stream.count());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareMultiValueGetTest#testPutAfterRemove
  void testPutAfterRemove() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    final var document = newDoc(1);
    document.removeProperty(fieldName);
    document.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      assertEquals(1, stream.count());
    }

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertEquals(1, stream.count());
    }
    session.rollback();
  }
}
