package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransactionQueryIndexTests {

  private YouTrackDBAbstract<?, ?> youTrackDB;
  private DatabaseSessionInternal database;
  private YTDBGraph graph;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    database =
        (DatabaseSessionInternal)
            youTrackDB.open("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    graph = youTrackDB.openGraph("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
  }

  @Test
  public void test() {

    graph.autoExecuteInTx(
        g -> g.addSchemaClass("test").addSchemaProperty("test", PropertyType.STRING)
            .addPropertyIndex(IndexType.NOT_UNIQUE));

    database.begin();
    var doc = database.newInstance("test");
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
    graph.autoExecuteInTx(g -> g.addSchemaClass("Test2",
            __.addSchemaProperty("foo", PropertyType.STRING),
            __.addSchemaProperty("bar", PropertyType.STRING)
        ).addClassIndex("Test2.foo_bar", IndexType.NOT_UNIQUE, "foo", "bar")
    );

    database.begin();
    var doc = database.newInstance("Test2");
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
    graph.close();
    database.close();
    youTrackDB.close();
  }
}
