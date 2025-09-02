package com.jetbrains.youtrackdb.internal.lucene.index;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LuceneFailTest {

  private YouTrackDBImpl ytdb;

  @Before
  public void before() {
    ytdb = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    ytdb.execute("create database tdb memory users (admin identified by 'admpwd' role admin)")
        .close();
  }

  @After
  public void after() {
    ytdb.close();
  }

  @Test
  public void test() {
    try (var session = ytdb.open("tdb", "admin", "admpwd")) {
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
