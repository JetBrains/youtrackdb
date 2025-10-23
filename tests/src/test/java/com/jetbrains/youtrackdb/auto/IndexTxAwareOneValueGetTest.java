package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareOneValueGetTest extends IndexTxAwareBaseTest {

  public IndexTxAwareOneValueGetTest() {
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

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    session.begin();

    var doc3 = newDoc(3);
    verifyTxIndexPut(Map.of(3, Set.of(doc3.getIdentity())));

    try (var iterator = index.getRids(session, 3)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    session.rollback();

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 3)) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
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

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    final var tx = session.begin();

    doc1 = tx.load(doc1);
    doc1.delete();

    verifyTxIndexRemove(Map.of(1, Set.of(doc1.getIdentity())));

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    session.rollback();

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
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

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc1 = activeTx.load(doc1);
    doc1.removeProperty(fieldName);
    doc1.setProperty(fieldName, 1);

    verifyTxIndexChanges(
        Map.of(1, Set.of(doc1.getIdentity())),
        Map.of(1, Set.of(doc1.getIdentity()))
    );
    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

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

    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));
    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    session.commit();

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
  }

  @Test
  public void testPutAfterTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var doc1 = newDoc(1);
    verifyTxIndexPut(Map.of(1, Set.of(doc1.getIdentity())));

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
    session.commit();

    session.begin();
    newDoc(2);
    session.commit();

    try (var iterator = index.getRids(session, 2)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
  }

  @Test
  public void testRemoveOneWithinTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var document = newDoc(1);
    document.delete();

    verifyTxIndexChanges(null, null);

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    session.commit();

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertFalse(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
  }

  @Test
  public void testPutAfterRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    var document = newDoc(1);
    document.removeProperty(fieldName);
    document.setProperty(fieldName, 1);

    verifyTxIndexPut(Map.of(1, Set.of(document.getIdentity())));

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }

    session.commit();

    try (var iterator = index.getRids(session, 1)) {
      Assert.assertTrue(YTDBIteratorUtils.findFirst(iterator).isPresent());
    }
  }

  public void testInsertionDeletionInsideTx() {
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
        Assert.assertEquals(stream.count(), 0);
      }
    }
  }
}
