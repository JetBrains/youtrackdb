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
public class IndexTxAwareMultiValueGetTest extends BaseDBTest {

  private static final String CLASS_NAME = "idxTxAwareMultiValueGetTest";
  private static final String FIELD_NAME = "value";
  private static final String INDEX_NAME = "idxTxAwareMultiValueGetTestIndex";

  @Parameters(value = "remote")
  public IndexTxAwareMultiValueGetTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final var cls = session.getMetadata().getSchema().createClass(CLASS_NAME);
    cls.createProperty(FIELD_NAME, PropertyType.INTEGER);
    cls.createIndex(INDEX_NAME, SchemaClass.INDEX_TYPE.NOTUNIQUE, FIELD_NAME);
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
    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.begin();

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 2);
    }

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
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
    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    var docOne = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    var docTwo = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.begin();

    var activeTx1 = session.getActiveTransaction();
    docOne = activeTx1.load(docOne);
    var activeTx = session.getActiveTransaction();
    docTwo = activeTx.load(docTwo);

    docOne.delete();
    docTwo.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertFalse(stream.findAny().isPresent());
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
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
    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    var document = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 2);

    session.commit();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 2);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.begin();

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
    try (var stream = index.getRids(session, 2)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.rollback();

    Assert.assertNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
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

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    final var document = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME,
        1);

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }

    document.setProperty(FIELD_NAME, 0);
    document.setProperty(FIELD_NAME, 1);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
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

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
    session.commit();

    session.begin();
    session.newEntity(CLASS_NAME).setProperty(FIELD_NAME, 1);

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

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    final var document = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME,
        1);

    document.delete();

    Assert.assertNotNull(session.getTransactionInternal().getIndexChanges(INDEX_NAME));
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

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, INDEX_NAME);

    final var document = ((EntityImpl) session.newEntity(CLASS_NAME)).setPropertyInChain(FIELD_NAME,
        1);

    document.removeProperty(FIELD_NAME);

    document.setProperty(FIELD_NAME, 1);

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }

    session.commit();

    try (var stream = index.getRids(session, 1)) {
      Assert.assertEquals(stream.count(), 1);
    }
  }
}
