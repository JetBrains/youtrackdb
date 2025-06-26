package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class TransactionTest {

  private YouTrackDB youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB =
        (YouTrackDBImpl) CreateDatabaseUtil.createDatabase("test",
            DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    db = (DatabaseSessionEmbedded) youTrackDB.open("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @Test
  public void test() {
    var tx = db.begin();
    var v = tx.newVertex("V");
    v.setProperty("name", "Foo");
    tx.commit();

    tx = db.begin();
    v = tx.load(v);
    v.setProperty("name", "Bar");
    tx.rollback();

    tx = db.begin();
    v = tx.load(v);
    Assert.assertEquals("Foo", v.getProperty("name"));
    tx.commit();
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }
}
