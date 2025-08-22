package com.jetbrains.youtrackdb.internal.lucene.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneQeuryParserTest extends BaseLuceneTest {

  @Before
  public void init() {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");
    session.computeScript("sql", getScriptFromStream(stream)).close();
  }

  @Test
  public void shouldSearchWithLeadingWildcard() {

    // enabling leading wildcard
    session.execute(
            "create index Song.title on Song (title) FULLTEXT ENGINE LUCENE metadata"
                + " {\"allowLeadingWildcard\": true}")
        .close();

    // querying with leading wildcard
    var docs = session.query("select * from Song where [title] LUCENE \"(title:*tain)\"");

    assertThat(docs).hasSize(4);
  }

  @Test
  public void shouldSearchWithLowercaseExpandedTerms() {

    // enabling leading wildcard
    session.execute(
            "create index Song.author on Song (author) FULLTEXT ENGINE LUCENE metadata"
                + " {\"default\": \""
                + KeywordAnalyzer.class.getCanonicalName()
                + "\", \"lowercaseExpandedTerms\": false}")
        .close();

    var docs = session.query("select * from Song where [author] LUCENE \"Hunter\"");

    assertThat(docs).hasSize(97);

    docs = session.query("select * from Song where [author] LUCENE \"HUNTER\"");

    assertThat(docs).hasSize(0);
  }
}
