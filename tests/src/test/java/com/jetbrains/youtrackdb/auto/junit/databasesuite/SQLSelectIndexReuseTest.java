/*
 * JUnit 4 version of SQLSelectIndexReuseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;
import com.jetbrains.youtrackdb.auto.junit.AbstractIndexReuseTest;

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
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLSelectIndexReuseTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
 * <p>
 * This test class is @Ignored because the original TestNG version was also @Ignored.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Ignore("Migrated from TestNG - original test class was @Ignore")
public class SQLSelectIndexReuseTest extends AbstractIndexReuseTest {

  private static SQLSelectIndexReuseTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLSelectIndexReuseTest();
    instance.beforeClass();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (instance != null) {
      instance.afterClass();
    }
  }

  /**
   * Original: beforeClass (line 25) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Override
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

  /**
   * Original: afterClass (line 166) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Override
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.execute("drop class sqlSelectIndexReuseTestClass").close();

    super.afterClass();
  }

  /**
   * Helper method to check if entity is contained in result list. Original: containsEntity (line
   * 2868) Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
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

  /**
   * Original: testCompositeSearchEquals (line 179) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test01_CompositeSearchEquals() {
//    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
//    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");
//    var oldcompositeIndexUsed2 = profiler.getCounter("db.demo.query.compositeIndexUsed.2");

    try (var resultSet = session
        .query("select * from sqlSelectIndexReuseTestClass where prop1 = 1 and prop2 = 2")) {
      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

      final var resultList = resultSet.toList();

      Assert.assertEquals(resultList.size(), 1);

      final var result = resultList.getFirst();
      Assert.assertEquals(result.<Integer>getProperty("prop1").intValue(), 1);
      Assert.assertEquals(result.<Integer>getProperty("prop2").intValue(), 2);
    }
//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);

  }

  /**
   * Original: testCompositeSearchHasChainOperatorsEquals (line 205) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test02_CompositeSearchHasChainOperatorsEquals() {
    try (var resultSet = session.query(
        "select * from sqlSelectIndexReuseTestClass where prop1.asInteger() = 1 and"
            + " prop2 = 2")) {
      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 0);
      var resultList = resultSet.toList();

      Assert.assertEquals(resultList.size(), 1);

      final var document = resultList.getFirst();
      Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
      Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    }
  }

  /**
   * Original: testCompositeSearchEqualsOneField (line 221) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test03_CompositeSearchEqualsOneField() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var entity = session.newEntity();
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
    });
  }

  /**
   * Original: testCompositeSearchEqualsOneFieldWithLimit (line 268) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test04_CompositeSearchEqualsOneFieldWithLimit() {
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

        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = transaction.newEntity();
        entity.setProperty("prop1", 1);
        entity.setProperty("prop3", 18);

        Assert.assertEquals(containsEntity(resultList, entity), 1);

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
      }
    });
  }

  /**
   * Original: testCompositeSearchEqualsOneFieldMapIndexByKey (line 315) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test05_CompositeSearchEqualsOneFieldMapIndexByKey() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

        embeddedMap.put("key11", 11);
        embeddedMap.put("key12", 12);
        embeddedMap.put("key13", 13);
        embeddedMap.put("key14", 11);

        for (var i = 0; i < 10; i++) {
          final var entity = session.newEntity();
          entity.setProperty("prop8", 1);
          entity.setProperty("fEmbeddedMapTwo", embeddedMap);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
      }
    });
  }

  /**
   * Original: testCompositeSearchEqualsMapIndexByKey (line 371) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test06_CompositeSearchEqualsMapIndexByKey() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
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
//
//        Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//        Assert.assertEquals(
//            profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//        Assert.assertEquals(
//            profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//        Assert.assertEquals(
//            profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"),
//            oldcompositeIndexUsed22 + 1);
      }
    });
  }

  /**
   * Original: testCompositeSearchEqualsOneFieldMapIndexByValue (line 425) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test07_CompositeSearchEqualsOneFieldMapIndexByValue() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

        final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

        embeddedMap.put("key21", 21);
        embeddedMap.put("key22", 22);
        embeddedMap.put("key23", 23);
        embeddedMap.put("key24", 21);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 10);

        for (var i = 0; i < 10; i++) {
          final var document = ((EntityImpl) session.newEntity());
          document.setProperty("prop8", i);
          document.setProperty("fEmbeddedMapTwo", embeddedMap);

          Assert.assertEquals(containsEntity(resultList, document), 1);
        }
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  /**
   * Original: testCompositeSearchEqualsMapIndexByValue (line 482) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test08_CompositeSearchEqualsMapIndexByValue() {
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

        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
        final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

        embeddedMap.put("key21", 21);
        embeddedMap.put("key22", 22);
        embeddedMap.put("key23", 23);
        embeddedMap.put("key24", 21);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", 1);
        entity.setProperty("fEmbeddedMap", embeddedMap);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  /**
   * Original: testCompositeSearchEqualsEmbeddedSetIndex (line 535) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test09_CompositeSearchEqualsEmbeddedSetIndex() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
        final Set<Integer> embeddedSet = new HashSet<Integer>();
        embeddedSet.add(10);
        embeddedSet.add(11);
        embeddedSet.add(12);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.setProperty("fEmbeddedSet", embeddedSet);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  /**
   * Original: testCompositeSearchEqualsEmbeddedSetInMiddleIndex (line 588) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test10_CompositeSearchEqualsEmbeddedSetInMiddleIndex() {
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
      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

      final Set<Integer> embeddedSet = new HashSet<Integer>(3);
      embeddedSet.add(90);
      embeddedSet.add(91);
      embeddedSet.add(92);

      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 3);

      for (var i = 0; i < 3; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", (i << 1) + 4);
        entity.setProperty("prop9", 0);
        entity.setProperty("fEmbeddedSet", embeddedSet);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3"), oldcompositeIndexUsed33 + 1);
  }

  /**
   * Original: testCompositeSearchEqualsOneFieldEmbeddedListIndex (line 652) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test11_CompositeSearchEqualsOneFieldEmbeddedListIndex() {
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

      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 10);

      final List<Integer> embeddedList = new ArrayList<Integer>(3);
      embeddedList.add(3);
      embeddedList.add(4);
      embeddedList.add(5);

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop8", i);
        entity.setProperty("fEmbeddedListTwo", embeddedList);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.1"), oldcompositeIndexUsed21 + 1);
  }

  /**
   * Original: testCompositeSearchEqualsEmbeddedListIndex (line 707) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test12_CompositeSearchEqualsEmbeddedListIndex() {
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

        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final List<Integer> embeddedList = new ArrayList<Integer>(3);
        embeddedList.add(3);
        embeddedList.add(4);
        embeddedList.add(5);

        final var entity = session.newEntity();
        entity.setProperty("prop8", 1);
        entity.setProperty("fEmbeddedListTwo", embeddedList);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });
//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2.2"), oldcompositeIndexUsed22 + 1);
  }

  /**
   * Original: testNoCompositeSearchEquals (line 760) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test13_NoCompositeSearchEquals() {
//    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 = 1");

      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 0);
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

  /**
   * Original: testCompositeSearchEqualsWithArgs (line 783) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test14_CompositeSearchEqualsWithArgs() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 1);

        final var document = resultList.getFirst();
        Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
        Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchEqualsOneFieldWithArgs (line 822) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test15_CompositeSearchEqualsOneFieldWithArgs() {
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
      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 10);

      for (var i = 0; i < 10; i++) {
        final var entity = ((EntityImpl) session.newEntity());
        entity.setProperty("prop1", 1);
        entity.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testNoCompositeSearchEqualsWithArgs (line 863) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test16_NoCompositeSearchEqualsWithArgs() {
    session.executeInTx(transaction -> {
      try (final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 = ?", 1)) {
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 0);
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

  /**
   * Original: testCompositeSearchGT (line 884) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test17_CompositeSearchGT() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
        var resultList = resultSet.toList();
        Assert.assertEquals(resultList.size(), 7);

        for (var i = 3; i < 10; i++) {
          final var entity = ((EntityImpl) session.newEntity());
          entity.setProperty("prop1", 1);
          entity.setProperty("prop2", i);

          Assert.assertEquals(containsEntity(resultList, entity), 1);
        }
      }
//      Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//      Assert.assertEquals(
//          profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//      Assert.assertEquals(
//          profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
    });
  }

  /**
   * Original: testCompositeSearchGTOneField (line 924) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test18_CompositeSearchGTOneField() {
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

        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);

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

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchGTOneFieldNoSearch (line 969) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test19_CompositeSearchGTOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 > 7").toList();

    Assert.assertEquals(result.size(), 20);

    for (var i = 8; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchGTWithArgs (line 992) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test20_CompositeSearchGTWithArgs() {
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
      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
      var resultList = resultSet.toList();
      Assert.assertEquals(resultList.size(), 7);

      for (var i = 3; i < 10; i++) {
        final var entity = session.newEntity();
        entity.setProperty("prop1", 1);
        entity.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(resultList, entity), 1);
      }
    });

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchGTOneFieldWithArgs (line 1033) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test21_CompositeSearchGTOneFieldWithArgs() {
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

      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
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

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchGTOneFieldNoSearchWithArgs (line 1077) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test22_CompositeSearchGTOneFieldNoSearchWithArgs() {
//    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    session.executeInTx(transaction -> {
      final var resultSet =
          session
              .query("select * from sqlSelectIndexReuseTestClass where prop2 > ?", 7);
      Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 0);
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

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchGTQ (line 1104) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test23_CompositeSearchGTQ() {
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
        Assert.assertEquals(indexesUsed(resultSet.getExecutionPlan()), 1);
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

//    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
//    Assert.assertEquals(
//        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchGTQOneField (line 1145) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test24_CompositeSearchGTQOneField() {
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
    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchGTQOneFieldNoSearch (line 1183) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test25_CompositeSearchGTQOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 >= 7").toList();
    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchGTQWithArgs (line 1205) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test26_CompositeSearchGTQWithArgs() {
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
                1, 2).toList();
    Assert.assertEquals(result.size(), 8);

    for (var i = 2; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchGTQOneFieldWithArgs (line 1242) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test27_CompositeSearchGTQOneFieldWithArgs() {
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
    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchGTQOneFieldNoSearchWithArgs (line 1280) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test28_CompositeSearchGTQOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 >= ?", 7).toList();
    Assert.assertEquals(result.size(), 30);

    for (var i = 7; i < 10; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchLTQ (line 1302) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test29_CompositeSearchLTQ() {
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
    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTQOneField (line 1339) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test30_CompositeSearchLTQOneField() {
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
    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTQOneFieldNoSearch (line 1377) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test31_CompositeSearchLTQOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 <= 7").toList();
    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchLTQWithArgs (line 1399) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test32_CompositeSearchLTQWithArgs() {
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
                2).toList();

    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTQOneFieldWithArgs (line 1437) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test33_CompositeSearchLTQOneFieldWithArgs() {
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
    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTQOneFieldNoSearchWithArgs (line 1475) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test34_CompositeSearchLTQOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 <= ?", 7).toList();

    Assert.assertEquals(result.size(), 80);

    for (var i = 0; i <= 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchLT (line 1498) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test35_CompositeSearchLT() {
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

    Assert.assertEquals(result.size(), 2);

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTOneField (line 1536) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test36_CompositeSearchLTOneField() {
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
    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTOneFieldNoSearch (line 1574) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test37_CompositeSearchLTOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 < 7").toList();
    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchLTWithArgs (line 1596) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test38_CompositeSearchLTWithArgs() {
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
    Assert.assertEquals(result.size(), 2);

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTOneFieldWithArgs (line 1634) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test39_CompositeSearchLTOneFieldWithArgs() {
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
    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchLTOneFieldNoSearchWithArgs (line 1672) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test40_CompositeSearchLTOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 < ?", 7).toList();
    Assert.assertEquals(result.size(), 70);

    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchBetween (line 1694) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test41_CompositeSearchBetween() {
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
                    + " and 3").toList();
    Assert.assertEquals(result.size(), 3);

    for (var i = 1; i <= 3; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchBetweenOneField (line 1732) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test42_CompositeSearchBetweenOneField() {
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
    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchBetweenOneFieldNoSearch (line 1771) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test43_CompositeSearchBetweenOneFieldNoSearch() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 between 1 and 3")
            .toList();
    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testCompositeSearchBetweenWithArgs (line 1794) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test44_CompositeSearchBetweenWithArgs() {
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
                    + " and ?", 1, 3).toList();

    Assert.assertEquals(result.size(), 3);

    for (var i = 1; i <= 3; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchBetweenOneFieldWithArgs (line 1833) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test45_CompositeSearchBetweenOneFieldWithArgs() {
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
    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", i);
        document.setProperty("prop2", j);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCompositeSearchBetweenOneFieldNoSearchWithArgs (line 1872) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test46_CompositeSearchBetweenOneFieldNoSearchWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop2 between ? and ?", 1, 3)
            .toList();
    Assert.assertEquals(result.size(), 30);

    for (var i = 1; i <= 3; i++) {
      for (var j = 0; j < 10; j++) {
        final var document = ((EntityImpl) session.newEntity());
        document.setProperty("prop1", j);
        document.setProperty("prop2", i);

        Assert.assertEquals(containsEntity(result, document), 1);
      }
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage);
  }

  /**
   * Original: testSingleSearchEquals (line 1895) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test47_SingleSearchEquals() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 = 1").toList();
    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchEqualsWithArgs (line 1916) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test48_SingleSearchEqualsWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 = ?", 1).toList();
    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchGT (line 1937) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test49_SingleSearchGT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 > 90").toList();
    Assert.assertEquals(result.size(), 9);

    for (var i = 91; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchGTWithArgs (line 1962) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test50_SingleSearchGTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 > ?", 90).toList();
    Assert.assertEquals(result.size(), 9);

    for (var i = 91; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchGTQ (line 1987) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test51_SingleSearchGTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 >= 90").toList();
    Assert.assertEquals(result.size(), 10);

    for (var i = 90; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchGTQWithArgs (line 2012) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test52_SingleSearchGTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 >= ?", 90).toList();
    Assert.assertEquals(result.size(), 10);

    for (var i = 90; i < 100; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchLTQ (line 2037) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test53_SingleSearchLTQ() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 <= 10").toList();
    Assert.assertEquals(result.size(), 11);

    for (var i = 0; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchLTQWithArgs (line 2062) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test54_SingleSearchLTQWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 <= ?", 10).toList();
    Assert.assertEquals(result.size(), 11);

    for (var i = 0; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchLT (line 2087) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test55_SingleSearchLT() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 < 10").toList();
    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchLTWithArgs (line 2112) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test56_SingleSearchLTWithArgs() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 < ?", 10).toList();
    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchBetween (line 2137) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test57_SingleSearchBetween() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 between 1 and 10")
            .toList();
    Assert.assertEquals(result.size(), 10);

    for (var i = 1; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchBetweenWithArgs (line 2163) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test58_SingleSearchBetweenWithArgs() {
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

    Assert.assertEquals(result.size(), 10);

    for (var i = 1; i <= 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchIN (line 2191) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test59_SingleSearchIN() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    var oldcompositeIndexUsed = profiler.getCounter("db.demo.query.compositeIndexUsed");

    if (oldIndexUsage == -1) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query("select * from sqlSelectIndexReuseTestClass where prop3 in [0, 5, 10]")
            .toList();
    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 10; i += 5) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testSingleSearchINWithArgs (line 2217) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test60_SingleSearchINWithArgs() {
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

    Assert.assertEquals(result.size(), 3);

    for (var i = 0; i <= 10; i += 5) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop3", i);
      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
  }

  /**
   * Original: testMostSpecificOnesProcessedFirst (line 2245) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test61_MostSpecificOnesProcessedFirst() {
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
                    + " prop3 = 11").toList();

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 11);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testTripleSearch (line 2281) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test62_TripleSearch() {
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
                    + " prop4 >= 1").toList();
    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
  }

  /**
   * Original: testTripleSearchLastFieldNotInIndexFirstCase (line 2316) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test63_TripleSearchLastFieldNotInIndexFirstCase() {
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
                    + " prop5 >= 1").toList();

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop5").intValue(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testTripleSearchLastFieldNotInIndexSecondCase (line 2352) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test64_TripleSearchLastFieldNotInIndexSecondCase() {
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

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);
      document.setProperty("prop4", 1);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testTripleSearchLastFieldInIndex (line 2391) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test65_TripleSearchLastFieldInIndex() {
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

    Assert.assertEquals(result.size(), 10);

    for (var i = 0; i < 10; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop1", 1);
      document.setProperty("prop2", i);
      document.setProperty("prop4", 1);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
  }

  /**
   * Original: testTripleSearchLastFieldsCanNotBeMerged (line 2430) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test66_TripleSearchLastFieldsCanNotBeMerged() {
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

    Assert.assertEquals(result.size(), 2);

    for (var i = 0; i < 2; i++) {
      final var document = ((EntityImpl) session.newEntity());
      document.setProperty("prop6", i);
      document.setProperty("prop4", 0);

      Assert.assertEquals(containsEntity(result, document), 1);
    }

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
  }

  /**
   * Original: testLastFieldNotCompatibleOperator (line 2468) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test67_LastFieldNotCompatibleOperator() {
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

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testEmbeddedMapByKeyIndexReuse (line 2501) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test68_EmbeddedMapByKeyIndexReuse() {
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
                    + " 'key12'").toList();

    Assert.assertEquals(result.size(), 10);

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsEntity(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  /**
   * Original: testEmbeddedMapBySpecificKeyIndexReuse (line 2539) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test69_EmbeddedMapBySpecificKeyIndexReuse() {
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
                    + " 'key12' ) and ( fEmbeddedMap['key12'] = 12 )").toList();
    Assert.assertEquals(result.size(), 10);

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsEntity(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  /**
   * Original: testEmbeddedMapByValueIndexReuse (line 2576) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test70_EmbeddedMapByValueIndexReuse() {
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
                    + " 11").toList();
    Assert.assertEquals(result.size(), 10);

    final var document = ((EntityImpl) session.newEntity());

    final Map<String, Integer> embeddedMap = new HashMap<String, Integer>();

    embeddedMap.put("key11", 11);
    embeddedMap.put("key12", 12);
    embeddedMap.put("key13", 13);
    embeddedMap.put("key14", 11);

    document.setProperty("fEmbeddedMap", embeddedMap);

    Assert.assertEquals(containsEntity(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  /**
   * Original: testEmbeddedListIndexReuse (line 2613) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test71_EmbeddedListIndexReuse() {
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

    Assert.assertEquals(containsEntity(result, document), 10);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2);
  }

  /**
   * Original: testNotIndexOperatorFirstCase (line 2645) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test72_NotIndexOperatorFirstCase() {
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

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testIndexUsedOnOrClause (line 2681) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test73_IndexUsedOnOrClause() {
    var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    if (oldIndexUsage < 0) {
      oldIndexUsage = 0;
    }

    final var result =
        session
            .query(
                "select * from sqlSelectIndexReuseTestClass where ( prop1 = 1 and prop2 = 2 )"
                    + " or ( prop4  = 1 and prop6 = 2 )").toList();

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop2").intValue(), 2);
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop6").intValue(), 2);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 2);
  }

  /**
   * Original: testCompositeIndexEmptyResult (line 2705) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test74_CompositeIndexEmptyResult() {
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
                    + " 2777").toList();

    Assert.assertEquals(result.size(), 0);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testReuseOfIndexOnSeveralClassesFields (line 2736) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test75_ReuseOfIndexOnSeveralClassesFields() {
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
                    + " 1").toList();

    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.2"), oldcompositeIndexUsed2 + 1);
  }

  /**
   * Original: testCountFunctionWithNotUniqueIndex (line 2782) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test76_CountFunctionWithNotUniqueIndex() {
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
      Assert.assertEquals(indexesUsed(rs.getExecutionPlan()), 1);
      Assert.assertEquals(rs.findFirst(r -> r.getLong("count")).longValue(), 0L);
    }
  }

  /**
   * Original: testCountFunctionWithUniqueIndex (line 2821) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test77_CountFunctionWithUniqueIndex() {
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
      Assert.assertEquals(indexesUsed(rs.getExecutionPlan()), 1);
      Assert.assertEquals(rs.findFirst(r -> r.getLong("count")).longValue(), 2L);
    }

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(doc).delete();
    session.commit();
  }

  /**
   * Original: testCompositeSearchIn1 (line 2886) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test78_CompositeSearchIn1() {
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
                    + " prop3 in [13, 113]").toDetachedList();

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
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

  /**
   * Original: testCompositeSearchIn2 (line 2928) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test79_CompositeSearchIn2() {
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
                    + " and prop3 = 13").toDetachedList();

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3")
            < oldcompositeIndexUsed33 + 1);
  }

  /**
   * Original: testCompositeSearchIn3 (line 2971) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test80_CompositeSearchIn3() {
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
                    + " and prop3 in [13, 15]").toDetachedList();

    Assert.assertEquals(result.size(), 2);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertTrue(
        document.<Integer>getProperty("prop3").equals(13) || document.<Integer>getProperty(
                "prop3")
            .equals(15));

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3"), oldcompositeIndexUsed3 + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3")
            < oldcompositeIndexUsed33 + 1);
  }

  /**
   * Original: testCompositeSearchIn4 (line 3017) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectIndexReuseTest.java
   */
  @Test
  public void test81_CompositeSearchIn4() {
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
                    + " and prop3 = 13").toDetachedList();

    Assert.assertEquals(result.size(), 1);

    final var document = result.getFirst();
    Assert.assertEquals(document.<Integer>getProperty("prop4").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop1").intValue(), 1);
    Assert.assertEquals(document.<Integer>getProperty("prop3").intValue(), 13);

    Assert.assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 1);
    Assert.assertEquals(
        profiler.getCounter("db.demo.query.compositeIndexUsed"), oldcompositeIndexUsed + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3") < oldcompositeIndexUsed3 + 1);
    Assert.assertTrue(
        profiler.getCounter("db.demo.query.compositeIndexUsed.3.3")
            < oldcompositeIndexUsed33 + 1);
  }

}
