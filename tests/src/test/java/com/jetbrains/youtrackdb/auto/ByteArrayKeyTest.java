package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Tests for byte array index keys.
 *
 * @since 03.07.12
 */
@Test
public class ByteArrayKeyTest extends BaseDBTest {
  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final var byteArrayKeyTest =
        session.getMetadata().getSchema().createClass("ByteArrayKeyTest");
    byteArrayKeyTest.createProperty("byteArrayKey", PropertyType.BINARY);

    byteArrayKeyTest.createIndex("byteArrayKeyIndex", SchemaClass.INDEX_TYPE.UNIQUE,
        "byteArrayKey");

    final var compositeByteArrayKeyTest =
        session.getMetadata().getSchema().createClass("CompositeByteArrayKeyTest");
    compositeByteArrayKeyTest.createProperty("byteArrayKey", PropertyType.BINARY);
    compositeByteArrayKeyTest.createProperty("intKey", PropertyType.INTEGER);

    compositeByteArrayKeyTest.createIndex(
        "compositeByteArrayKey", SchemaClass.INDEX_TYPE.UNIQUE, "byteArrayKey", "intKey");
  }

  public void testAutomaticUsage() {

    var key1 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9,
            0, 1
        };

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("ByteArrayKeyTest"));
    doc1.setProperty("byteArrayKey", key1);

    var key2 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9,
            0, 2
        };
    var doc2 = ((EntityImpl) session.newEntity("ByteArrayKeyTest"));
    doc2.setProperty("byteArrayKey", key2);

    session.commit();

    session.begin();
    var index =
        session.getSharedContext().getIndexManager().getIndex("byteArrayKeyIndex");
    final var tx = session.getActiveTransaction();
    try (var stream = index.getRids(session, key1)) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc1)
      );
    }
    try (var stream = index.getRids(session, key2)) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc2)
      );
    }
    session.commit();
  }

  public void testAutomaticCompositeUsage() {

    var key1 = new byte[]{1, 2, 3};
    var key2 = new byte[]{4, 5, 6};

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc1.setProperty("byteArrayKey", key1);
    doc1.setProperty("intKey", 1);

    var doc2 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc2.setProperty("byteArrayKey", key2);
    doc2.setProperty("intKey", 2);

    session.commit();

    session.begin();
    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("compositeByteArrayKey");
    final var tx = session.getActiveTransaction();
    try (var stream = index.getRids(session, new CompositeKey(key1, 1))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc1)
      );
    }
    try (var stream = index.getRids(session, new CompositeKey(key2, 2))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc2)
      );
    }
    session.commit();
  }

  public void testAutomaticCompositeUsageInTX() {

    var key1 = new byte[]{7, 8, 9};
    var key2 = new byte[]{10, 11, 12};

    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc1.setProperty("byteArrayKey", key1);
    doc1.setProperty("intKey", 1);

    var doc2 = ((EntityImpl) session.newEntity("CompositeByteArrayKeyTest"));
    doc2.setProperty("byteArrayKey", key2);
    doc2.setProperty("intKey", 2);

    session.commit();

    session.begin();
    var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("compositeByteArrayKey");
    final var tx = session.getActiveTransaction();
    try (var stream = index.getRids(session, new CompositeKey(key1, 1))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc1)
      );
    }
    try (var stream = index.getRids(session, new CompositeKey(key2, 2))) {
      Assert.assertEquals(
          stream.findAny().map(rid -> {
            var transaction = session.getActiveTransaction();
            return transaction.load(rid);
          }).orElse(null),
          tx.load(doc2)
      );
    }
    session.commit();
  }

  @Test(dependsOnMethods = {"testAutomaticUsage"})
  public void testContains() {
    var key1 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9,
            0, 1
        };
    var key2 =
        new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8,
            9,
            0, 2
        };

    var autoIndex =
        session.getSharedContext().getIndexManager().getIndex("byteArrayKeyIndex");
    session.begin();
    try (var stream = autoIndex.getRids(session, key1)) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    try (var stream = autoIndex.getRids(session, key2)) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    session.rollback();
  }
}
