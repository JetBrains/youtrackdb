package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class DateBinaryComparatorTest extends DbTestBase {

  private final String dateFormat = "yyyy-MM-dd";
  private final String dateValue = "2017-07-18";

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    initSchema();
  }

  private void initSchema() {
    var testClass = session.getMetadata().getSchema().createClass("Test");
    testClass.createProperty("date", PropertyType.DATE);
    session.begin();
    var document = (EntityImpl) session.newEntity(testClass.getName());

    try {
      document.setProperty("date", new SimpleDateFormat(dateFormat).parse(dateValue));

      session.commit();
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testDateJavaClassPreparedStatement() throws ParseException {
    var str = "SELECT FROM Test WHERE date = :dateParam";
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("dateParam", new SimpleDateFormat(dateFormat).parse(dateValue));

    try (var result = session.query(str, params)) {
      assertEquals(1, result.stream().count());
    }
  }
}
