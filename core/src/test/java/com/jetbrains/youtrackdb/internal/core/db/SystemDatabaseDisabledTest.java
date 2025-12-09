package com.jetbrains.youtrackdb.internal.core.db;


import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SystemDatabaseDisabledTest extends DbTestBase {

  @BeforeClass
  public static void beforeClass() {
    GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.setValue(false);
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.setValue(true);
  }

  @Test
  public void testDisabledSystemDatabase() {

    try {
      session.getSharedContext().getYouTrackDB().getSystemDatabase().executeWithDB(
          s -> s.computeInTx(tx -> tx.query("select count(*) from V"))
      );
      fail("Should fail with DatabaseException: System database is disabled");
    } catch (DatabaseException e) {
      if (!e.getMessage().contains("System database is disabled")) {
        throw e;
      }
    }
  }
}