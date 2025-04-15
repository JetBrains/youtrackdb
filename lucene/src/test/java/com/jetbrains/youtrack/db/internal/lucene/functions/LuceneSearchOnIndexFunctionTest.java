package com.jetbrains.youtrack.db.internal.lucene.functions;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.lucene.test.BaseLuceneTest;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSearchOnIndexFunctionTest extends BaseLuceneTest {

  @Before
  public void setUp() throws Exception {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    //    db.runScript("sql", getScriptFromStream(stream)).close();
    session.runScript("sql", getScriptFromStream(stream));

    session.execute("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE ");
    session.execute("create index Song.author on Song (author) FULLTEXT ENGINE LUCENE ");
    session.execute("create index Author.name on Author (name) FULLTEXT ENGINE LUCENE ");
    session.execute(
        "create index Song.lyrics_description on Song (lyrics,description) FULLTEXT ENGINE LUCENE"
            + " ");
  }

  @Test
  public void shouldSearchOnSingleIndex() throws Exception {

    var resultSet =
        session.query("SELECT from Song where SEARCH_INDEX('Song.title', 'BELIEVE') = true");

    //    resultSet.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 2)));
    assertThat(resultSet).hasSize(2);

    resultSet.close();

    resultSet = session.query("SELECT from Song where SEARCH_INDEX('Song.title', \"bel*\") = true");

    assertThat(resultSet).hasSize(3);
    resultSet.close();

    resultSet = session.query("SELECT from Song where SEARCH_INDEX('Song.title', 'bel*') = true");

    assertThat(resultSet).hasSize(3);

    resultSet.close();
  }

  @Test
  public void shouldFindNothingOnEmptyQuery() throws Exception {

    var resultSet = session.query(
        "SELECT from Song where SEARCH_INDEX('Song.title', '') = true");

    //    resultSet.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 2)));
    assertThat(resultSet).hasSize(0);

    resultSet.close();
  }

  @Test
  //  @Ignore
  public void shouldSearchOnSingleIndexWithLeadingWildcard() throws Exception {

    // TODO: metadata still not used
    var resultSet =
        session.query(
            "SELECT from Song where SEARCH_INDEX('Song.title', '*EVE*', {'allowLeadingWildcard':"
                + " true}) = true");

    //    resultSet.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 2)));
    assertThat(resultSet).hasSize(14);

    resultSet.close();
  }

  @Test
  public void shouldSearchOnTwoIndexesInOR() throws Exception {

    var resultSet =
        session.query(
            "SELECT from Song where SEARCH_INDEX('Song.title', 'BELIEVE') = true OR"
                + " SEARCH_INDEX('Song.author', 'Bob') = true ");

    assertThat(resultSet).hasSize(41);
    resultSet.close();
  }

  @Test
  public void shouldSearchOnTwoIndexesInAND() throws Exception {

    var resultSet =
        session.query(
            "SELECT from Song where SEARCH_INDEX('Song.title', 'tambourine') = true AND"
                + " SEARCH_INDEX('Song.author', 'Bob') = true ");

    assertThat(resultSet).hasSize(1);
    resultSet.close();
  }

  @Test
  public void shouldSearchOnTwoIndexesWithLeadingWildcardInAND() throws Exception {

    var resultSet =
        session.query(
            "SELECT from Song where SEARCH_INDEX('Song.title', 'tambourine') = true AND"
                + " SEARCH_INDEX('Song.author', 'Bob', {'allowLeadingWildcard': true}) = true ");

    assertThat(resultSet).hasSize(1);
    resultSet.close();
  }

  @Test(expected = CommandExecutionException.class)
  public void shouldFailWithWrongIndexName() throws Exception {

    session.query("SELECT from Song where SEARCH_INDEX('Song.wrongName', 'tambourine') = true ")
        .close();
  }

  @Test
  public void shouldSupportParameterizedMetadata() throws Exception {
    final var query = "SELECT from Song where SEARCH_INDEX('Song.title', '*EVE*', ?) = true";

    Map<String, Object> mdMap = new HashMap();
    mdMap.put("allowLeadingWildcard", true);
    session.query(query, new Object[]{mdMap}).close();
  }
}
