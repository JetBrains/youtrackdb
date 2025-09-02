package com.jetbrains.youtrackdb.internal.lucene.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import org.apache.lucene.document.DateTools;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneRangeTest extends BaseLuceneTest {

  private long baseTime;

  @Before
  public void setUp() throws Exception {
    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("Person");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createProperty("date", PropertyType.DATETIME);
    cls.createProperty("age", PropertyType.INTEGER);

    baseTime = System.currentTimeMillis();
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
          .setPropertyInChain("date", baseTime - (i * 3600 * 24 * 1000))
          .setProperty("age", i);
      session.commit();
    }
  }

  @Test
  public void shouldUseRangeQueryOnSingleIntegerField() {
    session.execute("create index Person.age on Person(age) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex("Person.age")

            .size(session))
        .isEqualTo(10);
    session.commit();

    session.begin();
    // range
    var results = session.execute("SELECT FROM Person WHERE age LUCENE 'age:[5 TO 6]'");

    assertThat(IteratorUtils.count(results)).isEqualTo(2);

    // single value
    results = session.execute("SELECT FROM Person WHERE age LUCENE 'age:5'");

    assertThat(IteratorUtils.count(results)).isEqualTo(1);
    session.commit();
  }

  @Test
  public void shouldUseRangeQueryOnSingleDateField() {
    session.execute("create index Person.date on Person(date) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex("Person.date")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(baseTime, DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            baseTime - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    session.begin();
    // range
    var results =
        session.execute(
            "SELECT FROM Person WHERE date LUCENE 'date:[" + fiveDaysAgo + " TO " + today + "]'");

    assertThat(IteratorUtils.count(results)).isEqualTo(5);
    session.commit();
  }

  @Test
  @Ignore
  public void shouldUseRangeQueryMultipleField() {
    session.execute(
            "create index Person.composite on Person(name,surname,date,age) FULLTEXT ENGINE LUCENE")
        .close();

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex("Person.composite")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(baseTime, DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            baseTime - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    // name and age range
    var results =
        session.query(
            "SELECT * FROM Person WHERE [name,surname,date,age] LUCENE 'age:[5 TO 6] name:robert "
                + " '");

    assertThat(IteratorUtils.count(results)).isEqualTo(3);

    // date range
    results =
        session.query(
            "SELECT FROM Person WHERE [name,surname,date,age] LUCENE 'date:["
                + fiveDaysAgo
                + " TO "
                + today
                + "]'");

    assertThat(IteratorUtils.count(results)).isEqualTo(5);

    // age and date range with MUST
    results =
        session.query(
            "SELECT FROM Person WHERE [name,surname,date,age] LUCENE '+age:[4 TO 7]  +date:["
                + fiveDaysAgo
                + " TO "
                + today
                + "]'");

    assertThat(IteratorUtils.count(results)).isEqualTo(2);
  }

  @Test
  public void shouldUseRangeQueryMultipleFieldWithDirectIndexAccess() {
    session.execute(
            "create index Person.composite on Person(name,surname,date,age) FULLTEXT ENGINE LUCENE")
        .close();

    session.begin();
    assertThat(
        session.getSharedContext()
            .getIndexManager()
            .getIndex("Person.composite")

            .size(session))
        .isEqualTo(10);
    session.commit();

    var today = DateTools.timeToString(baseTime, DateTools.Resolution.MINUTE);
    var fiveDaysAgo =
        DateTools.timeToString(
            baseTime - (5 * 3600 * 24 * 1000), DateTools.Resolution.MINUTE);

    // name and age range
    final var index =
        session.getSharedContext().getIndexManager().getIndex("Person.composite");
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
}
