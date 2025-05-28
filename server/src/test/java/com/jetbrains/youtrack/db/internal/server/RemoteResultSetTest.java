package com.jetbrains.youtrack.db.internal.server;


import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import java.util.stream.IntStream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RemoteResultSetTest extends BaseServerMemoryDatabase {

  private static int oldPageSize;

  @BeforeClass
  public static void beforeClass() {
    oldPageSize = GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.getValueAsInteger();

    GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(10);
  }

  @AfterClass
  public static void afterClass() {
    GlobalConfiguration.QUERY_REMOTE_RESULTSET_PAGE_SIZE.setValue(oldPageSize);
  }

   // This test passes, but produces stack-overflow on the server when we close the session
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

  // Fails with No query with id '1748464764695_180' found probably expired session
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
