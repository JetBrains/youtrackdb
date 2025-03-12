package com.jetbrains.youtrack.db.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class TransactionQueryIndexTests {

  private YouTrackDB youTrackDB;
  private DatabaseSessionInternal database;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    database =
        (DatabaseSessionInternal)
            youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
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
