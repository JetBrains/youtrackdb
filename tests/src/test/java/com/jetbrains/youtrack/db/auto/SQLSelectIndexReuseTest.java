package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLSelectIndexReuseTest extends AbstractIndexReuseTest {

  @Override
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
      final Map<String, Integer> embeddedMap = new HashMap<>();

      embeddedMap.put("key" + (i * 10 + 1), i * 10 + 1);
      embeddedMap.put("key" + (i * 10 + 2), i * 10 + 2);
      embeddedMap.put("key" + (i * 10 + 3), i * 10 + 3);
      embeddedMap.put("key" + (i * 10 + 4), i * 10 + 1);

      final List<Integer> embeddedList = new ArrayList<>(3);
      embeddedList.add(i * 3);
      embeddedList.add(i * 3 + 1);
      embeddedList.add(i * 3 + 2);

      final Set<Integer> embeddedSet = new HashSet<>();
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

  @Override
  @AfterClass
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.execute("drop class sqlSelectIndexReuseTestClass").close();

    super.afterClass();
  }

  @Test
  public void testCompositeSearchEquals() {
    try (var resultSet = session
        .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 2")) {
      assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());

      final var resultList = resultSet.toList();

      Assert.assertEquals(resultList.size(), 1);

      final var result = resultList.getFirst();
      Assert.assertEquals(result.<Integer>getProperty("prop1").intValue(), 1);
      Assert.assertEquals(result.<Integer>getProperty("prop2").intValue(), 2);
    }
  }

  @Test
  public void testCompositeSearchHasChainOperatorsEquals() {
    try (var resultSet = session.query(
        "select * from sqlSelectIndexReuseTestClass where prop1.asInteger() = 1 and"
            + " prop2 = 2")) {
      assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();

      Assert.assertEquals(resultList.size(), 1);

      final var document = resultList.getFirst();
      Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
      Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    }
  }

  @Test
  public void testCompositeSearchEqualsOneField() {
    session.executeInTx(transaction -> {
      try (var resultSet = transaction
          .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1")) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var entity = session.newEntity();
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsOneFieldWithLimit() {
    session.executeInTx(transaction -> {
      try (var resultSet = transaction.query(
          "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop3 = 18"
              + " limit 1")) {

        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = transaction.newEntity();
        entity.setProperty("prop1", 1);
        entity.setProperty("prop3", 18);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsOneFieldMapIndexByKey() {
    session.executeInTx(transaction -> {
      try (var resultSet = transaction.query(
          "select * from sqlSelectIndexReuseTestClass where fEmbeddedMapTwo containsKey"
              + " 'key11'")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedMapByKeyProp8",
            List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        for (var i = 0; i < 10; i++) {
          final var entity = session.newEntity();
          entity.setProperty("prop8", 1);
          entity.newEmbeddedMap("fEmbeddedMapTwo", embeddedMap);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsMapIndexByKey() {
    session.executeInTx(transaction -> {
      try (var resultSet = transaction.query(
          "select * from sqlSelectIndexReuseTestClass "
              + "where prop8 = 1 and fEmbeddedMapTwo containsKey 'key11'")) {
        assertIndexesUsed(Map.of(
                "sqlSelectIndexReuseTestEmbeddedMapByKeyProp8",
                List.of(2)),
            resultSet.getExecutionPlan());
        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.newEmbeddedMap("fEmbeddedMap", embeddedMap);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsOneFieldMapIndexByValue() {
    session.executeInTx(transaction -> {
      try (var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass "
                      + "where fEmbeddedMapTwo containsValue 22")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedMapByValueProp8", List.of(1)),
            resultSet.getExecutionPlan());
        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key21", 21);
        embeddedMap.put("key22", 22);
        embeddedMap.put("key23", 23);
        embeddedMap.put("key24", 21);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop8", i);
          document.newEmbeddedMap("fEmbeddedMapTwo", embeddedMap);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsMapIndexByValue() {
    session.executeInTx(transaction -> {
      try (var resultSet = session.query(
          "select * from sqlSelectIndexReuseTestClass "
              + "where prop8 = 1 and fEmbeddedMapTwo containsValue 22")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedMapByValueProp8", List.of(2)),
            resultSet.getExecutionPlan());
        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key21", 21);
        embeddedMap.put("key22", 22);
        embeddedMap.put("key23", 23);
        embeddedMap.put("key24", 21);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", 1);
        entity.newEmbeddedMap("fEmbeddedMap", embeddedMap);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsEmbeddedSetIndex() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass "
                      + "where prop8 = 1 and fEmbeddedSetTwo contains 12")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedSetProp8", List.of(2)),
            resultSet.getExecutionPlan());
        final Set<Integer> embeddedSet = new HashSet<>();
        embeddedSet.add(10);
        embeddedSet.add(11);
        embeddedSet.add(12);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.newEmbeddedSet("fEmbeddedSet", embeddedSet);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsEmbeddedSetInMiddleIndex() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass "
                      + "where prop9 = 0 and fEmbeddedSetTwo contains 92 and prop8 > 2");
      assertIndexesUsed(Map.of("sqlSelectIndexReuseTestProp9EmbeddedSetProp8", List.of(3)),
          resultSet.getExecutionPlan());

      final Set<Integer> embeddedSet = new HashSet<>(3);
      embeddedSet.add(90);
      embeddedSet.add(91);
      embeddedSet.add(92);

      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 3);

      for (var i = 0; i < 3; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", (i << 1) + 4);
        entity.setProperty("prop9", 0);
        entity.newEmbeddedSet("fEmbeddedSet", embeddedSet);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsOneFieldEmbeddedListIndex() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where fEmbeddedListTwo contains 4");
      assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedListTwoProp8", List.of(1)),
          resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 10);

      final List<Integer> embeddedList = new ArrayList<>(3);
      embeddedList.add(3);
      embeddedList.add(4);
      embeddedList.add(5);

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", i);
        entity.newEmbeddedList("fEmbeddedListTwo", embeddedList);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsEmbeddedListIndex() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where"
                      + " prop8 = 1 and fEmbeddedListTwo contains 4")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedListTwoProp8", List.of(2)),
            resultSet.getExecutionPlan());

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final List<Integer> embeddedList = new ArrayList<>(3);
        embeddedList.add(3);
        embeddedList.add(4);
        embeddedList.add(5);

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.newEmbeddedList("fEmbeddedListTwo", embeddedList);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testNoCompositeSearchEquals() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 = 1");

      assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 10);

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop1", i);
        entity.setProperty("prop2", 1);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 = ?", 1,
                  2)) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
      }
    });
  }

  @Test
  public void testCompositeSearchEqualsOneFieldWithArgs() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ?", 1);
      assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());

      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 10);

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop1", 1);
        entity.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testNoCompositeSearchEqualsWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 = ?", 1)) {
        assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", i);
          entity.setProperty("prop2", 1);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGT() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 > 2")) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 7);

        for (var i = 3; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTOneField() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 > 7")) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 20);

        for (var i = 8; i < 10; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = session.newEntity();
            entity.setProperty("prop1", i);
            entity.setProperty("prop2", j);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTOneFieldNoSearch() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 > 7")) {
        assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 20);

        for (var i = 8; i < 10; i++) {
          for (var j = 0; j < 10; j++) {
            final var document = ((EntityImpl) session.newEntity());
            document.setProperty("prop1", j);
            document.setProperty("prop2", i);

            Assert.assertEquals(containsEntity(resultList, document), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTWithArgs() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 > ?", 1,
                  2);
      assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 7);

      for (var i = 3; i < 10; i++) {
        final var entity = session.newEntity();
        entity.setProperty("prop1", 1);
        entity.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
  }

  @Test
  public void testCompositeSearchGTOneFieldWithArgs() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 > ?", 7);
      assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 20);

      for (var i = 8; i < 10; i++) {
        for (var j = 0; j < 10; j++) {
          final var entity = session.newEntity();

          entity.setProperty("prop1", i);
          entity.setProperty("prop2", j);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTOneFieldNoSearchWithArgs() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 > ?", 7);
      assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();

      Assert.assertEquals(resultList.size(), 20);

      for (var i = 8; i < 10; i++) {
        for (var j = 0; j < 10; j++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", j);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTQ() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 >= 2")) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 8);

        for (var i = 2; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTQOneField() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 >= 7")) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 30);

        for (var i = 7; i < 10; i++) {
          for (var j = 0; j < 10; j++) {
            final var document = ((EntityImpl) session.newEntity());
            document.setProperty("prop1", i);
            document.setProperty("prop2", j);

            Assert.assertEquals(containsEntity(resultList, document), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTQOneFieldNoSearch() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 >= 7");
      assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());

      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 30);

      for (var i = 7; i < 10; i++) {
        for (var j = 0; j < 10; j++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop1", j);
          document.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTQWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 >= ?",
                  1, 2)) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 8);

        for (var i = 2; i < 10; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop1", 1);
          document.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTQOneFieldWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 >= ?", 7)) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 30);

        for (var i = 7; i < 10; i++) {
          for (var j = 0; j < 10; j++) {
            final var document = ((EntityImpl) session.newEntity());
            document.setProperty("prop1", i);
            document.setProperty("prop2", j);

            Assert.assertEquals(containsEntity(resultList, document), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchGTQOneFieldNoSearchWithArgs() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 >= ?", 7);
      assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 30);

      for (var i = 7; i < 10; i++) {
        for (var j = 0; j < 10; j++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop1", j);
          document.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTQ() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 <= 2")) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 3);

        for (var i = 0; i <= 2; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTQOneField() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 <= 7")) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 80);

        for (var i = 0; i <= 7; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", i);
            entity.setProperty("prop2", j);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTQOneFieldNoSearch() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 <= 7");

      assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 80);

      for (var i = 0; i <= 7; i++) {
        for (var j = 0; j < 10; j++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", j);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTQWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 <= ?", 1,
                  2)) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 3);

        for (var i = 0; i <= 2; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop1", 1);
          document.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTQOneFieldWithArgs() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 <= ?", 7);
      assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();

      Assert.assertEquals(resultList.size(), 80);

      for (var i = 0; i <= 7; i++) {
        for (var j = 0; j < 10; j++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop1", i);
          document.setProperty("prop2", j);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTQOneFieldNoSearchWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 <= ?", 7)) {

        assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 80);

        for (var i = 0; i <= 7; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", j);
            entity.setProperty("prop2", i);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLT() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 < 2")) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 2);

        for (var i = 0; i < 2; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop1", 1);
          document.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTOneField() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 < 7");
      assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());

      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 70);

      for (var i = 0; i < 7; i++) {
        for (var j = 0; j < 10; j++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", i);
          entity.setProperty("prop2", j);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTOneFieldNoSearch() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 < 7")) {
        assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 70);

        for (var i = 0; i < 7; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", j);
            entity.setProperty("prop2", i);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = ? and prop2 < ?", 1,
                  2)) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 2);

        for (var i = 0; i < 2; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTOneFieldWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 < ?", 7)) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 70);

        for (var i = 0; i < 7; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", i);
            entity.setProperty("prop2", j);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchLTOneFieldNoSearchWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 < ?", 7)) {
        assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 70);

        for (var i = 0; i < 7; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", j);
            entity.setProperty("prop2", i);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchBetween() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between 1"
                      + " and 3")) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 3);

        for (var i = 1; i <= 3; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchBetweenOneField() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 between 1 and 3")) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 30);

        for (var i = 1; i <= 3; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", i);
            entity.setProperty("prop2", j);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchBetweenOneFieldNoSearch() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 between 1 and 3")) {
        assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 30);

        for (var i = 1; i <= 3; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", j);
            entity.setProperty("prop2", i);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchBetweenWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 between ?"
                      + " and ?", 1, 3)) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 3);

        for (var i = 1; i <= 3; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testCompositeSearchBetweenOneFieldWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 between ? and ?", 1,
                  3)) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 30);

        for (var i = 1; i <= 3; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", i);
            entity.setProperty("prop2", j);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testCompositeSearchBetweenOneFieldNoSearchWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 between ? and ?", 1,
                  3)) {
        assertIndexesUsed(Map.of(), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 30);

        for (var i = 1; i <= 3; i++) {
          for (var j = 0; j < 10; j++) {
            final var entity = ((EntityImpl) session.newEntity());
            entity.setProperty("prop1", j);
            entity.setProperty("prop2", i);

            Assert.assertEquals(containsEntity(resultList, entity), 1);
          }
        }
      }
    });
  }

  @Test
  public void testSingleSearchEquals() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 = 1")) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 1);
      }
    });
  }

  @Test
  public void testSingleSearchEqualsWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 = ?", 1)) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 1);
      }
    });
  }

  @Test
  public void testSingleSearchGT() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 > 90")) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 9);

        for (var i = 91; i < 100; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchGTWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 > ?", 90)) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 9);

        for (var i = 91; i < 100; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchGTQ() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 >= 90")) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 90; i < 100; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchGTQWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 >= ?", 90)) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 90; i < 100; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchLTQ() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 <= 10")) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 11);

        for (var i = 0; i <= 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchLTQWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 <= ?", 10)) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 11);

        for (var i = 0; i <= 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchLT() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 < 10")) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchLTWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 < ?", 10)) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchBetween() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 between 1 and 10")) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 1; i <= 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchBetweenWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 between ? and ?", 1,
                  10)) {

        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 1; i <= 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchIN() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 in [0, 5, 10]")) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 3);

        for (var i = 0; i <= 10; i += 5) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testSingleSearchINWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop3 in [?, ?, ?]", 0, 5,
                  10)) {
        assertIndexesUsed(Map.of("indextwo", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 3);

        for (var i = 0; i <= 10; i += 5) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop3", i);
          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testMostSpecificOnesProcessedFirst() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                      + " prop3 = 11")) {

        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var result = resultList.getFirst();
        Assert.assertEquals(result.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(result.<Integer>getProperty("prop2").intValue(), 1);
        Assert.assertEquals(result.<Integer>getProperty("prop3").intValue(), 11);
      }
    });
  }

  @Test
  public void testTripleSearch() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                      + " prop4 >= 1")) {
        assertIndexesUsed(Map.of("indexthree", List.of(3)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = resultList.getFirst();
        Assert.assertEquals(entity.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(entity.<Integer>getProperty("prop2").intValue(), 1);
        Assert.assertEquals(entity.<Integer>getProperty("prop4").intValue(), 1);
      }
    });
  }

  @Test
  public void testTripleSearchLastFieldNotInIndexFirstCase() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 1 and"
                      + " prop5 >= 1")) {

        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop5").intValue(), 1);
      }
    });
  }

  @Test
  public void testTripleSearchLastFieldNotInIndexSecondCase() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 >= 1")) {
        assertIndexesUsed(Map.of("indexfour", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);
          entity.setProperty("prop4", 1);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testTripleSearchLastFieldInIndex() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop4 = 1")) {
        assertIndexesUsed(Map.of("indexfour", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);
          entity.setProperty("prop4", 1);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testTripleSearchLastFieldsCanNotBeMerged() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop6 <= 1 and prop4 < 1")) {
        assertIndexesUsed(Map.of("indexfour", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 2);

        for (var i = 0; i < 2; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop6", i);
          entity.setProperty("prop4", 0);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
    });
  }

  @Test
  public void testLastFieldNotCompatibleOperator() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 + 1 = 3")) {
        assertIndexesUsed(Map.of("indexone", List.of(1)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
      }
    });
  }

  @Test
  public void testEmbeddedMapByKeyIndexReuse() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containskey"
                      + " 'key12'")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedMapByKey", List.of(1)),
            resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        final var entity = ((EntityImpl) session.newEntity());

        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        entity.newEmbeddedMap("fEmbeddedMap", embeddedMap);

        Assert.assertEquals(containsEntity(resultList, entity), 10);
      }
    });
  }

  @Test
  public void testEmbeddedMapBySpecificKeyIndexReuse() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where ( fEmbeddedMap containskey"
                      + " 'key12' ) and ( fEmbeddedMap['key12'] = 12 )")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedMapByKey", List.of(1)),
            resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        final var document = ((EntityImpl) session.newEntity());

        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        document.newEmbeddedMap("fEmbeddedMap", embeddedMap);
      }
    });
  }

  @Test
  public void testEmbeddedMapByValueIndexReuse() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where fEmbeddedMap containsvalue"
                      + " 11")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedMapByValue", List.of(1)),
            resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        final var entity = ((EntityImpl) session.newEntity());

        final Map<String, Integer> embeddedMap = new HashMap<>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        entity.newEmbeddedMap("fEmbeddedMap", embeddedMap);

        Assert.assertEquals(containsEntity(resultList, entity), 10);
      }
    });
  }

  @Test
  public void testEmbeddedListIndexReuse() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where fEmbeddedList contains 7")) {
        assertIndexesUsed(Map.of("sqlSelectIndexReuseTestEmbeddedList", List.of(1)),
            resultSet.getExecutionPlan());
        var resultList = resultSet.toList();

        final List<Integer> embeddedList = new ArrayList<>(3);
        embeddedList.add(6);
        embeddedList.add(7);
        embeddedList.add(8);

        final var entity = session.newEntity();
        entity.newEmbeddedList("fEmbeddedList", embeddedList);

        Assert.assertEquals(containsEntity(resultList, entity), 10);
      }
    });
  }

  @Test
  public void testNotIndexOperatorFirstCase() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2  = 2 and"
                      + " ( prop4 = 3 or prop4 = 1 )")) {
        assertIndexesUsed(Map.of("indexthree", List.of(3, 3)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
        Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
      }
    });
  }

  @Test
  public void testIndexUsedOnOrClause() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where ( prop1 = 1 and prop2 = 2 )"
                      + " or ( prop4  = 1 and prop6 = 2 )")) {
        assertIndexesUsed(Map.of("indexone", List.of(2), "indexfour", List.of(1)),
            resultSet.getExecutionPlan());

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
        Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop6").intValue(), 2);
      }
    });
  }

  @Test
  public void testCompositeIndexEmptyResult() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop1 = 1777 and prop2  ="
                      + " 2777")) {
        assertIndexesUsed(Map.of("indexone", List.of(2)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 0);
      }
    });
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

    session.executeInTx(transaction -> {
      final var docOne = ((EntityImpl) session.newEntity("sqlSelectIndexReuseTestChildClass"));
      docOne.setProperty("prop0", 0);
      docOne.setProperty("prop1", 1);
    });

    session.executeInTx(transaction -> {
      final var docTwo = ((EntityImpl) session.newEntity("sqlSelectIndexReuseTestChildClass"));
      docTwo.setProperty("prop0", 2);
      docTwo.setProperty("prop1", 3);
    });

    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestChildClass where prop0 = 0 and prop1 ="
                      + " 1")) {
        assertIndexesUsed(
            Map.of("sqlSelectIndexReuseTestOnPropertiesFromClassAndSuperclass", List.of(2)),
            resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);
      }
    });
  }

  @Test
  public void testCountFunctionWithNotUniqueIndex() {
    var klazz =
        session.getMetadata().getSchema().getOrCreateClass("CountFunctionWithNotUniqueIndexTest");
    if (!klazz.existsProperty("a")) {
      klazz.createProperty("a", PropertyType.STRING);
      klazz.createIndex("a", "NOTUNIQUE", "a");
    }

    session.executeInTx(transaction -> {
      var e1 = transaction
          .newEntity("CountFunctionWithNotUniqueIndexTest");
      e1.setProperty("a", "a");
      e1.setProperty("b", "b");

      var e2 = transaction
          .newEntity("CountFunctionWithNotUniqueIndexTest");
      e2.setProperty("a", "a");
      e1.setProperty("b", "b");

      var entity = transaction
          .newEntity("CountFunctionWithNotUniqueIndexTest");
      entity.setProperty("a", "a");

      var entity1 = transaction
          .newEntity("CountFunctionWithNotUniqueIndexTest");
      entity1.setProperty("a", "c");
      entity1.setProperty("b", "c");
    });

    try (var rs = session.query(
        "select count(*) as count from CountFunctionWithNotUniqueIndexTest where a = 'a' and"
            + " b = 'c'")) {
      assertIndexesUsed(Map.of("a", List.of(1)), rs.getExecutionPlan());
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

    var ent = session.computeInTx(transaction -> {
      var entity4 = transaction
          .newEntity("CountFunctionWithUniqueIndexTest");
      entity4.setProperty("a", "a");
      entity4.setProperty("b", "c");

      var entity3 = transaction
          .newEntity("CountFunctionWithUniqueIndexTest");
      entity3.setProperty("a", "a");
      entity3.setProperty("b", "c");

      var entity1 = transaction
          .newEntity("CountFunctionWithUniqueIndexTest");
      entity1.setProperty("a", "a");
      entity1.setProperty("b", "e");

      var entity = transaction
          .newEntity("CountFunctionWithUniqueIndexTest");
      entity.setProperty("a", "a");
      entity.setProperty("b", "b");

      return entity;
    });

    session.executeInTx(transaction -> {

      try (var rs = transaction.query(
          "select count(*) as count from CountFunctionWithUniqueIndexTest where a = 'a' and b"
              + " = 'c'")) {
        assertIndexesUsed(Map.of("testCountFunctionWithUniqueIndex", List.of(1)),
            rs.getExecutionPlan());
        Assert.assertEquals(rs.findFirst(r -> r.getLong("count")).longValue(), 2L);
      }
    });

    session.executeInTx(transaction -> {
      transaction.<EntityImpl>load(ent).delete();
    });
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
  public void testCompositeSearchIn1() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 = 1 and"
                      + " prop3 in [13, 113]")) {
        assertIndexesUsed(Map.of("indexfour", List.of(3)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);
      }
    });
  }

  @Test
  public void testCompositeSearchIn2() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                      + " and prop3 = 13")) {
        assertIndexesUsed(Map.of("indexfour", List.of(3)), resultSet.getExecutionPlan());
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);
      }
    });
  }

  @Test
  public void testCompositeSearchIn3() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop4 = 1 and prop1 in [1, 2]"
                      + " and prop3 in [13, 15]");
      assertIndexesUsed(Map.of("indexfour", List.of(3)), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 2);

      final var document = resultList.getFirst();
      Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
      Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
      Assert.assertTrue(
          document.<Integer>getProperty("prop3").equals(13) || document.<Integer>getProperty(
                  "prop3")
              .equals(15));
    });
  }

  @Test
  public void testCompositeSearchIn4() {
    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query(
                  "select * from sqlSelectIndexReuseTestClass where prop4 in [1, 2] and prop1 = 1"
                      + " and prop3 = 13");

      assertIndexesUsed(Map.of("indexfour", List.of(3)), resultSet.getExecutionPlan());
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 1);

      final var document = resultList.getFirst();
      Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
      Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
      Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);
    });
  }
}
