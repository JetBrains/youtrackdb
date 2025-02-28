package com.jetbrains.youtrack.db.internal.core.sql.functions.sql;

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class SqlUpdateContentValidationTest extends DbTestBase {

  @Test
  public void testReadOnlyValidation() {
    var clazz = session.getMetadata().getSchema().createClass("Test");
    clazz.createProperty(session, "testNormal", PropertyType.STRING);
    clazz.createProperty(session, "test", PropertyType.STRING).setReadonly(session, true);

    session.begin();
    var res =
        session.command(
            "insert into Test content {\"testNormal\":\"hello\",\"test\":\"only read\"} ");
    var id = res.next().getIdentity();
    session.commit();
    try {
      session.begin();
      session.command("update " + id + " CONTENT {\"testNormal\":\"by\"}").close();
      session.commit();
      Assert.fail("Error on update of a record removing a readonly property");
    } catch (ValidationException val) {

    }
  }
}
