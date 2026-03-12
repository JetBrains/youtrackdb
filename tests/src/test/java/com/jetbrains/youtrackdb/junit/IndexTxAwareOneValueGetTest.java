package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class IndexTxAwareOneValueGetTest
    extends IndexTxAwareBaseJUnit5Test {

  public IndexTxAwareOneValueGetTest() {
    super(true);
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testPut
  void testPut() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())));

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();

    var doc3 = newDoc(3);
    verifyTxIndexPut(Map.of(3, Set.of(doc3.getIdentity())));

    try (var stream = index.getRids(session, 3)) {
      assertTrue(stream.findAny().isPresent());
    }

    session.rollback();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 3)) {
      assertFalse(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testRemove
  void testRemove() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())));

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      assertFalse(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }

    session.rollback();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testRemoveAndPut
  void testRemoveAndPut() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);
    var doc2 = newDoc(2);

    verifyTxIndexPut(Map.of(
        1, Set.of(doc1.getIdentity()),
        2, Set.of(doc2.getIdentity())));

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc1 = activeTx.load(doc1);
    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexChanges(
        Map.of(1, Set.of(doc1.getIdentity())),
        Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }

    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testMultiPut
  void testMultiPut() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var doc1 = newDoc(1);

    doc1.setProperty(fieldName, 0);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testPutAfterTransaction
  void testPutAfterTransaction() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    final var doc1 = newDoc(1);
    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.commit();

    session.begin();
    newDoc(2);
    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 2)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testRemoveOneWithinTransaction
  void testRemoveOneWithinTransaction() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var document = newDoc(1);
    document.delete();

    verifyTxIndexChanges(null, null);

    try (var stream = index.getRids(session, 1)) {
      assertFalse(stream.findAny().isPresent());
    }

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertFalse(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testPutAfterRemove
  void testPutAfterRemove() {
    assumeFalse(session.getStorage().isRemote(),
        "Test is enabled only for embedded database");

    session.begin();

    var document = newDoc(1);
    document.removeProperty(fieldName);
    document.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));

    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }

    session.commit();

    session.begin();
    try (var stream = index.getRids(session, 1)) {
      assertTrue(stream.findAny().isPresent());
    }
    session.rollback();
  }

  @Test
  // Migrated from: com.jetbrains.youtrackdb.auto.IndexTxAwareOneValueGetTest#testInsertionDeletionInsideTx
  void testInsertionDeletionInsideTx() {
    final var className = "_" + IndexTxAwareOneValueGetTest.class.getSimpleName();
    session.execute("create class " + className + " extends V").close();
    session.execute("create property " + className + ".name STRING").close();
    session.execute("CREATE INDEX " + className + ".name UNIQUE").close();

    session
        .computeScript(
            "SQL",
            "begin;\n"
                + "insert into "
                + className
                + "(name) values ('c');\n"
                + "let top = (select from "
                + className
                + " where name='c');\n"
                + "delete vertex $top;\n"
                + "commit;\n"
                + "return $top")
        .close();

    try (final var resultSet = session.query("select * from " + className)) {
      try (var stream = resultSet.stream()) {
        assertEquals(0, stream.count());
      }
    }
  }
}
