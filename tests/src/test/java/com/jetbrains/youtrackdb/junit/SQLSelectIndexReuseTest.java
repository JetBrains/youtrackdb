package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

@Disabled("Disabled in original TestNG suite via @Ignore")
public class SQLSelectIndexReuseTest extends AbstractIndexReuseTest {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

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

    final var fullTextIndexStrings = new String[] {
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

        document.newEmbeddedMap("fEmbeddedMap", embeddedMap);
        document.newEmbeddedMap("fEmbeddedMapTwo", embeddedMap);

        document.newEmbeddedList("fEmbeddedList", embeddedList);
        document.newEmbeddedList("fEmbeddedListTwo", embeddedList);

        document.newEmbeddedSet("fEmbeddedSet", embeddedSet);
        document.newEmbeddedSet("fEmbeddedSetTwo", embeddedSet);

        session.commit();
      }
    }
  }

  @AfterAll
  @Override
  void afterAll() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.execute("drop class sqlSelectIndexReuseTestClass").close();

    super.afterAll();
  }

  @Test
  @Order(1)
  void testCompositeSearchEquals() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    try (var resultSet = session
        .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 2")) {
      assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

      final var resultList = resultSet.toList();

      assertEquals(1, resultList.size());

      final var result = resultList.getFirst();
      assertEquals(1, result.<Integer>getProperty("prop1").intValue());
      assertEquals(2, result.<Integer>getProperty("prop2").intValue());
    }
    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);

  }

  @Test
  @Order(2)
  void testCompositeSearchHasChainOperatorsEquals() {
    try (var resultSet = session.query(
        "select * from sqlSelectIndexReuseTestClass where prop1.asInteger() = 1 and"
            + " prop2 = 2")) {
      assertEquals(0, indexesUsed(resultSet.getExecutionPlan()));
      var resultList = resultSet.toList();

      assertEquals(1, resultList.size());

      final var document = resultList.getFirst();
      assertEquals(1, document.<Integer>getProperty("prop1").intValue());
      assertEquals(2, document.<Integer>getProperty("prop2").intValue());
    }
  }

  @Test
  @Order(3)
  void testCompositeSearchEqualsOneField() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed21 == -1) {
    //      oldcompositeIndexUsed21 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (var resultSet = transaction
          .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1")) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

        var resultList = resultSet.toList();
        assertEquals(10, resultList.size());

        for (var i = 0; i < 10; i++) {
          final var entity = session.newEntity();
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          assertEquals(1, containsEntity(resultList, entity));
        }
      }
      //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
      //    assertEquals(
      //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
      //    assertEquals(
      //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
      //    assertEquals(
      //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
    });
  }

  @Test
  @Order(4)
  void testCompositeSearchEqualsOneFieldWithLimit() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");

    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed21 == -1) {
    //      oldcompositeIndexUsed21 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (var resultSet = transaction.query(
          "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop3 = 18"
              + " limit 1")) {

        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
        var resultList = resultSet.toList();
        assertEquals(1, resultList.size());

        final var entity = transaction.newEntity();
        entity.setProperty("prop1", 1);
        entity.setProperty("prop3", 18);

        assertEquals(1, containsEntity(resultList, entity));

        //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
        //    assertEquals(
        //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
        //    assertEquals(
        //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
        //    assertEquals(
        //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
      }
    });
  }

  @Test
  @Order(5)
  void testCompositeSearchEqualsOneFieldMapIndexByKey() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed21 == -1) {
    //      oldcompositeIndexUsed21 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (var resultSet = transaction.query(
          "select * from sqlSelectIndexReuseTestClass where fEmbeddedMapTwo containsKey"
              + " 'key11'")) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
        var resultList = resultSet.toList();
        assertEquals(10, resultList.size());

        final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        for (var i = 0; i < 10; i++) {
          final var entity = session.newEntity();
          entity.setProperty("prop8", 1);
          entity.setProperty("fEmbeddedMapTwo", embeddedMap);

          assertEquals(1, containsEntity(resultList, entity));
        }

        //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
        //    assertEquals(
        //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
        //    assertEquals(
        //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
        //    assertEquals(
        //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
      }
    });
  }

  @Test
  @Order(6)
  void testCompositeSearchEqualsMapIndexByKey() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed22 == -1) {
    //      oldcompositeIndexUsed22 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (var resultSet = transaction.query(
          "select * from sqlSelectIndexReuseTestClass "
              + "where prop8 = 1 and fEmbeddedMapTwo containsKey 'key11'")) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        var resultList = resultSet.toList();
        assertEquals(1, resultList.size());

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.newEmbeddedMap("fEmbeddedMap", embeddedMap);

        assertEquals(1, containsEntity(resultList, entity));
        //
        //        assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
        //        assertEquals(
        //            profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
        //        assertEquals(
        //            profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
        //        assertEquals(
        //            profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"),
        //            oldcompositeIndexUsed22 + 1);
      }
    });
  }

  @Test
  @Order(7)
  void testCompositeSearchEqualsOneFieldMapIndexByValue() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //    if (oldcompositeIndexUsed21 == -1) {
    //      oldcompositeIndexUsed21 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass "
                      + "where fEmbeddedMapTwo containsValue 22")) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

        final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

        embeddedMap.put("key21", 21);
        embeddedMap.put("key22", 22);
        embeddedMap.put("key23", 23);
        embeddedMap.put("key24", 21);

        var resultList = resultSet.toList();
        assertEquals(10, resultList.size());

        for (var i = 0; i < 10; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop8", i);
          document.setProperty("fEmbeddedMapTwo", embeddedMap);

          assertEquals(1, containsEntity(resultList, document));
        }
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  @Test
  @Order(8)
  void testCompositeSearchEqualsMapIndexByValue() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //    if (oldcompositeIndexUsed22 == -1) {
    //      oldcompositeIndexUsed22 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (var resultSet = session.query(
          "select * from sqlSelectIndexReuseTestClass "
              + "where prop8 = 1 and fEmbeddedMapTwo containsValue 22")) {

        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
        final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

        embeddedMap.put("key21", 21);
        embeddedMap.put("key22", 22);
        embeddedMap.put("key23", 23);
        embeddedMap.put("key24", 21);

        var resultList = resultSet.toList();
        assertEquals(1, resultList.size());

        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", 1);
        entity.setProperty("fEmbeddedMap", embeddedMap);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  @Test
  @Order(9)
  void testCompositeSearchEqualsEmbeddedSetIndex() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed22 == -1) {
    //      oldcompositeIndexUsed22 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass "
                      + "where prop8 = 1 and fEmbeddedSetTwo contains 12")) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
        final Set<Integer> embeddedSet = new HashSet<Integer>();
        embeddedSet.add(10);
        embeddedSet.add(11);
        embeddedSet.add(12);

        var resultList = resultSet.toList();
        assertEquals(1, resultList.size());

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.setProperty("fEmbeddedSet", embeddedSet);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  @Test
  @Order(10)
  void testCompositeSearchEqualsEmbeddedSetInMiddleIndex() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed3 = profiler.getCounter("db.demo.query.compositeIndexUsed.3");
    //    var oldcompositeIndexUsed33 = profiler.getCounter("db.demo.query.compositeIndexUsed.3.3");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed3 == -1) {
    //      oldcompositeIndexUsed3 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed33 == -1) {
    //      oldcompositeIndexUsed33 = 0;
    //    }

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass "
                      + "where prop9 = 0 and fEmbeddedSetTwo contains 92 and prop8 > 2");
      assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

      final Set<Integer> embeddedSet = new HashSet<Integer>(3);
      embeddedSet.add(90);
      embeddedSet.add(91);
      embeddedSet.add(92);

      var resultList = resultSet.toList();
      assertEquals(3, resultList.size());

      for (var i = 0; i < 3; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", (i << 1) + 4);
        entity.setProperty("prop9", 0);
        entity.setProperty("fEmbeddedSet", embeddedSet);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3"), oldcompositeIndexUsed33 + 1);
  }

  @Test
  @Order(11)
  void testCompositeSearchEqualsOneFieldEmbeddedListIndex() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed21 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.1");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //
    //    if (oldcompositeIndexUsed21 == -1) {
    //      oldcompositeIndexUsed21 = 0;
    //    }

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where fEmbeddedListTwo contains 4");

      assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
      var resultList = resultSet.toList();
      assertEquals(10, resultList.size());

      final List<Integer> embeddedList = new ArrayList<Integer>(3);
      embeddedList.add(3);
      embeddedList.add(4);
      embeddedList.add(5);

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", i);
        entity.setProperty("fEmbeddedListTwo", embeddedList);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  @Test
  @Order(12)
  void testCompositeSearchEqualsEmbeddedListIndex() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //    var oldcompositeIndexUsed22 = profiler.getCounter("db.demo.query.compositeIndexUsed.2.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }
    //    if (oldcompositeIndexUsed22 == -1) {
    //      oldcompositeIndexUsed22 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where"
                      + " prop8 = 1 and fEmbeddedListTwo contains 4")) {

        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

        var resultList = resultSet.toList();
        assertEquals(1, resultList.size());

        final List<Integer> embeddedList = new ArrayList<Integer>(3);
        embeddedList.add(3);
        embeddedList.add(4);
        embeddedList.add(5);

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.setProperty("fEmbeddedListTwo", embeddedList);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });
    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  @Test
  @Order(13)
  void testNoCompositeSearchEquals() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 = 1");

      assertEquals(0, indexesUsed(resultSet.getExecutionPlan()));
      var resultList = resultSet.toList();
      assertEquals(10, resultList.size());

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop1", i);
        entity.setProperty("prop2", 1);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });
  }

  @Test
  @Order(14)
  void testCompositeSearchEqualsWithArgs() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 = ?", 1,
                  2)) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

        var resultList = resultSet.toList();
        assertEquals(1, resultList.size());

        final var document = resultList.getFirst();
        assertEquals(1, document.<Integer>getProperty("prop1").intValue());
        assertEquals(2, document.<Integer>getProperty("prop2").intValue());
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  @Order(15)
  void testCompositeSearchEqualsOneFieldWithArgs() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ?", 1);
      assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

      var resultList = resultSet.toList();
      assertEquals(10, resultList.size());

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop1", 1);
        entity.setProperty("prop2", i);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  @Order(16)
  void testNoCompositeSearchEqualsWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 = ?", 1)) {
        assertEquals(0, indexesUsed(resultSet.getExecutionPlan()));
        var resultList = resultSet.toList();
        assertEquals(10, resultList.size());

        for (var i = 0; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", i);
          entity.setProperty("prop2", 1);

          assertEquals(1, containsEntity(resultList, entity));
        }
      }
    });
  }

  @Test
  @Order(17)
  void testCompositeSearchGT() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 > 2")) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
        var resultList = resultSet.toList();
        assertEquals(7, resultList.size());

        for (var i = 3; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          assertEquals(1, containsEntity(resultList, entity));
        }
      }
      //      assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
      //      assertEquals(
      //          profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
      //      assertEquals(
      //          profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    });
  }

  @Test
  @Order(18)
  void testCompositeSearchGTOneField() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 > 7")) {

        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));

        var resultList = resultSet.toList();
        assertEquals(20, resultList.size());

        for (var i = 8; i < 10; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = session.newEntity();
            entity.setProperty("prop1", i);
            entity.setProperty("prop2", j);

            assertEquals(1, containsEntity(resultList, entity));
          }
        }
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  @Order(19)
  void testCompositeSearchGTOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 > 7").toList();

    assertEquals(20, result.size());

    for (var i = 8; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(20)
  void testCompositeSearchGTWithArgs() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 > ?", 1,
                  2);
      assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
      var resultList = resultSet.toList();
      assertEquals(7, resultList.size());

      for (var i = 3; i < 10; i++) {
        final var entity = session.newEntity();
        entity.setProperty("prop1", 1);
        entity.setProperty("prop2", i);

        assertEquals(1, containsEntity(resultList, entity));
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  @Order(21)
  void testCompositeSearchGTOneFieldWithArgs() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 > ?", 7);

      assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
      var resultList = resultSet.toList();
      assertEquals(20, resultList.size());

      for (var i = 8; i < 10; i++) {
        for (var j = 0; j < 10; j++) {
          final var entity = session.newEntity();

          entity.setProperty("prop1", i);
          entity.setProperty("prop2", j);

          assertEquals(1, containsEntity(resultList, entity));
        }
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  @Order(22)
  void testCompositeSearchGTOneFieldNoSearchWithArgs() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 > ?", 7);
      assertEquals(0, indexesUsed(resultSet.getExecutionPlan()));
      var resultList = resultSet.toList();

      assertEquals(20, resultList.size());

      for (var i = 8; i < 10; i++) {
        for (var j = 0; j < 10; j++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", j);
          entity.setProperty("prop2", i);

          assertEquals(1, containsEntity(resultList, entity));
        }
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  @Test
  @Order(23)
  void testCompositeSearchGTQ() {
    //    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    //    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    //    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");
    //
    //    if (oldIndexUsage == -1) {
    //      oldIndexUsage = 0;
    //    }
    //    if (oldcompositeIndexUsed == -1) {
    //      oldcompositeIndexUsed = 0;
    //    }
    //    if (oldcompositeIndexUsed2 == -1) {
    //      oldcompositeIndexUsed2 = 0;
    //    }

    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 >= 2")) {
        assertEquals(1, indexesUsed(resultSet.getExecutionPlan()));
        var resultList = resultSet.toList();
        assertEquals(8, resultList.size());

        for (var i = 2; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          assertEquals(1, containsEntity(resultList, entity));
        }
      }
    });

    //    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    //    assertEquals(
    //        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  @Test
  @Order(24)
  void testCompositeSearchGTQOneField() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 >= 7").toList();
    assertEquals(30, result.size());

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(25)
  void testCompositeSearchGTQOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 >= 7").toList();
    assertEquals(30, result.size());

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(26)
  void testCompositeSearchGTQWithArgs() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 >= ?",
                1, 2)
            .toList();
    assertEquals(8, result.size());

    for (var i = 2; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(27)
  void testCompositeSearchGTQOneFieldWithArgs() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 >= ?", 7).toList();
    assertEquals(30, result.size());

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(28)
  void testCompositeSearchGTQOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 >= ?", 7).toList();
    assertEquals(30, result.size());

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(29)
  void testCompositeSearchLTQ() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 <= 2")
            .toList();
    assertEquals(3, result.size());

    for (var i = 0; i <= 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(30)
  void testCompositeSearchLTQOneField() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 <= 7").toList();
    assertEquals(80, result.size());

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(31)
  void testCompositeSearchLTQOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 <= 7").toList();
    assertEquals(80, result.size());

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(32)
  void testCompositeSearchLTQWithArgs() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 <= ?", 1,
                2)
            .toList();

    assertEquals(3, result.size());

    for (var i = 0; i <= 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(33)
  void testCompositeSearchLTQOneFieldWithArgs() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 <= ?", 7).toList();
    assertEquals(80, result.size());

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(34)
  void testCompositeSearchLTQOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 <= ?", 7).toList();

    assertEquals(80, result.size());

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(35)
  void testCompositeSearchLT() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 < 2")
            .toList();

    assertEquals(2, result.size());

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(36)
  void testCompositeSearchLTOneField() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 < 7").toList();
    assertEquals(70, result.size());

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(37)
  void testCompositeSearchLTOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 < 7").toList();
    assertEquals(70, result.size());

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(38)
  void testCompositeSearchLTWithArgs() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 < ?", 1,
                2)
            .toList();
    assertEquals(2, result.size());

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(39)
  void testCompositeSearchLTOneFieldWithArgs() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 < ?", 7).toList();
    assertEquals(70, result.size());

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(40)
  void testCompositeSearchLTOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 < ?", 7).toList();
    assertEquals(70, result.size());

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(41)
  void testCompositeSearchBetween() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between 1"
                    + " and 3")
            .toList();
    assertEquals(3, result.size());

    for (var i = 1; i <= 3; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(42)
  void testCompositeSearchBetweenOneField() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 between 1 and 3")
            .toList();
    assertEquals(30, result.size());

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(43)
  void testCompositeSearchBetweenOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 between 1 and 3")
            .toList();
    assertEquals(30, result.size());

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(44)
  void testCompositeSearchBetweenWithArgs() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between ?"
                    + " and ?",
                1, 3)
            .toList();

    assertEquals(3, result.size());

    for (var i = 1; i <= 3; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(45)
  void testCompositeSearchBetweenOneFieldWithArgs() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 between ? and ?", 1, 3)
            .toList();
    assertEquals(30, result.size());

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(46)
  void testCompositeSearchBetweenOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 between ? and ?", 1, 3)
            .toList();
    assertEquals(30, result.size());

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        assertEquals(1, containsEntity(result, document));
      }
    }

    assertEquals(oldIndexUsage, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(47)
  void testSingleSearchEquals() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 = 1").toList();
    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop3").intValue());
    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(48)
  void testSingleSearchEqualsWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 = ?", 1).toList();
    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop3").intValue());
    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(49)
  void testSingleSearchGT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 > 90").toList();
    assertEquals(9, result.size());

    for (var i = 91; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(50)
  void testSingleSearchGTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 > ?", 90).toList();
    assertEquals(9, result.size());

    for (var i = 91; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(51)
  void testSingleSearchGTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 >= 90").toList();
    assertEquals(10, result.size());

    for (var i = 90; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(52)
  void testSingleSearchGTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 >= ?", 90).toList();
    assertEquals(10, result.size());

    for (var i = 90; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(53)
  void testSingleSearchLTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 <= 10").toList();
    assertEquals(11, result.size());

    for (var i = 0; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(54)
  void testSingleSearchLTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 <= ?", 10).toList();
    assertEquals(11, result.size());

    for (var i = 0; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(55)
  void testSingleSearchLT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 < 10").toList();
    assertEquals(10, result.size());

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(56)
  void testSingleSearchLTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 < ?", 10).toList();
    assertEquals(10, result.size());

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(57)
  void testSingleSearchBetween() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 between 1 and 10")
            .toList();
    assertEquals(10, result.size());

    for (var i = 1; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(58)
  void testSingleSearchBetweenWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 between ? and ?", 1,
                10)
            .toList();

    assertEquals(10, result.size());

    for (var i = 1; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(59)
  void testSingleSearchIN() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 in [0, 5, 10]")
            .toList();
    assertEquals(3, result.size());

    for (var i = 0; i <= 10; i += 5) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(60)
  void testSingleSearchINWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 in [?, ?, ?]", 0, 5,
                10)
            .toList();

    assertEquals(3, result.size());

    for (var i = 0; i <= 10; i += 5) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
  }

  @Test
  @Order(61)
  void testMostSpecificOnesProcessedFirst() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                    + " prop3 = 11")
            .toList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(1, document.<Integer>getProperty("prop2").intValue());
    assertEquals(11, document.<Integer>getProperty("prop3").intValue());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(62)
  void testTripleSearch() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                    + " prop4 >= 1")
            .toList();
    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(1, document.<Integer>getProperty("prop2").intValue());
    assertEquals(1, document.<Integer>getProperty("prop4").intValue());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed3 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"));
  }

  @Test
  @Order(63)
  void testTripleSearchLastFieldNotInIndexFirstCase() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                    + " prop5 >= 1")
            .toList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(1, document.<Integer>getProperty("prop2").intValue());
    assertEquals(1, document.<Integer>getProperty("prop5").intValue());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(64)
  void testTripleSearchLastFieldNotInIndexSecondCase() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 >= 1")
            .toList();

    assertEquals(10, result.size());

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);
      document.setProperty("prop4", 1);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(65)
  void testTripleSearchLastFieldInIndex() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 = 1")
            .toList();

    assertEquals(10, result.size());

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);
      document.setProperty("prop4", 1);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed3 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"));
  }

  @Test
  @Order(66)
  void testTripleSearchLastFieldsCanNotBeMerged() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop6 <= 1 and prop4 < 1")
            .toList();

    assertEquals(2, result.size());

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop6", i);
      document.setProperty("prop4", 0);

      assertEquals(1, containsEntity(result, document));
    }

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed3 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"));
  }

  @Test
  @Order(67)
  void testLastFieldNotCompatibleOperator() {
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

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 + 1 = 3")
            .toList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(2, document.<Integer>getProperty("prop2").intValue());
    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(68)
  void testEmbeddedMapByKeyIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containskey"
                    + " 'key12'")
            .toList();

    assertEquals(10, result.size());

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    assertEquals(10, containsEntity(result, document));

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2, profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(69)
  void testEmbeddedMapBySpecificKeyIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where ( fEmbeddedMap containskey"
                    + " 'key12' ) and ( fEmbeddedMap['key12'] = 12 )")
            .toList();
    assertEquals(10, result.size());

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    assertEquals(10, containsEntity(result, document));

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2, profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(70)
  void testEmbeddedMapByValueIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containsvalue"
                    + " 11")
            .toList();
    assertEquals(10, result.size());

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    assertEquals(10, containsEntity(result, document));

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2, profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(71)
  void testEmbeddedListIndexReuse() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where fEmbeddedList contains 7")
            .toList();

    final List<Integer> embeddedList = new ArrayList<Integer>(3);
    embeddedList.add(6);
    embeddedList.add(7);
    embeddedList.add(8);

    final var document = ((EntityImpl) session.newEntity());
    document.setProperty("fEmbeddedList", embeddedList);

    assertEquals(10, containsEntity(result, document));

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed, profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2, profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(72)
  void testNotIndexOperatorFirstCase() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2  = 2 and"
                    + " ( prop4 = 3 or prop4 = 1 )")
            .toList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(2, document.<Integer>getProperty("prop2").intValue());
    assertEquals(1, document.<Integer>getProperty("prop4").intValue());
    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(73)
  void testIndexUsedOnOrClause() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    if (oldIndexUsage < 0) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where ( prop1 = 1 and prop2 = 2 )"
                    + " or ( prop4  = 1 and prop6 = 2 )")
            .toList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(2, document.<Integer>getProperty("prop2").intValue());
    assertEquals(1, document.<Integer>getProperty("prop4").intValue());
    assertEquals(2, document.<Integer>getProperty("prop6").intValue());

    assertEquals(oldIndexUsage + 2, profiler.getCounter("db.demo.query.indexUsed"));
  }

  @Test
  @Order(74)
  void testCompositeIndexEmptyResult() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop1 = 1777 and prop2  ="
                    + " 2777")
            .toList();

    assertEquals(0, result.size());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(75)
  void testReuseOfIndexOnSeveralClassesFields() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestChildClass where prop0 = 0 and prop1 ="
                    + " 1")
            .toList();

    assertEquals(1, result.size());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed2 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"));
  }

  @Test
  @Order(76)
  void testCountFunctionWithNotUniqueIndex() {
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

    var entity = session
        .newInstance("CountFunctionWithNotUniqueIndexTest");
    entity.setProperty("a", "a");

    var entity1 = session
        .newInstance("CountFunctionWithNotUniqueIndexTest");
    entity1.setProperty("a", "c");
    entity.setProperty("b", "c");

    session.commit();

    try (var rs = session.query(
        "select count(*) as count from CountFunctionWithNotUniqueIndexTest where a = 'a' and"
            + " b = 'c'")) {
      assertEquals(1, indexesUsed(rs.getExecutionPlan()));
      assertEquals(0L, rs.findFirst(r -> r.getLong("count")).longValue());
    }
  }

  @Test
  @Order(77)
  void testCountFunctionWithUniqueIndex() {
    var klazz =
        session.getMetadata().getSchema().getOrCreateClass("CountFunctionWithUniqueIndexTest");
    if (!klazz.existsProperty("a")) {
      klazz.createProperty("a", PropertyType.STRING);
      klazz.createIndex("testCountFunctionWithUniqueIndex", "NOTUNIQUE", "a");
    }

    session.begin();
    var entity4 = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity4.setProperty("a", "a");
    entity4.setProperty("b", "c");

    var entity3 = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity3.setProperty("a", "a");
    var entity2 = entity3;
    entity2.setProperty("b", "c");

    var entity1 = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity1.setProperty("a", "a");
    entity1.setProperty("b", "e");

    var entity = session
        .newInstance("CountFunctionWithUniqueIndexTest");
    entity.setProperty("a", "a");
    entity.setProperty("b", "b");
    var doc =
        entity;

    session.commit();

    try (var rs = session.query(
        "select count(*) as count from CountFunctionWithUniqueIndexTest where a = 'a' and b"
            + " = 'c'")) {
      assertEquals(1, indexesUsed(rs.getExecutionPlan()));
      assertEquals(2L, rs.findFirst(r -> r.getLong("count")).longValue());
    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  private static int containsEntity(final List<Result> resultList, final Entity entity) {
    var count = 0;
    for (final var result : resultList) {
      var containsAllFields = true;
      for (final var fieldName : entity.getPropertyNames()) {
        if (!entity.getProperty(fieldName).equals(result.getProperty(fieldName))) {
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
  @Order(78)
  void testCompositeSearchIn1() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 = 1 and"
                    + " prop3 in [13, 113]")
            .toDetachedList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop4").intValue());
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(13, document.<Integer>getProperty("prop3").intValue());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed3 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"));
    assertEquals(oldcompositeIndexUsed33 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3"));
  }

  @Test
  @Order(79)
  void testCompositeSearchIn2() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                    + " and prop3 = 13")
            .toDetachedList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop4").intValue());
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(13, document.<Integer>getProperty("prop3").intValue());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed3 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"));
    assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3")
            < oldcompositeIndexUsed33 + 1);
  }

  @Test
  @Order(80)
  void testCompositeSearchIn3() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                    + " and prop3 in [13, 15]")
            .toDetachedList();

    assertEquals(2, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop4").intValue());
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertTrue(
        document.<Integer>getProperty("prop3").equals(13) || document.<Integer>getProperty(
            "prop3")
            .equals(15));

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertEquals(oldcompositeIndexUsed3 + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"));
    assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3")
            < oldcompositeIndexUsed33 + 1);
  }

  @Test
  @Order(81)
  void testCompositeSearchIn4() {
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

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where prop4 in [1, 2] and prop1 = 1"
                    + " and prop3 = 13")
            .toDetachedList();

    assertEquals(1, result.size());

    final var document = result.getFirst();
    assertEquals(1, document.<Integer>getProperty("prop4").intValue());
    assertEquals(1, document.<Integer>getProperty("prop1").intValue());
    assertEquals(13, document.<Integer>getProperty("prop3").intValue());

    assertEquals(oldIndexUsage + 1, profiler.getCounter("db.demo.query.indexUsed"));
    assertEquals(oldcompositeIndexUsed + 1,
        profiler.getCounter("db.demo.query.compositeIndexUsed"));
    assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3") < oldcompositeIndexUsed3 + 1);
    assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3")
            < oldcompositeIndexUsed33 + 1);
  }
}
