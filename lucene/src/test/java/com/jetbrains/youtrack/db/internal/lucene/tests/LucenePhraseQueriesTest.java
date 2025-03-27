package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LucenePhraseQueriesTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {

    var type = session.createVertexClass("Role");
    type.createProperty("name", PropertyType.STRING);

    session.execute(
        "create index Role.name on Role (name) FULLTEXT ENGINE LUCENE "
            + "METADATA{"
            + "\"name_index\": \"org.apache.lucene.analysis.standard.StandardAnalyzer\","
            + "\"name_index_stopwords\": [],"
            + "\"name_query\": \"org.apache.lucene.analysis.standard.StandardAnalyzer\","
            + "\"name_query_stopwords\": []"
            //                + "\"name_query\":
            // \"org.apache.lucene.analysis.core.KeywordAnalyzer\""
            + "} ");

    session.begin();
    var role = session.newVertex("Role");
    role.setProperty("name", "System IT Owner");

    role = session.newVertex("Role");
    role.setProperty("name", "System Business Owner");

    role = session.newVertex("Role");
    role.setProperty("name", "System Business SME");

    role = session.newVertex("Role");
    role.setProperty("name", "System Technical SME");

    role = session.newVertex("Role");
    role.setProperty("name", "System");

    role = session.newVertex("Role");
    role.setProperty("name", "boat");

    role = session.newVertex("Role");
    role.setProperty("name", "moat");
    session.commit();
  }

  @Test
  public void testPhraseQueries() throws Exception {
    session.begin();
    var vertexes =
        session.execute("select from Role where search_class(' \"Business Owner\" ')=true  ");

    assertThat(vertexes).hasSize(1);

    vertexes = session.execute(
        "select from Role where search_class( ' \"Owner of Business\" ')=true  ");

    assertThat(vertexes).hasSize(0);

    vertexes = session.execute(
        "select from Role where search_class(' \"System Owner\" '  )=true  ");

    assertThat(vertexes).hasSize(0);

    vertexes = session.execute(
        "select from Role where search_class(' \"System SME\"~1 '  )=true  ");

    assertThat(vertexes).hasSize(2);

    vertexes =
        session.execute("select from Role where search_class(' \"System Business\"~1 '  )=true  ");

    assertThat(vertexes).hasSize(2);

    vertexes = session.execute("select from Role where search_class(' /[mb]oat/ '  )=true  ");

    assertThat(vertexes).hasSize(2);
    session.commit();
  }

  @Test
  public void testComplexPhraseQueries() throws Exception {

    session.begin();
    var vertexes =
        session.execute("select from Role where search_class(?)=true", "\"System SME\"~1");

    assertThat(vertexes).allMatch(v -> v.<String>getProperty("name").contains("SME"));

    vertexes = session.execute("select from Role where search_class(? )=true", "\"SME System\"~1");

    assertThat(vertexes).isEmpty();

    vertexes = session.execute("select from Role where search_class(?) =true",
        "\"Owner Of Business\"");
    vertexes.stream().forEach(v -> System.out.println("v = " + v.getProperty("name")));

    assertThat(vertexes).isEmpty();

    vertexes =
        session.execute("select from Role where search_class(? )=true", "\"System Business SME\"");

    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System Business SME"));

    vertexes = session.execute("select from Role where search_class(? )=true",
        "\"System Owner\"~1 -IT");
    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System Business Owner"));

    vertexes = session.execute("select from Role where search_class(? )=true",
        "+System +Own*~0.0 -IT");
    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System Business Owner"));

    vertexes =
        session.execute("select from Role where search_class(? )=true",
            "\"System Owner\"~1 -Business");
    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System IT Owner"));
    session.commit();
  }
}
