package com.jetbrains.youtrackdb.internal.server;


import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import java.util.stream.IntStream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RemoteResultSetTest extends BaseServerMemoryDatabase {

  private static int oldPageSize;
  private static int oldWarnThreshold;

  @BeforeClass
  public static void beforeClass() {
    oldPageSize = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();
    oldWarnThreshold = GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(10);
    GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD.setValue(0);
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

    for (var i = 0; i < 10_000; i++) {
      session.query("SELECT FROM ABC;");
    }
  }

  @Test
  public void testResultSetAutoCloseDisabled() {
    session.command("CREATE CLASS XYZ;");
    for (var i = 0; i < 20; i++) {
      session.executeSQLScript("BEGIN;INSERT INTO XYZ SET name = 'name" + i + "';COMMIT;");
    }

    // creating a list of non-closed ResultSets
    final var results = IntStream.range(0, 1000)
        .mapToObj(i ->
            session.query("SELECT FROM XYZ;")
        )
        .toList();

    // checking that these result sets are still active and were not closed on the server.
    for (final var result : results) {
      assertThat(result.toList()).hasSize(20);
      result.close();
    }
  }
}
