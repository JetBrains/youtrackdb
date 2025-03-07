package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Ignore;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
@Ignore("Rewrite these tests for the new SQL engine")
public class SQLSelectIndexReuseTest extends AbstractIndexReuseTest {

  @Parameters(value = "remote")
  public SQLSelectIndexReuseTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = database.getMetadata().getSchema();
    final SchemaClass oClass = schema.createClass("sqlSelectIndexReuseTestClass");

    oClass.createProperty(database, "prop1", PropertyType.INTEGER);
    oClass.createProperty(database, "prop2", PropertyType.INTEGER);
    oClass.createProperty(database, "prop3", PropertyType.INTEGER);
    oClass.createProperty(database, "prop4", PropertyType.INTEGER);
    oClass.createProperty(database, "prop5", PropertyType.INTEGER);
    oClass.createProperty(database, "prop6", PropertyType.INTEGER);
    oClass.createProperty(database, "prop7", PropertyType.STRING);
    oClass.createProperty(database, "prop8", PropertyType.INTEGER);
    oClass.createProperty(database, "prop9", PropertyType.INTEGER);

    oClass.createProperty(database, "fEmbeddedMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    oClass.createProperty(database, "fEmbeddedMapTwo", PropertyType.EMBEDDEDMAP,
        PropertyType.INTEGER);

    oClass.createProperty(database, "fLinkMap", PropertyType.LINKMAP);

