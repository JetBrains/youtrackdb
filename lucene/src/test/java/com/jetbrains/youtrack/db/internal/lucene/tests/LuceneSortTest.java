package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class LuceneSortTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.runScript("sql", getScriptFromStream(stream));

    session.execute("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE ");
  }

  @Test
  public void shouldSortByReverseDocScore() throws Exception {

    session.execute("create index Author.ft on Author (name,score) FULLTEXT ENGINE LUCENE ");

    session.begin();
    var resultSet =
        session.query(
            "SELECT score, name from Author where SEARCH_CLASS('*:* ', {"
                + "sort: [ { 'field': 'score', reverse:true, type:'INT' }]"
                + "} ) = true ");

    var scores =
        resultSet.stream().map(o -> o.<Integer>getProperty("score")).collect(Collectors.toList());

    assertThat(scores).containsExactly(10, 10, 7, 5, 4);
    resultSet.close();
    session.commit();
  }

  @Test
  public void shouldSortByReverseScoreFieldValue() throws Exception {

    session.execute("create index Author.ft on Author (score) FULLTEXT ENGINE LUCENE ");

    var resultSet =
        session.query(
            "SELECT score, name from Author where SEARCH_CLASS('*:* ', {"
                + "sort: [ { 'field': 'score', reverse:true, type:'INT' }]"
                + "} ) = true ");

    var scores =
        resultSet.stream().map(o -> o.<Integer>getProperty("score")).collect(Collectors.toList());

    assertThat(scores).containsExactly(10, 10, 7, 5, 4);
    resultSet.close();
  }

  @Test
  public void shouldSortByReverseNameValue() throws Exception {

    session.execute("create index Author.ft on Author (name) FULLTEXT ENGINE LUCENE ");

    var resultSet =
        session.query(
            "SELECT score, name from Author where SEARCH_CLASS('*:* ', {"
                + "sort: [ {field: 'name', type:'STRING' , reverse:true}] "
                + "} ) = true ");

    var names =
        resultSet.stream().map(o -> o.<String>getProperty("name")).collect(Collectors.toList());

    assertThat(names)
        .containsExactly(
            "Lennon McCartney", "Jack Mountain", "Grateful Dead", "Chuck Berry", "Bob Dylan");
    resultSet.close();
  }

  @Test
  public void shouldSortByReverseNameValueWithTxRollback() throws Exception {

    session.execute("create index Author.ft on Author (name) FULLTEXT ENGINE LUCENE ");

    session.begin();

    var artist = session.newVertex("Author");

    artist.setProperty("name", "Jimi Hendrix");

    var resultSet =
        session.query(
            "SELECT score, name from Author where SEARCH_CLASS('*:* ', {"
                + "sort: [ {field: 'name', type:'STRING' , reverse:true}] "
                + "} ) = true ");

    var names =
        resultSet.stream().map(o -> o.<String>getProperty("name")).collect(Collectors.toList());

    assertThat(names)
        .containsExactly(
            "Lennon McCartney",
            "Jimi Hendrix",
            "Jack Mountain",
            "Grateful Dead",
            "Chuck Berry",
            "Bob Dylan");

    session.rollback();

    resultSet.close();
    resultSet =
        session.query(
            "SELECT score, name from Author where SEARCH_CLASS('*:* ', {"
                + "sort: [ {field: 'name', type:'STRING' , reverse:true}] "
                + "} ) = true ");

    names = resultSet.stream().map(o -> o.<String>getProperty("name")).collect(Collectors.toList());

    assertThat(names)
        .containsExactly(
            "Lennon McCartney", "Jack Mountain", "Grateful Dead", "Chuck Berry", "Bob Dylan");
    resultSet.close();
  }

  @Test
  public void shouldSortByReverseScoreFieldValueAndThenReverseName() throws Exception {

    session.execute("create index Author.ft on Author (name,score) FULLTEXT ENGINE LUCENE ");

    var resultSet =
        session.query(
            "SELECT score, name from Author where SEARCH_CLASS('*:* ', {sort: [ { 'field': 'score',"
                + " reverse:true, type:'INT' },{field: 'name', type:'STRING' , reverse:true}] } ) ="
                + " true ");

    var names =
        resultSet.stream()
            .map(o -> o.<Integer>getProperty("score") + o.<String>getProperty("name"))
            .collect(Collectors.toList());

    assertThat(names)
        .containsExactly(
            "10Chuck Berry",
            "10Bob Dylan",
            "7Lennon McCartney",
            "5Grateful Dead",
            "4Jack Mountain");
    resultSet.close();
  }
}
