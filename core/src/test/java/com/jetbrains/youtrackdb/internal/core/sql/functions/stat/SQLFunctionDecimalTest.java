package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionDecimal;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

public class SQLFunctionDecimalTest {

  private SQLFunctionDecimal function;

  @Before
  public void setup() {
    function = new SQLFunctionDecimal();
  }

  @Test
  public void testEmpty() {
    var result = function.getResult();
    assertNull(result);
  }

  @Test
  public void testFromInteger() {
    function.execute(null, null, null, new Object[]{12}, null);
    var result = function.getResult();
    assertEquals(result, BigDecimal.valueOf(12));
  }

  @Test
  public void testFromLong() {
    function.execute(null, null, null, new Object[]{1287623847384L}, null);
    var result = function.getResult();
    assertEquals(new BigDecimal(1287623847384L), result);
  }

  @Test
  public void testFromString() {
    var initial = "12324124321234543256758654.76543212345676543254356765434567654";
    function.execute(null, null, null, new Object[]{initial}, null);
    var result = function.getResult();
    assertEquals(new BigDecimal(initial), result);
  }

  @Test
  public void testFromQuery() {
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      ytdb.create("test", DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
      try (var db = ytdb.open("test", "admin", DbTestBase.ADMIN_PASSWORD)) {
        var initial = "12324124321234543256758654.76543212345676543254356765434567654";
        db.executeInTx(transaction -> {
          try (var result = transaction.query("select decimal('" + initial + "') as decimal")) {
            assertEquals(new BigDecimal(initial), result.next().getProperty("decimal"));
          }
        });
      }
      ytdb.drop("test");
    }
  }
}
