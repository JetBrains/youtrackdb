package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class SchemaIndexTest extends BaseDBTest {

  @Override
  @BeforeMethod
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("SchemaSharedIndexSuperTest",
            __.createSchemaProperty("prop1", PropertyType.DOUBLE),
            __.createSchemaProperty("prop2", PropertyType.DOUBLE)
        )
    );
  }

  @AfterMethod
  public void tearDown() throws Exception {
    graph.autoExecuteInTx(g ->
        g.schemaClass("SchemaIndexTest").drop().fold().schemaClass("SchemaSharedIndexSuperTest")
            .drop()
    );
  }

  @Test
  public void testDropClass() throws Exception {
    graph.autoExecuteInTx(g -> g.schemaClass("SchemaIndexTest").
        createClassIndex("SchemaSharedIndexCompositeIndex", YTDBSchemaIndex.IndexType.UNIQUE,
            "prop1", "prop2")
    );

    Assert.assertNotNull(
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("SchemaSharedIndexCompositeIndex"));

    graph.autoExecuteInTx(g -> g.schemaClass("SchemaIndexTest").drop());

    Assert.assertNull(
        session.getMetadata().getFastImmutableSchemaSnapshot().getClass("SchemaIndexTest"));
    Assert.assertNotNull(
        session.getMetadata().getFastImmutableSchemaSnapshot()
            .getClass("SchemaSharedIndexSuperTest"));

    Assert.assertNull(
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("SchemaSharedIndexCompositeIndex"));
  }

  @Test
  public void testDropSuperClass() throws Exception {
    graph.autoExecuteInTx(g ->
        g.schemaClass("SchemaIndexTest")
            .createClassIndex("SchemaSharedIndexCompositeIndex", YTDBSchemaIndex.IndexType.UNIQUE,
                "prop1", "prop2")
    );

    try {
      graph.autoExecuteInTx(g -> g.schemaClass("SchemaSharedIndexSuperTest").drop());
      Assert.fail();
    } catch (SchemaException e) {
      Assert.assertTrue(
          e.getMessage()
              .startsWith(
                  "Class 'SchemaSharedIndexSuperTest' cannot be dropped because it has sub"
                      + " classes"));
    }

    Assert.assertNotNull(
        session.getMetadata().getFastImmutableSchemaSnapshot().getClass("SchemaIndexTest"));
    Assert.assertNotNull(
        session.getMetadata().getFastImmutableSchemaSnapshot()
            .getClass("SchemaSharedIndexSuperTest"));

    Assert.assertNotNull(
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getIndex("SchemaSharedIndexCompositeIndex"));
  }


  @Test
  public void testIndexWithNumberProperties() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("SchemaIndexTest_numberclass",
            __.createSchemaProperty("1", PropertyType.STRING).mandatoryAttr(false),
            __.createSchemaProperty("2", PropertyType.STRING).mandatoryAttr(false)
        ).createClassIndex("SchemaIndexTest_numberclass_1_2", IndexType.UNIQUE,
            "1",
            "2")
    );

    graph.autoExecuteInTx(g -> g.schemaClass("SchemaIndexTest_numberclass").drop());
  }
}
