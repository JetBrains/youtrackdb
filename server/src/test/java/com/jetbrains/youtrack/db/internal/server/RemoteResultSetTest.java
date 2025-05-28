package com.jetbrains.youtrack.db.internal.server;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class RemoteResultSetTest extends BaseServerMemoryDatabase {

  private static int oldPageSize;
  private static int oldWarnThreshold;

  @BeforeClass
  public static void beforeClass() {
    oldPageSize = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    oldWarnThreshold = GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.getValueAsInteger();
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(oldPageSize);
    GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.setValue(oldWarnThreshold);
  }

  @Test
  public void testResultSetAutoClose() {
    session.command("CREATE CLASS ABC;");
    for (var i = 0; i < 20; i++) {
      session.executeSQLScript("BEGIN;INSERT INTO ABC SET name = 'name" + i + "';COMMIT;");
    }

    for (var i = 0; i < 100_000; i++) {
      session.query("SELECT FROM ABC;");
    }
  }
}
