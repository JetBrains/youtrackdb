/*
 * JUnit 4 version of DbClosedTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/DbClosedTest.java
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of DbClosedTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbClosedTest.java
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
