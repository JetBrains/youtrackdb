package com.jetbrains.youtrackdb.internal.core.db.record;

import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DBRecordLazyListTest {

  private YouTrackDBImpl youTrackDb;
  private DatabaseSessionEmbedded db;

  @Before
  public void init() throws Exception {
    youTrackDb = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDb.create(DBRecordLazyListTest.class.getSimpleName(), DatabaseType.MEMORY,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    db = youTrackDb.open(
        DBRecordLazyListTest.class.getSimpleName(),
        "admin",
        DbTestBase.ADMIN_PASSWORD);
  }

  @Test
  public void test() {
    var schema = db.getMetadata().getSchema();
    var mainClass = schema.createClass("MainClass");
    mainClass.createProperty("name", PropertyType.STRING);
    var itemsProp = mainClass.createProperty("items", PropertyType.LINKLIST);
    var itemClass = schema.createClass("ItemClass");
    itemClass.createProperty("name", PropertyType.STRING);
    itemsProp.setLinkedClass(itemClass);

    db.begin();
    var doc1 = ((EntityImpl) db.newEntity(itemClass));
    doc1.setProperty("name", "Doc1");

    var doc2 = ((EntityImpl) db.newEntity(itemClass));
    doc2.setProperty("name", "Doc2");

    var doc3 = ((EntityImpl) db.newEntity(itemClass));
    doc3.setProperty("name", "Doc3");

    var mainDoc = ((EntityImpl) db.newEntity(mainClass));
    mainDoc.setProperty("name", "Main Doc");
    mainDoc.newLinkList("items").addAll(Arrays.asList(doc1, doc2, doc3));
    db.commit();

    db.begin();
    var activeTx = db.getActiveTransaction();
    mainDoc = activeTx.load(mainDoc);
    Collection<EntityImpl> origItems = mainDoc.getProperty("items");
    var it = origItems.iterator();
    assertNotNull(it.next());
    assertNotNull(it.next());

    List<EntityImpl> items = new ArrayList<EntityImpl>(origItems);
    assertNotNull(items.get(0));
    assertNotNull(items.get(1));
    assertNotNull(items.get(2));
    db.rollback();
  }

  @After
  public void close() {
    if (db != null) {
      db.close();
    }
    if (youTrackDb != null && db != null) {
      youTrackDb.drop(DBRecordLazyListTest.class.getSimpleName());
    }
  }
}
