package com.jetbrains.youtrack.db.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.sql.functions.math.SQLFunctionDecimal;
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
    assertEquals(result, new BigDecimal(1287623847384L));
  }

  @Test
  public void testFromString() {
    var initial = "12324124321234543256758654.76543212345676543254356765434567654";
    function.execute(null, null, null, new Object[]{initial}, null);
    var result = function.getResult();
    assertEquals(result, new BigDecimal(initial));
  }

  @Test
  public void testFromQuery() {
    try (var ctx = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var db = ctx.open("test", "admin", "adminpwd")) {
        var initial = "12324124321234543256758654.76543212345676543254356765434567654";
        db.executeInTx(transaction -> {
          try (var result = transaction.query("select decimal('" + initial + "')")) {
            assertEquals(result.next().getProperty("decimal"), new BigDecimal(initial));
          }
        });
      }
      ctx.drop("test");
    }
  }
}
