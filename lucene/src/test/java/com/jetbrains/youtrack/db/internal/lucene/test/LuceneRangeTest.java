package com.jetbrains.youtrack.db.internal.lucene.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import org.apache.lucene.document.DateTools;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneRangeTest extends BaseLuceneTest {

  @Before
  public void setUp() throws Exception {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("Person");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createProperty("date", PropertyType.DATETIME);
    cls.createProperty("age", PropertyType.INTEGER);

    var names =
        Arrays.asList(
            "John",
            "Robert",
            "Jane",
            "andrew",
            "Scott",
            "luke",
            "Enriquez",
            "Luis",
            "Gabriel",
            "Sara");
    for (var i = 0; i < 10; i++) {
      session.begin();
      // from today back one day a time
      ((EntityImpl) session.newEntity("Person"))
          .field("name", names.get(i))
          .field("surname", "Reese")
          // from today back one day a time
          .field("date", System.currentTimeMillis() - (i * 3600 * 24 * 1000))
          .field("age", i);
      session.commit();
    }
  }

  @Test
  public void shouldUseRangeQueryOnSingleIntegerField() {
    session.command("create index Person.age on Person(age) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    assertThat(
        session.getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "Person.age")

            .size(session))
        .isEqualTo(10);
    session.commit();

    // range
    var results = session.command("SELECT FROM Person WHERE age LUCENE 'age:[5 TO 6]'");

    assertThat(results).hasSize(2);

    // single value
    results = session.command("SELECT FROM Person WHERE age LUCENE 'age:5'");

    assertThat(results).hasSize(1);
  }

  @Test
  public void shouldUseRangeQueryOnSingleDateField() {
    session.command("create index Person.date on Person(date) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    assertThat(
        session.getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "Person.date")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(System.currentTimeMillis(), DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            System.currentTimeMillis() - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    // range
    var results =
        session.command(
            "SELECT FROM Person WHERE date LUCENE 'date:[" + fiveDaysAgo + " TO " + today + "]'");

    assertThat(results).hasSize(5);
  }

  @Test
  @Ignore
  public void shouldUseRangeQueryMultipleField() {
    session.command(
            "create index Person.composite on Person(name,surname,date,age) FULLTEXT ENGINE LUCENE")
        .close();

    session.begin();
    assertThat(
        session.getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "Person.composite")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(System.currentTimeMillis(), DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            System.currentTimeMillis() - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    // name and age range
    var results =
        session.query(
            "SELECT * FROM Person WHERE [name,surname,date,age] LUCENE 'age:[5 TO 6] name:robert "
                + " '");

    assertThat(results).hasSize(3);

    // date range
    results =
        session.query(
            "SELECT FROM Person WHERE [name,surname,date,age] LUCENE 'date:["
                + fiveDaysAgo
                + " TO "
                + today
                + "]'");

    assertThat(results).hasSize(5);

    // age and date range with MUST
    results =
        session.query(
            "SELECT FROM Person WHERE [name,surname,date,age] LUCENE '+age:[4 TO 7]  +date:["
                + fiveDaysAgo
                + " TO "
                + today
                + "]'");

    assertThat(results).hasSize(2);
  }

  @Test
  public void shouldUseRangeQueryMultipleFieldWithDirectIndexAccess() {
    session.command(
            "create index Person.composite on Person(name,surname,date,age) FULLTEXT ENGINE LUCENE")
        .close();

    session.begin();
    assertThat(
        session.getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "Person.composite")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(System.currentTimeMillis(), DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            System.currentTimeMillis() - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    // name and age range
    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, "Person.composite");
    try (var stream = index.getRids(session, "name:luke  age:[5 TO 6]")) {
      assertThat(stream.count()).isEqualTo(2);
    }

    // date range
    try (var stream =
        index.getRids(session, "date:[" + fiveDaysAgo + " TO " + today + "]")) {
      assertThat(stream.count()).isEqualTo(5);
    }

    // age and date range with MUST
    try (var stream =
        index

            .getRids(session, "+age:[4 TO 7]  +date:[" + fiveDaysAgo + " TO " + today + "]")) {
      assertThat(stream.count()).isEqualTo(2);
    }
  }

  @Test
  public void shouldFetchOnlyFromACluster() {
    session.command("create index Person.name on Person(name) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    assertThat(
        session.getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "Person.name")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var cluster = session.getMetadata().getSchema().getClass("Person").getClusterIds()[1];

    var results =
        session.command("SELECT FROM Person WHERE name LUCENE '+_CLUSTER:" + cluster + "'");

    assertThat(results).hasSize(2);
  }
}
