package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SchemaIndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SchemaIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    final Schema schema = session.getMetadata().getSchema();
    final var superTest = schema.createClass("SchemaSharedIndexSuperTest");
    final var test = schema.createClass("SchemaIndexTest", superTest);
    test.createProperty("prop1", PropertyType.DOUBLE);
    test.createProperty("prop2", PropertyType.DOUBLE);
  }

  @AfterMethod
  public void tearDown() throws Exception {
    if (session.getMetadata().getSchema().existsClass("SchemaIndexTest")) {
      session.command("drop class SchemaIndexTest").close();
    }
    session.command("drop class SchemaSharedIndexSuperTest").close();
  }

  @Test
  public void testDropClass() throws Exception {
    session
        .command(
            "CREATE INDEX SchemaSharedIndexCompositeIndex ON SchemaIndexTest (prop1, prop2) UNIQUE")
        .close();
    session.getMetadata().getIndexManagerInternal().reload(session);
    Assert.assertNotNull(
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "SchemaSharedIndexCompositeIndex"));

    session.getMetadata().getSchema().dropClass("SchemaIndexTest");
    session.getMetadata().getIndexManagerInternal().reload(session);

    Assert.assertNull(session.getMetadata().getSchema().getClass("SchemaIndexTest"));
    Assert.assertNotNull(session.getMetadata().getSchema().getClass("SchemaSharedIndexSuperTest"));

    Assert.assertNull(
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "SchemaSharedIndexCompositeIndex"));
  }

  @Test
  public void testDropSuperClass() throws Exception {
    session
        .command(
            "CREATE INDEX SchemaSharedIndexCompositeIndex ON SchemaIndexTest (prop1, prop2) UNIQUE")
        .close();

    try {
      session.getMetadata().getSchema().dropClass("SchemaSharedIndexSuperTest");
      Assert.fail();
    } catch (SchemaException e) {
      Assert.assertTrue(
          e.getMessage()
              .startsWith(
                  "Class 'SchemaSharedIndexSuperTest' cannot be dropped because it has sub"
                      + " classes"));
    }

    Assert.assertNotNull(session.getMetadata().getSchema().getClass("SchemaIndexTest"));
    Assert.assertNotNull(session.getMetadata().getSchema().getClass("SchemaSharedIndexSuperTest"));

    Assert.assertNotNull(
        session
            .getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "SchemaSharedIndexCompositeIndex"));
  }


  @Test
  public void testIndexWithNumberProperties() {
    var oclass = session.getMetadata().getSchema()
        .createClass("SchemaIndexTest_numberclass");
    oclass.createProperty("1", PropertyType.STRING).setMandatory(false);
    oclass.createProperty("2", PropertyType.STRING).setMandatory(false);
    oclass.createIndex("SchemaIndexTest_numberclass_1_2", SchemaClass.INDEX_TYPE.UNIQUE,
        "1",
        "2");

    session.getMetadata().getSchema().dropClass(oclass.getName());
  }
}
