package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class CompositeIndexSQLInsertTest extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();
    Schema schema = session.getMetadata().getSchema();
    var book = schema.createClass("Book");
    book.createProperty(session, "author", PropertyType.STRING);
    book.createProperty(session, "title", PropertyType.STRING);
    book.createProperty(session, "publicationYears", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    book.createIndex(session, "books", "unique", "author", "title", "publicationYears");

    book.createProperty(session, "nullKey1", PropertyType.STRING);
    Map<String, Boolean> indexOptions = new HashMap<>();
    indexOptions.put("ignoreNullValues", true);
    book.createIndex(session,
        "indexignoresnulls", "NOTUNIQUE", null, indexOptions, new String[]{"nullKey1"});
  }

  @Test
  public void testCompositeIndexWithRangeAndContains() {
    final Schema schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CompositeIndexWithRangeAndConditions");
    clazz.createProperty(session, "id", PropertyType.INTEGER);
    clazz.createProperty(session, "bar", PropertyType.INTEGER);
    clazz.createProperty(session, "tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    clazz.createProperty(session, "name", PropertyType.STRING);

    session.command(
            "create index CompositeIndexWithRangeAndConditions_id_tags_name on"
                + " CompositeIndexWithRangeAndConditions (id, tags, name) NOTUNIQUE")
        .close();

    session.begin();
    session.command(
            "insert into CompositeIndexWithRangeAndConditions set id = 1, tags ="
                + " [\"green\",\"yellow\"] , name = \"Foo\", bar = 1")
        .close();
    session.command(
            "insert into CompositeIndexWithRangeAndConditions set id = 1, tags ="
                + " [\"blue\",\"black\"] , name = \"Foo\", bar = 14")
        .close();
    session.command(
            "insert into CompositeIndexWithRangeAndConditions set id = 1, tags = [\"white\"] , name"
                + " = \"Foo\"")
        .close();
    session.command(
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
