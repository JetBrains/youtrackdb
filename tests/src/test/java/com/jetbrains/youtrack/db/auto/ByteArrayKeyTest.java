package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 03.07.12
 */
@Test
public class ByteArrayKeyTest extends BaseDBTest {

  @Parameters(value = "remote")
  public ByteArrayKeyTest(boolean remote) {
    super(remote);
  }

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
    checkEmbeddedDB();

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

    var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, "byteArrayKeyIndex");
    try (var stream = index.getRids(session, key1)) {
      Assert.assertEquals(stream.findAny().map(rid -> rid.getRecord(session)).orElse(null), doc1);
    }
    try (var stream = index.getRids(session, key2)) {
      Assert.assertEquals(stream.findAny().map(rid -> rid.getRecord(session)).orElse(null), doc2);
    }
  }

  public void testAutomaticCompositeUsage() {
    checkEmbeddedDB();

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

    var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "compositeByteArrayKey");
    try (var stream = index.getRids(session, new CompositeKey(key1, 1))) {
      Assert.assertEquals(stream.findAny().map(rid -> rid.getRecord(session)).orElse(null), doc1);
    }
    try (var stream = index.getRids(session, new CompositeKey(key2, 2))) {
      Assert.assertEquals(stream.findAny().map(rid -> rid.getRecord(session)).orElse(null), doc2);
    }
  }

  public void testAutomaticCompositeUsageInTX() {
    checkEmbeddedDB();

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

    var index =
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "compositeByteArrayKey");
    try (var stream = index.getRids(session, new CompositeKey(key1, 1))) {
      Assert.assertEquals(stream.findAny().map(rid -> rid.getRecord(session)).orElse(null), doc1);
    }
    try (var stream = index.getRids(session, new CompositeKey(key2, 2))) {
      Assert.assertEquals(stream.findAny().map(rid -> rid.getRecord(session)).orElse(null), doc2);
    }
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
        session.getMetadata().getIndexManagerInternal().getIndex(session, "byteArrayKeyIndex");
    try (var stream = autoIndex.getRids(session, key1)) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
    try (var stream = autoIndex.getRids(session, key2)) {
      Assert.assertTrue(stream.findFirst().isPresent());
    }
  }
}
