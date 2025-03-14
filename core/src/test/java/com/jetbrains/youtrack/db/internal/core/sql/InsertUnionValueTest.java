package com.jetbrains.youtrack.db.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InsertUnionValueTest {

  private YouTrackDB youTrackDB;

  @Before
  public void before() {
    youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig());
    youTrackDB
        .execute(
            "create database ? memory users (admin identified by 'admpwd' role admin)",
            InsertUnionValueTest.class.getSimpleName())
        .close();
  }

  @After
  public void after() {
    youTrackDB.close();
  }

  @Test
  public void testUnionInsert() {
    try (var session =
        youTrackDB.open(InsertUnionValueTest.class.getSimpleName(), "admin", "admpwd")) {
      session.command("create class example extends V").close();
      session.command("create property example.metadata EMBEDDEDMAP").close();
      session
          .execute(
              "SQL",
              """
                    begin; \
                    let $example = create vertex example;
                    let $a = {"aKey":"aValue"};
                    let $b = {"anotherKey":"anotherValue"};
                    let $u = unionAll($a, $b);\s
                  
                    /* both of the following throw the exception and require to restart the\
                   server*/
                    update $example set metadata["something"] = $u;
                    update $example set metadata.something = $u;\
                    commit;\
                  """)
          .close();
      var entries =
          session.query("select expand(metadata.something) from example").stream().count();
      assertEquals(2, entries);
    }
  }
}
