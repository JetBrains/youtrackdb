package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionQueryIndexTests {

  private static final String ADMIN_PASSWORD = "adminpwd";
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionInternal database;

  @Before
  public void before() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    if (youTrackDB.exists("test")) {
      youTrackDB.drop("test");
    }

    youTrackDB.create("test", DatabaseType.MEMORY, "admin", ADMIN_PASSWORD, "admin");
    database = youTrackDB.open("test", "admin", ADMIN_PASSWORD);
  }

  @Test
  public void test() {
    var clazz = database.createClass("test");
    var prop = clazz.createProperty("test", PropertyType.STRING);
    prop.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    database.begin();
    EntityImpl doc = database.newInstance("test");
    doc.setProperty("test", "abcdefg");
    var res = database.query("select from Test where test='abcdefg' ");

    assertEquals(1L, res.stream().count());
    res.close();
    res = database.query("select from Test where test='aaaaa' ");

    assertEquals(0L, res.stream().count());
    res.close();
  }

  @Test
  public void test2() {
    var clazz = database.createClass("Test2");
    clazz.createProperty("foo", PropertyType.STRING);
    clazz.createProperty("bar", PropertyType.STRING);
    clazz.createIndex("Test2.foo_bar", SchemaClass.INDEX_TYPE.NOTUNIQUE, "foo", "bar");

    database.begin();
    EntityImpl doc = database.newInstance("Test2");
    doc.setProperty("foo", "abcdefg");
    doc.setProperty("bar", "abcdefg");
    var res = database.query("select from Test2 where foo='abcdefg' and bar = 'abcdefg' ");

    assertEquals(1L, res.stream().count());
    res.close();
    res = database.query("select from Test2 where foo='aaaaa' and bar = 'aaa'");

    assertEquals(0L, res.stream().count());
    res.close();
  }

  @After
  public void after() {
    database.close();
    youTrackDB.close();
  }
}
