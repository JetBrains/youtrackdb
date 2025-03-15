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

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("sqlSelectIndexReuseTestClass");

    oClass.createProperty("prop1", PropertyType.INTEGER);
    oClass.createProperty("prop2", PropertyType.INTEGER);
    oClass.createProperty("prop3", PropertyType.INTEGER);
    oClass.createProperty("prop4", PropertyType.INTEGER);
    oClass.createProperty("prop5", PropertyType.INTEGER);
    oClass.createProperty("prop6", PropertyType.INTEGER);
    oClass.createProperty("prop7", PropertyType.STRING);
    oClass.createProperty("prop8", PropertyType.INTEGER);
    oClass.createProperty("prop9", PropertyType.INTEGER);

    oClass.createProperty("fEmbeddedMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    oClass.createProperty("fEmbeddedMapTwo", PropertyType.EMBEDDEDMAP,
        PropertyType.INTEGER);

    oClass.createProperty("fLinkMap", PropertyType.LINKMAP);

    oClass.createProperty("fEmbeddedList", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    oClass.createProperty("fEmbeddedListTwo", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);

    oClass.createProperty("fLinkList", PropertyType.LINKLIST);

    oClass.createProperty("fEmbeddedSet", PropertyType.EMBEDDEDSET, PropertyType.INTEGER);
    oClass.createProperty("fEmbeddedSetTwo", PropertyType.EMBEDDEDSET,
        PropertyType.INTEGER);

    oClass.createIndex("indexone", SchemaClass.INDEX_TYPE.UNIQUE, "prop1", "prop2");
    oClass.createIndex("indextwo", SchemaClass.INDEX_TYPE.UNIQUE, "prop3");
    oClass.createIndex("indexthree", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop1", "prop2",
        "prop4");
    oClass.createIndex("indexfour", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop4", "prop1",
        "prop3");
    oClass.createIndex("indexfive", SchemaClass.INDEX_TYPE.NOTUNIQUE, "prop6", "prop1",
        "prop3");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByKey", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMap");
    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByValue",
        SchemaClass.INDEX_TYPE.NOTUNIQUE, "fEmbeddedMap by value");
    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedList", SchemaClass.INDEX_TYPE.NOTUNIQUE, "fEmbeddedList");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByKeyProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMapTwo", "prop8");
    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedMapByValueProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedMapTwo by value", "prop8");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedSetProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedSetTwo", "prop8");
    oClass.createIndex(
        "sqlSelectIndexReuseTestProp9EmbeddedSetProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "prop9",
        "fEmbeddedSetTwo", "prop8");

    oClass.createIndex(
        "sqlSelectIndexReuseTestEmbeddedListTwoProp8",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fEmbeddedListTwo", "prop8");

    final var fullTextIndexStrings = new String[]{
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

    for (var i = 0; i < 10; i++) {
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

      for (var j = 0; j < 10; j++) {
        session.begin();
        final var document = ((EntityImpl) session.newEntity("sqlSelectIndexReuseTestClass"));
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);
        document.setProperty("prop3", i * 10 + j);

        document.setProperty("prop4", i);
        document.setProperty("prop5", i);

        document.setProperty("prop6", j);

        document.setProperty("prop7", fullTextIndexStrings[i]);

        document.setProperty("prop8", j);

        document.setProperty("prop9", j % 2);
        document.setProperty("fEmbeddedMap", embeddedMap);

        document.setProperty("fEmbeddedMapTwo", embeddedMap);

        document.setProperty("fEmbeddedList", embeddedList);
        document.setProperty("fEmbeddedListTwo", embeddedList);

        document.setProperty("fEmbeddedSet", embeddedSet);
        document.setProperty("fEmbeddedSetTwo", embeddedSet);

        session.commit();
      }
    }
  }

  @AfterClass
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.command("drop class sqlSelectIndexReuseTestClass").close();

    super.afterClass();
  }

  @Test
  public void testCompositeSearchEquals() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 2"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchHasChainOperatorsEquals() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1.asInteger() = 1 and"
                        + " prop2 = 2"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchEqualsOneField() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop3 = 18"
                        + " limit 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = ((EntityImpl) session.newEntity());
    document.setProperty("prop1", 1);
    document.setProperty("prop3", 18);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedMapTwo containsKey"
                        + " 'key11'"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop8", 1);
      document.setProperty("fEmbeddedMapTwo", embeddedMap);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop8 = 1 and fEmbeddedMapTwo containsKey 'key11'"))
            .execute(session);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    Assert.assertEquals(result.size(), 1);

    final var document = ((EntityImpl) session.newEntity());
    document.setProperty("prop8", 1);
    document.setProperty("fEmbeddedMap", embeddedMap);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where fEmbeddedMapTwo containsValue 22"))
            .execute(session);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key21", 21);
    embeddedMap.put("key22", 22);
    embeddedMap.put("key23", 23);
    embeddedMap.put("key24", 21);

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop8", i);
      document.setProperty("fEmbeddedMapTwo", embeddedMap);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop8 = 1 and fEmbeddedMapTwo containsValue 22"))
            .execute(session);

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key21", 21);
    embeddedMap.put("key22", 22);
    embeddedMap.put("key23", 23);
    embeddedMap.put("key24", 21);

    Assert.assertEquals(result.size(), 1);

    final var document = ((EntityImpl) session.newEntity());
    document.setProperty("prop8", 1);
    document.setProperty("fEmbeddedMap", embeddedMap);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop8 = 1 and fEmbeddedSetTwo contains 12"))
            .execute(session);

    final Set<Integer> embeddedSet = new HashSet<Integer>();
    embeddedSet.add(10);
    embeddedSet.add(11);
    embeddedSet.add(12);

    Assert.assertEquals(result.size(), 1);

    final var document = ((EntityImpl) session.newEntity());
    document.setProperty("prop8", 1);
    document.setProperty("fEmbeddedSet", embeddedSet);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    var oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass "
                        + "where prop9 = 0 and fEmbeddedSetTwo contains 92 and prop8 > 2"))
            .execute(session);

    final Set<Integer> embeddedSet = new HashSet<Integer>(3);
    embeddedSet.add(90);
    embeddedSet.add(91);
    embeddedSet.add(92);

    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i < 3; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop8", i * 2 + 4);
      document.setProperty("prop9", 0);
      document.setProperty("fEmbeddedSet", embeddedSet);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedListTwo contains 4"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    final List<Integer> embeddedList = new ArrayList<Integer>(3);
    embeddedList.add(3);
    embeddedList.add(4);
    embeddedList.add(5);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop8", i);
      document.setProperty("fEmbeddedListTwo", embeddedList);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where"
                        + " prop8 = 1 and fEmbeddedListTwo contains 4"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final List<Integer> embeddedList = new ArrayList<Integer>(3);
    embeddedList.add(3);
    embeddedList.add(4);
    embeddedList.add(5);

    final var document = ((EntityImpl) session.newEntity());
    document.setProperty("prop8", 1);
    document.setProperty("fEmbeddedListTwo", embeddedList);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 = 1"))
            .execute(session);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", i);
      document.setProperty("prop2", 1);

      Assert.assertEquals(containsDocument(result, document), 1);
    }
  }

  @Test
  public void testCompositeSearchEqualsWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 = ?"))
            .execute(session, 1, 2);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCompositeSearchEqualsOneFieldWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ?"))
            .execute(session, 1);

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 = ?"))
            .execute(session, 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", i);
      document.setProperty("prop2", 1);

      Assert.assertEquals(containsDocument(result, document), 1);
    }
  }

  @Test
  public void testCompositeSearchGT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 > 2"))
            .execute(session);

    Assert.assertEquals(result.size(), 7);

    for (var i = 3; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 > 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 20);

    for (var i = 8; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 > 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 20);

    for (var i = 8; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchGTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 > ?"))
            .execute(session, 1, 2);

    Assert.assertEquals(result.size(), 7);

    for (var i = 3; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 > ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 20);

    for (var i = 8; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 > ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 20);

    for (var i = 8; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchGTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 >= 2"))
            .execute(session);

    Assert.assertEquals(result.size(), 8);

    for (var i = 2; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 >= 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 >= 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchGTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 >= ?"))
            .execute(session, 1, 2);

    Assert.assertEquals(result.size(), 8);

    for (var i = 2; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 >= ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 >= ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 <= 2"))
            .execute(session);

    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 <= 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 <= 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 <= ?"))
            .execute(session, 1, 2);

    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 <= ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 <= ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 < 2"))
            .execute(session);

    Assert.assertEquals(result.size(), 2);

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 < 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 < 7"))
            .execute(session);

    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchLTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 < ?"))
            .execute(session, 1, 2);

    Assert.assertEquals(result.size(), 2);

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 < ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 < ?"))
            .execute(session, 7);

    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchBetween() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between 1"
                        + " and 3"))
            .execute(session);

    Assert.assertEquals(result.size(), 3);

    for (var i = 1; i <= 3; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 between 1 and 3"))
            .execute(session);

    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 between 1 and 3"))
            .execute(session);

    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testCompositeSearchBetweenWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between ?"
                        + " and ?"))
            .execute(session, 1, 3);

    Assert.assertEquals(result.size(), 3);

    for (var i = 1; i <= 3; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 between ? and ?"))
            .execute(session, 1, 3);

    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop2 between ? and ?"))
            .execute(session, 1, 3);

    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsDocument(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  public void testSingleSearchEquals() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 = 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchEqualsWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 = ?"))
            .execute(session, 1);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 > 90"))
            .execute(session);

    Assert.assertEquals(result.size(), 9);

    for (var i = 91; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 > ?"))
            .execute(session, 90);

    Assert.assertEquals(result.size(), 9);

    for (var i = 91; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 >= 90"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    for (var i = 90; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchGTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 >= ?"))
            .execute(session, 90);

    Assert.assertEquals(result.size(), 10);

    for (var i = 90; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 <= 10"))
            .execute(session);

    Assert.assertEquals(result.size(), 11);

    for (var i = 0; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 <= ?"))
            .execute(session, 10);

    Assert.assertEquals(result.size(), 11);

    for (var i = 0; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 < 10"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchLTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 < ?"))
            .execute(session, 10);

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchBetween() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 between 1 and 10"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    for (var i = 1; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchBetweenWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 between ? and ?"))
            .execute(session, 1, 10);

    Assert.assertEquals(result.size(), 10);

    for (var i = 1; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchIN() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 in [0, 5, 10]"))
            .execute(session);

    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 10; i += 5) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testSingleSearchINWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop3 in [?, ?, ?]"))
            .execute(session, 0, 5, 10);

    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 10; i += 5) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsDocument(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  @Test
  public void testMostSpecificOnesProcessedFirst() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                        + " prop3 = 11"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 11);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testTripleSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                        + " prop4 >= 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
  }

  @Test
  public void testTripleSearchLastFieldNotInIndexFirstCase() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                        + " prop5 >= 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop5").intValue(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testTripleSearchLastFieldNotInIndexSecondCase() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 >= 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);
      document.setProperty("prop4", 1);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 = 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);
      document.setProperty("prop4", 1);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop6 <= 1 and prop4 < 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 2);

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop6", i);
      document.setProperty("prop4", 0);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 + 1 = 3"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testEmbeddedMapByKeyIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containskey"
                        + " 'key12'"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testEmbeddedMapBySpecificKeyIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where ( fEmbeddedMap containskey"
                        + " 'key12' ) and ( fEmbeddedMap['key12'] = 12 )"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testEmbeddedMapByValueIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containsvalue"
                        + " 11"))
            .execute(session);

    Assert.assertEquals(result.size(), 10);

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testEmbeddedListIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where fEmbeddedList contains 7"))
            .execute(session);

    final List<Integer> embeddedList = new ArrayList<Integer>(3);
    embeddedList.add(6);
    embeddedList.add(7);
    embeddedList.add(8);

    final var document = ((EntityImpl) session.newEntity());
    document.setProperty("fEmbeddedList", embeddedList);

    Assert.assertEquals(containsDocument(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  @Test
  public void testNotIndexOperatorFirstCase() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2  = 2 and"
                        + " ( prop4 = 3 or prop4 = 1 )"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testIndexUsedOnOrClause() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    if (oldIndexUsage < 0) {
      oldIndexUsage = 0;
    }

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where ( prop1 = 1 and prop2 = 2 )"
                        + " or ( prop4  = 1 and prop6 = 2 )"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop6").intValue(), 2);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 2);
  }

  @Test
  public void testCompositeIndexEmptyResult() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop1 = 1777 and prop2  ="
                        + " 2777"))
            .execute(session);

    Assert.assertEquals(result.size(), 0);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testReuseOfIndexOnSeveralClassesFields() {
    final Schema schema = session.getMetadata().getSchema();
    final var superClass = schema.createClass("sqlSelectIndexReuseTestSuperClass");
    superClass.createProperty("prop0", PropertyType.INTEGER);
    final var oClass = schema.createClass("sqlSelectIndexReuseTestChildClass", superClass);
    oClass.createProperty("prop1", PropertyType.INTEGER);

    oClass.createIndex(
        "sqlSelectIndexReuseTestOnPropertiesFromClassAndSuperclass",
        SchemaClass.INDEX_TYPE.UNIQUE,
        "prop0", "prop1");

    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity("sqlSelectIndexReuseTestChildClass"));
    docOne.setProperty("prop0", 0);
    docOne.setProperty("prop1", 1);

    session.commit();

    session.begin();
    final var docTwo = ((EntityImpl) session.newEntity("sqlSelectIndexReuseTestChildClass"));
    docTwo.setProperty("prop0", 2);
    docTwo.setProperty("prop1", 3);

    session.commit();

    final List<EntityImpl> result =
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestChildClass where prop0 = 0 and prop1 ="
                        + " 1"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  public void testCountFunctionWithNotUniqueIndex() {
    var klazz =
        session.getMetadata().getSchema().getOrCreateClass("CountFunctionWithNotUniqueIndexTest");
    if (!klazz.existsProperty("a")) {
      klazz.createProperty("a", PropertyType.STRING);
      klazz.createIndex("a", "NOTUNIQUE", "a");
    }

    session.begin();
    var e1 = session
        .newInstance("CountFunctionWithNotUniqueIndexTest");
    e1.setProperty("a", "a");
    e1.setProperty("b", "b");

    var e2 = session
        .newInstance("CountFunctionWithNotUniqueIndexTest");
    e2.setProperty("a", "a");
    e1.setProperty("b", "b");

    EntityImpl entity = session
        .newInstance("CountFunctionWithNotUniqueIndexTest");
    entity.setProperty("a", "a");

    EntityImpl entity1 = session
        .newInstance("CountFunctionWithNotUniqueIndexTest");
    entity1.setProperty("a", "c");
    entity.setProperty("b", "c");

    session.commit();

    try (var rs = session.query(
        "select count(*) as count from CountFunctionWithNotUniqueIndexTest where a = 'a' and"
            + " b = 'c'")) {
      if (!remoteDB) {
        Assert.assertEquals(indexesUsed(rs.getExecutionPlan()), 1);
      }
      Assert.assertEquals(rs.findFirst(r -> r.getLong("count")).longValue(), 0L);
    }
  }

  @Test
  public void testCountFunctionWithUniqueIndex() {
    var klazz =
        session.getMetadata().getSchema().getOrCreateClass("CountFunctionWithUniqueIndexTest");
    if (!klazz.existsProperty("a")) {
      klazz.createProperty("a", PropertyType.STRING);
      klazz.createIndex("testCountFunctionWithUniqueIndex", "NOTUNIQUE", "a");
    }

    session.begin();
    EntityImpl entity4 = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity4.setProperty("a", "a");
    entity4.setProperty("b", "c");

    EntityImpl entity3 = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity3.setProperty("a", "a");
    EntityImpl entity2 = entity3;
    entity2.setProperty("b", "c");

    EntityImpl entity1 = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity1.setProperty("a", "a");
    entity1.setProperty("b", "e");

    EntityImpl entity = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity.setProperty("a", "a");
    entity.setProperty("b", "b");
    var doc =
        entity;

    session.commit();

    try (var rs = session.query(
        "select count(*) as count from CountFunctionWithUniqueIndexTest where a = 'a' and b"
            + " = 'c'")) {
      if (!remoteDB) {
        Assert.assertEquals(indexesUsed(rs.getExecutionPlan()), 1);
      }
      Assert.assertEquals(rs.findFirst(r -> r.getLong("count")).longValue(), 2L);
    }

    session.begin();
    session.bindToSession(doc).delete();
    session.commit();
  }

  private static int containsDocument(final List<EntityImpl> docList,
      final EntityImpl document) {
    var count = 0;
    for (final var docItem : docList) {
      var containsAllFields = true;
      for (final var fieldName : document.propertyNames()) {
        if (!document.getProperty(fieldName).equals(docItem.getProperty(fieldName))) {
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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    var oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 = 1 and"
                        + " prop3 in [13, 113]"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    var oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                        + " and prop3 = 13"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    var oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                        + " and prop3 in [13, 15]"))
            .execute(session);

    Assert.assertEquals(result.size(), 2);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertTrue(
        document.<Integer>getProperty("prop3").equals(13) || document.<Integer>getProperty("prop3")
            .equals(15));

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
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    var oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");

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
        session
            .command(
                new SQLSynchQuery<EntityImpl>(
                    "select * from sqlSelectIndexReuseTestClass where prop4 in [1, 2] and prop1 = 1"
                        + " and prop3 = 13"))
            .execute(session);

    Assert.assertEquals(result.size(), 1);

    final var document = result.get(0);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3") < oldcompositeIndexUsed3 + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3") < oldcompositeIndexUsed33 + 1);
  }
}
