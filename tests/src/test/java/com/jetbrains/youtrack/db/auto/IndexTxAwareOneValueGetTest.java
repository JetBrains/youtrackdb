package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class IndexTxAwareOneValueGetTest extends BaseDBTest {

  private static final String CLASS_NAME = "idxTxAwareOneValueGetTest";
  private static final String PROPERTY_NAME = "value";
  private static final String INDEX = "idxTxAwareOneValueGetTestIndex";

  @Parameters(value = "remote")
  public IndexTxAwareOneValueGetTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    var cls = session.getMetadata().getSchema().createClass(CLASS_NAME);
    cls.createProperty(PROPERTY_NAME, PropertyType.INTEGER);
    cls.createIndex(INDEX, SchemaClass.INDEX_TYPE.UNIQUE, PROPERTY_NAME);
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    session.getMetadata().getSchema().getClassInternal(CLASS_NAME).truncate();

    super.afterMethod();
  }

  @Test
  public void testPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX);

    ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 1);

    ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();

    ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 3);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 3)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 3)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  @Test
  public void testRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX);

    var document = ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 1);

    ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();

    document = session.bindToSession(document);
    document.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  @Test
  public void testRemoveAndPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();
    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX);

    var document = ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 1);

    ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.begin();

    document = session.bindToSession(document);
    document.removeField(PROPERTY_NAME);

    document.field(PROPERTY_NAME, 1);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.rollback();
  }

  @Test
  public void testMultiPut() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX);

    var document = ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 1);

    document.field(PROPERTY_NAME, 0);
    document.field(PROPERTY_NAME, 1);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  @Test
  public void testPutAfterTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX);

    ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 1);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
    session.commit();

    session.begin();
    ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 2);

    session.commit();

    try (var stream = index.getRids(session, 2)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  @Test
  public void testRemoveOneWithinTransaction() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX);

    var document = ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 1);

    document.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
  }

  @Test
  public void testPutAfterRemove() {
    if (session.getStorage().isRemote()) {
      throw new SkipException("Test is enabled only for embedded database");
    }

    session.begin();

    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX);

    var document = ((EntityImpl) session.newEntity(CLASS_NAME)).field(PROPERTY_NAME, 1);

    document.removeField(PROPERTY_NAME);

    document.field(PROPERTY_NAME, 1);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertTrue(stream.findAny().isPresent());
    }
  }

  public void testInsertionDeletionInsideTx() {
    final var className = "_" + IndexTxAwareOneValueGetTest.class.getSimpleName();
    session.command("create class " + className + " extends V").close();
    session.command("create property " + className + ".name STRING").close();
    session.command("CREATE INDEX " + className + ".name UNIQUE").close();

    session
        .execute(
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
