/*
 * JUnit 4 version of SQLSelectByLinkedSchemaPropertyIndexReuseTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
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
package com.jetbrains.youtrackdb.auto.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * When some unique composite index in the chain is queried by partial result, the final result
 * become not unique.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Ignore("Rewrite these tests for the new SQL engine")
public class SQLSelectByLinkedSchemaPropertyIndexReuseTest extends AbstractIndexReuseTest {

  private static SQLSelectByLinkedSchemaPropertyIndexReuseTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLSelectByLinkedSchemaPropertyIndexReuseTest();
    instance.beforeClass();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (instance != null) {
      instance.afterClass();
    }
  }

  /**
   * Original: beforeClass (line 35) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    createSchemaForTest();
    fillDataSet();
  }

  /**
   * Original: afterClass (line 44) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Override
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.execute("drop class lpirtStudent").close();
    session.execute("drop class lpirtGroup").close();
    session.execute("drop class lpirtCurator").close();

    super.afterClass();
  }

  /**
   * Original: indexUsages (line 317) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  private long indexUsages() {
    final var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    return oldIndexUsage == -1 ? 0 : oldIndexUsage;
  }

  /**
   * Original: fillDataSet (line 325) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  private void fillDataSet() {
    session.begin();
    var curator1 = session.newInstance("lpirtCurator");
    curator1.setProperty("name", "Someone");
    curator1.setProperty("salary", 2000);

    final var group1 = session.newInstance("lpirtGroup");
    group1.setProperty("name", "PZ-08-1");
    group1.setProperty("curator", curator1);

    final var diploma1 = session.newInstance("lpirtDiploma");
    diploma1.setProperty("GPA", 3.);
    diploma1.setProperty("name", "diploma1");
    diploma1.setProperty("thesis",
        "Researching and visiting universities before making a final decision is very beneficial"
            + " because you student be able to experience the campus, meet the professors, and"
            + " truly understand the traditions of the university.");

    final var transcript = session.newInstance("lpirtTranscript");
    transcript.setProperty("id", "1");

    final var skill = session.newInstance("lpirtSkill");
    skill.setProperty("name", "math");

    final var student1 = session.newInstance("lpirtStudent");
    student1.setProperty("name", "John Smith");
    student1.setProperty("group", group1);
    student1.setProperty("diploma", diploma1);
    student1.setProperty("transcript", transcript);
    student1.setProperty("skill", skill);

    var curator2 = session.newInstance("lpirtCurator");
    curator2.setProperty("name", "Someone else");
    curator2.setProperty("salary", 500);

    final var group2 = session.newInstance("lpirtGroup");
    group2.setProperty("name", "PZ-08-2");
    group2.setProperty("curator", curator2);

    final var diploma2 = session.newInstance("lpirtDiploma");
    diploma2.setProperty("GPA", 5.);
    diploma2.setProperty("name", "diploma2");
    diploma2.setProperty("thesis",
        "While both Northerners and Southerners believed they fought against tyranny and"
            + " oppression, Northerners focused on the oppression of slaves while Southerners"
            + " defended their own right to self-government.");

    final var student2 = session.newInstance("lpirtStudent");
    student2.setProperty("name", "Jane Smith");
    student2.setProperty("group", group2);
    student2.setProperty("diploma", diploma2);

    var curator3 = session.newInstance("lpirtCurator");
    curator3.setProperty("name", "Someone else");
    curator3.setProperty("salary", 600);

    final var group3 = session.newInstance("lpirtGroup");
    group3.setProperty("name", "PZ-08-3");
    group3.setProperty("curator", curator3);

    final var diploma3 = session.newInstance("lpirtDiploma");
    diploma3.setProperty("GPA", 4.);
    diploma3.setProperty("name", "diploma3");
    diploma3.setProperty("thesis",
        "College student shouldn't have to take a required core curriculum, and many core "
            + "courses are graded too stiffly.");

    final var student3 = session.newInstance("lpirtStudent");
    student3.setProperty("name", "James Bell");
    student3.setProperty("group", group3);
    student3.setProperty("diploma", diploma3);

    final var student4 = session.newInstance("lpirtStudent");
    student4.setProperty("name", "Roger Connor");
    student4.setProperty("group", group3);

    final var student5 = session.newInstance("lpirtStudent");
    student5.setProperty("name", "William James");
    student5.setProperty("group", group3);
    student5.setProperty("diploma", diploma3);

    session.commit();
  }

  /**
   * Original: createSchemaForTest (line 409) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  private void createSchemaForTest() {
    final Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("lpirtStudent")) {
      final var curatorClass = schema.createClass("lpirtCurator");
      curatorClass.createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      curatorClass
          .createProperty("salary", PropertyType.INTEGER)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));
      curatorClass.createIndex(
          "curotorCompositeIndex",
          SchemaClass.INDEX_TYPE.UNIQUE.name(),
          null,
          Map.of("ignoreNullValues", true), new String[]{"salary", "name"});

      final var groupClass = schema.createClass("lpirtGroup");
      groupClass
          .createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));
      groupClass
          .createProperty("curator", PropertyType.LINK, curatorClass)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));

      final var diplomaClass = schema.createClass("lpirtDiploma");
      diplomaClass.createProperty("GPA", PropertyType.DOUBLE)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      diplomaClass.createProperty("thesis", PropertyType.STRING);
      diplomaClass
          .createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));
      diplomaClass.createIndex(
          "diplomaThesisUnique",
          SchemaClass.INDEX_TYPE.UNIQUE.name(),
          null,
          Map.of("ignoreNullValues", true), new String[]{"thesis"});

      final var transcriptClass = schema.createClass("lpirtTranscript");
      transcriptClass
          .createProperty("id", PropertyType.STRING)
          .createIndex(
              SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));

      final var skillClass = schema.createClass("lpirtSkill");
      skillClass
          .createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));

      final var studentClass = schema.createClass("lpirtStudent");
      studentClass
          .createProperty("name", PropertyType.STRING)
          .createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));
      studentClass
          .createProperty("group", PropertyType.LINK, groupClass)
          .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
      studentClass.createProperty("diploma", PropertyType.LINK, diplomaClass);
      studentClass
          .createProperty("transcript", PropertyType.LINK, transcriptClass)
          .createIndex(
              SchemaClass.INDEX_TYPE.UNIQUE,
              Map.of("ignoreNullValues", true));
      studentClass.createProperty("skill", PropertyType.LINK, skillClass);

      var metadata = Map.of("ignoreNullValues", false);
      studentClass.createIndex(
          "studentDiplomaAndNameIndex",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          new HashMap<>(metadata), new String[]{"diploma", "name"});
      studentClass.createIndex(
          "studentSkillAndGroupIndex",
          SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
          null,
          new HashMap<>(metadata), new String[]{"skill", "group"});
    }
  }

  /**
   * Original: testNotUniqueUniqueNotUniqueEqualsUsing (line 57) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test01_NotUniqueUniqueNotUniqueEqualsUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where group.curator.name = 'Someone'").toList();
    assertEquals(result.size(), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "John Smith"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testNotUniqueUniqueUniqueEqualsUsing (line 71) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test02_NotUniqueUniqueUniqueEqualsUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(

            "select from lpirtStudent where group.curator.salary = 600").toList();
    assertEquals(result.size(), 3);
    assertEquals(containsDocumentWithFieldValue(result, "name", "James Bell"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "Roger Connor"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "William James"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testNotUniqueUniqueNotUniqueEqualsLimitUsing (line 88) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test03_NotUniqueUniqueNotUniqueEqualsLimitUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where group.curator.name = 'Someone else' limit 1").toList();
    assertEquals(result.size(), 1);
    assertTrue(
        Arrays.asList("Jane Smith", "James Bell", "Roger Connor", "William James")
            .contains(result.get(0).getProperty("name")));

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testNotUniqueUniqueUniqueMinorUsing (line 104) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test04_NotUniqueUniqueUniqueMinorUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(

            "select from lpirtStudent where group.curator.salary < 1000").toList();
    assertEquals(result.size(), 4);
    assertEquals(containsDocumentWithFieldValue(result, "name", "Jane Smith"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "James Bell"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "Roger Connor"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "William James"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 5);
  }

  /**
   * Original: testNotUniqueUniqueUniqueMinorLimitUsing (line 122) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test05_NotUniqueUniqueUniqueMinorLimitUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where group.curator.salary < 1000 limit 2").toList();
    assertEquals(result.size(), 2);

    final var expectedNames =
        Arrays.asList("Jane Smith", "James Bell", "Roger Connor", "William James");

    for (var aResult : result) {
      assertTrue(expectedNames.contains(aResult.getProperty("name")));
    }

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 5);
  }

  /**
   * Original: testUniqueNotUniqueMinorEqualsUsing (line 142) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test06_UniqueNotUniqueMinorEqualsUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query("select from lpirtStudent where diploma.GPA <= 4").toList();
    assertEquals(result.size(), 3);
    assertEquals(containsDocumentWithFieldValue(result, "name", "John Smith"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "James Bell"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "William James"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testUniqueNotUniqueMinorEqualsLimitUsing (line 157) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test07_UniqueNotUniqueMinorEqualsLimitUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where diploma.GPA <= 4 limit 1").toList();
    assertEquals(result.size(), 1);
    assertTrue(
        Arrays.asList("John Smith", "James Bell", "William James")
            .contains(result.get(0).getProperty("name")));

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 2);
  }

  /**
   * Original: testNotUniqueUniqueUniqueMajorUsing (line 173) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test08_NotUniqueUniqueUniqueMajorUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where group.curator.salary > 1000").toList();
    assertEquals(result.size(), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "John Smith"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testNotUniqueUniqueUniqueMajorLimitUsing (line 187) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test09_NotUniqueUniqueUniqueMajorLimitUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where group.curator.salary > 550 limit 1").toList();
    assertEquals(result.size(), 1);
    final var expectedNames =
        Arrays.asList("John Smith", "James Bell", "Roger Connor", "William James");
    for (var aResult : result) {
      assertTrue(expectedNames.contains(aResult.getProperty("name")));
    }

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testUniqueUniqueBetweenUsing (line 205) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test10_UniqueUniqueBetweenUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtGroup where curator.salary between 500 and 1000").toList();
    assertEquals(result.size(), 2);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-2"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-3"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testUniqueUniqueBetweenLimitUsing (line 220) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test11_UniqueUniqueBetweenLimitUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtGroup where curator.salary between 500 and 1000 limit 1").toList();
    assertEquals(result.size(), 1);

    final var expectedNames = Arrays.asList("PZ-08-2", "PZ-08-3");
    for (var aResult : result) {
      assertTrue(expectedNames.contains(aResult.getProperty("name")));
    }

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 2);
  }

  /**
   * Original: testUniqueUniqueInUsing (line 238) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test12_UniqueUniqueInUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtGroup where curator.salary in [500, 600]").toList();
    assertEquals(result.size(), 2);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-2"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-3"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  /**
   * Original: testUniqueUniqueInLimitUsing (line 253) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test13_UniqueUniqueInLimitUsing() {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtGroup where curator.salary in [500, 600] limit 1").toList();
    assertEquals(result.size(), 1);

    final var expectedNames = Arrays.asList("PZ-08-2", "PZ-08-3");
    for (var aResult : result) {
      assertTrue(expectedNames.contains(aResult.getProperty("name")));
    }

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 2);
  }

  /**
   * Original: testUniquePartialSearch (line 275) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test14_UniquePartialSearch() {
    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where diploma.name = 'diploma3'").toList();

    assertEquals(result.size(), 2);
    final var expectedNames = Arrays.asList("William James", "James Bell");
    for (var aResult : result) {
      assertTrue(expectedNames.contains(aResult.getProperty("name")));
    }

    assertEquals(indexUsages(), oldIndexUsage + 2);
  }

  /**
   * Original: testHashIndexIsUsedAsBaseIndex (line 292) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test15_HashIndexIsUsedAsBaseIndex() {
    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where transcript.id = '1'").toList();

    assertEquals(result.size(), 1);

    assertEquals(indexUsages(), oldIndexUsage + 2);
  }

  /**
   * Original: testCompositeIndex (line 305) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  @Test
  public void test16_CompositeIndex() {
    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where skill.name = 'math'").toList();

    assertEquals(result.size(), 1);

    assertEquals(indexUsages(), oldIndexUsage + 2);
  }

  /**
   * Helper method to count documents with a specific field value. Original:
   * containsDocumentWithFieldValue (line 492) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectByLinkedSchemaPropertyIndexReuseTest.java
   */
  private static int containsDocumentWithFieldValue(
      final List<Result> resultList, final String fieldName, final Object fieldValue) {
    var count = 0;
    for (final var docItem : resultList) {
      if (fieldValue.equals(docItem.getProperty(fieldName))) {
        count++;
      }
    }
    return count;
  }
}
