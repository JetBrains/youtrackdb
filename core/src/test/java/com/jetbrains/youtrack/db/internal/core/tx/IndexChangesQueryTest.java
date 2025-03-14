package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class IndexChangesQueryTest {

  public static final String CLASS_NAME = "idxTxAwareMultiValueGetEntriesTest";
  private static final String FIELD_NAME = "value";
  private static final String INDEX_NAME = "idxTxAwareMultiValueGetEntriesTestIndex";
  private YouTrackDB youTrackDB;
  private DatabaseSessionInternal db;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    db =
        (DatabaseSessionInternal)
            youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);

    final Schema schema = db.getMetadata().getSchema();
    final var cls = schema.createClass(CLASS_NAME);
    cls.createProperty(FIELD_NAME, PropertyType.INTEGER);
    cls.createIndex(INDEX_NAME, SchemaClass.INDEX_TYPE.NOTUNIQUE, FIELD_NAME);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  @Test
  public void testMultiplePut() {
    db.begin();

    final var index =
        db.getMetadata().getIndexManagerInternal().getIndex(db, INDEX_NAME);

    var doc = ((EntityImpl) db.newEntity(CLASS_NAME));
    doc.setProperty(FIELD_NAME, 1);

    var doc1 = ((EntityImpl) db.newEntity(CLASS_NAME));
    doc1.setProperty(FIELD_NAME, 2);

    Assert.assertNotNull(db.getTransactionInternal().getIndexChanges(INDEX_NAME));

    db.getTransactionInternal().preProcessRecordsAndExecuteCallCallbacks();

    Assert.assertFalse(fetchCollectionFromIndex(index, 1).isEmpty());
    Assert.assertFalse((fetchCollectionFromIndex(index, 2)).isEmpty());

    db.commit();

    Assert.assertEquals(2, index.size(db));
    Assert.assertFalse((fetchCollectionFromIndex(index, 1)).isEmpty());
    Assert.assertFalse((fetchCollectionFromIndex(index, 2)).isEmpty());
  }

  private Collection<RID> fetchCollectionFromIndex(Index index, int key) {
    try (var stream = index.getRids(db, key)) {
      return stream.collect(Collectors.toList());
    }
  }

  @Test
  public void testClearAndPut() {
    db.begin();

    var doc1 = ((EntityImpl) db.newEntity(CLASS_NAME));
    doc1.field(FIELD_NAME, 1);

    var doc2 = ((EntityImpl) db.newEntity(CLASS_NAME));
    doc2.field(FIELD_NAME, 1);

    var doc3 = ((EntityImpl) db.newEntity(CLASS_NAME));
    doc3.field(FIELD_NAME, 2);

    final var index =
        db.getMetadata().getIndexManagerInternal().getIndex(db, INDEX_NAME);

    db.commit();

    Assert.assertEquals(3, index.size(db));
    Assert.assertEquals(2, (fetchCollectionFromIndex(index, 1)).size());
    Assert.assertEquals(1, (fetchCollectionFromIndex(index, 2)).size());

    db.begin();

    doc1 = db.bindToSession(doc1);
    doc2 = db.bindToSession(doc2);
    doc3 = db.bindToSession(doc3);

    doc1.delete();
    doc2.delete();
    doc3.delete();

    doc3 = ((EntityImpl) db.newEntity(CLASS_NAME));
    doc3.field(FIELD_NAME, 1);

    var doc = ((EntityImpl) db.newEntity(CLASS_NAME));
    doc.field(FIELD_NAME, 2);

    db.getTransactionInternal().preProcessRecordsAndExecuteCallCallbacks();

    Assert.assertEquals(1, (fetchCollectionFromIndex(index, 1)).size());
    Assert.assertEquals(1, (fetchCollectionFromIndex(index, 2)).size());

    db.rollback();

    Assert.assertNull(db.getTransactionInternal().getIndexChanges(INDEX_NAME));

    Assert.assertEquals(3, index.size(db));
    Assert.assertEquals(2, (fetchCollectionFromIndex(index, 1)).size());
    Assert.assertEquals(1, (fetchCollectionFromIndex(index, 2)).size());
  }
}
