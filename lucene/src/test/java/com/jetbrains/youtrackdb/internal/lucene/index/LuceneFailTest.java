package com.jetbrains.youtrackdb.internal.lucene.index;

import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LuceneFailTest {

  private YouTrackDB odb;

  @Before
  public void before() {
    odb = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig());
    odb.execute("create database tdb memory users (admin identified by 'admpwd' role admin)")
        .close();
  }

  @After
  public void after() {
    odb.close();
  }

  @Test
  public void test() {
    try (var session = odb.open("tdb", "admin", "admpwd")) {
      session.computeScript("sql", "create property V.text string").close();
      session.computeScript("sql", "create index lucene_index on V(text) FULLTEXT ENGINE LUCENE")
          .close();

      session.executeInTx(transaction -> {
        try {
          transaction.query("select from V where search_class('*this breaks') = true").close();
        } catch (Exception e) {
        }
      });
      session.executeInTx(transaction -> {
        transaction.query("select from V ").close();
      });
    }
  }
}
