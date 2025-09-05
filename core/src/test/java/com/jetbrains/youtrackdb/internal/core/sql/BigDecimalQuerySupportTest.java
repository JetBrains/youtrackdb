package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.math.BigDecimal;
import org.junit.Test;

public class BigDecimalQuerySupportTest extends DbTestBase {

  @Test
  public void testDecimalPrecision() {
    session.execute("CREATE Class Test").close();
    session.execute("CREATE Property Test.salary DECIMAL").close();
    session.begin();
    session.execute(
            "INSERT INTO Test set salary = ?", new BigDecimal("179999999999.99999999999999999999"))
        .close();
    session.commit();
    try (var result = session.query("SELECT * FROM Test")) {
      BigDecimal salary = result.next().getProperty("salary");
      assertEquals(new BigDecimal("179999999999.99999999999999999999"), salary);
    }
  }
}
