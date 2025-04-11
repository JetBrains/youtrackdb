package com.jetbrains.youtrack.db.internal.lucene.tests;

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
public class LuceneRangeTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("Person");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createProperty("date", PropertyType.DATETIME);
    cls.createProperty("age", PropertyType.INTEGER);
    cls.createProperty("weight", PropertyType.FLOAT);

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
          .setPropertyInChain("name", names.get(i))
          .setPropertyInChain("surname", "Reese")
          // from today back one day a time
          .setPropertyInChain("date", System.currentTimeMillis() - (i * 3600 * 24 * 1000))
          .setPropertyInChain("age", i)
          .setProperty("weight", i + 0.1f);
      session.commit();
    }
  }

  @Test
  public void shouldUseRangeQueryOnSingleFloatField() {

    //noinspection EmptyTryBlock
    try (final var command =
        session.execute("create index Person.weight on Person(weight) FULLTEXT ENGINE LUCENE")) {
    }

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex(session, "Person.weight")

            .size(session))
        .isEqualTo(10);
    session.commit();

    session.begin();
    // range
    try (final var results =
        session.execute("SELECT FROM Person WHERE search_class('weight:[0.0 TO 1.1]') = true")) {
      assertThat(results).hasSize(2);
    }

    // single value
    try (final var results =
        session.execute("SELECT FROM Person WHERE search_class('weight:7.1') = true")) {
      assertThat(results).hasSize(1);
    }
    session.commit();
  }

  @Test
  public void shouldUseRangeQueryOnSingleIntegerField() {

    //noinspection EmptyTryBlock
    try (var command =
        session.execute("create index Person.age on Person(age) FULLTEXT ENGINE LUCENE")) {
    }

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex(session, "Person.age")

            .size(session))
        .isEqualTo(10);
    session.commit();

    session.begin();
    // range
    try (var results =
        session.execute("SELECT FROM Person WHERE search_class('age:[5 TO 6]') = true")) {

      assertThat(results).hasSize(2);
    }

    // single value
    try (var results = session.execute(
        "SELECT FROM Person WHERE search_class('age:5') = true")) {
      assertThat(results).hasSize(1);
    }
    session.commit();
  }

  @Test
  public void shouldUseRangeQueryOnSingleDateField() {
    //noinspection EmptyTryBlock
    try (var command =
        session.execute("create index Person.date on Person(date) FULLTEXT ENGINE LUCENE")) {
    }

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex(session, "Person.date")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(System.currentTimeMillis(), DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            System.currentTimeMillis() - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    session.begin();
    // range
    try (final var results =
        session.execute(
            "SELECT FROM Person WHERE search_class('date:["
                + fiveDaysAgo
                + " TO "
                + today
                + "]')=true")) {
      assertThat(results).hasSize(5);
    }
    session.commit();
  }

  @Test
  @Ignore
  public void shouldUseRangeQueryMultipleField() {

    //noinspection EmptyTryBlock
    try (var command =
        session.execute(
            "create index Person.composite on Person(name,surname,date,age) FULLTEXT ENGINE"
                + " LUCENE")) {
    }

    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex(session, "Person.composite")

            .size(session))
        .isEqualTo(10);

    var today = DateTools.timeToString(System.currentTimeMillis(), DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            System.currentTimeMillis() - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    // name and age range
    try (var results =
        session.execute(
            "SELECT * FROM Person WHERE search_class('age:[5 TO 6] name:robert  ')=true")) {

      assertThat(results).hasSize(3);
    }

    // date range
    try (var results =
        session.execute(
            "SELECT FROM Person WHERE search_class('date:["
                + fiveDaysAgo
                + " TO "
                + today
                + "]')=true")) {

      assertThat(results).hasSize(5);
    }

    // age and date range with MUST
    try (var results =
        session.execute(
            "SELECT FROM Person WHERE search_class('+age:[4 TO 7]  +date:["
                + fiveDaysAgo
                + " TO "
                + today
                + "]')=true")) {
      assertThat(results).hasSize(2);
    }
  }

  @Test
  public void shouldUseRangeQueryMultipleFieldWithDirectIndexAccess() {
    //noinspection EmptyTryBlock
    try (var command =
        session.execute(
            "create index Person.composite on Person(name,surname,date,age) FULLTEXT ENGINE"
                + " LUCENE")) {
    }

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex(session, "Person.composite")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(System.currentTimeMillis(), DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            System.currentTimeMillis() - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    var index = session.getSharedContext().getIndexManager()
        .getIndex(session, "Person.composite");

    // name and age range
    try (var stream = index.getRids(session, "name:luke  age:[5 TO 6]")) {
      assertThat(stream.count()).isEqualTo(2);
    }
    try (var stream =
        index.getRids(session, "date:[" + fiveDaysAgo + " TO " + today + "]")) {
      assertThat(stream.count()).isEqualTo(5);
    }
    try (var stream =
        index

            .getRids(session, "+age:[4 TO 7]  +date:[" + fiveDaysAgo + " TO " + today + "]")) {
      assertThat(stream.count()).isEqualTo(2);
    }
    try (var stream = index.getRids(session, "*:*")) {
      assertThat(stream.count()).isEqualTo(10);
    }
  }
}
