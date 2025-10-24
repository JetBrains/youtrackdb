package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import java.util.Arrays;
import java.util.List;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

/**
 * <p>Each test method tests different traverse index combination with different operations.
 *
 * <p>Method name are used to describe test case, first part is chain of types of indexes that are
 * used in test, second part define operation which are tested, and the last part describe whether
 * or not {@code limit} operator are used in query.
 *
 * <p>
 *
 * <p>Prefix "lpirt" in class names means "LinkedPropertyIndexReuseTest".
 */
@SuppressWarnings("SuspiciousMethodCalls")
@Ignore("Rewrite these tests for the new SQL engine")
public class SQLSelectByLinkedSchemaPropertyIndexReuseTest extends AbstractIndexReuseTest {

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    createSchemaForTest();
    fillDataSet();
  }

  @Override
  @AfterClass
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.execute("drop class lpirtStudent").close();
    session.execute("drop class lpirtGroup").close();
    session.execute("drop class lpirtCurator").close();

    super.afterClass();
  }

  @Test
  public void testNotUniqueUniqueNotUniqueEqualsUsing() throws Exception {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where group.curator.name = 'Someone'").toList();
    assertEquals(result.size(), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "John Smith"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  @Test
  public void testNotUniqueUniqueUniqueEqualsUsing() throws Exception {

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

  @Test
  public void testNotUniqueUniqueNotUniqueEqualsLimitUsing() throws Exception {

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

  @Test
  public void testNotUniqueUniqueUniqueMinorUsing() throws Exception {

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

  @Test
  public void testNotUniqueUniqueUniqueMinorLimitUsing() throws Exception {

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

  @Test
  public void testUniqueNotUniqueMinorEqualsUsing() throws Exception {

    var oldIndexUsage = indexUsages();

    var result =
        session.query("select from lpirtStudent where diploma.GPA <= 4").toList();
    assertEquals(result.size(), 3);
    assertEquals(containsDocumentWithFieldValue(result, "name", "John Smith"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "James Bell"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "William James"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  @Test
  public void testUniqueNotUniqueMinorEqualsLimitUsing() throws Exception {

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

  @Test
  public void testNotUniqueUniqueUniqueMajorUsing() throws Exception {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where group.curator.salary > 1000").toList();
    assertEquals(result.size(), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "John Smith"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  @Test
  public void testNotUniqueUniqueUniqueMajorLimitUsing() throws Exception {

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

  @Test
  public void testUniqueUniqueBetweenUsing() throws Exception {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtGroup where curator.salary between 500 and 1000").toList();
    assertEquals(result.size(), 2);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-2"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-3"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  @Test
  public void testUniqueUniqueBetweenLimitUsing() throws Exception {

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

  @Test
  public void testUniqueUniqueInUsing() throws Exception {

    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtGroup where curator.salary in [500, 600]").toList();
    assertEquals(result.size(), 2);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-2"), 1);
    assertEquals(containsDocumentWithFieldValue(result, "name", "PZ-08-3"), 1);

    assertEquals(profiler.getCounter("db.demo.query.indexUsed"), oldIndexUsage + 3);
  }

  @Test
  public void testUniqueUniqueInLimitUsing() throws Exception {

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
   * When some unique composite index in the chain is queried by partial result, the final result
   * become not unique.
   */
  @Test
  public void testUniquePartialSearch() {
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

  @Test
  public void testHashIndexIsUsedAsBaseIndex() {
    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where transcript.id = '1'").toList();

    assertEquals(result.size(), 1);

    assertEquals(indexUsages(), oldIndexUsage + 2);
  }

  @Test
  public void testCompositeIndex() {
    var oldIndexUsage = indexUsages();

    var result =
        session.query(
            "select from lpirtStudent where skill.name = 'math'").toList();

    assertEquals(result.size(), 1);

    assertEquals(indexUsages(), oldIndexUsage + 2);
  }

  private long indexUsages() {
    final var oldIndexUsage = profiler.getCounter("db.demo.query.indexUsed");
    return oldIndexUsage == -1 ? 0 : oldIndexUsage;
  }

  /**
   * William James and James Bell work together on the same diploma.
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

  private void createSchemaForTest() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();

    graph.autoExecuteInTx(g ->
        g.schemaClass("lpirtStudent").fold().coalesce(
            __.unfold(),

            __.createSchemaClass("lpirtCurator",
                    __.createSchemaProperty("name", PropertyType.STRING).createPropertyIndex(
                        YTDBSchemaIndex.IndexType.UNIQUE, true),
                    __.createSchemaProperty("salary", PropertyType.INTEGER).createPropertyIndex(
                        YTDBSchemaIndex.IndexType.UNIQUE, true)
                ).createClassIndex("curotorCompositeIndex",
                    YTDBSchemaIndex.IndexType.UNIQUE, true,
                    "salary", "name").
                createSchemaClass("lpirtGroup",
                    __.createSchemaProperty("name", PropertyType.STRING).createPropertyIndex(
                        YTDBSchemaIndex.IndexType.UNIQUE, true),
                    __.createSchemaProperty("curator", PropertyType.LINK,
                            "lpirtCurator").
                        createPropertyIndex(YTDBSchemaIndex.IndexType.UNIQUE, true)
                ).
                createSchemaClass("lpirtDiploma",
                    __.createSchemaProperty("GPA", PropertyType.DOUBLE)
                        .createPropertyIndex(YTDBSchemaIndex.IndexType.NOT_UNIQUE),
                    __.createSchemaProperty("thesis", PropertyType.STRING)
                        .createPropertyIndex("diplomaThesisUnique",
                            YTDBSchemaIndex.IndexType.UNIQUE, true),
                    __.createSchemaProperty("name", PropertyType.STRING).createPropertyIndex(
                        YTDBSchemaIndex.IndexType.UNIQUE, true)
                ).
                createSchemaClass("lpirtTranscript",
                    __.createSchemaProperty("id", PropertyType.STRING).createPropertyIndex(
                        YTDBSchemaIndex.IndexType.UNIQUE, true)
                ).
                createSchemaClass("lpirtSkill").
                createSchemaProperty("name", PropertyType.STRING).createPropertyIndex(
                    YTDBSchemaIndex.IndexType.UNIQUE, true).

                createSchemaClass("lpirtStudent",
                    __.createSchemaProperty("name", PropertyType.STRING).
                        createPropertyIndex(YTDBSchemaIndex.IndexType.UNIQUE, true),
                    __.createSchemaProperty("group", PropertyType.LINK, "lpirtGroup")
                        .createPropertyIndex(
                            YTDBSchemaIndex.IndexType.NOT_UNIQUE),
                    __.createSchemaProperty("diploma", PropertyType.LINK, "lpirtDiploma"),
                    __.createSchemaProperty("transcript", PropertyType.LINK,
                            "lpirtTranscript")
                        .createPropertyIndex(YTDBSchemaIndex.IndexType.UNIQUE, true),
                    __.createSchemaProperty("skill", PropertyType.LINK, "lpirtSkill")
                )
                .createClassIndex("studentDiplomaAndNameIndex", YTDBSchemaIndex.IndexType.UNIQUE,
                    "diploma", "name")
                .createClassIndex("studentSkillAndGroupIndex", YTDBSchemaIndex.IndexType.NOT_UNIQUE,
                    "skill", "group")
        )
    );
  }

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
