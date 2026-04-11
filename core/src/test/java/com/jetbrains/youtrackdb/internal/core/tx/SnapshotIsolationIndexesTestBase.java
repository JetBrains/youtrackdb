package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import org.junit.After;
import org.junit.Before;

/**
 * Shared base class for snapshot-isolation index tests. Provides in-memory database lifecycle
 * (create/open/close) and a Gremlin traversal source helper.
 */
public abstract class SnapshotIsolationIndexesTestBase {

  protected YouTrackDBImpl youTrackDB;
  protected DatabaseSessionEmbedded db;

  @Before
  public void setUp() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void tearDown() {
    db.close();
    youTrackDB.close();
  }

  protected YTDBGraphTraversalSource openGraph() {
    return youTrackDB.openTraversal("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }
}
