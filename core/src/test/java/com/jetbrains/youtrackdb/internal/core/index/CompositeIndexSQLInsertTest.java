package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import org.junit.Assert;
import org.junit.Test;

public class CompositeIndexSQLInsertTest extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();
    graph.executeInTx(g ->
        g.addSchemaClass("Book").as("cl").
            addSchemaProperty("author", PropertyType.STRING).select("cl").
            addSchemaProperty("title", PropertyType.STRING).select("cl").
            addSchemaProperty("publicationYears", PropertyType.EMBEDDEDLIST,
                PropertyType.INTEGER).select("cl").
            addClassIndex("books", IndexType.UNIQUE, "author", "title", "publicationYears")
            .select("cl").
            addSchemaProperty("nullKey1", PropertyType.STRING)
            .addPropertyIndex("indexignoresnulls", IndexType.NOT_UNIQUE, IndexBy.BY_VALUE)
    );
  }

  @Test
  public void testCompositeIndexWithRangeAndContains() {
    graph.autoExecuteInTx(
        g -> g.addSchemaClass("CompositeIndexWithRangeAndConditions").as("cl").
            addSchemaProperty("id", PropertyType.INTEGER).select("cl").
            addSchemaProperty("bar", PropertyType.INTEGER).select("cl").
            addSchemaProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING).select("cl").
            addSchemaProperty("name", PropertyType.STRING).
            addClassIndex("CompositeIndexWithRangeAndConditions_id_tags_name", IndexType.NOT_UNIQUE,
                "id", "tags", "name")
    );

    session.begin();
    session.execute(
            "insert into CompositeIndexWithRangeAndConditions set id = 1, tags ="
                + " [\"green\",\"yellow\"] , name = \"Foo\", bar = 1")
        .close();
    session.execute(
            "insert into CompositeIndexWithRangeAndConditions set id = 1, tags ="
                + " [\"blue\",\"black\"] , name = \"Foo\", bar = 14")
        .close();
    session.execute(
            "insert into CompositeIndexWithRangeAndConditions set id = 1, tags = [\"white\"] , name"
                + " = \"Foo\"")
        .close();
    session.execute(
            "insert into CompositeIndexWithRangeAndConditions set id = 1, tags ="
                + " [\"green\",\"yellow\"], name = \"Foo1\", bar = 14")
        .close();
    session.commit();

    session.begin();
    var res =
        session.query("select from CompositeIndexWithRangeAndConditions where id > 0 and bar = 1");

    var count = res.stream().count();
    Assert.assertEquals(1, count);

    var count1 =
        session
            .query(
                "select from CompositeIndexWithRangeAndConditions where id = 1 and tags CONTAINS"
                    + " \"white\"")
            .stream()
            .count();
    Assert.assertEquals(1, count1);

    var count2 =
        session
            .query(
                "select from CompositeIndexWithRangeAndConditions where id > 0 and tags CONTAINS"
                    + " \"white\"")
            .stream()
            .count();
    Assert.assertEquals(1, count2);

    var count3 =
        session
            .query("select from CompositeIndexWithRangeAndConditions where id > 0 and bar = 1")
            .stream()
            .count();

    Assert.assertEquals(1, count3);

    var count4 =
        session
            .query(
                "select from CompositeIndexWithRangeAndConditions where tags CONTAINS \"white\" and"
                    + " id > 0")
            .stream()
            .count();
    Assert.assertEquals(1, count4);
    session.commit();
  }
}
