package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class SQLMethodKeysTest extends DbTestBase {

  private SQLMethodKeys function;

  @Before
  public void setup() {
    function = new SQLMethodKeys();
  }

  @Test
  public void testWithOResult() {

    var resultInternal = new ResultInternal(session);
    resultInternal.setProperty("name", "Foo");
    resultInternal.setProperty("surname", "Bar");

    var result = function.execute(null, null, null, resultInternal, null);
    //noinspection unchecked
    assertEquals(new HashSet<>(Arrays.asList("name", "surname")),
        new HashSet<>((List<String>) result));
  }
}
