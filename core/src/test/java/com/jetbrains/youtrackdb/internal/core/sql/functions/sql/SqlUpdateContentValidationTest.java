package com.jetbrains.youtrackdb.internal.core.sql.functions.sql;

import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class SqlUpdateContentValidationTest extends DbTestBase {

  @Test
  public void testReadOnlyValidation() {
    var clazz = session.getMetadata().getSlowMutableSchema().createClass("Test");
    clazz.createProperty("testNormal", PropertyType.STRING);
    clazz.createProperty("test", PropertyType.STRING).setReadonly(true);

    session.begin();
    var res =
        session.execute(
            "insert into Test content {\"testNormal\":\"hello\",\"test\":\"only read\"} ");
    var id = res.next().getIdentity();
    session.commit();
    try {
      session.begin();
      session.execute("update " + id + " CONTENT {\"testNormal\":\"by\"}").close();
      session.commit();
      Assert.fail("Error on update of a record removing a readonly property");
    } catch (ValidationException val) {

    }
  }
}
