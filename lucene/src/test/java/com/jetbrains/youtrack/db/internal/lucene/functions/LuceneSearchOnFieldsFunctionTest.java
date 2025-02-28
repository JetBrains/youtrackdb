package com.jetbrains.youtrack.db.internal.lucene.functions;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.lucene.test.BaseLuceneTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSearchOnFieldsFunctionTest extends BaseLuceneTest {

  @Before
  public void setUp() throws Exception {
    final var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");
    session.execute("sql", getScriptFromStream(stream));
    session.command("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE ");
    session.command("create index Song.author on Song (author) FULLTEXT ENGINE LUCENE ");
    session.command(
        "create index Song.lyrics_description on Song (lyrics,description) FULLTEXT ENGINE LUCENE"
            + " ");
  }

  @Test
  public void shouldSearchOnSingleField() throws Exception {
    final var resultSet =
        session.query("SELECT from Song where SEARCH_FIELDS(['title'], 'BELIEVE') = true");
    assertThat(resultSet).hasSize(2);
    resultSet.close();
  }

  @Test
  public void shouldSearchOnSingleFieldWithLeadingWildcard() throws Exception {
    // TODO: metadata still not used
    final var resultSet =
        session.query(
            "SELECT from Song where SEARCH_FIELDS(['title'], '*EVE*', {'allowLeadingWildcard':"
                + " true}) = true");
    assertThat(resultSet).hasSize(14);
    resultSet.close();
  }

  @Test
  public void shouldSearhOnTwoFieldsInOR() throws Exception {
    final var resultSet =
        session.query(
            "SELECT from Song where SEARCH_FIELDS(['title'], 'BELIEVE') = true OR"
                + " SEARCH_FIELDS(['author'], 'Bob') = true ");
    assertThat(resultSet).hasSize(41);
    resultSet.close();
  }

  @Test
  public void shouldSearchOnTwoFieldsInAND() throws Exception {
    final var resultSet =
        session.query(
            "SELECT from Song where SEARCH_FIELDS(['title'], 'tambourine') = true AND"
                + " SEARCH_FIELDS(['author'], 'Bob') = true ");
    assertThat(resultSet).hasSize(1);
    resultSet.close();
  }

  @Test
  public void shouldSearhOnTwoFieldsWithLeadingWildcardInAND() throws Exception {
    final var resultSet =
        session.query(
            "SELECT from Song where SEARCH_FIELDS(['title'], 'tambourine') = true AND"
                + " SEARCH_FIELDS(['author'], 'Bob', {'allowLeadingWildcard': true}) = true ");
    assertThat(resultSet).hasSize(1);
    resultSet.close();
  }

  @Test
  public void shouldSearchOnMultiFieldIndex() throws Exception {
    var resultSet =
        session.query(
            "SELECT from Song where SEARCH_FIELDS(['lyrics','description'],"
                + " '(description:happiness) (lyrics:sad)  ') = true ");
    assertThat(resultSet).hasSize(2);
    resultSet.close();

    resultSet =
        session.query(
            "SELECT from Song where SEARCH_FIELDS(['description','lyrics'],"
                + " '(description:happiness) (lyrics:sad)  ') = true ");

    assertThat(resultSet).hasSize(2);
    resultSet.close();

    resultSet =
        session.query(
            "SELECT from Song where SEARCH_FIELDS(['description'], '(description:happiness)"
                + " (lyrics:sad)  ') = true ");
    assertThat(resultSet).hasSize(2);
    resultSet.close();
  }

  @Test(expected = CommandExecutionException.class)
  public void shouldFailWithWrongFieldName() throws Exception {
    session.query(
        "SELECT from Song where SEARCH_FIELDS(['wrongName'], '(description:happiness) (lyrics:sad) "
            + " ') = true ");
  }

  @Test
  public void shouldSearchWithHesitance() throws Exception {
    session.command("create class RockSong extends Song");

    session.begin();
    session.command(
        "create vertex RockSong set title=\"This is only rock\", author=\"A cool rocker\"");
    session.commit();

    final var resultSet =
        session.query("SELECT from RockSong where SEARCH_FIELDS(['title'], '+only +rock') = true ");
    assertThat(resultSet).hasSize(1);
    resultSet.close();
  }

  @Test
  public void testSquareBrackets() throws Exception {
    final var className = "testSquareBrackets";
    final var classNameE = "testSquareBracketsE";

    session.command("create class " + className + " extends V;");
    session.command("create property " + className + ".id Integer;");
    session.command("create property " + className + ".name String;");
    session.command(
        "CREATE INDEX " + className + ".name ON " + className + "(name) FULLTEXT ENGINE LUCENE;");

    session.command("CREATE CLASS " + classNameE + " EXTENDS E");

    session.begin();
    session.command("insert into " + className + " set id = 1, name = 'A';");
    session.command("insert into " + className + " set id = 2, name = 'AB';");
    session.command("insert into " + className + " set id = 3, name = 'ABC';");
    session.command("insert into " + className + " set id = 4, name = 'ABCD';");

    session.command(
        "CREATE EDGE "
            + classNameE
            + " FROM (SELECT FROM "
            + className
            + " WHERE id = 1) to (SELECT FROM "
            + className
            + " WHERE id IN [2, 3, 4]);");

    session.commit();

    session.begin();
    final var result =
        session.query(
            "SELECT out('"
                + classNameE
                + "')[SEARCH_FIELDS(['name'], 'A*') = true] as theList FROM "
                + className
                + " WHERE id = 1;");
    assertThat(result.hasNext());
    final var item = result.next();
    assertThat((Object) item.getProperty("theList")).isInstanceOf(List.class);
    assertThat((List) item.getProperty("theList")).hasSize(3);
    result.close();
    session.commit();
  }

  @Test
  public void shouldSupportParameterizedMetadata() throws Exception {
    final var query = "SELECT from Song where SEARCH_FIELDS(['title'], '*EVE*', ?) = true";

    Map<String, Object> mdMap = new HashMap();
    mdMap.put("allowLeadingWildcard", true);
    session.query(query, new Object[]{mdMap}).close();
  }
}
