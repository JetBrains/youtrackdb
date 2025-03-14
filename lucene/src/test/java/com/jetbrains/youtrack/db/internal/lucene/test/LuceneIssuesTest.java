package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.api.query.ResultSet;
import org.assertj.core.api.Assertions;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneIssuesTest extends BaseLuceneTest {

  @Test
  public void testGh_7382() throws Exception {

    try (var stream = ClassLoader.getSystemResourceAsStream("testGh_7382.osql")) {
      session.execute("sql", getScriptFromStream(stream)).close();
    }

    final var index =
        session.getMetadata().getIndexManagerInternal().getIndex(session, "class_7382_multi");
    try (var rids =
        index

            .getRids(session, "server:206012226875414 AND date:[201703120000 TO  201703120001]")) {
      Assertions.assertThat(rids.count()).isEqualTo(1);
    }
  }

  @Test
  public void testGh_4880_moreIndexesOnProperty() throws Exception {
    try (final var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql")) {
      session.execute("sql", getScriptFromStream(stream)).close();
    }

    session.command("create index Song.title_ft on Song (title,author) FULLTEXT ENGINE LUCENE")
        .close();
    session.command("CREATE INDEX Song.author on Song (author)  NOTUNIQUE").close();

    session.query("SELECT from Song where title = 'BELIEVE IT OR NOT' ").close();

    session.command(
            "EXPLAIN SELECT from Song where author = 'Traditional'  OR [title,author] LUCENE"
                + " '(title:believe'")
        .close();
  }

  @Test
  @Ignore
  public void testGh_issue7513() throws Exception {

    try (var stream = ClassLoader.getSystemResourceAsStream("testGh_7513.osql")) {
      session.execute("sql", getScriptFromStream(stream)).close();
    }

    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "Item.content");
    try (var rids = index.getRids(session, "'Харько~0.2")) {
      Assertions.assertThat(rids.count() >= 3).isTrue();
    }
  }

  @Test
  public void test_ph8929() throws Exception {
    try (var stream = ClassLoader.getSystemResourceAsStream("testPh_8929.osql")) {
      session.execute("sql", getScriptFromStream(stream)).close();
    }

    ResultSet documents;

    documents = session.query("select from Test where [a] lucene 'lion'");

    Assertions.assertThat(documents).hasSize(1);

    documents = session.query("select from Test where [b] lucene 'mouse'");

    Assertions.assertThat(documents).hasSize(1);

    documents = session.query("select from Test where [a] lucene 'lion' OR [b] LUCENE 'mouse' ");

    Assertions.assertThat(documents).hasSize(2);
  }

  @Test
  public void test_ph8929_Single() throws Exception {

    try (var stream = ClassLoader.getSystemResourceAsStream("testPh_8929.osql")) {
      session.execute("sql", getScriptFromStream(stream)).close();
    }

    ResultSet documents;

    documents = session.query("select from Test where a lucene 'lion'");

    Assertions.assertThat(documents).hasSize(1);

    documents = session.query("select from Test where b lucene 'mouse'");

    Assertions.assertThat(documents).hasSize(1);

    documents = session.query("select from Test where a lucene 'lion' OR b LUCENE 'mouse' ");

    Assertions.assertThat(documents).hasSize(2);
  }
}
