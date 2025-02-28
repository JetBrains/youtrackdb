package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;

public class SQLMethodValuesTest extends DbTestBase {

  private SQLMethodValues function;

  @Before
  public void setup() {
    function = new SQLMethodValues();
  }

  @Test
  public void testWithOResult() {

    var resultInternal = new ResultInternal(session);
    resultInternal.setProperty("name", "Foo");
    resultInternal.setProperty("surname", "Bar");

    var result = function.execute(null, null, null, resultInternal, null);
    //noinspection unchecked
    assertEquals(new HashSet<>(Arrays.asList("Foo", "Bar")), new HashSet<>((List<String>) result));
  }
}
