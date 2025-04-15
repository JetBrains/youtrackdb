package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneQueryParserTest extends LuceneBaseTest {

  @Before
  public void init() {

    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");
    session.runScript("sql", getScriptFromStream(stream));
  }

  @Test
  public void shouldSearchWithLeadingWildcard() {

    // enabling leading wildcard
    session.execute(
        "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE metadata"
            + " {\"allowLeadingWildcard\": true}");

    session.begin();
    // querying with leading wildcard
    var docs = session.query("select * from Song where search_class(\"(title:*tain)\") = true");

    assertThat(docs).hasSize(4);
    docs.close();
    session.commit();
  }

  @Test
  public void shouldSearchWithLowercaseExpandedTerms() {

    // enabling leading wildcard
    session.execute(
        "create index Song.author on Song (author) FULLTEXT ENGINE LUCENE metadata {\"default\": \""
            + KeywordAnalyzer.class.getCanonicalName()
            + "\", \"lowercaseExpandedTerms\": false}");

    var docs = session.query("select * from Song where search_class('Hunter') =true");

    assertThat(docs).hasSize(97);
    docs.close();

    docs = session.query("select * from Song where search_class('HUNTER')=true");

    assertThat(docs).hasSize(0);
    docs.close();
  }

  @Test
  public void shouldFailIfLeadingWild() {

    // enabling leading wildcard
    session.execute(
        "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE metadata"
            + " {\"allowLeadingWildcard\": true}");

    // querying with leading wildcard
    var docs = session.query("select * from Song where search_class ('title:*tain')=true");

    assertThat(docs).hasSize(4);
    docs.close();
  }

  @Test
  public void shouldUseBoostsFromQuery() throws Exception {
    // enabling leading wildcard
    session.execute(
        "create index Song.title_author on Song (title,author) FULLTEXT ENGINE LUCENE ");

    // querying with boost
    var rs =
        session.query(
            "select * from Song where search_class ('(title:forever)^2 OR author:Boudleaux')=true");
    var boostedDocs =
        rs.stream().map(r -> r.<String>getProperty("title")).collect(Collectors.toList());

    assertThat(boostedDocs).hasSize(5);

    rs.close();
    // forever in title is boosted
    assertThat(boostedDocs)
        .contains(
            "THIS TIME FOREVER",
            "FOREVER YOUNG",
            "TOMORROW IS FOREVER",
            "STARS AND STRIPES FOREVER" // boosted
            ,
            "ALL I HAVE TO DO IS DREAM");

    rs =
        session.query(
            "select * from Song where search_class ('(title:forever) OR author:Boudleaux')=true");
    var docs =
        rs.stream().map(r -> r.<String>getProperty("title")).collect(Collectors.toList());

    assertThat(docs).hasSize(5);
    rs.close();
    // no boost, order changed
    assertThat(docs)
        .contains(
            "THIS TIME FOREVER",
            "FOREVER YOUNG",
            "TOMORROW IS FOREVER",
            "ALL I HAVE TO DO IS DREAM",
            "STARS AND STRIPES FOREVER"); // no boost, last position
  }

  @Test
  public void shouldUseBoostsFromMap() throws Exception {
    // enabling leading wildcard
    session.execute(
        "create index Song.title_author on Song (title,author) FULLTEXT ENGINE LUCENE ");

    // querying with boost
    var rs =
        session.query(
            "select * from Song where search_class ('title:forever OR author:Boudleaux' ,"
                + " {'boost':{ 'title': 2  }  })=true");
    var boostedDocs =
        rs.stream().map(r -> r.<String>getProperty("title")).collect(Collectors.toList());

    assertThat(boostedDocs).hasSize(5);

    rs.close();
    // forever in title is boosted
    assertThat(boostedDocs)
        .contains(
            "THIS TIME FOREVER",
            "FOREVER YOUNG",
            "TOMORROW IS FOREVER",
            "STARS AND STRIPES FOREVER" // boosted
            ,
            "ALL I HAVE TO DO IS DREAM");

    rs =
        session.query(
            "select * from Song where search_class ('(title:forever) OR author:Boudleaux')=true");
    var docs =
        rs.stream().map(r -> r.<String>getProperty("title")).collect(Collectors.toList());

    assertThat(docs).hasSize(5);
    rs.close();

    // no boost, order changed
    assertThat(docs)
        .contains(
            "THIS TIME FOREVER",
            "FOREVER YOUNG",
            "TOMORROW IS FOREVER",
            "ALL I HAVE TO DO IS DREAM",
            "STARS AND STRIPES FOREVER"); // no boost, last position
  }

  @Test
  public void shouldUseBoostsFromMapAndSyntax() throws Exception {
    // enabling leading wildcard
    session.execute(
        "create index Song.title_author on Song (title,author) FULLTEXT ENGINE LUCENE ");

    // querying with boost
    var rs =
        session.query(
            "select $score from Song where search_class ('title:forever OR author:Boudleaux' ,"
                + " {'boost':{ 'title': 2  }  })=true order by $score desc");
    var boostedDocs =
        rs.stream().map(r -> r.<Float>getProperty("$score")).collect(Collectors.toList());

    assertThat(boostedDocs).hasSize(5);

    rs.close();

    rs =
        session.query(
            "select $score from Song where search_class ('(title:forever)^2 OR"
                + " author:Boudleaux')=true order by $score desc");
    var docs =
        rs.stream().map(r -> r.<Float>getProperty("$score")).collect(Collectors.toList());

    assertThat(docs).hasSize(5);
    rs.close();

    Assert.assertEquals(boostedDocs, docs);

    assertThat(docs).hasSize(5);
    rs.close();
  }

  @Test
  public void ahouldOverrideAnalyzer() throws Exception {

    // enabling leading wildcard
    session.execute(
        "create index Song.title_author on Song (title,author) FULLTEXT ENGINE LUCENE ");

    // querying with boost
    var resultSet =
        session.query(
            "select * from Song where search_class ('title:forever OR author:boudleaux' , "
                + "{'customAnalysis': true, "
                + "  \"query\": \"org.apache.lucene.analysis.core.KeywordAnalyzer\" } "
                + ")=true");

    assertThat(resultSet).hasSize(5);
    resultSet.close();
  }
}