    oClass.createProperty(database, "fEmbeddedList", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    oClass.createProperty(database, "fEmbeddedListTwo", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);

    oClass.createProperty(database, "fLinkList", PropertyType.LINKLIST);

    oClass.createProperty(database, "fEmbeddedSet", PropertyType.EMBEDDEDSET, PropertyType.INTEGER);
    oClass.createProperty(database, "fEmbeddedSetTwo", PropertyType.EMBEDDEDSET,
        PropertyType.INTEGER);

    oClass.createIndex(database, "indexone", SchemaClass.INDEX_TYPE.UNIQUE, "prop1", "prop2");
    oClass.createIndex(database, "indextwo", SchemaClass.INDEX_TYPE.UNIQUE, "prop3");
    oClass.createIndex(database, "indexthree", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop1", "prop2",
        "prop4");
    oClass.createIndex(database, "indexfour", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop4", "prop1",
        "prop3");
    oClass.createIndex(database, "indexfive", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop6", "prop1",
        "prop3");

    oClass.createIndex(database,
        "sqlSelectIndexReuseTestEmbeddedMapByKey", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMap");
    oClass.createIndex(database,
        "sqlSelectIndexReuseTestEmbeddedMapByValue",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "fEmbeddedMap by value");
    oClass.createIndex(database,
        "sqlSelectIndexReuseTestEmbeddedList", SchemaClass.INDEX_TYPE.NOTUNIQUE, "fEmbeddedList");

    oClass.createIndex(database,
        "sqlSelectIndexReuseTestEmbeddedMapByKeyProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMapTwo", "prop8");
    oClass.createIndex(database,
        "sqlSelectIndexReuseTestEmbeddedMapByValueProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMapTwo by value", "prop8");

    oClass.createIndex(database,
        "sqlSelectIndexReuseTestEmbeddedSetProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedSetTwo", "prop8");
    oClass.createIndex(database,
        "sqlSelectIndexReuseTestProp9EmbeddedSetProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "prop9",
        "fEmbeddedSetTwo", "prop8");

    oClass.createIndex(database,
        "sqlSelectIndexReuseTestEmbeddedListTwoProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedListTwo", "prop8");

    final String[] fullTextIndexStrings = {
        "Alice : What is the use of a book, without pictures or conversations?",
        "Rabbit : Oh my ears and whiskers, how late it's getting!",
        "Alice : If it had grown up, it would have made a dreadfully ugly child; but it makes rather"
            + " a handsome pig, I think",
        "The Cat : We're all mad here.",
        "The Hatter : Why is a raven like a writing desk?",
        "The Hatter : Twinkle, twinkle, little bat! How I wonder what you're at.",
        "The Queen : Off with her head!",
        "The Duchess : Tut, tut, child! Everything's got a moral, if only you can find it.",
        "The Duchess : Take care of the sense, and the sounds will take care of themselves.",
        "The King : Begin at the beginning and go on till you come to the end: then stop."
    };

    for (int i = 0; i < 10; i++) {
      final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

      embeddedMap.put("key" + (i * 10 + 1), i * 10 + 1);
      embeddedMap.put("key" + (i * 10 + 2), i * 10 + 2);
      embeddedMap.put("key" + (i * 10 + 3), i * 10 + 3);
      embeddedMap.put("key" + (i * 10 + 4), i * 10 + 1);

      final List<Integer> embeddedList = new ArrayList<Integer>(3);
      embeddedList.add(i * 3);
      embeddedList.add(i * 3 + 1);
      embeddedList.add(i * 3 + 2);

      final Set<Integer> embeddedSet = new HashSet<Integer>();
      embeddedSet.add(i * 10);
      embeddedSet.add(i * 10 + 1);
      embeddedSet.add(i * 10 + 2);

      for (int j = 0; j < 10; j++) {
        database.begin();
        final EntityImpl document = new EntityImpl("sqlSelectIndexReuseTestClass");
        document.field("prop1", i);
        document.field("prop2", j);
        document.field("prop3", i * 10 + j);

        document.field("prop4", i);
        document.field("prop5", i);

        document.field("prop6", j);

        document.field("prop7", fullTextIndexStrings[i]);

        document.field("prop8", j);

        document.field("prop9", j % 2);
        document.field("fEmbeddedMap", embeddedMap);

        document.field("fEmbeddedMapTwo", embeddedMap);

        document.field("fEmbeddedList", embeddedList);
        document.field("fEmbeddedListTwo", embeddedList);

        document.field("fEmbeddedSet", embeddedSet);
        document.field("fEmbeddedSetTwo", embeddedSet);

        document.save();
        database.commit();
      }
    }
  }

  @AfterClass
  public void afterClass() throws Exception {
    if (database.isClosed()) {
      database = createSessionInstance();
    }

    database.command("drop class sqlSelectIndexReuseTestClass").close();

    super.afterClass();
  }

  @Test
  public void testCompositeSearchEquals() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 2"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchHasChainOperatorsEquals() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1.asInteger() = 1 and"
                        + " prop2 = 2"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchEqualsOneField() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    if (oldcompositeIndexUsed21 == -1) {
      oldcompositeIndexUsed21 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  @Test
  public void testCompositeSearchEqualsOneFieldWithLimit() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    if (oldcompositeIndexUsed21 == -1) {
      oldcompositeIndexUsed21 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop3 = 18"
                        + " limit 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = new EntityImpl();
    document.field("prop1", 1);
    document.field("prop3", 18);

    Assert.assertEquals(containsDocument(result, document), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  @Test
  public void testCompositeSearchEqualsOneFieldMapIndexByKey() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }

    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    if (oldcompositeIndexUsed21 == -1) {
      oldcompositeIndexUsed21 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedMapTwo containsKey"
                        + " 'key11'"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop8", 1);
      document.field("fEmbeddedMapTwo", embeddedMap);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  @Test
  public void testCompositeSearchEqualsMapIndexByKey() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    if (oldcompositeIndexUsed22 == -1) {
      oldcompositeIndexUsed22 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop8 = 1 and fEmbeddedMapTwo containsKey 'key11'"))
            .execute(database);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = new EntityImpl();
    document.field("prop8", 1);
    document.field("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  @Test
  public void testCompositeSearchEqualsOneFieldMapIndexByValue() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }
    if (oldcompositeIndexUsed21 == -1) {
      oldcompositeIndexUsed21 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where fEmbeddedMapTwo containsValue 22"))
            .execute(database);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key21", 21);
    embeddedMap.put("key22", 22);
    embeddedMap.put("key23", 23);
    embeddedMap.put("key24", 21);

    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop8", i);
      document.field("fEmbeddedMapTwo", embeddedMap);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  @Test
  public void testCompositeSearchEqualsMapIndexByValue() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }
    if (oldcompositeIndexUsed22 == -1) {
      oldcompositeIndexUsed22 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop8 = 1 and fEmbeddedMapTwo containsValue 22"))
            .execute(database);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key21", 21);
    embeddedMap.put("key22", 22);
    embeddedMap.put("key23", 23);
    embeddedMap.put("key24", 21);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = new EntityImpl();
    document.field("prop8", 1);
    document.field("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  @Test
  public void testCompositeSearchEqualsEmbeddedSetIndex() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    if (oldcompositeIndexUsed22 == -1) {
      oldcompositeIndexUsed22 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop8 = 1 and fEmbeddedSetTwo contains 12"))
            .execute(database);

    final Set<Integer> embeddedSet = new HashSet<Integer>();
    embeddedSet.add(10);
    embeddedSet.add(11);
    embeddedSet.add(12);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = new EntityImpl();
    document.field("prop8", 1);
    document.field("fEmbeddedSet", embeddedSet);

    Assert.assertEquals(containsDocument(result, document), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  @Test
  public void testCompositeSearchEqualsEmbeddedSetInMiddleIndex() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    long oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }

    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }

    if (oldcompositeIndexUsed33 == -1) {
      oldcompositeIndexUsed33 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop9 = 0 and fEmbeddedSetTwo contains 92 and prop8 > 2"))
            .execute(database);

    final Set<Integer> embeddedSet = new HashSet<Integer>(3);
    embeddedSet.add(90);
    embeddedSet.add(91);
    embeddedSet.add(92);

    Assert.assertEquals(result.size(), 3);

    for (int i = 0; i < 3; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop8", i * 2 + 4);
      document.field("prop9", 0);
      document.field("fEmbeddedSet", embeddedSet);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3"), oldcompositeIndexUsed33 + 1);
  }

  @Test
  public void testCompositeSearchEqualsOneFieldEmbeddedListIndex() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }

    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    if (oldcompositeIndexUsed21 == -1) {
      oldcompositeIndexUsed21 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedListTwo contains 4"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    final List<Integer> embeddedList = new ArrayList<Integer>(3);
    embeddedList.add(3);
    embeddedList.add(4);
    embeddedList.add(5);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop8", i);
      document.field("fEmbeddedListTwo", embeddedList);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  @Test
  public void testCompositeSearchEqualsEmbeddedListIndex() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    long oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }
    if (oldcompositeIndexUsed22 == -1) {
      oldcompositeIndexUsed22 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where"
                        + " prop8 = 1 and fEmbeddedListTwo contains 4"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final List<Integer> embeddedList = new ArrayList<Integer>(3);
    embeddedList.add(3);
    embeddedList.add(4);
    embeddedList.add(5);

    final EntityImpl document = new EntityImpl();
    document.field("prop8", 1);
    document.field("fEmbeddedListTwo", embeddedList);

    Assert.assertEquals(containsDocument(result, document), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  @Test
  public void testNoCompositeSearchEquals() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 = 1"))
            .execute(database);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", i);
      document.field("prop2", 1);

      Assert.assertEquals(containsDocument(result, document), 1);
    }
  }

  @Test
  public void testCompositeSearchEqualsWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 = ?"))
            .execute(database, 1, 2);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchEqualsOneFieldWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ?"))
            .execute(database, 1);

    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testNoCompositeSearchEqualsWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 = ?"))
            .execute(database, 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", i);
      document.field("prop2", 1);

      Assert.assertEquals(containsDocument(result, document), 1);
    }
  }

  @Test
  public void testCompositeSearchGT() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 > 2"))
            .execute(database);

    Assert.assertEquals(result.size(), 7);

    for (int i = 3; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTOneField() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 > 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 20);

    for (int i = 8; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTOneFieldNoSearch() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 > 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 20);

    for (int i = 8; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchGTWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 > ?"))
            .execute(database, 1, 2);

    Assert.assertEquals(result.size(), 7);

    for (int i = 3; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTOneFieldWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 > ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 20);

    for (int i = 8; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTOneFieldNoSearchWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 > ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 20);

    for (int i = 8; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchGTQ() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 >= 2"))
            .execute(database);

    Assert.assertEquals(result.size(), 8);

    for (int i = 2; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTQOneField() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 >= 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 30);

    for (int i = 7; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTQOneFieldNoSearch() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 >= 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 30);

    for (int i = 7; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchGTQWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 >= ?"))
            .execute(database, 1, 2);

    Assert.assertEquals(result.size(), 8);

    for (int i = 2; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTQOneFieldWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 >= ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 30);

    for (int i = 7; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchGTQOneFieldNoSearchWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 >= ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 30);

    for (int i = 7; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLTQ() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 <= 2"))
            .execute(database);

    Assert.assertEquals(result.size(), 3);

    for (int i = 0; i <= 2; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTQOneField() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 <= 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 80);

    for (int i = 0; i <= 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTQOneFieldNoSearch() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 <= 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 80);

    for (int i = 0; i <= 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLTQWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 <= ?"))
            .execute(database, 1, 2);

    Assert.assertEquals(result.size(), 3);

    for (int i = 0; i <= 2; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTQOneFieldWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 <= ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 80);

    for (int i = 0; i <= 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTQOneFieldNoSearchWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 <= ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 80);

    for (int i = 0; i <= 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLT() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 < 2"))
            .execute(database);

    Assert.assertEquals(result.size(), 2);

    for (int i = 0; i < 2; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTOneField() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 < 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 70);

    for (int i = 0; i < 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTOneFieldNoSearch() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 < 7"))
            .execute(database);

    Assert.assertEquals(result.size(), 70);

    for (int i = 0; i < 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLTWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 < ?"))
            .execute(database, 1, 2);

    Assert.assertEquals(result.size(), 2);

    for (int i = 0; i < 2; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTOneFieldWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 < ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 70);

    for (int i = 0; i < 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchLTOneFieldNoSearchWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 < ?"))
            .execute(database, 7);

    Assert.assertEquals(result.size(), 70);

    for (int i = 0; i < 7; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchBetween() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between 1"
                        + " and 3"))
            .execute(database);

    Assert.assertEquals(result.size(), 3);

    for (int i = 1; i <= 3; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchBetweenOneField() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 between 1 and 3"))
            .execute(database);

    Assert.assertEquals(result.size(), 30);

    for (int i = 1; i <= 3; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchBetweenOneFieldNoSearch() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 between 1 and 3"))
            .execute(database);

    Assert.assertEquals(result.size(), 30);

    for (int i = 1; i <= 3; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchBetweenWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between ?"
                        + " and ?"))
            .execute(database, 1, 3);

    Assert.assertEquals(result.size(), 3);

    for (int i = 1; i <= 3; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchBetweenOneFieldWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 between ? and ?"))
            .execute(database, 1, 3);

    Assert.assertEquals(result.size(), 30);

    for (int i = 1; i <= 3; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", i);
        document.field("prop2", j);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchBetweenOneFieldNoSearchWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 between ? and ?"))
            .execute(database, 1, 3);

    Assert.assertEquals(result.size(), 30);

    for (int i = 1; i <= 3; i++) {
      for (int j = 0; j < 10; j++) {
        final EntityImpl document = new EntityImpl();
        document.field("prop1", j);
        document.field("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testSingleSearchEquals() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 = 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop3").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchEqualsWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 = ?"))
            .execute(database, 1);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop3").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGT() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 > 90"))
            .execute(database);

    Assert.assertEquals(result.size(), 9);

    for (int i = 91; i < 100; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGTWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 > ?"))
            .execute(database, 90);

    Assert.assertEquals(result.size(), 9);

    for (int i = 91; i < 100; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGTQ() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 >= 90"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    for (int i = 90; i < 100; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGTQWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 >= ?"))
            .execute(database, 90);

    Assert.assertEquals(result.size(), 10);

    for (int i = 90; i < 100; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLTQ() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 <= 10"))
            .execute(database);

    Assert.assertEquals(result.size(), 11);

    for (int i = 0; i <= 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLTQWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 <= ?"))
            .execute(database, 10);

    Assert.assertEquals(result.size(), 11);

    for (int i = 0; i <= 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLT() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 < 10"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLTWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 < ?"))
            .execute(database, 10);

    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchBetween() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 between 1 and 10"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    for (int i = 1; i <= 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchBetweenWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 between ? and ?"))
            .execute(database, 1, 10);

    Assert.assertEquals(result.size(), 10);

    for (int i = 1; i <= 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchIN() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 in [0, 5, 10]"))
            .execute(database);

    Assert.assertEquals(result.size(), 3);

    for (int i = 0; i <= 10; i += 5) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchINWithArgs() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 in [?, ?, ?]"))
            .execute(database, 0, 5, 10);

    Assert.assertEquals(result.size(), 3);

    for (int i = 0; i <= 10; i += 5) {
      final EntityImpl document = new EntityImpl();
      document.field("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testMostSpecificOnesProcessedFirst() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                        + " prop3 = 11"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop3").intValue(), 11);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testTripleSearch() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                        + " prop4 >= 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop4").intValue(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
  }

  @Test
  public void testTripleSearchLastFieldNotInIndexFirstCase() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                        + " prop5 >= 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop5").intValue(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testTripleSearchLastFieldNotInIndexSecondCase() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 >= 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);
      document.field("prop4", 1);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testTripleSearchLastFieldInIndex() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 = 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    for (int i = 0; i < 10; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop1", 1);
      document.field("prop2", i);
      document.field("prop4", 1);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
  }

  @Test
  public void testTripleSearchLastFieldsCanNotBeMerged() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop6 <= 1 and prop4 < 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 2);

    for (int i = 0; i < 2; i++) {
      final EntityImpl document = new EntityImpl();
      document.field("prop6", i);
      document.field("prop4", 0);

      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
  }

  @Test
  public void testLastFieldNotCompatibleOperator() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 + 1 = 3"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testEmbeddedMapByKeyIndexReuse() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containskey"
                        + " 'key12'"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    final EntityImpl document = new EntityImpl();

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.field("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testEmbeddedMapBySpecificKeyIndexReuse() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where ( fEmbeddedMap containskey"
                        + " 'key12' ) and ( fEmbeddedMap['key12'] = 12 )"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    final EntityImpl document = new EntityImpl();

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.field("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testEmbeddedMapByValueIndexReuse() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containsvalue"
                        + " 11"))
            .execute(database);

    Assert.assertEquals(result.size(), 10);

    final EntityImpl document = new EntityImpl();

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.field("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testEmbeddedListIndexReuse() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedList contains 7"))
            .execute(database);

    final List<Integer> embeddedList = new ArrayList<Integer>(3);
    embeddedList.add(6);
    embeddedList.add(7);
    embeddedList.add(8);

    final EntityImpl document = new EntityImpl();
    document.field("fEmbeddedList", embeddedList);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testNotIndexOperatorFirstCase() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2  = 2 and"
                        + " ( prop4 = 3 or prop4 = 1 )"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 2);
    Assert.assertEquals(document.<Integer>field("prop4").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testIndexUsedOnOrClause() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    if (oldIndexUsage < 0) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where ( prop1 = 1 and prop2 = 2 )"
                        + " or ( prop4  = 1 and prop6 = 2 )"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop2").intValue(), 2);
    Assert.assertEquals(document.<Integer>field("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop6").intValue(), 2);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 2);
  }

  @Test
  public void testCompositeIndexEmptyResult() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed2 == -1) {
      oldcompositeIndexUsed2 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1777 and prop2  ="
                        + " 2777"))
            .execute(database);

    Assert.assertEquals(result.size(), 0);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testReuseOfIndexOnSeveralClassesFields() {
    final Schema schema = database.getMetadata().getSchema();
    final SchemaClass superClass = schema.createClass("sqlSelectIndexReuseTestSuperClass");
    superClass.createProperty(database, "prop0", PropertyType.INTEGER);
    final SchemaClass oClass = schema.createClass("sqlSelectIndexReuseTestChildClass", superClass);
    oClass.createProperty(database, "prop1", PropertyType.INTEGER);

    oClass.createIndex(database,
        "sqlSelectIndexReuseTestOnPropertiesFromClassAndSuperclass",
        SchemaClass.INDEX_TYPE.UNIQUE,
        "prop0", "prop1");

    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    database.begin();
    final EntityImpl docOne = new EntityImpl("sqlSelectIndexReuseTestChildClass");
    docOne.field("prop0", 0);
    docOne.field("prop1", 1);
    docOne.save();
    database.commit();

    database.begin();
    final EntityImpl docTwo = new EntityImpl("sqlSelectIndexReuseTestChildClass");
    docTwo.field("prop0", 2);
    docTwo.field("prop1", 3);
    docTwo.save();
    database.commit();

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestChildClass where prop0 = 0 and prop1 ="
                        + " 1"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCountFunctionWithNotUniqueIndex() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    SchemaClass klazz =
        database.getMetadata().getSchema().getOrCreateClass("CountFunctionWithNotUniqueIndexTest");
    if (!klazz.existsProperty("a")) {
      klazz.createProperty(database, "a", PropertyType.STRING);
      klazz.createIndex(database, "a", "NOTUNIQUE", "a");
    }

    database.begin();
    database
        .<EntityImpl>newInstance("CountFunctionWithNotUniqueIndexTest")
        .field("a", "a")
        .field("b", "b")
        .save();
    database
        .<EntityImpl>newInstance("CountFunctionWithNotUniqueIndexTest")
        .field("a", "a")
        .field("b", "b")
        .save();
    database
        .<EntityImpl>newInstance("CountFunctionWithNotUniqueIndexTest")
        .field("a", "a")
        .field("b", "e")
        .save();
    database
        .<EntityImpl>newInstance("CountFunctionWithNotUniqueIndexTest")
        .field("a", "c")
        .field("b", "c")
        .save();
    database.commit();

    try (var rs = database.query(
        "select count(*) as count from CountFunctionWithNotUniqueIndexTest where a = 'a' and"
            + " b = 'c'")) {
      if (!remoteDB) {
        Assert.assertEquals(indexesUsed(rs.getExecutionPlan().orElseThrow()), 1);
      }
      Assert.assertEquals(rs.findFirst().<Long>getProperty("count"), 0L);
    }
  }

  @Test
  public void testCountFunctionWithUniqueIndex() {
    SchemaClass klazz =
        database.getMetadata().getSchema().getOrCreateClass("CountFunctionWithUniqueIndexTest");
    if (!klazz.existsProperty("a")) {
      klazz.createProperty(database, "a", PropertyType.STRING);
      klazz.createIndex(database, "testCountFunctionWithUniqueIndex", "NOTUNIQUE", "a");
    }

    database.begin();
    database
        .<EntityImpl>newInstance("CountFunctionWithUniqueIndexTest")
        .field("a", "a")
        .field("b", "c")
        .save();
    database
        .<EntityImpl>newInstance("CountFunctionWithUniqueIndexTest")
        .field("a", "a")
        .field("b", "c")
        .save();
    database
        .<EntityImpl>newInstance("CountFunctionWithUniqueIndexTest")
        .field("a", "a")
        .field("b", "e")
        .save();
    EntityImpl doc =
        database
            .<EntityImpl>newInstance("CountFunctionWithUniqueIndexTest")
            .field("a", "a")
            .field("b", "b");
    doc.save();
    database.commit();

    try (var rs = database.query(
        "select count(*) as count from CountFunctionWithUniqueIndexTest where a = 'a' and b"
            + " = 'c'")) {
      if (!remoteDB) {
        Assert.assertEquals(indexesUsed(rs.getExecutionPlan().orElseThrow()), 1);
      }
      Assert.assertEquals(rs.findFirst().<Long>getProperty("count"), 2L);
    }

    database.begin();
    database.bindToSession(doc).delete();
    database.commit();
  }

  private static int containsDocument(final List<EntityImpl> docList,
      final EntityImpl document) {
    int count = 0;
    for (final EntityImpl docItem : docList) {
      boolean containsAllFields = true;
      for (final String fieldName : document.fieldNames()) {
        if (!document.field(fieldName).equals(docItem.field(fieldName))) {
          containsAllFields = false;
          break;
        }
      }
      if (containsAllFields) {
        count++;
      }
    }
    return count;
  }

  @Test
  public void testCompositeSearchIn1() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    long oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }
    if (oldcompositeIndexUsed33 == -1) {
      oldcompositeIndexUsed33 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 = 1 and"
                        + " prop3 in [13, 113]"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop3").intValue(), 13);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3"), oldcompositeIndexUsed33 + 1);
  }

  @Test
  public void testCompositeSearchIn2() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    long oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }
    if (oldcompositeIndexUsed33 == -1) {
      oldcompositeIndexUsed33 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                        + " and prop3 = 13"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop3").intValue(), 13);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3") < oldcompositeIndexUsed33 + 1);
  }

  @Test
  public void testCompositeSearchIn3() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    long oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }
    if (oldcompositeIndexUsed33 == -1) {
      oldcompositeIndexUsed33 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                        + " and prop3 in [13, 15]"))
            .execute(database);

    Assert.assertEquals(result.size(), 2);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertTrue(
        document.<Integer>field("prop3").equals(13) || document.<Integer>field("prop3").equals(15));

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3") < oldcompositeIndexUsed33 + 1);
  }

  @Test
  public void testCompositeSearchIn4() {
    long oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    long oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    long oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    long oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }
    if (oldcompositeIndexUsed == -1) {
      oldcompositeIndexUsed = 0;
    }
    if (oldcompositeIndexUsed3 == -1) {
      oldcompositeIndexUsed3 = 0;
    }
    if (oldcompositeIndexUsed33 == -1) {
      oldcompositeIndexUsed33 = 0;
    }

    final List<EntityImpl> result =
        database
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 in [1, 2] and prop1 = 1"
                        + " and prop3 = 13"))
            .execute(database);

    Assert.assertEquals(result.size(), 1);

    final EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Integer>field("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>field("prop3").intValue(), 13);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3") < oldcompositeIndexUsed3 + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3") < oldcompositeIndexUsed33 + 1);
  }
}
