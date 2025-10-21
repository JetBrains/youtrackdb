package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import org.junit.Assert;
import org.junit.Test;

public class CreateIndexStatementExecutionTest extends BaseMemoryInternalDatabase {
  @Test
  public void testPlain() {
    var className = "testPlain";

    graph.autoExecuteInTx(
        g -> g.createSchemaClass(className).createSchemaProperty("name", PropertyType.STRING));
    Assert.assertNull(
        session.getMetadata().getFastImmutableSchemaSnapshot().getIndex(className + ".name"));

    graph.executeInTx(g ->
        g.schemaClass(className).
            schemaClassProperty("name").
            createPropertyIndex(IndexType.NOT_UNIQUE)
    );

    var idx = session.getMetadata().getFastImmutableSchemaSnapshot()
        .getIndex(className + ".name");
    Assert.assertNotNull(idx);
    Assert.assertFalse(idx.isUnique());
  }

  @Test
  public void testIfNotExists() {
    var className = "testIfNotExists";
    graph.autoExecuteInTx(g ->
        g.createSchemaClass(className).createSchemaProperty("name", PropertyType.STRING)
    );

    Assert.assertNull(
        session.getMetadata().getFastImmutableSchemaSnapshot().getIndex(className + ".name"));

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaIndex(className + ".name").fold().coalesce(
            __.unfold(),
            __.schemaClass(className).schemaClassProperty("name").
                createPropertyIndex(IndexType.NOT_UNIQUE)
        ));

    var idx = session.getMetadata().getFastImmutableSchemaSnapshot()
        .getIndex(className + ".name");
    Assert.assertNotNull(idx);
    Assert.assertFalse(idx.isUnique());

    //noinspection unchecked
    graph.autoExecuteInTx(g ->
        g.schemaIndex(className + ".name").fold().coalesce(
            __.unfold(),
            __.schemaClass(className).schemaClassProperty("name").
                createPropertyIndex(IndexType.NOT_UNIQUE)
        ));
  }
}
