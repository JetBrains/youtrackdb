package com.jetbrains.youtrack.db.internal.lucene.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Before;
import org.junit.Test;

public class LucenePhraseQueriesTest extends BaseLuceneTest {

  @Before
  public void setUp() throws Exception {

    var type = session.createVertexClass("Role");
    type.createProperty("name", PropertyType.STRING);

    session.execute(
            "create index Role.name on Role (name) FULLTEXT ENGINE LUCENE "
                + "METADATA {"
                + "\"name_index\": \"org.apache.lucene.analysis.standard.StandardAnalyzer\","
                + "\"name_index_stopwords\": [],"
                + "\"name_query\": \"org.apache.lucene.analysis.standard.StandardAnalyzer\","
                + "\"name_query_stopwords\": []"
                //                + "\"name_query\":
                // \"org.apache.lucene.analysis.core.KeywordAnalyzer\""
                + "} ")
        .close();

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
  public void testPhraseQueries() {

    var vertexes = session.query("select from Role where name lucene ' \"Business Owner\" '  ");

    assertThat(IteratorUtils.count(vertexes)).isEqualTo(1);

    vertexes = session.query("select from Role where name lucene ' \"Owner of Business\" '  ");

    assertThat(IteratorUtils.count(vertexes)).isEqualTo(0);

    vertexes = session.query("select from Role where name lucene ' \"System Owner\" '  ");

    assertThat(IteratorUtils.count(vertexes)).isEqualTo(0);

    vertexes = session.query("select from Role where name lucene ' \"System SME\"~1 '  ");

    assertThat(IteratorUtils.count(vertexes)).isEqualTo(2);

    vertexes = session.query("select from Role where name lucene ' \"System Business\"~1 '  ");

    assertThat(IteratorUtils.count(vertexes)).isEqualTo(2);

    vertexes = session.query("select from Role where name lucene ' /[mb]oat/ '  ");

    assertThat(IteratorUtils.count(vertexes)).isEqualTo(2);
  }

  @Test
  public void testComplexPhraseQueries() {

    session.begin();
    var vertexes = session.query("select from Role where name lucene ?", "\"System SME\"~1")
        .toVertexList();

    assertThat(vertexes).allMatch(v -> v.<String>getProperty("name").contains("SME"));

    vertexes = session.query("select from Role where name lucene ? ", "\"SME System\"~1")
        .toVertexList();

    assertThat(vertexes).isEmpty();

    vertexes = session.query("select from Role where name lucene ? ", "\"Owner Of Business\"")
        .toVertexList();
    vertexes.forEach(v -> System.out.println("v = " + v.getProperty("name")));

    assertThat(vertexes).isEmpty();

    vertexes = session.query("select from Role where name lucene ? ", "\"System Business SME\"")
        .toVertexList();

    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System Business SME"));

    vertexes = session.query("select from Role where name lucene ? ", "\"System Owner\"~1 -IT")
        .toVertexList();
    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System Business Owner"));

    vertexes = session.query("select from Role where name lucene ? ", "+System +Own*~0.0 -IT")
        .toVertexList();
    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System Business Owner"));

    vertexes = session.query("select from Role where name lucene ? ",
        "\"System Owner\"~1 -Business").toVertexList();
    assertThat(vertexes)
        .hasSize(1)
        .allMatch(v -> v.<String>getProperty("name").equalsIgnoreCase("System IT Owner"));
    session.commit();
  }
}
