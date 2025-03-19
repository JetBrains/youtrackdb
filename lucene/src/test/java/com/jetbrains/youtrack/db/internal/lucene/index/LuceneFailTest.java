package com.jetbrains.youtrack.db.internal.lucene.index;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LuceneFailTest {

  private YouTrackDB odb;

  @Before
  public void before() {
    odb = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
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
      session.execute("create property V.text string").close();
      session.execute("create index lucene_index on V(text) FULLTEXT ENGINE LUCENE").close();
      try {
        session.query("select from V where search_class('*this breaks') = true").close();
      } catch (Exception e) {
      }
      session.query("select from V ").close();
    }
  }
}
