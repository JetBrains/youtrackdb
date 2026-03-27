package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for SQL commands: property creation, linked class/type properties,
 * property removal, and SQL script execution.
 *
 */
class SQLCommandsTest extends BaseDBJUnit5Test {

  @Test
  @Order(1)
  void createProperty() {
    Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("SQLCommandsTest_account")) {
      schema.createClass("SQLCommandsTest_account");
    }

    session.execute("create property SQLCommandsTest_account.timesheet string").close();

    assertEquals(
        PropertyType.STRING,
        session.getMetadata().getSchema()
            .getClass("SQLCommandsTest_account")
            .getProperty("timesheet").getType());
  }

  @Test
  @Order(2)
  void createLinkedClassProperty() {
    session.execute(
        "create property SQLCommandsTest_account.knows embeddedmap"
            + " SQLCommandsTest_account")
        .close();

    assertEquals(
        PropertyType.EMBEDDEDMAP,
        session.getMetadata().getSchema()
            .getClass("SQLCommandsTest_account").getProperty("knows")
            .getType());
    assertEquals(
        session.getMetadata().getSchema().getClass("SQLCommandsTest_account"),
        session
            .getMetadata()
            .getSchema()
            .getClass("SQLCommandsTest_account")
            .getProperty("knows")
            .getLinkedClass());
  }

  @Test
  @Order(3)
  void createLinkedTypeProperty() {
    session.execute(
        "create property SQLCommandsTest_account.tags embeddedlist string").close();

    assertEquals(
        PropertyType.EMBEDDEDLIST,
        session.getMetadata().getSchema()
            .getClass("SQLCommandsTest_account").getProperty("tags")
            .getType());
    assertEquals(
        PropertyType.STRING,
        session.getMetadata().getSchema()
            .getClass("SQLCommandsTest_account").getProperty("tags")
            .getLinkedType());
  }

  @Test
  @Order(4)
  void removeProperty() {
    session.execute("drop property SQLCommandsTest_account.timesheet").close();
    session.execute("drop property SQLCommandsTest_account.tags").close();

    assertFalse(
        session.getMetadata().getSchema().getClass("SQLCommandsTest_account")
            .existsProperty("timesheet"));
    assertFalse(
        session.getMetadata().getSchema().getClass("SQLCommandsTest_account")
            .existsProperty("tags"));
  }

  @Test
  @Order(5)
  void testSQLScript() {
    var cmd = "";
    cmd += "select from OUser limit 1;begin;";
    cmd += "let a = create vertex set script = true;";
    cmd += "let b = select from V limit 1;";
    cmd += "create edge from $a to $b;";
    cmd += "commit;";
    cmd += "return $a;";

    final var tx = session.begin();
    var result = session.computeScript("sql", cmd).findFirst(Result::asEntity);

    assertInstanceOf(EntityImpl.class, tx.load(result));
    EntityImpl identifiable = tx.load(result);
    var activeTx = session.getActiveTransaction();
    EntityImpl entity = activeTx.load(identifiable);
    assertTrue(
        (boolean) entity.getProperty("script"));
    session.commit();
  }
}
