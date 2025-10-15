package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import org.junit.Assert;
import org.junit.Test;

public class CreateIndexStatementExecutionTest extends BaseMemoryInternalDatabase {
  @Test
  public void testPlain() {
    var className = "testPlain";

    graph.autoExecuteInTx(
        g -> g.addSchemaClass(className).addSchemaProperty("name", PropertyType.STRING));
    Assert.assertNull(
        session.getMetadata().getFastImmutableSchema().getIndex(className + ".name"));

    graph.executeInTx(g ->
        g.schemaClass(className).
            schemaClassProperties("name").
            addPropertyIndex(IndexType.NOT_UNIQUE)
    );

    var idx = session.getMetadata().getFastImmutableSchema()
        .getIndex(className + ".name");
    Assert.assertNotNull(idx);
    Assert.assertFalse(idx.isUnique());
  }

  @Test
  public void testIfNotExists() {
    var className = "testIfNotExists";
    graph.autoExecuteInTx(g ->
        g.addSchemaClass(className).addSchemaProperty("name", PropertyType.STRING)
    );

    Assert.assertNull(
        session.getMetadata().getFastImmutableSchema().getIndex(className + ".name"));

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaIndex(className + ".name").fold().coalesce(
            __.unfold(),
            __.schemaClass(className).schemaClassProperties("name").
                addPropertyIndex(IndexType.NOT_UNIQUE)
        ));

    var idx = session.getMetadata().getFastImmutableSchema()
        .getIndex(className + ".name");
    Assert.assertNotNull(idx);
    Assert.assertFalse(idx.isUnique());

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaIndex(className + ".name").fold().coalesce(
            __.unfold(),
            __.schemaClass(className).schemaClassProperties("name").
                addPropertyIndex(IndexType.NOT_UNIQUE)
        ));
  }
}
