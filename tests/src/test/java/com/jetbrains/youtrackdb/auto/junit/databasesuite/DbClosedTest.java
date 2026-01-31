/*
 * JUnit 4 version of DbClosedTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/DbClosedTest.java
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for database connection handling and closure.
 *
 * <p><b>Suite Position:</b> This is the <b>last</b> test in {@link DatabaseTestSuite} and
 * verifies that database connections can be properly opened and closed after all other tests
 * have completed.</p>
 *
 * <p><b>Standalone Execution:</b> Can be run independently as the {@code @BeforeClass} method
 * initializes the required database.</p>
 *
 * <p>Original: {@code tests/src/test/java/com/jetbrains/youtrackdb/auto/DbClosedTest.java}</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DbClosedTest extends BaseDBTest {

  private static DbClosedTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new DbClosedTest();
    instance.beforeClass();
  }

  /**
   * Original: testRemoteConns (line 45) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbClosedTest.java
   */
  @Test
  public void test01_TestRemoteConns() {
    final var max = GlobalConfiguration.NETWORK_MAX_CONCURRENT_SESSIONS.getValueAsInteger();
    for (var i = 0; i < max * 2; ++i) {
      final DatabaseSession db = acquireSession();
      db.close();
    }
  }

}
