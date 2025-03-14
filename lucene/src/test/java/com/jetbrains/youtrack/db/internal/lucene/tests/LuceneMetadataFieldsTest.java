package com.jetbrains.youtrack.db.internal.lucene.tests;

import static com.jetbrains.youtrack.db.internal.lucene.functions.LuceneFunctionsUtils.doubleEscape;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneMetadataFieldsTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.execute("sql", getScriptFromStream(stream));

    session.command("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE ");
  }

  @Test
  public void shouldFetchOnlyFromACluster() throws Exception {

    assertThat(
        session.getMetadata()
            .getIndexManagerInternal()
            .getIndex(session, "Song.title")

            .size(session))
        .isEqualTo(585);

    var cluster = session.getMetadata().getSchema().getClass("Song").getClusterIds()[1];

    var results =
        session.query("SELECT FROM Song WHERE search_class('+_CLUSTER:" + cluster + "')=true ");

    assertThat(results).hasSize(73);
    results.close();
  }

  @Test
  public void shouldFetchByRid() throws Exception {
    var songs = session.query("SELECT FROM Song limit 2").toList();

    var ridQuery = doubleEscape(songs.get(0).getIdentity() + " " + songs.get(1).getIdentity());
    var results =
        session.query("SELECT FROM Song WHERE search_class('RID:(" + ridQuery + ") ')=true ");

    assertThat(results).hasSize(2);
    results.close();
  }
}
