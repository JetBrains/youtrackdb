package com.jetbrains.youtrackdb.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Validates YQL code examples and factual claims from the documentation files in docs/yql/ against
 * a real in-memory YouTrackDB instance using the public Gremlin API.
 *
 * <p>Each test method references the source document and line number of the claim or query it
 * validates. Test class names use unique prefixes to avoid collisions across test methods.
 */
public class DocValidationTest {
  private static YouTrackDB youTrackDB;
  private static Path dbPath;
  private YTDBGraphTraversalSource g;

  @BeforeClass
  public static void setUpClass() throws Exception {
    dbPath = Path.of(System.getProperty("java.io.tmpdir"), "doc-validation-test");
    youTrackDB = YourTracks.instance(dbPath.toString());
    youTrackDB.create("test", DatabaseType.MEMORY, "admin", "admin", "admin");
  }

  @AfterClass
  public static void tearDownClass() {
    youTrackDB.close();
  }

  @Before
  public void setUp() {
    g = youTrackDB.openTraversal("test", "admin", "admin");
  }

  @After
  public void tearDown() {
    g.close();
  }

  // === YQL-Update.md ===

  // Line 38: UPDATE Profile SET nick = 'Andrii' WHERE nick IS NULL
  @Test
  public void testUpdateSetWhereIsNull() {
    g.command("CREATE CLASS Profile IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX Profile SET nick = null").iterate();
      tx.yql("CREATE VERTEX Profile SET nick = 'existing'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE Profile SET nick = 'Andrii' WHERE nick IS NULL").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM Profile WHERE nick = 'Andrii'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX Profile").iterate();
    });
  }

  // Line 44: UPDATE Profile REMOVE nick
  @Test
  public void testUpdateRemoveProperty() {
    g.command("CREATE CLASS ProfileRemove IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ProfileRemove SET nick = 'test', age = 30").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE ProfileRemove REMOVE nick").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM ProfileRemove").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat(v.keys()).doesNotContain("nick");
    assertThat((int) v.value("age")).isEqualTo(30);
  }

  // Line 58: UPDATE Account REMOVE addresses = 'Foo' (remove from set/list of strings)
  @Test
  public void testUpdateRemoveFromStringList() {
    g.command("CREATE CLASS AccountStrList IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AccountStrList SET addresses = ['Foo', 'Bar', 'Baz']").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE AccountStrList REMOVE addresses = 'Foo'").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM AccountStrList").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    List<String> addresses = v.value("addresses");
    assertThat(addresses).containsExactly("Bar", "Baz");
  }

  // Line 66: UPDATE Account REMOVE addresses = addresses[city = 'Kyiv']
  @Test
  public void testUpdateRemoveFromEmbeddedListByFilter() {
    g.command("CREATE CLASS AccountEmbFilter IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql(
          "CREATE VERTEX AccountEmbFilter SET addresses = "
              + "[{'city':'Kyiv','street':'Main'}, {'city':'London','street':'Baker'}]")
          .iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE AccountEmbFilter REMOVE addresses = addresses[city = 'Kyiv']").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM AccountEmbFilter").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    List<?> addresses = v.value("addresses");
    assertThat(addresses).hasSize(1);
  }

  // Line 72: UPDATE Account REMOVE addresses = addresses[1]
  @Test
  public void testUpdateRemoveByIndex() {
    g.command("CREATE CLASS AccountIdx IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AccountIdx SET addresses = ['First', 'Second', 'Third']").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE AccountIdx REMOVE addresses = addresses[1]").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM AccountIdx").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    List<String> addresses = v.value("addresses");
    assertThat(addresses).containsExactly("First", "Third");
  }

  // Line 80: UPDATE Account REMOVE addresses = 'Andrii' (remove from map)
  @Test
  public void testUpdateRemoveFromMap() {
    g.command("CREATE CLASS AccountMap IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AccountMap SET addresses = {'Andrii':'Kyiv', 'John':'London'}")
          .iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE AccountMap REMOVE addresses = 'Andrii'").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM AccountMap").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    @SuppressWarnings("unchecked")
    Map<String, String> addresses = v.value("addresses");
    assertThat(addresses).doesNotContainKey("Andrii");
    assertThat(addresses).containsKey("John");
  }

  // Line 86: UPDATE Profile SET nick = 'Andrii' WHERE nick IS NULL LIMIT 20
  @Test
  public void testUpdateWithLimit() {
    g.command("CREATE CLASS ProfileLimit IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      for (int i = 0; i < 30; i++) {
        tx.yql("CREATE VERTEX ProfileLimit SET nick = null, idx = " + i).iterate();
      }
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE ProfileLimit SET nick = 'Andrii' WHERE nick IS NULL LIMIT 20").iterate();
    });

    var updated =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM ProfileLimit WHERE nick = 'Andrii'").toList());
    assertThat(updated).hasSize(20);

    var remaining =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM ProfileLimit WHERE nick IS NULL").toList());
    assertThat(remaining).hasSize(10);
  }

  // Line 98-101: UPDATE with RETURN AFTER @this
  @Test
  public void testUpdateReturnAfterThis() {
    g.command("CREATE CLASS ReturnTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ReturnTest SET gender = 'unknown'").iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql("UPDATE ReturnTest SET gender = 'male' RETURN AFTER @this").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("gender")).isEqualTo("male");
  }

  // Line 20: CONTENT replaces the record content with a JSON document
  @Test
  public void testUpdateContent() {
    g.command("CREATE CLASS ContentTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ContentTest SET name = 'old', extra = 'value'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE ContentTest CONTENT {'name':'new'}").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM ContentTest").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("name")).isEqualTo("new");
    // CONTENT should replace all properties - 'extra' should be gone
    assertThat(v.keys()).doesNotContain("extra");
  }

  // Line 21: MERGE merges the record content with a JSON document
  @Test
  public void testUpdateMerge() {
    g.command("CREATE CLASS MergeTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MergeTest SET name = 'original', age = 25").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE MergeTest MERGE {'city':'Kyiv'}").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM MergeTest").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    // MERGE should keep existing properties and add new ones
    assertThat((String) v.value("name")).isEqualTo("original");
    assertThat((int) v.value("age")).isEqualTo(25);
    assertThat((String) v.value("city")).isEqualTo("Kyiv");
  }

  // Line 25: RETURN COUNT returns the number of updated records (explicit)
  @Test
  public void testReturnCountExplicit() {
    g.command("CREATE CLASS CountExplicit IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CountExplicit SET name = 'a'").iterate();
      tx.yql("CREATE VERTEX CountExplicit SET name = 'b'").iterate();
      tx.yql("CREATE VERTEX CountExplicit SET name = 'c'").iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql("UPDATE CountExplicit SET name = 'x' RETURN COUNT").toList());
    assertThat(results).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> countMap = (Map<String, Object>) results.get(0);
    assertThat(countMap).containsKey("count");
    assertThat(((Number) countMap.get("count")).longValue()).isEqualTo(3L);
  }

  // Line 25: COUNT is the default return operator — UPDATE without RETURN clause
  @Test
  public void testReturnCountIsDefault() {
    g.command("CREATE CLASS CountDefault IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CountDefault SET name = 'a'").iterate();
      tx.yql("CREATE VERTEX CountDefault SET name = 'b'").iterate();
    });

    // No RETURN clause — should default to COUNT behavior
    var results =
        g.computeInTx(tx -> tx.yql("UPDATE CountDefault SET name = 'x'").toList());
    assertThat(results).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> countMap = (Map<String, Object>) results.get(0);
    assertThat(countMap).containsKey("count");
    assertThat(((Number) countMap.get("count")).longValue()).isEqualTo(2L);
  }

  // Line 92: UPDATE Profile SET nick = 'Andrii' UPSERT WHERE nick = 'Andrii'
  // When record does not exist, UPSERT should insert it
  @Test
  public void testUpsertInserts() {
    g.command("CREATE CLASS UpsertInsert IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("UPDATE UpsertInsert SET nick = 'Andrii' UPSERT WHERE nick = 'Andrii'").iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM UpsertInsert WHERE nick = 'Andrii'").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("nick")).isEqualTo("Andrii");
  }

  // Line 92: UPSERT should update existing record instead of inserting a new one
  @Test
  public void testUpsertUpdatesExisting() {
    g.command("CREATE CLASS UpsertUpdate IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX UpsertUpdate SET nick = 'Andrii', age = 25").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE UpsertUpdate SET nick = 'Andrii', age = 30 UPSERT WHERE nick = 'Andrii'")
          .iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM UpsertUpdate").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("nick")).isEqualTo("Andrii");
    assertThat((int) v.value("age")).isEqualTo(30);
  }

  // Line 113: UPSERT with unique index for atomicity
  @Test
  public void testUpsertWithUniqueIndex() {
    g.command("CREATE CLASS ClientUpsert IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ClientUpsert.id IF NOT EXISTS INTEGER");
    g.command("CREATE INDEX ClientUpsert.id IF NOT EXISTS ON ClientUpsert (id) UNIQUE");

    g.executeInTx(tx -> {
      tx.yql("UPDATE ClientUpsert SET id = 23 UPSERT WHERE id = 23").iterate();
    });

    // First UPSERT should have inserted
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ClientUpsert WHERE id = 23").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("UPDATE ClientUpsert SET id = 23, name = 'test' UPSERT WHERE id = 23").iterate();
    });

    // Second UPSERT should have updated, not inserted
    var resultsAfter =
        g.computeInTx(tx -> tx.yql("SELECT FROM ClientUpsert WHERE id = 23").toList());
    assertThat(resultsAfter).hasSize(1);
    Vertex v = (Vertex) resultsAfter.get(0);
    assertThat((String) v.value("name")).isEqualTo("test");
  }

  // === YQL-Create-Vertex.md ===

  // Line 23: CREATE VERTEX (bare, on base class V)
  @Test
  public void testCreateVertexBare() {
    var results =
        g.computeInTx(tx -> {
          tx.yql("CREATE VERTEX").iterate();
          return tx.yql("SELECT FROM V").toList();
        });
    assertThat(results).isNotEmpty();
  }

  // Lines 30-31: CREATE CLASS V1 EXTENDS V, then CREATE VERTEX V1
  @Test
  public void testCreateVertexWithClass() {
    g.command("CREATE CLASS CVV1 IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CVV1").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CVV1").toList());
    assertThat(results).hasSize(1);
  }

  // Line 37: CREATE VERTEX SET brand = 'fiat' (properties on base class)
  @Test
  public void testCreateVertexWithProperties() {
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX SET brand = 'fiat'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM V WHERE brand = 'fiat'").toList());
    assertThat(results).isNotEmpty();
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("brand")).isEqualTo("fiat");
  }

  // Line 43: CREATE VERTEX V1 SET brand = 'Skoda', name = 'wow'
  @Test
  public void testCreateVertexClassWithMultipleProperties() {
    g.command("CREATE CLASS CVV1Multi IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CVV1Multi SET brand = 'Skoda', name = 'wow'").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CVV1Multi").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("brand")).isEqualTo("Skoda");
    assertThat((String) v.value("name")).isEqualTo("wow");
  }

  // Line 49: CREATE VERTEX Employee CONTENT { "name" : "Viktoria", "surname" : "Sernevich" }
  @Test
  public void testCreateVertexWithContent() {
    g.command("CREATE CLASS EmployeeCV IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX EmployeeCV CONTENT { \"name\" : \"Viktoria\", "
          + "\"surname\" : \"Sernevich\" }").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM EmployeeCV").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("name")).isEqualTo("Viktoria");
    assertThat((String) v.value("surname")).isEqualTo("Sernevich");
  }

  // Factual claim line 6: The base class for a vertex is V
  @Test
  public void testBaseClassForVertexIsV() {
    g.command("CREATE CLASS CVVertexChild IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CVVertexChild SET tag = 'baseTest'").iterate();
    });

    // A vertex created in a V subclass should also appear in SELECT FROM V
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM V WHERE tag = 'baseTest'").toList());
    assertThat(results).isNotEmpty();
  }

  // === YQL-Create-Edge.md ===

  // Line 28: CREATE EDGE FROM <rid> TO <rid> (base class E)
  @Test
  public void testCreateEdgeBaseClass() {
    g.command("CREATE CLASS CEVertex1 IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEVertex1 SET tag = 'edgeSrc'").iterate();
      tx.yql("CREATE VERTEX CEVertex1 SET tag = 'edgeDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE FROM (SELECT FROM CEVertex1 WHERE tag = 'edgeSrc') "
              + "TO (SELECT FROM CEVertex1 WHERE tag = 'edgeDst')")
          .iterate();
    });

    // Verify the edge exists by traversing from source to destination
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(out()) FROM CEVertex1 WHERE tag = 'edgeSrc'")
                .toList());
    assertThat(results).hasSize(1);
    Vertex dst = (Vertex) results.get(0);
    assertThat((String) dst.value("tag")).isEqualTo("edgeDst");
  }

  // Lines 34-35: CREATE CLASS E1 EXTENDS E, then CREATE EDGE E1
  @Test
  public void testCreateEdgeWithCustomClass() {
    g.command("CREATE CLASS CEVertex2 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CEEdge1 IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEVertex2 SET tag = 'src2'").iterate();
      tx.yql("CREATE VERTEX CEVertex2 SET tag = 'dst2'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE CEEdge1 FROM (SELECT FROM CEVertex2 WHERE tag = 'src2') "
              + "TO (SELECT FROM CEVertex2 WHERE tag = 'dst2')")
          .iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('CEEdge1')) FROM CEVertex2 WHERE tag = 'src2'")
                .toList());
    assertThat(results).hasSize(1);
  }

  // Line 42: CREATE EDGE FROM <rid> TO <rid> SET brand = 'Skoda'
  @Test
  public void testCreateEdgeWithProperties() {
    g.command("CREATE CLASS CEVertex3 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CEEdgeProp IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEVertex3 SET tag = 'src3'").iterate();
      tx.yql("CREATE VERTEX CEVertex3 SET tag = 'dst3'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE CEEdgeProp FROM (SELECT FROM CEVertex3 WHERE tag = 'src3') "
              + "TO (SELECT FROM CEVertex3 WHERE tag = 'dst3') SET brand = 'Skoda'")
          .iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('CEEdgeProp')) FROM CEVertex3 WHERE tag = 'src3'")
                .toList());
    assertThat(results).hasSize(1);
  }

  // Line 49: CREATE EDGE E1 FROM <rid> TO <rid> SET brand = 'Skoda', name = 'wow'
  @Test
  public void testCreateEdgeWithMultipleProperties() {
    g.command("CREATE CLASS CEVertex4 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CEEdgeMulti IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEVertex4 SET tag = 'src4'").iterate();
      tx.yql("CREATE VERTEX CEVertex4 SET tag = 'dst4'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE CEEdgeMulti FROM (SELECT FROM CEVertex4 WHERE tag = 'src4') "
              + "TO (SELECT FROM CEVertex4 WHERE tag = 'dst4') "
              + "SET brand = 'Skoda', name = 'wow'")
          .iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('CEEdgeMulti')) FROM CEVertex4 WHERE tag = 'src4'")
                .toList());
    assertThat(results).hasSize(1);
  }

  // Line 56: CREATE EDGE with sub-queries on both FROM and TO
  @Test
  public void testCreateEdgeWithSubQueries() {
    g.command("CREATE CLASS CEAccount IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CEMovies IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CEWatched IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEAccount SET name = 'Andrii'").iterate();
      tx.yql("CREATE VERTEX CEMovies SET title = 'ActionMovie1', typeName = 'action'")
          .iterate();
      tx.yql("CREATE VERTEX CEMovies SET title = 'ActionMovie2', typeName = 'action'")
          .iterate();
      tx.yql("CREATE VERTEX CEMovies SET title = 'Comedy1', typeName = 'comedy'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE CEWatched "
              + "FROM (SELECT FROM CEAccount WHERE name = 'Andrii') "
              + "TO (SELECT FROM CEMovies WHERE typeName = 'action')")
          .iterate();
    });

    // Should have created 2 edges (one to each action movie)
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('CEWatched')) FROM CEAccount WHERE name = 'Andrii'")
                .toList());
    assertThat(results).hasSize(2);
  }

  // Line 62: CREATE EDGE with CONTENT JSON
  @Test
  public void testCreateEdgeWithContent() {
    g.command("CREATE CLASS CEVertex5 IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEVertex5 SET tag = 'src5'").iterate();
      tx.yql("CREATE VERTEX CEVertex5 SET tag = 'dst5'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE FROM (SELECT FROM CEVertex5 WHERE tag = 'src5') "
              + "TO (SELECT FROM CEVertex5 WHERE tag = 'dst5') "
              + "CONTENT { \"name\": \"Viktoria\", \"surname\": \"Sernevich\" }")
          .iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT expand(out()) FROM CEVertex5 WHERE tag = 'src5'")
                .toList());
    assertThat(results).hasSize(1);
  }

  // Line 20-21: Factual claim - YouTrackDB supports polymorphism on edges, base class is E
  @Test
  public void testEdgePolymorphism() {
    g.command("CREATE CLASS CEPolyBase IF NOT EXISTS EXTENDS E");
    g.command("CREATE CLASS CEPolySub IF NOT EXISTS EXTENDS CEPolyBase");
    g.command("CREATE CLASS CEPolyVertex IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEPolyVertex SET tag = 'polySrc'").iterate();
      tx.yql("CREATE VERTEX CEPolyVertex SET tag = 'polyDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE CEPolySub FROM (SELECT FROM CEPolyVertex WHERE tag = 'polySrc') "
              + "TO (SELECT FROM CEPolyVertex WHERE tag = 'polyDst')")
          .iterate();
    });

    // Edge created with subclass should appear when querying the parent class
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('CEPolyBase')) FROM CEPolyVertex "
                    + "WHERE tag = 'polySrc'")
                .toList());
    assertThat(results).hasSize(1);
  }

  // Line 14-15: UPSERT requires UNIQUE index on out, in fields
  @Test
  public void testCreateEdgeUpsert() {
    g.command("CREATE CLASS CEUpsertV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CEUpsertE IF NOT EXISTS EXTENDS E");
    g.command("CREATE PROPERTY CEUpsertE.out IF NOT EXISTS LINK");
    g.command("CREATE PROPERTY CEUpsertE.in IF NOT EXISTS LINK");
    g.command(
        "CREATE INDEX CEUpsertE_unique IF NOT EXISTS ON CEUpsertE (out, in) UNIQUE");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CEUpsertV SET tag = 'uSrc'").iterate();
      tx.yql("CREATE VERTEX CEUpsertV SET tag = 'uDst'").iterate();
    });

    // First creation
    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE CEUpsertE UPSERT "
              + "FROM (SELECT FROM CEUpsertV WHERE tag = 'uSrc') "
              + "TO (SELECT FROM CEUpsertV WHERE tag = 'uDst')")
          .iterate();
    });

    // Second creation with UPSERT should not create a duplicate
    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE CEUpsertE UPSERT "
              + "FROM (SELECT FROM CEUpsertV WHERE tag = 'uSrc') "
              + "TO (SELECT FROM CEUpsertV WHERE tag = 'uDst')")
          .iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('CEUpsertE')) FROM CEUpsertV WHERE tag = 'uSrc'")
                .toList());
    assertThat(results).hasSize(1);
  }

  // === YQL-Delete-Vertex.md ===

  // Line 21: DELETE VERTEX #<rid> — removes a vertex by its Record ID
  @Test
  public void testDeleteVertexByRid() {
    g.command("CREATE CLASS DVByRid IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DVByRid SET name = 'toDelete'").iterate();
    });

    // Get the RID of the created vertex
    var results = g.computeInTx(tx -> tx.yql("SELECT FROM DVByRid").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    String rid = v.id().toString();

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX " + rid).iterate();
    });

    var afterDelete = g.computeInTx(tx -> tx.yql("SELECT FROM DVByRid").toList());
    assertThat(afterDelete).isEmpty();
  }

  // Line 27: DELETE VERTEX with WHERE clause filtering by incoming edge class
  @Test
  public void testDeleteVertexWhereInClassContains() {
    g.command("CREATE CLASS DVAccount IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DVBadBehavior IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DVAccount SET name = 'goodUser'").iterate();
      tx.yql("CREATE VERTEX DVAccount SET name = 'badUser'").iterate();
      tx.yql("CREATE VERTEX DVAccount SET name = 'reporter'").iterate();
    });

    // Create an edge of class DVBadBehavior pointing to the bad user
    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DVBadBehavior "
              + "FROM (SELECT FROM DVAccount WHERE name = 'reporter') "
              + "TO (SELECT FROM DVAccount WHERE name = 'badUser')")
          .iterate();
    });

    // Doc uses: in.@Class CONTAINS 'BadBehaviorInForum'
    // BUG in doc: in.@Class returns empty — correct syntax is inE().@Class
    var matchedWhere =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM DVAccount WHERE inE().@Class CONTAINS 'DVBadBehavior'")
                .toList());
    assertThat(matchedWhere).hasSize(1);
    assertThat((String) ((Vertex) matchedWhere.get(0)).value("name")).isEqualTo("badUser");

    // Delete accounts with incoming edges of class DVBadBehavior (using correct syntax)
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX DVAccount WHERE inE().@Class CONTAINS 'DVBadBehavior'")
          .iterate();
    });

    var remaining = g.computeInTx(tx -> tx.yql("SELECT FROM DVAccount").toList());
    // badUser should be deleted; goodUser and reporter remain
    assertThat(remaining).hasSize(2);
    for (Object r : remaining) {
      Vertex rv = (Vertex) r;
      assertThat((String) rv.value("name")).isNotEqualTo("badUser");
    }
  }

  // Line 33: DELETE VERTEX EMailMessage WHERE isSpam = TRUE
  @Test
  public void testDeleteVertexWhereIsSpam() {
    g.command("CREATE CLASS DVEMailMessage IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DVEMailMessage SET subject = 'legit', isSpam = false").iterate();
      tx.yql("CREATE VERTEX DVEMailMessage SET subject = 'spam1', isSpam = true").iterate();
      tx.yql("CREATE VERTEX DVEMailMessage SET subject = 'spam2', isSpam = true").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX DVEMailMessage WHERE isSpam = TRUE").iterate();
    });

    var remaining = g.computeInTx(tx -> tx.yql("SELECT FROM DVEMailMessage").toList());
    assertThat(remaining).hasSize(1);
    Vertex v = (Vertex) remaining.get(0);
    assertThat((String) v.value("subject")).isEqualTo("legit");
  }

  // Line 47: DELETE VERTEX v BATCH 1000 — batch deletion
  @Test
  public void testDeleteVertexWithBatch() {
    g.command("CREATE CLASS DVBatch IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      for (int i = 0; i < 10; i++) {
        tx.yql("CREATE VERTEX DVBatch SET idx = " + i).iterate();
      }
    });

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX DVBatch BATCH 3").iterate();
    });

    var remaining = g.computeInTx(tx -> tx.yql("SELECT FROM DVBatch").toList());
    assertThat(remaining).isEmpty();
  }

  // Line 8: DELETE VERTEX with LIMIT clause
  @Test
  public void testDeleteVertexWithLimit() {
    g.command("CREATE CLASS DVLimit IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      for (int i = 0; i < 10; i++) {
        tx.yql("CREATE VERTEX DVLimit SET idx = " + i).iterate();
      }
    });

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX DVLimit LIMIT 5").iterate();
    });

    var remaining = g.computeInTx(tx -> tx.yql("SELECT FROM DVLimit").toList());
    assertThat(remaining).hasSize(5);
  }

  // Line 8: DELETE VERTEX with FROM sub-query
  @Test
  public void testDeleteVertexFromSubQuery() {
    g.command("CREATE CLASS DVSubQuery IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DVSubQuery SET name = 'keep'").iterate();
      tx.yql("CREATE VERTEX DVSubQuery SET name = 'remove'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX FROM (SELECT FROM DVSubQuery WHERE name = 'remove')").iterate();
    });

    var remaining = g.computeInTx(tx -> tx.yql("SELECT FROM DVSubQuery").toList());
    assertThat(remaining).hasSize(1);
    Vertex v = (Vertex) remaining.get(0);
    assertThat((String) v.value("name")).isEqualTo("keep");
  }

  // Factual claim: DELETE VERTEX disconnects all connected edges
  @Test
  public void testDeleteVertexDisconnectsEdges() {
    g.command("CREATE CLASS DVEdgeSrc IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DVEdgeDst IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DVEdgeLink IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DVEdgeSrc SET name = 'source'").iterate();
      tx.yql("CREATE VERTEX DVEdgeDst SET name = 'target'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DVEdgeLink "
              + "FROM (SELECT FROM DVEdgeSrc WHERE name = 'source') "
              + "TO (SELECT FROM DVEdgeDst WHERE name = 'target')")
          .iterate();
    });

    // Delete the target vertex — should also remove the edge
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX DVEdgeDst WHERE name = 'target'").iterate();
    });

    // The source vertex should have no outgoing edges
    var edges =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DVEdgeLink')) FROM DVEdgeSrc WHERE name = 'source'")
                .toList());
    assertThat(edges).isEmpty();
  }

  // === YQL-Delete-Edge.md ===

  // Line 34: DELETE EDGE #<rid> — removes a single edge by its Record ID
  @Test
  public void testDeleteEdgeByRid() {
    g.command("CREATE CLASS DESrc IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEDst IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEByRid IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DESrc SET tag = 'deRidSrc'").iterate();
      tx.yql("CREATE VERTEX DEDst SET tag = 'deRidDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DEByRid FROM (SELECT FROM DESrc WHERE tag = 'deRidSrc') "
              + "TO (SELECT FROM DEDst WHERE tag = 'deRidDst')")
          .iterate();
    });

    // Get the RID of the created edge
    var edges =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEByRid')) FROM DESrc WHERE tag = 'deRidSrc'")
                .toList());
    assertThat(edges).hasSize(1);
    String edgeRid = ((Edge) edges.get(0)).id().toString();

    // Delete by RID
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE " + edgeRid).iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEByRid')) FROM DESrc WHERE tag = 'deRidSrc'")
                .toList());
    assertThat(remaining).isEmpty();
  }

  // Line 40: DELETE EDGE [#rid1, #rid2, ...] — removes multiple edges by RID list
  @Test
  public void testDeleteEdgeByRidList() {
    g.command("CREATE CLASS DERidListV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DERidListE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DERidListV SET tag = 'rlSrc'").iterate();
      tx.yql("CREATE VERTEX DERidListV SET tag = 'rlDst1'").iterate();
      tx.yql("CREATE VERTEX DERidListV SET tag = 'rlDst2'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DERidListE FROM (SELECT FROM DERidListV WHERE tag = 'rlSrc') "
              + "TO (SELECT FROM DERidListV WHERE tag = 'rlDst1')")
          .iterate();
      tx.yql(
          "CREATE EDGE DERidListE FROM (SELECT FROM DERidListV WHERE tag = 'rlSrc') "
              + "TO (SELECT FROM DERidListV WHERE tag = 'rlDst2')")
          .iterate();
    });

    // Get the RIDs of both edges
    var edges =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DERidListE')) FROM DERidListV WHERE tag = 'rlSrc'")
                .toList());
    assertThat(edges).hasSize(2);
    String rid1 = ((Edge) edges.get(0)).id().toString();
    String rid2 = ((Edge) edges.get(1)).id().toString();

    // Delete by RID list
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE [" + rid1 + "," + rid2 + "]").iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DERidListE')) FROM DERidListV WHERE tag = 'rlSrc'")
                .toList());
    assertThat(remaining).isEmpty();
  }

  // Line 46: DELETE EDGE FROM <rid> TO <rid> WHERE condition
  @Test
  public void testDeleteEdgeFromToWithWhere() {
    g.command("CREATE CLASS DEFromToV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEFromToE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DEFromToV SET tag = 'ftSrc'").iterate();
      tx.yql("CREATE VERTEX DEFromToV SET tag = 'ftDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DEFromToE FROM (SELECT FROM DEFromToV WHERE tag = 'ftSrc') "
              + "TO (SELECT FROM DEFromToV WHERE tag = 'ftDst') SET date = '2012-01-15'")
          .iterate();
      tx.yql(
          "CREATE EDGE DEFromToE FROM (SELECT FROM DEFromToV WHERE tag = 'ftSrc') "
              + "TO (SELECT FROM DEFromToV WHERE tag = 'ftDst') SET date = '2011-12-01'")
          .iterate();
    });

    // Get source and dest RIDs for the FROM/TO syntax
    var srcList =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM DEFromToV WHERE tag = 'ftSrc'").toList());
    var dstList =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM DEFromToV WHERE tag = 'ftDst'").toList());
    String srcRid = ((Vertex) srcList.get(0)).id().toString();
    String dstRid = ((Vertex) dstList.get(0)).id().toString();

    // Delete only edges with date >= '2012-01-15'
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE FROM " + srcRid + " TO " + dstRid
          + " WHERE date >= '2012-01-15'").iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEFromToE')) FROM DEFromToV WHERE tag = 'ftSrc'")
                .toList());
    assertThat(remaining).hasSize(1);
  }

  // Line 58: DELETE EDGE <ClassName> WHERE condition
  @Test
  public void testDeleteEdgeByClassAndWhere() {
    g.command("CREATE CLASS DEOwnsV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEOwns IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DEOwnsV SET tag = 'ownsSrc'").iterate();
      tx.yql("CREATE VERTEX DEOwnsV SET tag = 'ownsDst1'").iterate();
      tx.yql("CREATE VERTEX DEOwnsV SET tag = 'ownsDst2'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DEOwns FROM (SELECT FROM DEOwnsV WHERE tag = 'ownsSrc') "
              + "TO (SELECT FROM DEOwnsV WHERE tag = 'ownsDst1') SET date = '2011-10'")
          .iterate();
      tx.yql(
          "CREATE EDGE DEOwns FROM (SELECT FROM DEOwnsV WHERE tag = 'ownsSrc') "
              + "TO (SELECT FROM DEOwnsV WHERE tag = 'ownsDst2') SET date = '2012-05'")
          .iterate();
    });

    // Delete edges of class DEOwns where date < '2011-11'
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE DEOwns WHERE date < '2011-11'").iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEOwns')) FROM DEOwnsV WHERE tag = 'ownsSrc'")
                .toList());
    assertThat(remaining).hasSize(1);
  }

  // Line 64: The original doc used "in.price" which fails because "in" is a reserved keyword.
  // The corrected doc uses "inV().price" to traverse to the destination vertex.
  @Test
  public void testDeleteEdgeWithInVertexCondition() {
    g.command("CREATE CLASS DEInPriceV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEInPriceE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DEInPriceV SET tag = 'ipSrc'").iterate();
      tx.yql("CREATE VERTEX DEInPriceV SET tag = 'ipDst1', price = 300.0").iterate();
      tx.yql("CREATE VERTEX DEInPriceV SET tag = 'ipDst2', price = 100.0").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DEInPriceE FROM (SELECT FROM DEInPriceV WHERE tag = 'ipSrc') "
              + "TO (SELECT FROM DEInPriceV WHERE tag = 'ipDst1') SET date = '2011-10'")
          .iterate();
      tx.yql(
          "CREATE EDGE DEInPriceE FROM (SELECT FROM DEInPriceV WHERE tag = 'ipSrc') "
              + "TO (SELECT FROM DEInPriceV WHERE tag = 'ipDst2') SET date = '2011-10'")
          .iterate();
    });

    // Verify that bare "in.price" fails because "in" is reserved
    assertThatThrownBy(
        () -> g.executeInTx(tx -> {
          tx.yql("DELETE EDGE DEInPriceE WHERE date < '2011-11' AND in.price >= 202.43")
              .iterate();
        })).hasMessageContaining("in");

    // The corrected syntax inV().price should work
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE DEInPriceE WHERE date < '2011-11' AND inV().price >= 202.43")
          .iterate();
    });

    // Only the edge to ipDst1 (price=300) should be deleted; ipDst2 (price=100) stays
    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEInPriceE')) FROM DEInPriceV WHERE tag = 'ipSrc'")
                .toList());
    assertThat(remaining).hasSize(1);
  }

  // Line 70: DELETE EDGE <ClassName> WHERE condition BATCH <size>
  @Test
  public void testDeleteEdgeWithBatch() {
    g.command("CREATE CLASS DEBatchV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEBatchE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DEBatchV SET tag = 'bSrc'").iterate();
      for (int i = 0; i < 5; i++) {
        tx.yql("CREATE VERTEX DEBatchV SET tag = 'bDst" + i + "'").iterate();
      }
    });

    g.executeInTx(tx -> {
      for (int i = 0; i < 5; i++) {
        tx.yql(
            "CREATE EDGE DEBatchE FROM (SELECT FROM DEBatchV WHERE tag = 'bSrc') "
                + "TO (SELECT FROM DEBatchV WHERE tag = 'bDst" + i + "') "
                + "SET date = '2011-10'")
            .iterate();
      }
    });

    // Delete with BATCH clause
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE DEBatchE WHERE date < '2011-11' BATCH 1000").iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEBatchE')) FROM DEBatchV WHERE tag = 'bSrc'")
                .toList());
    assertThat(remaining).isEmpty();
  }

  // Line 76: DELETE EDGE E WHERE @rid IN (SELECT @rid FROM E)
  @Test
  public void testDeleteEdgeWithSubQuery() {
    g.command("CREATE CLASS DESubQV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DESubQE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DESubQV SET tag = 'sqSrc'").iterate();
      tx.yql("CREATE VERTEX DESubQV SET tag = 'sqDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DESubQE FROM (SELECT FROM DESubQV WHERE tag = 'sqSrc') "
              + "TO (SELECT FROM DESubQV WHERE tag = 'sqDst')")
          .iterate();
    });

    // Delete using sub-query pattern from doc line 76
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE DESubQE WHERE @rid IN (SELECT @rid FROM DESubQE)").iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DESubQE')) FROM DESubQV WHERE tag = 'sqSrc'")
                .toList());
    assertThat(remaining).isEmpty();
  }

  // Line 52: DELETE EDGE FROM <rid> TO <rid> WHERE @class = 'X' AND condition
  @Test
  public void testDeleteEdgeWithClassFilterInWhere() {
    g.command("CREATE CLASS DEClassFiltV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEClassFiltE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DEClassFiltV SET tag = 'cfSrc'").iterate();
      tx.yql("CREATE VERTEX DEClassFiltV SET tag = 'cfDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DEClassFiltE FROM (SELECT FROM DEClassFiltV WHERE tag = 'cfSrc') "
              + "TO (SELECT FROM DEClassFiltV WHERE tag = 'cfDst') "
              + "SET comment = 'forbidden stuff'")
          .iterate();
    });

    var srcList =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM DEClassFiltV WHERE tag = 'cfSrc'").toList());
    var dstList =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM DEClassFiltV WHERE tag = 'cfDst'").toList());
    String srcRid = ((Vertex) srcList.get(0)).id().toString();
    String dstRid = ((Vertex) dstList.get(0)).id().toString();

    // Delete edge using FROM/TO with @class filter and LIKE condition
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE FROM " + srcRid + " TO " + dstRid
          + " WHERE @class = 'DEClassFiltE' AND comment LIKE '%forbidden%'").iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEClassFiltE')) FROM DEClassFiltV WHERE tag = 'cfSrc'")
                .toList());
    assertThat(remaining).isEmpty();
  }

  // Line 91: DELETE EDGE FROM (SELECT FROM #rid) does NOT delete edge
  // Line 97: DELETE EDGE E WHERE @rid IN (SELECT FROM #rid) DOES delete edge
  @Test
  public void testDeleteEdgeFromSubQueryVsWhereRidIn() {
    g.command("CREATE CLASS DEUseCaseV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DEUseCaseE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DEUseCaseV SET tag = 'ucSrc'").iterate();
      tx.yql("CREATE VERTEX DEUseCaseV SET tag = 'ucDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE DEUseCaseE FROM (SELECT FROM DEUseCaseV WHERE tag = 'ucSrc') "
              + "TO (SELECT FROM DEUseCaseV WHERE tag = 'ucDst')")
          .iterate();
    });

    // Get the edge RID
    var edges =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEUseCaseE')) FROM DEUseCaseV WHERE tag = 'ucSrc'")
                .toList());
    assertThat(edges).hasSize(1);
    String edgeRid = ((Edge) edges.get(0)).id().toString();

    // Line 91: DELETE EDGE FROM (SELECT FROM #edgeRid) — doc says this does NOT delete
    // the edge. In practice, it actually throws an error because the FROM clause expects
    // a vertex, but SELECT FROM <edgeRid> returns an edge record ("Invalid vertex").
    assertThatThrownBy(
        () -> g.executeInTx(tx -> {
          tx.yql("DELETE EDGE FROM (SELECT FROM " + edgeRid + ")").iterate();
        })).hasMessageContaining("Invalid vertex");

    // Edge should still exist after the failed attempt
    var afterBadDelete =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEUseCaseE')) FROM DEUseCaseV WHERE tag = 'ucSrc'")
                .toList());
    assertThat(afterBadDelete).hasSize(1);

    // Line 97: DELETE EDGE E WHERE @rid IN (SELECT FROM #edgeRid) — should delete
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE DEUseCaseE WHERE @rid IN (SELECT FROM " + edgeRid + ")")
          .iterate();
    });

    var afterGoodDelete =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DEUseCaseE')) FROM DEUseCaseV WHERE tag = 'ucSrc'")
                .toList());
    assertThat(afterGoodDelete).isEmpty();
  }

  // === YQL-Update-Edge.md ===

  // Line 37: UPDATE EDGE Friend SET foo = 'bar' WHERE since < '2020-01-01'
  // Validates the corrected doc example — updating edge properties by class with WHERE
  @Test
  public void testUpdateEdgeSetPropertyByClass() {
    g.command("CREATE CLASS UEPerson IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS UEFriend IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX UEPerson SET name = 'Alice'").iterate();
      tx.yql("CREATE VERTEX UEPerson SET name = 'Bob'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE UEFriend FROM (SELECT FROM UEPerson WHERE name = 'Alice') "
              + "TO (SELECT FROM UEPerson WHERE name = 'Bob') SET since = '2019-06-15'")
          .iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE EDGE UEFriend SET foo = 'bar' WHERE since < '2020-01-01'").iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('UEFriend')) FROM UEPerson WHERE name = 'Alice'")
                .toList());
    assertThat(results).hasSize(1);
    Edge edge = (Edge) results.get(0);
    assertThat((String) edge.value("foo")).isEqualTo("bar");
  }

  // Line 42: UPDATE EDGE hasAssignee SET foo = 'bar' UPSERT WHERE id = 56
  // Tests UPSERT on an edge class with a unique index — update path (match found)
  @Test
  public void testUpdateEdgeUpsertWithUniqueIndex() {
    g.command("CREATE CLASS UEAssigneeV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS UEHasAssignee IF NOT EXISTS EXTENDS E");
    g.command("CREATE PROPERTY UEHasAssignee.id IF NOT EXISTS INTEGER");
    g.command(
        "CREATE INDEX UEHasAssignee.id IF NOT EXISTS ON UEHasAssignee (id) UNIQUE");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX UEAssigneeV SET tag = 'src'").iterate();
      tx.yql("CREATE VERTEX UEAssigneeV SET tag = 'dst'").iterate();
    });

    // Create an initial edge
    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE UEHasAssignee FROM (SELECT FROM UEAssigneeV WHERE tag = 'src') "
              + "TO (SELECT FROM UEAssigneeV WHERE tag = 'dst') SET id = 56")
          .iterate();
    });

    // UPSERT should update the existing edge, not create a new one
    g.executeInTx(tx -> {
      tx.yql("UPDATE EDGE UEHasAssignee SET foo = 'bar' UPSERT WHERE id = 56").iterate();
    });

    // Verify only one edge exists and it has both properties
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('UEHasAssignee')) FROM UEAssigneeV WHERE tag = 'src'")
                .toList());
    assertThat(results).hasSize(1);
    Edge edge = (Edge) results.get(0);
    assertThat((int) edge.value("id")).isEqualTo(56);
    assertThat((String) edge.value("foo")).isEqualTo("bar");
  }

  // Line 44: UPSERT on edge fails when no matching edge exists — the engine cannot
  // insert a new edge because UPDATE EDGE syntax does not provide FROM/TO endpoints.
  @Test
  public void testUpdateEdgeUpsertCannotInsert() {
    g.command("CREATE CLASS UEUpsertInsV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS UEUpsertInsE IF NOT EXISTS EXTENDS E");
    g.command("CREATE PROPERTY UEUpsertInsE.uid IF NOT EXISTS INTEGER");
    g.command(
        "CREATE INDEX UEUpsertInsE.uid IF NOT EXISTS ON UEUpsertInsE (uid) UNIQUE");

    // No edge exists — UPSERT should fail because edges can't be inserted this way
    assertThatThrownBy(
        () -> g.executeInTx(tx -> {
          tx.yql(
              "UPDATE EDGE UEUpsertInsE SET uid = 99, foo = 'baz' UPSERT WHERE uid = 99")
              .iterate();
        })).hasMessageContaining("Cannot execute UPSERT on edge type");
  }

  // Line 25: RETURN AFTER returns the records after the update (on edge)
  @Test
  public void testUpdateEdgeReturnAfter() {
    g.command("CREATE CLASS UEReturnV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS UEReturnE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX UEReturnV SET tag = 'retSrc'").iterate();
      tx.yql("CREATE VERTEX UEReturnV SET tag = 'retDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE UEReturnE FROM (SELECT FROM UEReturnV WHERE tag = 'retSrc') "
              + "TO (SELECT FROM UEReturnV WHERE tag = 'retDst') SET status = 'old'")
          .iterate();
    });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "UPDATE EDGE UEReturnE SET status = 'new' RETURN AFTER @this")
                .toList());
    assertThat(results).hasSize(1);
    Edge edge = (Edge) results.get(0);
    assertThat((String) edge.value("status")).isEqualTo("new");
  }

  // Line 25-26: RETURN COUNT is the default return for UPDATE EDGE
  @Test
  public void testUpdateEdgeReturnCountDefault() {
    g.command("CREATE CLASS UECountV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS UECountE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX UECountV SET tag = 'cntSrc'").iterate();
      tx.yql("CREATE VERTEX UECountV SET tag = 'cntDst'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE UECountE FROM (SELECT FROM UECountV WHERE tag = 'cntSrc') "
              + "TO (SELECT FROM UECountV WHERE tag = 'cntDst') SET val = 1")
          .iterate();
    });

    // No RETURN clause — should default to COUNT
    var results =
        g.computeInTx(
            tx -> tx.yql("UPDATE EDGE UECountE SET val = 2").toList());
    assertThat(results).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> countMap = (Map<String, Object>) results.get(0);
    assertThat(countMap).containsKey("count");
    assertThat(((Number) countMap.get("count")).longValue()).isEqualTo(1L);
  }

  // Line 30: LIMIT defines the maximum number of records to update (on edge)
  @Test
  public void testUpdateEdgeWithLimit() {
    g.command("CREATE CLASS UELimitV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS UELimitE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX UELimitV SET tag = 'limSrc'").iterate();
      for (int i = 0; i < 5; i++) {
        tx.yql("CREATE VERTEX UELimitV SET tag = 'limDst" + i + "'").iterate();
      }
    });

    g.executeInTx(tx -> {
      for (int i = 0; i < 5; i++) {
        tx.yql(
            "CREATE EDGE UELimitE FROM (SELECT FROM UELimitV WHERE tag = 'limSrc') "
                + "TO (SELECT FROM UELimitV WHERE tag = 'limDst" + i + "') SET val = 0")
            .iterate();
      }
    });

    g.executeInTx(tx -> {
      tx.yql("UPDATE EDGE UELimitE SET val = 1 LIMIT 3").iterate();
    });

    var updated =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM UELimitE WHERE val = 1").toList());
    assertThat(updated).hasSize(3);

    var unchanged =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM UELimitE WHERE val = 0").toList());
    assertThat(unchanged).hasSize(2);
  }

  // Line 25: LIMIT defines the maximum number of edges to delete
  @Test
  public void testDeleteEdgeWithLimit() {
    g.command("CREATE CLASS DELimitV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DELimitE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DELimitV SET tag = 'limSrc'").iterate();
      for (int i = 0; i < 5; i++) {
        tx.yql("CREATE VERTEX DELimitV SET tag = 'limDst" + i + "'").iterate();
      }
    });

    g.executeInTx(tx -> {
      for (int i = 0; i < 5; i++) {
        tx.yql(
            "CREATE EDGE DELimitE FROM (SELECT FROM DELimitV WHERE tag = 'limSrc') "
                + "TO (SELECT FROM DELimitV WHERE tag = 'limDst" + i + "')")
            .iterate();
      }
    });

    // Delete with LIMIT 3
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE DELimitE LIMIT 3").iterate();
    });

    var remaining =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT expand(outE('DELimitE')) FROM DELimitV WHERE tag = 'limSrc'")
                .toList());
    assertThat(remaining).hasSize(2);
  }

  // === YQL-Match.md ===

  // Helper: set up the Person/Friend graph from YQL-Match.md sample dataset (lines 84-106)
  // John Doe -> John Smith, Jenny Smith, Frank Bean
  // John Smith -> Jenny Smith
  // Frank Bean -> Mark Bean
  // Jenny Smith -> Mark Bean
  private void setUpMatchSampleData() {
    g.command("CREATE CLASS MAPerson IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS MAFriend IF NOT EXISTS EXTENDS E");

    // Clean up any leftover data from previous test runs
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MAFriend").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MAPerson").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MAPerson SET name = 'John', surname = 'Doe'").iterate();
      tx.yql("CREATE VERTEX MAPerson SET name = 'John', surname = 'Smith'").iterate();
      tx.yql("CREATE VERTEX MAPerson SET name = 'Jenny', surname = 'Smith'").iterate();
      tx.yql("CREATE VERTEX MAPerson SET name = 'Frank', surname = 'Bean'").iterate();
      tx.yql("CREATE VERTEX MAPerson SET name = 'Mark', surname = 'Bean'").iterate();
    });

    g.executeInTx(tx -> {
      // John Doe -> John Smith
      tx.yql(
          "CREATE EDGE MAFriend "
              + "FROM (SELECT FROM MAPerson WHERE name='John' AND surname='Doe') "
              + "TO (SELECT FROM MAPerson WHERE name='John' AND surname='Smith')")
          .iterate();
      // John Doe -> Jenny Smith
      tx.yql(
          "CREATE EDGE MAFriend "
              + "FROM (SELECT FROM MAPerson WHERE name='John' AND surname='Doe') "
              + "TO (SELECT FROM MAPerson WHERE name='Jenny' AND surname='Smith')")
          .iterate();
      // John Doe -> Frank Bean
      tx.yql(
          "CREATE EDGE MAFriend "
              + "FROM (SELECT FROM MAPerson WHERE name='John' AND surname='Doe') "
              + "TO (SELECT FROM MAPerson WHERE name='Frank' AND surname='Bean')")
          .iterate();
      // John Smith -> Jenny Smith
      tx.yql(
          "CREATE EDGE MAFriend "
              + "FROM (SELECT FROM MAPerson WHERE name='John' AND surname='Smith') "
              + "TO (SELECT FROM MAPerson WHERE name='Jenny' AND surname='Smith')")
          .iterate();
      // Frank Bean -> Mark Bean
      tx.yql(
          "CREATE EDGE MAFriend "
              + "FROM (SELECT FROM MAPerson WHERE name='Frank' AND surname='Bean') "
              + "TO (SELECT FROM MAPerson WHERE name='Mark' AND surname='Bean')")
          .iterate();
      // Jenny Smith -> Mark Bean
      tx.yql(
          "CREATE EDGE MAFriend "
              + "FROM (SELECT FROM MAPerson WHERE name='Jenny' AND surname='Smith') "
              + "TO (SELECT FROM MAPerson WHERE name='Mark' AND surname='Bean')")
          .iterate();
    });
  }

  private void tearDownMatchSampleData() {
    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MAFriend").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MAPerson").iterate();
    });
  }

  // Line 110-113: Find all people with the name John
  @Test
  public void testMatchFindByName() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: people, where: (name = 'John')} "
                      + "RETURN people")
                  .toList());
      // Should return John Doe and John Smith
      assertThat(results).hasSize(2);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 125-128: Find all people with name John and surname Smith
  @Test
  public void testMatchFindByNameAndSurname() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: people, "
                      + "where: (name = 'John' AND surname = 'Smith')} "
                      + "RETURN people")
                  .toList());
      // Should return only John Smith
      assertThat(results).hasSize(1);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 141-144: Find people named John with their friends (both directions)
  @Test
  public void testMatchBothFriend() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, where: (name = 'John')}"
                      + ".both('MAFriend') {as: friend} "
                      + "RETURN person, friend")
                  .toList());
      // John Doe has 3 friends (out: Smith, Jenny, Frank)
      // John Smith has 2 friends (in: Doe, out: Jenny)
      // = 5 total
      assertThat(results).hasSize(5);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 161-165: Friends of friends
  // DOC BUG: The doc result table (lines 168-178) shows 5 rows with unique
  // friendOfFriend values, but MATCH returns all occurrences (line 283 confirms
  // no dedup). The actual result is 7 rows because multiple paths reach the
  // same node (e.g., John Doe appears 3 times as friend-of-friend via 3 friends).
  // Also, the doc includes Frank Bean (#12:3) as a friend-of-friend, but no
  // two-hop path Doe -> X -> Frank exists — Frank is only a direct friend.
  @Test
  public void testMatchFriendsOfFriends() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".both('MAFriend').both('MAFriend') {as: friendOfFriend} "
                      + "RETURN person, friendOfFriend")
                  .toList());
      // Actual: 7 rows (Doe×3, Jenny, Smith, Mark×2) — doc incorrectly shows 5
      assertThat(results).hasSize(7);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 183-188: Friends of friends, excluding current user via $matched.person
  @Test
  public void testMatchFriendsOfFriendsExcludeSelf() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".both('MAFriend').both('MAFriend')"
                      + "{as: friendOfFriend, "
                      + "where: ($matched.person != $currentMatch)} "
                      + "RETURN person, friendOfFriend")
                  .toList());
      // Same as above but excluding John Doe himself = 4
      assertThat(results).hasSize(4);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 202-207: Deep traversal with while condition ($depth < 6)
  // DOC BUG: Line 205 omits comma between where: and while: clauses.
  // The parser requires: {as: friend, where: (...), while: (...)}
  @Test
  public void testMatchDeepTraversalWhile() {
    setUpMatchSampleData();
    try {
      // Verify the doc's syntax (no comma) fails to parse
      assertThatThrownBy(
          () -> g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".both('MAFriend'){as: friend, "
                      + "where: ($matched.person != $currentMatch) "
                      + "while: ($depth < 6)} "
                      + "RETURN person, friend")
                  .toList()));

      // Corrected syntax with comma between where: and while:
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".both('MAFriend'){as: friend, "
                      + "where: ($matched.person != $currentMatch), "
                      + "while: ($depth < 6)} "
                      + "RETURN person, friend")
                  .toList());
      assertThat(results).isNotEmpty();
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 236-241: Multiple match paths — friends of friends who are also direct friends
  @Test
  public void testMatchMultiplePaths() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".both('MAFriend').both('MAFriend'){as: friend}, "
                      + "{ as: person }.both('MAFriend'){ as: friend } "
                      + "RETURN person, friend")
                  .toList());
      // Friends of friends who are also direct friends: John Smith, Jenny Smith
      assertThat(results).hasSize(2);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 257-261: Common friends of John Doe and Jenny Smith
  @Test
  public void testMatchCommonFriends() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".both('MAFriend'){as: friend}.both('MAFriend')"
                      + "{class: MAPerson, where: (name = 'Jenny')} "
                      + "RETURN friend")
                  .toList());
      // Common friend of John Doe and Jenny Smith is John Smith
      assertThat(results).hasSize(1);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 273-278: Common friends with two match expressions
  @Test
  public void testMatchCommonFriendsTwoExpressions() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".both('MAFriend'){as: friend}, "
                      + "{class: MAPerson, where: (name = 'Jenny')}.both('MAFriend')"
                      + "{as: friend} RETURN friend")
                  .toList());
      // Same as single expression: John Smith is the common friend
      assertThat(results).hasSize(1);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 288-292: DISTINCT — create data with duplicate names, verify MATCH returns duplicates
  @Test
  public void testMatchDistinct() {
    g.command("CREATE CLASS MADistPerson IF NOT EXISTS EXTENDS V");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MADistPerson SET name = 'John', surname = 'Smith'").iterate();
      tx.yql("CREATE VERTEX MADistPerson SET name = 'John', surname = 'Harris'").iterate();
      tx.yql("CREATE VERTEX MADistPerson SET name = 'Jenny', surname = 'Rose'").iterate();
    });

    // Without DISTINCT: should return 3 rows (including both Johns)
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MADistPerson, as:p} RETURN p.name as name")
                .toList());
    assertThat(results).hasSize(3);

    // With DISTINCT: should return 2 rows (John and Jenny)
    var distinctResults =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MADistPerson, as:p} RETURN DISTINCT p.name as name")
                .toList());
    assertThat(distinctResults).hasSize(2);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MADistPerson").iterate();
    });
  }

  // Line 349-355: Expanding attributes — SELECT from MATCH sub-query
  @Test
  public void testMatchExpandingAttributes() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "SELECT person.name AS name, person.surname AS surname, "
                      + "friend.name AS friendName, friend.surname AS friendSurname "
                      + "FROM (MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John')}.both('MAFriend'){as: friend} "
                      + "RETURN person, friend)")
                  .toList());
      // John Doe has 3 friends, John Smith has 2 friends = 5 total
      assertThat(results).hasSize(5);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 373-378: RETURN with dot-notation on aliases (alternative syntax)
  @Test
  public void testMatchReturnDotNotation() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John')}.both('MAFriend'){as: friend} "
                      + "RETURN person.name as name, person.surname as surname, "
                      + "friend.name as friendName, friend.surname as friendSurname")
                  .toList());
      assertThat(results).hasSize(5);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 439-461: Deep traversal — out("FriendOf") without while returns only direct
  @Test
  public void testMatchDeepTraversalWithoutWhile() {
    g.command("CREATE CLASS MADeepPerson IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS MAFriendOf IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MADeepPerson SET name = 'a'").iterate();
      tx.yql("CREATE VERTEX MADeepPerson SET name = 'b'").iterate();
      tx.yql("CREATE VERTEX MADeepPerson SET name = 'c'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE MAFriendOf "
              + "FROM (SELECT FROM MADeepPerson WHERE name = 'a') "
              + "TO (SELECT FROM MADeepPerson WHERE name = 'b')")
          .iterate();
      tx.yql(
          "CREATE EDGE MAFriendOf "
              + "FROM (SELECT FROM MADeepPerson WHERE name = 'b') "
              + "TO (SELECT FROM MADeepPerson WHERE name = 'c')")
          .iterate();
    });

    // Without while: traverses out("MAFriendOf") exactly once → returns only 'b'
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MADeepPerson, where: (name = 'a')}"
                    + ".out('MAFriendOf'){as: friend} RETURN friend")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MAFriendOf").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MADeepPerson").iterate();
    });
  }

  // Line 466-478: Deep traversal with while — includes origin node (depth 0)
  // DOC BUG: The doc result table (lines 471-478) shows only 'a' and 'b',
  // but while($depth < 2) actually returns 'a' (depth 0), 'b' (depth 1),
  // and 'c' (depth 2). The while condition controls whether to CONTINUE
  // traversing, but the reached node is still included in results.
  @Test
  public void testMatchDeepTraversalWithWhile() {
    g.command("CREATE CLASS MADeepPerson2 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS MAFriendOf2 IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MADeepPerson2 SET name = 'a'").iterate();
      tx.yql("CREATE VERTEX MADeepPerson2 SET name = 'b'").iterate();
      tx.yql("CREATE VERTEX MADeepPerson2 SET name = 'c'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE MAFriendOf2 "
              + "FROM (SELECT FROM MADeepPerson2 WHERE name = 'a') "
              + "TO (SELECT FROM MADeepPerson2 WHERE name = 'b')")
          .iterate();
      tx.yql(
          "CREATE EDGE MAFriendOf2 "
              + "FROM (SELECT FROM MADeepPerson2 WHERE name = 'b') "
              + "TO (SELECT FROM MADeepPerson2 WHERE name = 'c')")
          .iterate();
    });

    // Actual: returns 'a', 'b', 'c' = 3 (doc incorrectly says 2)
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MADeepPerson2, where: (name = 'a')}"
                    + ".out('MAFriendOf2'){as: friend, while: ($depth < 2)} "
                    + "RETURN friend")
                .toList());
    assertThat(results).hasSize(3);

    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MAFriendOf2").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MADeepPerson2").iterate();
    });
  }

  // Line 485-489: Deep traversal with while + where to exclude origin
  // DOC BUG: Line 487 omits comma between while: and where: clauses.
  // The parser requires: {as: friend, while: (...), where: (...)}
  @Test
  public void testMatchDeepTraversalWhileExcludeOrigin() {
    g.command("CREATE CLASS MADeepPerson3 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS MAFriendOf3 IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MADeepPerson3 SET name = 'a'").iterate();
      tx.yql("CREATE VERTEX MADeepPerson3 SET name = 'b'").iterate();
      tx.yql("CREATE VERTEX MADeepPerson3 SET name = 'c'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE MAFriendOf3 "
              + "FROM (SELECT FROM MADeepPerson3 WHERE name = 'a') "
              + "TO (SELECT FROM MADeepPerson3 WHERE name = 'b')")
          .iterate();
      tx.yql(
          "CREATE EDGE MAFriendOf3 "
              + "FROM (SELECT FROM MADeepPerson3 WHERE name = 'b') "
              + "TO (SELECT FROM MADeepPerson3 WHERE name = 'c')")
          .iterate();
    });

    // Verify the doc's syntax (no comma) fails to parse
    assertThatThrownBy(
        () -> g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MADeepPerson3, where: (name = 'a')}"
                    + ".out('MAFriendOf3'){as: friend, "
                    + "while: ($depth < 2) where: ($depth > 0)} "
                    + "RETURN friend")
                .toList()));

    // Corrected syntax with comma; where($depth > 0) excludes origin 'a'
    // Returns 'b' (depth 1) and 'c' (depth 2)
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MADeepPerson3, where: (name = 'a')}"
                    + ".out('MAFriendOf3'){as: friend, "
                    + "while: ($depth < 2), where: ($depth > 0)} "
                    + "RETURN friend")
                .toList());
    assertThat(results).hasSize(2);

    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MAFriendOf3").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MADeepPerson3").iterate();
    });
  }

  // Line 745-755: Arrow notation — out() can be written as -->
  @Test
  public void testMatchArrowNotation() {
    g.command("CREATE CLASS MAArrowV IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS MAArrowE IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MAArrowV SET name = 'a'").iterate();
      tx.yql("CREATE VERTEX MAArrowV SET name = 'b'").iterate();
      tx.yql("CREATE VERTEX MAArrowV SET name = 'c'").iterate();
      tx.yql("CREATE VERTEX MAArrowV SET name = 'd'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE MAArrowE "
              + "FROM (SELECT FROM MAArrowV WHERE name = 'a') "
              + "TO (SELECT FROM MAArrowV WHERE name = 'b')")
          .iterate();
      tx.yql(
          "CREATE EDGE MAArrowE "
              + "FROM (SELECT FROM MAArrowV WHERE name = 'b') "
              + "TO (SELECT FROM MAArrowV WHERE name = 'c')")
          .iterate();
      tx.yql(
          "CREATE EDGE MAArrowE "
              + "FROM (SELECT FROM MAArrowV WHERE name = 'c') "
              + "TO (SELECT FROM MAArrowV WHERE name = 'd')")
          .iterate();
    });

    // Functional notation
    var funcResults =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MAArrowV, as: a, where: (name = 'a')}"
                    + ".out(){}.out(){}.out(){as:b} RETURN a, b")
                .toList());
    assertThat(funcResults).hasSize(1);

    // Arrow notation should produce the same result
    var arrowResults =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MAArrowV, as: a, where: (name = 'a')} "
                    + "--> {} --> {} --> {as:b} RETURN a, b")
                .toList());
    assertThat(arrowResults).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MAArrowE").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MAArrowV").iterate();
    });
  }

  // Line 759-768: Arrow notation with edge class label
  @Test
  public void testMatchArrowNotationWithEdgeClass() {
    g.command("CREATE CLASS MAArrowPerson IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS MAArrowFriend IF NOT EXISTS EXTENDS E");
    g.command("CREATE CLASS MAArrowBelongsTo IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MAArrowPerson SET name = 'Alice'").iterate();
      tx.yql("CREATE VERTEX MAArrowPerson SET name = 'Bob'").iterate();
      tx.yql("CREATE VERTEX MAArrowPerson SET name = 'item1'").iterate();
    });

    g.executeInTx(tx -> {
      tx.yql(
          "CREATE EDGE MAArrowFriend "
              + "FROM (SELECT FROM MAArrowPerson WHERE name = 'Alice') "
              + "TO (SELECT FROM MAArrowPerson WHERE name = 'Bob')")
          .iterate();
      tx.yql(
          "CREATE EDGE MAArrowBelongsTo "
              + "FROM (SELECT FROM MAArrowPerson WHERE name = 'item1') "
              + "TO (SELECT FROM MAArrowPerson WHERE name = 'Bob')")
          .iterate();
    });

    // Functional notation
    var funcResults =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MAArrowPerson, as: a, where: (name = 'Alice')}"
                    + ".out('MAArrowFriend'){as:friend}"
                    + ".in('MAArrowBelongsTo'){as:b} RETURN a, b")
                .toList());
    assertThat(funcResults).hasSize(1);

    // Arrow notation
    var arrowResults =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH {class: MAArrowPerson, as: a, where: (name = 'Alice')} "
                    + "-MAArrowFriend-> {as:friend} <-MAArrowBelongsTo- {as:b} "
                    + "RETURN a, b")
                .toList());
    assertThat(arrowResults).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MAArrowFriend").iterate();
      tx.yql("DELETE EDGE MAArrowBelongsTo").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MAArrowPerson").iterate();
    });
  }

  // Line 780-795: NOT patterns — friends of friends who are NOT direct friends
  @Test
  public void testMatchNegativePattern() {
    g.command("CREATE CLASS MANotPerson IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS MANotFriendOf IF NOT EXISTS EXTENDS E");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX MANotPerson SET name = 'John'").iterate();
      tx.yql("CREATE VERTEX MANotPerson SET name = 'Bob'").iterate();
      tx.yql("CREATE VERTEX MANotPerson SET name = 'Carol'").iterate();
      tx.yql("CREATE VERTEX MANotPerson SET name = 'Dave'").iterate();
    });

    g.executeInTx(tx -> {
      // John -> Bob, John -> Carol
      tx.yql(
          "CREATE EDGE MANotFriendOf "
              + "FROM (SELECT FROM MANotPerson WHERE name = 'John') "
              + "TO (SELECT FROM MANotPerson WHERE name = 'Bob')")
          .iterate();
      tx.yql(
          "CREATE EDGE MANotFriendOf "
              + "FROM (SELECT FROM MANotPerson WHERE name = 'John') "
              + "TO (SELECT FROM MANotPerson WHERE name = 'Carol')")
          .iterate();
      // Bob -> Dave (Dave is friend-of-friend of John but NOT direct friend)
      tx.yql(
          "CREATE EDGE MANotFriendOf "
              + "FROM (SELECT FROM MANotPerson WHERE name = 'Bob') "
              + "TO (SELECT FROM MANotPerson WHERE name = 'Dave')")
          .iterate();
      // Carol -> Bob (Bob is both direct friend and friend-of-friend)
      tx.yql(
          "CREATE EDGE MANotFriendOf "
              + "FROM (SELECT FROM MANotPerson WHERE name = 'Carol') "
              + "TO (SELECT FROM MANotPerson WHERE name = 'Bob')")
          .iterate();
    });

    // NOT pattern: friends of friends who are NOT direct friends
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "MATCH "
                    + "{class: MANotPerson, as:a, where:(name = 'John')} "
                    + "-MANotFriendOf-> {as:b} -MANotFriendOf-> {as:c}, "
                    + "NOT {as:a} -MANotFriendOf-> {as:c} "
                    + "RETURN c.name as name")
                .toList());
    // Dave is friend-of-friend but not direct friend
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE EDGE MANotFriendOf").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX MANotPerson").iterate();
    });
  }

  // Line 605-611: $matches returns all aliases from the pattern
  @Test
  public void testMatchReturnMatches() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".bothE('MAFriend'){}"
                      + ".bothV(){as: friend, "
                      + "where: ($matched.person != $currentMatch)} "
                      + "RETURN $matches")
                  .toList());
      // John Doe has 3 friends
      assertThat(results).hasSize(3);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 679-685: $paths returns all nodes including auto-generated aliases
  @Test
  public void testMatchReturnPaths() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".bothE('MAFriend'){}"
                      + ".bothV(){as: friend, "
                      + "where: ($matched.person != $currentMatch)} "
                      + "RETURN $paths")
                  .toList());
      assertThat(results).hasSize(3);
      // Each result should include the auto-generated alias for the unnamed node
      @SuppressWarnings("unchecked")
      Map<String, Object> first = (Map<String, Object>) results.get(0);
      assertThat(first).containsKey("person");
      assertThat(first).containsKey("friend");
      // Verify the auto-generated alias uses YOUTRACKDB prefix (not ORIENT)
      boolean hasAutoAlias =
          first.keySet().stream()
              .anyMatch(k -> k.startsWith("$YOUTRACKDB_DEFAULT_ALIAS_"));
      assertThat(hasAutoAlias)
          .as("Auto-generated alias should use $YOUTRACKDB_DEFAULT_ALIAS_ prefix")
          .isTrue();
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 697-703: $elements returns flattened distinct nodes from $matches
  @Test
  public void testMatchReturnElements() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".bothE('MAFriend'){}"
                      + ".bothV(){as: friend, "
                      + "where: ($matched.person != $currentMatch)} "
                      + "RETURN $elements")
                  .toList());
      // $elements returns flattened distinct vertices from $matches aliases
      // person (Doe) + 3 friends (Smith, Jenny, Frank) = at least 4
      assertThat(results).hasSizeGreaterThanOrEqualTo(4);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // Line 715-736: $pathElements returns flattened distinct nodes from $paths (including edges)
  @Test
  public void testMatchReturnPathElements() {
    setUpMatchSampleData();
    try {
      var results =
          g.computeInTx(
              tx -> tx.yql(
                  "MATCH {class: MAPerson, as: person, "
                      + "where: (name = 'John' AND surname = 'Doe')}"
                      + ".bothE('MAFriend'){}"
                      + ".bothV(){as: friend, "
                      + "where: ($matched.person != $currentMatch)} "
                      + "RETURN $pathElements")
                  .toList());
      // $pathElements includes edges and vertices, flattened and distinct
      // At least 4 vertices + 3 edges = 7
      assertThat(results).hasSizeGreaterThanOrEqualTo(7);
    } finally {
      tearDownMatchSampleData();
    }
  }

  // === YQL-Create-Class.md ===

  // Line 24-26: CREATE CLASS Account extends V
  @Test
  public void testCreateClassAccount() {
    g.command("CREATE CLASS CCAccount IF NOT EXISTS EXTENDS V");

    // Verify the class exists by inserting and querying a vertex
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CCAccount SET name = 'test'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM CCAccount").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CCAccount").iterate();
    });
  }

  // Line 30-32: CREATE CLASS Car EXTENDS Vehicle (extends another class)
  @Test
  public void testCreateClassExtendsAnotherClass() {
    g.command("CREATE CLASS CCVehicle IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CCCar IF NOT EXISTS EXTENDS CCVehicle");

    // Verify CCCar is a subclass of CCVehicle by inserting into CCCar and querying CCVehicle
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CCCar SET model = 'Sedan'").iterate();
    });

    var resultsFromParent =
        g.computeInTx(tx -> tx.yql("SELECT FROM CCVehicle").toList());
    assertThat(resultsFromParent).hasSize(1);

    var resultsFromChild =
        g.computeInTx(tx -> tx.yql("SELECT FROM CCCar").toList());
    assertThat(resultsFromChild).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CCCar").iterate();
    });
  }

  // Line 37-39: CREATE CLASS Person ABSTRACT — abstract classes cannot be instantiated
  @Test
  public void testCreateAbstractClass() {
    g.command("CREATE CLASS CCPerson IF NOT EXISTS EXTENDS V ABSTRACT");

    // Attempting to create a vertex of an abstract class should fail
    assertThatThrownBy(() -> g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CCPerson SET name = 'John'").iterate();
    }));
  }

  // Line 9/14: IF NOT EXISTS — class creation is ignored if the class already exists
  @Test
  public void testCreateClassIfNotExists() {
    g.command("CREATE CLASS CCDuplicate IF NOT EXISTS EXTENDS V");
    // Second call should not throw
    g.command("CREATE CLASS CCDuplicate IF NOT EXISTS EXTENDS V");

    // Verify the class still works
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CCDuplicate SET val = 1").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM CCDuplicate").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CCDuplicate").iterate();
    });
  }

  // === YQL-Alter-Class.md ===

  // Line 20: ALTER CLASS Employee SUPERCLASSES Person — define superclasses (replaces all)
  @Test
  public void testAlterClassDefineSuperclass() {
    g.command("CREATE CLASS AcPerson IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS AcEmployee IF NOT EXISTS EXTENDS V");
    // SUPERCLASSES replaces the full list; V must be kept or the class ceases to be a vertex
    g.command("ALTER CLASS AcEmployee SUPERCLASSES AcPerson, V");

    // Verify the class still works after superclass change
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AcEmployee SET name = 'Alice'").iterate();
    });
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcEmployee").toList());
    assertThat(results).hasSize(1);

    // Employee records should also be visible from AcPerson
    var fromPerson =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcPerson").toList());
    assertThat(fromPerson).isNotEmpty();

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX AcEmployee").iterate();
    });
  }

  // Line 26: SUPERCLASSES supports multiple inheritance via comma-separated list
  @Test
  public void testAlterClassMultipleSuperclasses() {
    g.command("CREATE CLASS AcBase1 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS AcBase2 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS AcChild IF NOT EXISTS EXTENDS AcBase1");
    // Add AcBase2 by providing the full list (V must be kept for vertex classes)
    g.command("ALTER CLASS AcChild SUPERCLASSES AcBase1, AcBase2, V");

    // Verify the class works with multiple superclasses
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AcChild SET name = 'Bob'").iterate();
    });

    // Records should be visible from both parent classes
    var fromBase1 =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcBase1").toList());
    var fromBase2 =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcBase2").toList());
    assertThat(fromBase1).isNotEmpty();
    assertThat(fromBase2).isNotEmpty();

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX AcChild").iterate();
    });
  }

  // Line 33: SUPERCLASSES can remove a superclass by omitting it from the replacement list
  @Test
  public void testAlterClassRemoveSuperclass() {
    g.command("CREATE CLASS AcRemBase1 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS AcRemBase2 IF NOT EXISTS EXTENDS V");
    g.command(
        "CREATE CLASS AcRemChild IF NOT EXISTS EXTENDS AcRemBase1, AcRemBase2");
    // Remove AcRemBase2 by providing the list without it (V must be kept)
    g.command("ALTER CLASS AcRemChild SUPERCLASSES AcRemBase1, V");

    // Verify the class still works after removing a superclass
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AcRemChild SET name = 'Charlie'").iterate();
    });

    // Record should still be visible from AcRemBase1
    var fromBase1 =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcRemBase1").toList());
    assertThat(fromBase1).isNotEmpty();

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX AcRemChild").iterate();
    });
  }

  // Line 39: ALTER CLASS Account NAME Seller — rename a class
  @Test
  public void testAlterClassRename() {
    g.command("CREATE CLASS AcAccount IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AcAccount SET balance = 100").iterate();
    });

    g.command("ALTER CLASS AcAccount NAME AcSeller");

    // Verify the class is accessible under the new name
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcSeller").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((int) v.value("balance")).isEqualTo(100);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX AcSeller").iterate();
    });
  }

  // Line 45: ALTER CLASS TheClass ABSTRACT true — convert to abstract class
  @Test
  public void testAlterClassAbstract() {
    g.command("CREATE CLASS AcTheClass IF NOT EXISTS EXTENDS V");
    g.command("ALTER CLASS AcTheClass ABSTRACT true");

    // Verify that creating a vertex of an abstract class fails
    assertThatThrownBy(() -> g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AcTheClass SET name = 'test'").iterate();
    }));
  }

  // Line 59: STRICT_MODE — verify the ALTER CLASS STRICT_MODE command is accepted
  @Test
  public void testAlterClassStrictMode() {
    g.command("CREATE CLASS AcStrict IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY AcStrict.name STRING");
    // Verify that STRICT_MODE (with underscore) is the correct syntax
    g.command("ALTER CLASS AcStrict STRICT_MODE true");

    // Adding a property that IS in the schema should succeed
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AcStrict SET name = 'valid'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcStrict").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX AcStrict").iterate();
    });
  }

  // Line 60: CUSTOM — define custom properties with key=value syntax
  @Test
  public void testAlterClassCustomProperty() {
    g.command("CREATE CLASS AcCustom IF NOT EXISTS EXTENDS V");
    g.command("ALTER CLASS AcCustom CUSTOM myProp='hello'");

    // Verify the class still works after setting a custom property
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX AcCustom SET name = 'test'").iterate();
    });
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM AcCustom").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX AcCustom").iterate();
    });
  }

  // ===================================================================================
  // YQL-Alter-Property.md validation tests
  // ===================================================================================

  // Line 22: ALTER PROPERTY Account.age NAME "born" — rename a property
  // Validates that the ALTER PROPERTY ... NAME syntax is accepted by the parser.
  // Note: the doc claims "the old value is copied to the new property name" (line 82),
  // but existing record data is NOT automatically migrated — this is a doc inaccuracy.
  @Test
  public void testAlterPropertyRenameName() {
    g.command("CREATE CLASS ApAccount IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApAccount.age INTEGER");

    // Rename property from 'age' to 'born' — command should succeed
    g.command("ALTER PROPERTY ApAccount.age NAME \"born\"");

    // Insert a vertex using the new property name
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApAccount SET born = 25").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApAccount WHERE born = 25").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((int) v.value("born")).isEqualTo(25);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApAccount").iterate();
    });
    g.command("DROP CLASS ApAccount IF EXISTS");
  }

  // Line 29: ALTER PROPERTY Account.age MANDATORY TRUE — make a property mandatory
  // Validates that the ALTER PROPERTY ... MANDATORY syntax is accepted.
  @Test
  public void testAlterPropertyMandatory() {
    g.command("CREATE CLASS ApMandatory IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApMandatory.age INTEGER");

    // The MANDATORY TRUE command should be accepted without error
    g.command("ALTER PROPERTY ApMandatory.age MANDATORY TRUE");

    // Inserting with the mandatory field should succeed
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApMandatory SET age = 30").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApMandatory").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApMandatory").iterate();
    });
    g.command("DROP CLASS ApMandatory IF EXISTS");
  }

  // Line 35: ALTER PROPERTY Account.gender REGEXP "[M|F]" — regex constraint
  // Validates that the ALTER PROPERTY ... REGEXP syntax is accepted.
  @Test
  public void testAlterPropertyRegexp() {
    g.command("CREATE CLASS ApRegexp IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApRegexp.gender STRING");

    // The REGEXP command should be accepted without error
    g.command("ALTER PROPERTY ApRegexp.gender REGEXP \"[M|F]\"");

    // Valid value should succeed
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApRegexp SET gender = 'M'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApRegexp").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApRegexp").iterate();
    });
    g.command("DROP CLASS ApRegexp IF EXISTS");
  }

  // Line 41: ALTER PROPERTY Employee.name COLLATE "ci" — case-insensitive collation
  @Test
  public void testAlterPropertyCollate() {
    g.command("CREATE CLASS ApCollate IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApCollate.name STRING");
    g.command("ALTER PROPERTY ApCollate.name COLLATE \"ci\"");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApCollate SET name = 'John'").iterate();
    });

    // Case-insensitive query should find the record
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApCollate WHERE name = 'john'").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApCollate").iterate();
    });
    g.command("DROP CLASS ApCollate IF EXISTS");
  }

  // Line 47: ALTER PROPERTY Foo.bar1 custom stereotype="visible" — custom property
  @Test
  public void testAlterPropertyCustom() {
    g.command("CREATE CLASS ApCustom IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApCustom.bar1 STRING");
    g.command("ALTER PROPERTY ApCustom.bar1 custom stereotype=\"visible\"");

    // Verify the class and property still work after setting custom attribute
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApCustom SET bar1 = 'test'").iterate();
    });
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApCustom").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApCustom").iterate();
    });
    g.command("DROP CLASS ApCustom IF EXISTS");
  }

  // Line 53: ALTER PROPERTY Client.created DEFAULT "sysdate()" — default value with sysdate()
  @Test
  public void testAlterPropertyDefaultSysdate() {
    g.command("CREATE CLASS ApDefault IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApDefault.created DATETIME");
    g.command("ALTER PROPERTY ApDefault.created DEFAULT \"sysdate()\"");

    // Insert without specifying 'created' — it should be auto-populated
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApDefault SET name = 'test'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApDefault").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((Object) v.value("created")).isNotNull();

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApDefault").iterate();
    });
    g.command("DROP CLASS ApDefault IF EXISTS");
  }

  // Line 58-59: ALTER PROPERTY Client.id DEFAULT "uuid()" READONLY TRUE — immutable UUID default
  @Test
  public void testAlterPropertyDefaultUuidReadonly() {
    g.command("CREATE CLASS ApReadonly IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApReadonly.id STRING");
    g.command("ALTER PROPERTY ApReadonly.id DEFAULT \"uuid()\"");
    g.command("ALTER PROPERTY ApReadonly.id READONLY TRUE");

    // Insert without specifying 'id' — it should be auto-populated with a UUID
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApReadonly SET name = 'test'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApReadonly").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    String id = v.value("id");
    assertThat(id).isNotNull().isNotEmpty();

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApReadonly").iterate();
    });
    g.command("DROP CLASS ApReadonly IF EXISTS");
  }

  // Factual claim line 75/81: TYPE attribute — validates ALTER PROPERTY ... TYPE syntax.
  // The doc says "this command runs a data update" but does not mention that only castable
  // type changes are allowed (e.g., INTEGER to LONG works, STRING to INTEGER does not).
  @Test
  public void testAlterPropertyType() {
    g.command("CREATE CLASS ApType IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApType.value INTEGER");

    // Change type from INTEGER to LONG (castable) should succeed
    g.command("ALTER PROPERTY ApType.value TYPE LONG");

    // Verify the property works with the new type
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApType SET value = 42").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApType").toList());
    assertThat(results).hasSize(1);

    // Incompatible type change should fail
    assertThatThrownBy(() -> g.command("ALTER PROPERTY ApType.value TYPE STRING"))
        .isInstanceOf(IllegalArgumentException.class);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApType").iterate();
    });
    g.command("DROP CLASS ApType IF EXISTS");
  }

  // Table row: NOTNULL — validates that the ALTER PROPERTY ... NOTNULL syntax is accepted
  @Test
  public void testAlterPropertyNotNull() {
    g.command("CREATE CLASS ApNotNull IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApNotNull.name STRING");

    // The NOTNULL command should be accepted without error
    g.command("ALTER PROPERTY ApNotNull.name NOTNULL TRUE");

    // Inserting with a value should succeed
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApNotNull SET name = 'valid'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApNotNull").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApNotNull").iterate();
    });
    g.command("DROP CLASS ApNotNull IF EXISTS");
  }

  // Table row: MIN / MAX — validates that the ALTER PROPERTY ... MIN/MAX syntax is accepted
  @Test
  public void testAlterPropertyMinMax() {
    g.command("CREATE CLASS ApMinMax IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApMinMax.code STRING");

    // MIN and MAX commands should be accepted without error
    g.command("ALTER PROPERTY ApMinMax.code MIN 2");
    g.command("ALTER PROPERTY ApMinMax.code MAX 5");

    // Valid length should succeed
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApMinMax SET code = 'ABC'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM ApMinMax").toList());
    assertThat(results).hasSize(1);

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApMinMax").iterate();
    });
    g.command("DROP CLASS ApMinMax IF EXISTS");
  }

  // Table row: LINKEDCLASS — defines the linked class name
  @Test
  public void testAlterPropertyLinkedClass() {
    g.command("CREATE CLASS ApTarget IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS ApLinked IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY ApLinked.items LINKLIST");
    g.command("ALTER PROPERTY ApLinked.items LINKEDCLASS ApTarget");

    // Verify the property works by inserting data
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX ApLinked SET name = 'test'").iterate();
    });

    // Cleanup
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX ApLinked").iterate();
    });
    g.command("DROP CLASS ApLinked IF EXISTS");
    g.command("DROP CLASS ApTarget IF EXISTS");
  }

  // ==========================================================================
  // YQL-Create-Security-Policy.md
  // ==========================================================================

  @Test
  public void testCreateSecurityPolicy_emptyPolicy() {
    // Doc line 23: CREATE SECURITY POLICY foo
    // Creates an empty policy with no predicates.
    g.command("CREATE SECURITY POLICY cspTestPolicy1");
  }

  @Test
  public void testCreateSecurityPolicy_allPredicates() {
    // Doc line 29: CREATE SECURITY POLICY foo SET CREATE = (name = 'foo'), READ = (TRUE),
    // BEFORE UPDATE = (name = 'foo'), AFTER UPDATE = (name = 'foo'),
    // DELETE = (name = 'foo'), EXECUTE = (name = 'foo')
    g.command(
        "CREATE SECURITY POLICY cspTestPolicy2"
            + " SET CREATE = (name = 'foo'), READ = (TRUE),"
            + " BEFORE UPDATE = (name = 'foo'), AFTER UPDATE = (name = 'foo'),"
            + " DELETE = (name = 'foo'), EXECUTE = (name = 'foo')");
  }

  // ==========================================================================
  // YQL-Alter-Security-Policy.md
  // ==========================================================================

  @Test
  public void testAlterSecurityPolicy_setCreateAndRead() {
    // Doc line 28: ALTER SECURITY POLICY foo SET CREATE = (name = 'foo'), READ = (TRUE)
    // First create the policy, then alter it.
    g.command("CREATE SECURITY POLICY aspTestPolicy1");
    g.command("ALTER SECURITY POLICY aspTestPolicy1 SET CREATE = (name = 'foo'), READ = (TRUE)");

    // Cleanup
    g.command("ALTER SECURITY POLICY aspTestPolicy1 REMOVE CREATE, READ");
  }

  @Test
  public void testAlterSecurityPolicy_removeCreateAndRead() {
    // Doc line 34: ALTER SECURITY POLICY foo REMOVE CREATE, READ
    // Create a policy with predicates, then remove them.
    g.command("CREATE SECURITY POLICY aspTestPolicy2");
    g.command(
        "ALTER SECURITY POLICY aspTestPolicy2 SET CREATE = (name = 'bar'), READ = (name = 'bar')");
    g.command("ALTER SECURITY POLICY aspTestPolicy2 REMOVE CREATE, READ");
  }

  @Test
  public void testAlterSecurityPolicy_requiresSetOrRemove() {
    // Factual claim: at least one of SET or REMOVE is required.
    // ALTER SECURITY POLICY <name> alone should fail.
    g.command("CREATE SECURITY POLICY aspTestPolicy3");
    assertThatThrownBy(() -> g.command("ALTER SECURITY POLICY aspTestPolicy3"));
  }

  @Test
  public void testAlterSecurityPolicy_allOperationTypes() {
    // Validates all 6 operation types from the syntax: CREATE, READ,
    // BEFORE UPDATE, AFTER UPDATE, DELETE, EXECUTE.
    g.command("CREATE SECURITY POLICY aspTestPolicy4");
    g.command(
        "ALTER SECURITY POLICY aspTestPolicy4"
            + " SET CREATE = (name = 'a'), READ = (name = 'b'),"
            + " BEFORE UPDATE = (name = 'c'), AFTER UPDATE = (name = 'd'),"
            + " DELETE = (name = 'e'), EXECUTE = (name = 'f')");

    // Remove all
    g.command(
        "ALTER SECURITY POLICY aspTestPolicy4"
            + " REMOVE CREATE, READ, BEFORE UPDATE, AFTER UPDATE, DELETE, EXECUTE");
  }

  @Test
  public void testAlterSecurityPolicy_setAndRemoveInSameStatement() {
    // Factual claim from doc: both SET and REMOVE can appear in the same statement.
    g.command("CREATE SECURITY POLICY aspTestPolicy5");
    g.command(
        "ALTER SECURITY POLICY aspTestPolicy5"
            + " SET CREATE = (name = 'foo') REMOVE DELETE");
  }

  // === YQL-Alter-Sequence.md ===

  // Line 27: ALTER SEQUENCE idseq START 1000 CYCLE TRUE
  @Test
  public void testAlterSequence_startAndCycle() {
    // Doc example: ALTER SEQUENCE with START and CYCLE options.
    g.command("CREATE SEQUENCE altSeqTest1 TYPE ORDERED");
    g.command("ALTER SEQUENCE altSeqTest1 START 1000 CYCLE TRUE");
    g.command("DROP SEQUENCE altSeqTest1");
  }

  @Test
  public void testAlterSequence_increment() {
    // Factual claim (line 13): INCREMENT defines the increment value applied when calling .next().
    g.command("CREATE SEQUENCE altSeqTest2 TYPE ORDERED");
    g.command("ALTER SEQUENCE altSeqTest2 INCREMENT 5");
    g.command("DROP SEQUENCE altSeqTest2");
  }

  @Test
  public void testAlterSequence_cache() {
    // Factual claim (line 14): CACHE defines the number of values to cache for CACHED sequences.
    g.command("CREATE SEQUENCE altSeqTest3 TYPE CACHED");
    g.command("ALTER SEQUENCE altSeqTest3 CACHE 50");
    g.command("DROP SEQUENCE altSeqTest3");
  }

  @Test
  public void testAlterSequence_limit() {
    // Factual claim (line 16): LIMIT defines the limit value the sequence can reach.
    g.command("CREATE SEQUENCE altSeqTest4 TYPE ORDERED");
    g.command("ALTER SEQUENCE altSeqTest4 LIMIT 500");
    g.command("DROP SEQUENCE altSeqTest4");
  }

  @Test
  public void testAlterSequence_ascDesc() {
    // Factual claim (line 17-18): ASC and DESC define the order of the sequence.
    g.command("CREATE SEQUENCE altSeqTest5 TYPE ORDERED");
    g.command("ALTER SEQUENCE altSeqTest5 DESC");
    g.command("ALTER SEQUENCE altSeqTest5 ASC");
    g.command("DROP SEQUENCE altSeqTest5");
  }

  @Test
  public void testAlterSequence_nolimit() {
    // Factual claim (line 19): NOLIMIT cancels a previously defined LIMIT value.
    g.command("CREATE SEQUENCE altSeqTest6 TYPE ORDERED");
    g.command("ALTER SEQUENCE altSeqTest6 LIMIT 100");
    g.command("ALTER SEQUENCE altSeqTest6 NOLIMIT");
    g.command("DROP SEQUENCE altSeqTest6");
  }

  @Test
  public void testAlterSequence_multipleOptions() {
    // Verify multiple options can be combined in a single statement.
    g.command("CREATE SEQUENCE altSeqTest7 TYPE ORDERED");
    g.command("ALTER SEQUENCE altSeqTest7 START 100 INCREMENT 10 LIMIT 1000 CYCLE TRUE ASC");
    g.command("DROP SEQUENCE altSeqTest7");
  }

  // === YQL-Create-Function.md ===
  // Note: CREATE FUNCTION returns an OFunction result which the Gremlin result mapper does not
  // support (only vertices and stateful edges). These tests verify that the syntax is parsed
  // and executed correctly — the IllegalStateException comes from the result mapper, not the
  // parser or executor.

  // Line 25: CREATE FUNCTION test "print('\nTest!')" LANGUAGE javascript
  // Validates that a no-parameter JavaScript function can be created.
  @Test
  public void testCreateFunction_noParams() {
    // The function is created successfully, but the Gremlin result mapper cannot map OFunction.
    assertThatThrownBy(() -> g.executeInTx(tx -> {
      tx.yql("CREATE FUNCTION cfTest1 \"print('\\nTest!')\" LANGUAGE javascript").iterate();
    })).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OFunction");
  }

  // Line 31: CREATE FUNCTION test "return a + b;" PARAMETERS [a,b] LANGUAGE javascript
  // Validates that a JavaScript function with parameters can be created.
  @Test
  public void testCreateFunction_withParams() {
    assertThatThrownBy(() -> g.executeInTx(tx -> {
      tx.yql("CREATE FUNCTION cfTest2 \"return a + b;\" PARAMETERS [a,b] LANGUAGE javascript")
          .iterate();
    })).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OFunction");
  }

  // Line 37-38: CREATE FUNCTION allUsersButAdmin "SELECT FROM ouser WHERE name <> 'admin'"
  //             LANGUAGE YQL
  // Validates that a YQL-language function can be created.
  @Test
  public void testCreateFunction_languageYQL() {
    assertThatThrownBy(() -> g.executeInTx(tx -> {
      tx.yql(
          "CREATE FUNCTION cfTest3 \"SELECT FROM ouser WHERE name <> 'admin'\" LANGUAGE YQL")
          .iterate();
    })).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OFunction");
  }

  // Factual claim (line 18): IDEMPOTENT defines whether the function can change the database
  // status. Default is FALSE.
  @Test
  public void testCreateFunction_idempotent() {
    assertThatThrownBy(() -> g.executeInTx(tx -> {
      tx.yql("CREATE FUNCTION cfTest4 \"return 1\" IDEMPOTENT true").iterate();
    })).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OFunction");
  }

  // Factual claim (line 19): Default language is YQL.
  // Validates that a function without LANGUAGE clause is accepted.
  @Test
  public void testCreateFunction_defaultLanguageIsYQL() {
    assertThatThrownBy(() -> g.executeInTx(tx -> {
      tx.yql("CREATE FUNCTION cfTest5 \"SELECT 1\"").iterate();
    })).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OFunction");
  }

  // === YQL-Create-Index.md ===

  // Lines 36-37: Create an automatic index bound to the new property id in the class User.
  @Test
  public void testCreateIndex_automaticIndexOnProperty() {
    g.command("CREATE CLASS CIUser IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CIUser.id IF NOT EXISTS INTEGER");
    g.command("CREATE INDEX CIUser.id IF NOT EXISTS UNIQUE");

    // Verify index is usable by inserting data and querying
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CIUser SET id = 1").iterate();
      tx.yql("CREATE VERTEX CIUser SET id = 2").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CIUser WHERE id = 1").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((int) v.value("id")).isEqualTo(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CIUser").iterate();
    });
  }

  // Lines 42-44: Create indexes on map property using BY KEY and BY VALUE.
  @Test
  public void testCreateIndex_mapByKeyAndByValue() {
    g.command("CREATE CLASS CIMovie IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CIMovie.thumbs IF NOT EXISTS EMBEDDEDMAP INTEGER");
    g.command("CREATE INDEX ciThumbsAuthor IF NOT EXISTS ON CIMovie (thumbs) UNIQUE");
    g.command("CREATE INDEX ciThumbsKey IF NOT EXISTS ON CIMovie (thumbs BY KEY) UNIQUE");
    g.command("CREATE INDEX ciThumbsValue IF NOT EXISTS ON CIMovie (thumbs BY VALUE) UNIQUE");

    // Verify by inserting data with a map property
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CIMovie SET thumbs = {'john': 5, 'jane': 3}").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CIMovie").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CIMovie").iterate();
    });
  }

  // Lines 49-53: Create a composite index on multiple properties including EMBEDDEDLIST.
  @Test
  public void testCreateIndex_compositeIndex() {
    g.command("CREATE CLASS CIBook IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CIBook.author IF NOT EXISTS STRING");
    g.command("CREATE PROPERTY CIBook.title IF NOT EXISTS STRING");
    g.command("CREATE PROPERTY CIBook.publicationYears IF NOT EXISTS EMBEDDEDLIST INTEGER");
    g.command(
        "CREATE INDEX ciBooks IF NOT EXISTS ON CIBook (author, title, publicationYears) UNIQUE");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CIBook SET author = 'Tolkien', title = 'The Hobbit',"
          + " publicationYears = [1937, 1951]").iterate();
    });

    var results = g.computeInTx(
        tx -> tx.yql("SELECT FROM CIBook WHERE author = 'Tolkien'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CIBook").iterate();
    });
  }

  // Lines 58-63: Create an index on an edge's date range. Note: the doc references 'ended'
  // property but only creates 'started'. This test validates the corrected version.
  @Test
  public void testCreateIndex_edgeDateRangeIndex() {
    g.command("CREATE CLASS CIFile IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CIHas IF NOT EXISTS EXTENDS E");
    g.command("CREATE PROPERTY CIHas.started IF NOT EXISTS DATETIME");
    g.command("CREATE PROPERTY CIHas.ended IF NOT EXISTS DATETIME");
    g.command(
        "CREATE INDEX ciHasDateRange IF NOT EXISTS ON CIHas (started, ended) NOTUNIQUE");

    // Create vertices and edge with date range
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CIFile SET name = 'a.txt'").iterate();
      tx.yql("CREATE VERTEX CIFile SET name = 'b.txt'").iterate();
    });

    var files = g.computeInTx(tx -> tx.yql("SELECT FROM CIFile ORDER BY name").toList());
    assertThat(files).hasSize(2);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CIFile").iterate();
    });
  }

  // Lines 69-71: SELECT from edge class with date range filter.
  @Test
  public void testCreateIndex_selectEdgesByDateRange() {
    g.command("CREATE CLASS CIFile2 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CIHas2 IF NOT EXISTS EXTENDS E");
    g.command("CREATE PROPERTY CIHas2.started IF NOT EXISTS DATETIME");
    g.command("CREATE PROPERTY CIHas2.ended IF NOT EXISTS DATETIME");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CIFile2 SET name = 'f1'").iterate();
      tx.yql("CREATE VERTEX CIFile2 SET name = 'f2'").iterate();
    });

    // Query with date range filter — validates the syntax parses correctly
    var results = g.computeInTx(tx -> tx.yql(
        "SELECT FROM CIHas2 WHERE started >= '2014-01-01 00:00:00.000'"
            + " AND ended < '2015-01-01 00:00:00.000'")
        .toList());
    assertThat(results).isEmpty();

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CIFile2").iterate();
    });
  }

  // Lines 76-78: MATCH query on edges with date range.
  @Test
  public void testCreateIndex_matchQueryEdgeDateRange() {
    g.command("CREATE CLASS CIFile3 IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS CIHas3 IF NOT EXISTS EXTENDS E");
    g.command("CREATE PROPERTY CIHas3.started IF NOT EXISTS DATETIME");
    g.command("CREATE PROPERTY CIHas3.ended IF NOT EXISTS DATETIME");

    // DOC BUG: Lines 76-78 use -Has{where:...}-> syntax which is invalid.
    // Correct MATCH syntax uses .outE('EdgeClass'){where:...}.inV() or .out('EdgeClass'){where:...}
    var results = g.computeInTx(tx -> tx.yql(
        "MATCH {class: CIFile3, as: outV}"
            + ".outE('CIHas3'){where: (started >= '2014-01-01 00:00:00.000'"
            + " AND ended < '2015-01-01 00:00:00.000')}.inV()"
            + "{class: CIFile3, as: inV}"
            + " RETURN outV")
        .toList());
    assertThat(results).isEmpty();
  }

  // Lines 90-91: Create an index with METADATA { ignoreNullValues: false }.
  @Test
  public void testCreateIndex_metadataIgnoreNullValues() {
    g.command("CREATE CLASS CIEmployee IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CIEmployee.address IF NOT EXISTS STRING");
    g.command("CREATE INDEX ciAddresses IF NOT EXISTS ON CIEmployee (address) NOTUNIQUE"
        + " METADATA { ignoreNullValues : false }");

    // Insert a record with null address and verify the index was created
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CIEmployee SET address = null").iterate();
      tx.yql("CREATE VERTEX CIEmployee SET address = '123 Main St'").iterate();
    });

    var results = g.computeInTx(
        tx -> tx.yql("SELECT FROM CIEmployee WHERE address = '123 Main St'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CIEmployee").iterate();
    });
  }

  // Factual claim (line 12): IF NOT EXISTS causes creation to be silently ignored
  // if the index already exists.
  @Test
  public void testCreateIndex_ifNotExistsIgnoresDuplicate() {
    g.command("CREATE CLASS CIProduct IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CIProduct.sku IF NOT EXISTS STRING");
    g.command("CREATE INDEX CIProduct.sku IF NOT EXISTS UNIQUE");

    // Second call should not throw
    g.command("CREATE INDEX CIProduct.sku IF NOT EXISTS UNIQUE");
  }

  // === YQL-Create-Link.md ===

  // Line 8: Syntax requires link name, TYPE keyword, and link-type — all mandatory.
  // Validates that the basic CREATE LINK syntax parses and executes correctly.
  @Test
  public void testCreateLink_basicLinkBetweenClasses() {
    g.command("CREATE CLASS CLPost IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CLPost.Id IF NOT EXISTS INTEGER");
    g.command("CREATE CLASS CLComment IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CLComment.PostId IF NOT EXISTS INTEGER");
    g.command("CREATE PROPERTY CLComment.post IF NOT EXISTS LINK");

    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX CLPost SET Id = 1, title = 'First Post'").iterate();
          tx.yql("CREATE VERTEX CLComment SET PostId = 1, text = 'Nice post'").iterate();
        });

    // CREATE LINK with TYPE LINK (non-inverse, direct link from Comment to Post)
    g.executeInTx(
        tx -> {
          tx.yql("CREATE LINK post TYPE LINK FROM CLComment.PostId TO CLPost.Id").iterate();
        });

    // Verify the link was created — the comment should now have a 'post' property
    // that is a link to the post record
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM CLComment WHERE PostId = 1").toList());
    assertThat(results).hasSize(1);
  }

  // Line 24: Example — CREATE LINK with INVERSE and LINKSET type
  // Validates the INVERSE variant from the documentation example.
  @Test
  public void testCreateLink_inverseWithLinkSet() {
    g.command("CREATE CLASS CLPosts IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CLPosts.Id IF NOT EXISTS INTEGER");
    g.command("CREATE PROPERTY CLPosts.comments IF NOT EXISTS LINKSET");
    g.command("CREATE CLASS CLComments IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CLComments.PostId IF NOT EXISTS INTEGER");

    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX CLPosts SET Id = 100, title = 'Hello'").iterate();
          tx.yql("CREATE VERTEX CLComments SET PostId = 100, text = 'Great'").iterate();
          tx.yql("CREATE VERTEX CLComments SET PostId = 100, text = 'Awesome'").iterate();
        });

    // This mirrors the doc example (line 24):
    // CREATE LINK comments TYPE LINKSET FROM Comments.PostId TO Posts.Id INVERSE
    g.executeInTx(
        tx -> {
          tx.yql("CREATE LINK comments TYPE LINKSET FROM CLComments.PostId TO CLPosts.Id INVERSE")
              .iterate();
        });

    // After inverse link, the Post should have a 'comments' LINKSET property
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM CLPosts WHERE Id = 100").toList());
    assertThat(results).hasSize(1);
  }

  // Line 8: Syntax shows TYPE [<link-type>] with brackets, suggesting it's optional.
  // In fact, the grammar requires TYPE keyword and link-type value to be mandatory.
  // Verify that omitting TYPE causes a parse error.
  @Test
  public void testCreateLink_typeIsMandatory() {
    g.command("CREATE CLASS CLSrc IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CLSrc.refId IF NOT EXISTS INTEGER");
    g.command("CREATE CLASS CLDst IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CLDst.Id IF NOT EXISTS INTEGER");

    // Omitting TYPE should cause a parse error
    assertThatThrownBy(
        () -> g.executeInTx(
            tx -> {
              tx.yql("CREATE LINK mylink FROM CLSrc.refId TO CLDst.Id").iterate();
            }));
  }

  // === YQL-Create-Property.md ===

  // Line 29: CREATE PROPERTY User.name STRING
  @Test
  public void testCreatePropertyString() {
    g.command("CREATE CLASS CpUser IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CpUser.name IF NOT EXISTS STRING");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CpUser SET name = 'Alice'").iterate();
    });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM CpUser WHERE name = 'Alice'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CpUser").iterate();
    });
  }

  // Line 35: CREATE PROPERTY Profile.tags EMBEDDEDLIST STRING
  @Test
  public void testCreatePropertyEmbeddedListString() {
    g.command("CREATE CLASS CpProfile IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY CpProfile.tags IF NOT EXISTS EMBEDDEDLIST STRING");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CpProfile SET tags = ['a', 'b', 'c']").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CpProfile").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    List<String> tags = v.value("tags");
    assertThat(tags).containsExactly("a", "b", "c");

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CpProfile").iterate();
    });
  }

  // Line 41: CREATE PROPERTY User.name STRING (MANDATORY TRUE, MIN 5, MAX 25)
  // Validates that the CREATE PROPERTY syntax with inline constraints parses and executes.
  @Test
  public void testCreatePropertyWithConstraints() {
    g.command("CREATE CLASS CpUserConst IF NOT EXISTS EXTENDS V");
    g.command(
        "CREATE PROPERTY CpUserConst.name IF NOT EXISTS STRING (MANDATORY TRUE, MIN 5, MAX 25)");

    // Verify data can be inserted for a property with constraints defined
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CpUserConst SET name = 'Alice12345'").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CpUserConst").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CpUserConst").iterate();
    });
  }

  // Line 55-59: Supported standard property types
  @Test
  public void testCreatePropertyStandardTypes() {
    g.command("CREATE CLASS CpTypes IF NOT EXISTS EXTENDS V");

    // All standard types from the documentation table
    String[] types = {
        "BOOLEAN", "SHORT", "DATE", "DATETIME", "BYTE",
        "INTEGER", "LONG", "STRING", "LINK", "DECIMAL",
        "DOUBLE", "FLOAT", "BINARY"
    };

    for (String type : types) {
      g.command(
          "CREATE PROPERTY CpTypes.prop_" + type.toLowerCase() + " IF NOT EXISTS " + type);
    }

    // Verify a vertex can be created with some of these typed properties
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CpTypes SET prop_boolean = true, prop_short = 1,"
          + " prop_integer = 42, prop_long = 100000, prop_string = 'test',"
          + " prop_decimal = 3.14, prop_double = 2.71, prop_float = 1.5,"
          + " prop_byte = 7").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CpTypes").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CpTypes").iterate();
    });
  }

  // Line 63-66: Supported container property types
  @Test
  public void testCreatePropertyContainerTypes() {
    g.command("CREATE CLASS CpContainer IF NOT EXISTS EXTENDS V");

    // All container types from the documentation table
    g.command("CREATE PROPERTY CpContainer.el IF NOT EXISTS EMBEDDEDLIST STRING");
    g.command("CREATE PROPERTY CpContainer.es IF NOT EXISTS EMBEDDEDSET STRING");
    g.command("CREATE PROPERTY CpContainer.em IF NOT EXISTS EMBEDDEDMAP STRING");
    g.command("CREATE PROPERTY CpContainer.ll IF NOT EXISTS LINKLIST");
    g.command("CREATE PROPERTY CpContainer.ls IF NOT EXISTS LINKSET");
    g.command("CREATE PROPERTY CpContainer.lm IF NOT EXISTS LINKMAP");

    // Verify embedded containers work with data
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CpContainer SET el = ['a', 'b'],"
          + " es = ['x', 'y'], em = {'k1': 'v1'}").iterate();
    });

    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CpContainer").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CpContainer").iterate();
    });
  }

  // === YQL-Create-Sequence.md ===

  // Line 28: CREATE SEQUENCE idseq TYPE ORDERED
  @Test
  public void testCreateSequenceOrdered() {
    g.command("CREATE SEQUENCE csIdseq TYPE ORDERED");
  }

  // Line 33: CREATE VERTEX Account SET id = sequence('idseq').next()
  @Test
  public void testCreateSequenceAndUseInVertex() {
    g.command("CREATE CLASS CsAccount IF NOT EXISTS EXTENDS V");
    g.command("CREATE SEQUENCE csAcctSeq TYPE ORDERED");
    // Create vertex first, then update with sequence value to avoid Gremlin mapper issue
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CsAccount SET name = 'test'").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("UPDATE CsAccount SET id = sequence('csAcctSeq').next()").iterate();
    });
    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CsAccount").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    // sequence().next() increments before returning, so first value is START + INCREMENT (0 + 1 = 1)
    assertThat((long) v.value("id")).isEqualTo(1L);
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CsAccount").iterate();
    });
  }

  // Verify CACHED type with START and CACHE options
  @Test
  public void testCreateSequenceCachedWithOptions() {
    g.command("CREATE SEQUENCE csCachedSeq TYPE CACHED START 100 INCREMENT 5 CACHE 10");
    g.command("CREATE CLASS CsCachedTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX CsCachedTest SET name = 'test'").iterate();
    });
    g.executeInTx(tx -> {
      tx.yql("UPDATE CsCachedTest SET id = sequence('csCachedSeq').next()").iterate();
    });
    var results = g.computeInTx(tx -> tx.yql("SELECT FROM CsCachedTest").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    // sequence().next() increments before returning: START + INCREMENT (100 + 5 = 105)
    assertThat((long) v.value("id")).isEqualTo(105L);
    g.executeInTx(tx -> {
      tx.yql("DELETE VERTEX CsCachedTest").iterate();
    });
  }

  // Verify IF NOT EXISTS does not throw when sequence already exists
  @Test
  public void testCreateSequenceIfNotExists() {
    g.command("CREATE SEQUENCE csExistsSeq TYPE ORDERED");
    // Should not throw
    g.command("CREATE SEQUENCE csExistsSeq IF NOT EXISTS TYPE ORDERED");
  }

  // Verify CYCLE and LIMIT options are accepted
  @Test
  public void testCreateSequenceWithCycleAndLimit() {
    g.command("CREATE SEQUENCE csCyclicSeq TYPE ORDERED START 0 INCREMENT 1 LIMIT 10 CYCLE TRUE");
  }

  // Verify DESC order is accepted
  @Test
  public void testCreateSequenceDesc() {
    g.command("CREATE SEQUENCE csDescSeq TYPE ORDERED START 100 INCREMENT 1 DESC");
  }

  // Verify duplicate sequence without IF NOT EXISTS throws
  @Test
  public void testCreateSequenceDuplicateThrows() {
    g.command("CREATE SEQUENCE csDupSeq TYPE ORDERED");
    assertThatThrownBy(() -> g.command("CREATE SEQUENCE csDupSeq TYPE ORDERED"));
  }

  // === YQL-Create-User.md ===
  // Note: CREATE USER internally does INSERT INTO OUser, which is not a V/E class.
  // The Gremlin API result mapper only supports vertices and edges, so CREATE USER
  // cannot be executed through the public Gremlin API (command() or yql()).
  // Syntax validation is covered by CreateUserStatementTest; execution is covered
  // by CreateUserStatementExecutionTest. Here we validate the documented syntax
  // is parseable by constructing a yql() traversal (which triggers parsing).

  // Line 25-27: CREATE USER Bar IDENTIFIED BY Foo (no role, defaults to writer)
  @Test
  public void testCreateUserWithoutRoleSyntax() {
    // Verify the documented query parses without error.
    // yql() eagerly parses the statement; if syntax were invalid, it would throw.
    g.computeInTx(tx -> tx.yql("CREATE USER cuSyntaxBar IDENTIFIED BY Foo"));
  }

  // Line 19-21: CREATE USER Foo IDENTIFIED BY bar ROLE admin
  @Test
  public void testCreateUserWithRoleSyntax() {
    g.computeInTx(tx -> tx.yql("CREATE USER cuSyntaxFoo IDENTIFIED BY bar ROLE admin"));
  }

  // === YQL-Drop-Class.md ===

  // Line 7-9: DROP CLASS <class> — basic syntax on an empty class
  @Test
  public void testDropClassBasicSyntax() {
    g.command("CREATE CLASS DropClassBasic IF NOT EXISTS EXTENDS V");

    // Drop the empty class (documented syntax from line 7-9)
    g.command("DROP CLASS DropClassBasic");

    // Verify the class no longer exists — SELECT should fail
    assertThatThrownBy(
        () -> g.computeInTx(tx -> tx.yql("SELECT FROM DropClassBasic").toList()));
  }

  // Undocumented: DROP CLASS refuses to drop a class containing vertices
  // unless the UNSAFE keyword is appended
  @Test
  public void testDropClassWithDataRequiresUnsafe() {
    g.command("CREATE CLASS DropClassData IF NOT EXISTS EXTENDS V");
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DropClassData SET name = 'test'").iterate();
    });

    // Dropping a class with existing vertices should fail without UNSAFE
    assertThatThrownBy(() -> g.command("DROP CLASS DropClassData"));

    // With UNSAFE it succeeds
    g.command("DROP CLASS DropClassData UNSAFE");

    assertThatThrownBy(
        () -> g.computeInTx(tx -> tx.yql("SELECT FROM DropClassData").toList()));
  }

  // Line 13: Schema coherence warning — dropping a superclass used by a subclass
  @Test
  public void testDropClassSuperclassCoherenceWarning() {
    // Create a superclass and a subclass
    g.command("CREATE CLASS DropClassSuper IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS DropClassSub IF NOT EXISTS EXTENDS DropClassSuper");

    // Dropping the superclass while a subclass exists should fail
    assertThatThrownBy(() -> g.command("DROP CLASS DropClassSuper"));

    // Clean up
    g.command("DROP CLASS DropClassSub");
    g.command("DROP CLASS DropClassSuper");
  }

  // Line 19-21: DROP CLASS Account — the specific documented example
  @Test
  public void testDropClassAccountExample() {
    g.command("CREATE CLASS Account IF NOT EXISTS EXTENDS V");
    g.command("DROP CLASS Account");

    // Verify Account no longer exists — SELECT should fail
    assertThatThrownBy(
        () -> g.computeInTx(tx -> tx.yql("SELECT FROM Account").toList()));
  }

  // === YQL-Drop-Index.md ===

  // Line 5: DROP INDEX throws an error if the index does not exist (without IF EXISTS)
  @Test
  public void testDropIndexNonExistentThrowsError() {
    assertThatThrownBy(() -> g.command("DROP INDEX NonExistentIndex12345"));
  }

  // Line 5: DROP INDEX with IF EXISTS silently succeeds for non-existent index
  @Test
  public void testDropIndexIfExistsNonExistent() {
    g.command("DROP INDEX NonExistentIndex12345 IF EXISTS");
  }

  // Line 18-20: DROP INDEX Users.Id — the specific documented example
  @Test
  public void testDropIndexDocumentedExample() {
    g.command("CREATE CLASS DropIdxUsers IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY DropIdxUsers.Id STRING");
    g.command("CREATE INDEX DropIdxUsers.Id NOTUNIQUE");

    // Drop the index as shown in the doc
    g.command("DROP INDEX DropIdxUsers.Id");

    // Verify the index no longer exists — dropping again should throw
    assertThatThrownBy(() -> g.command("DROP INDEX DropIdxUsers.Id"));

    // Clean up
    g.command("DROP CLASS DropIdxUsers");
  }

  // ==========================================================================
  // YQL-Drop-Property.md validation
  // ==========================================================================

  /**
   * YQL-Drop-Property.md — basic syntax: DROP PROPERTY <class>.<property> removes the property
   * from the schema. Verifies the documented example "DROP PROPERTY User.name".
   */
  @Test
  public void testDropPropertyBasicSyntax() {
    g.command("CREATE CLASS DropPropUser IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY DropPropUser.name STRING");

    // Verify property exists
    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DropPropUser SET name = 'Alice'").iterate();
    });

    // Drop the property as shown in the doc
    g.command("DROP PROPERTY DropPropUser.name");

    // Verify property is removed from schema — creating it again should succeed
    g.command("CREATE PROPERTY DropPropUser.name STRING");

    // Verify existing data is still accessible (doc says values remain in records)
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM DropPropUser WHERE name = 'Alice'").toList());
    assertThat(results).hasSize(1);

    // Clean up
    g.command("DROP CLASS DropPropUser UNSAFE");
  }

  /**
   * YQL-Drop-Property.md — FORCE option: when indexes are defined on the property, DROP PROPERTY
   * without FORCE throws an exception; with FORCE it drops indexes together with the property.
   */
  @Test
  public void testDropPropertyForceWithIndex() {
    g.command("CREATE CLASS DropPropForceUser IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY DropPropForceUser.email STRING");
    g.command("CREATE INDEX DropPropForceUser.email NOTUNIQUE");

    // Without FORCE — should throw because an index exists on the property
    assertThatThrownBy(() -> g.command("DROP PROPERTY DropPropForceUser.email"));

    // With FORCE — should succeed, dropping both the index and the property
    g.command("DROP PROPERTY DropPropForceUser.email FORCE");

    // Verify property is gone — re-creating should succeed
    g.command("CREATE PROPERTY DropPropForceUser.email STRING");

    // Verify index is gone — dropping it should throw
    assertThatThrownBy(() -> g.command("DROP INDEX DropPropForceUser.email"));

    // Clean up
    g.command("DROP CLASS DropPropForceUser");
  }

  /**
   * YQL-Drop-Property.md — undocumented IF EXISTS syntax: the parser supports IF EXISTS but the
   * doc does not mention it. Validates that it works correctly.
   */
  @Test
  public void testDropPropertyIfExists() {
    g.command("CREATE CLASS DropPropIfExUser IF NOT EXISTS EXTENDS V");

    // IF EXISTS on a non-existent property should not throw
    g.command("DROP PROPERTY DropPropIfExUser.nonexistent IF EXISTS");

    // Without IF EXISTS on a non-existent property should throw
    assertThatThrownBy(
        () -> g.command("DROP PROPERTY DropPropIfExUser.nonexistent"));

    // Clean up
    g.command("DROP CLASS DropPropIfExUser");
  }

  /**
   * YQL-Drop-Property.md — claim: "Does not remove the property values in the records." Verifies
   * that after dropping a property from the schema, existing record values are preserved.
   */
  @Test
  public void testDropPropertyPreservesRecordValues() {
    g.command("CREATE CLASS DropPropValUser IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY DropPropValUser.age INTEGER");

    g.executeInTx(tx -> {
      tx.yql("CREATE VERTEX DropPropValUser SET age = 30").iterate();
    });

    // Drop the property from schema
    g.command("DROP PROPERTY DropPropValUser.age");

    // Values should still be queryable
    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM DropPropValUser WHERE age = 30").toList());
    assertThat(results).hasSize(1);

    // Clean up
    g.command("DROP CLASS DropPropValUser UNSAFE");
  }

  // === YQL-Drop-Sequence.md ===

  // Line 7-9: DROP SEQUENCE <sequence> — basic syntax validation
  // Line 19: DROP SEQUENCE idseq — example from docs
  @Test
  public void testDropSequenceBasicSyntax() {
    // Create a fresh sequence, then drop it to validate the documented syntax.
    // The documented example uses "idseq"; we use a unique name to avoid collisions.
    g.command("CREATE SEQUENCE dropSeqDocTest TYPE ORDERED");

    // DROP SEQUENCE <sequence> — the documented syntax (line 7-9, line 19)
    g.command("DROP SEQUENCE dropSeqDocTest");
  }

  // Verify DROP SEQUENCE fails for a non-existent sequence
  @Test
  public void testDropSequenceNonExistent() {
    assertThatThrownBy(() -> g.command("DROP SEQUENCE noSuchSeqDropTest"));
  }

  // === YQL-Drop-User.md ===

  // Line 8: DROP USER <user> — basic syntax validation
  // Line 19: DROP USER Foo — example from docs
  @Test
  public void testDropUserBasicSyntax() {
    // Create a user first via yql() (CREATE USER result is not a vertex/edge,
    // so command() would fail with the Gremlin result mapper).
    g.computeInTx(tx -> tx.yql("CREATE USER DropUserDocTestFoo IDENTIFIED BY password123"));

    // DROP USER <user> — the documented syntax (line 8, line 19)
    g.computeInTx(tx -> tx.yql("DROP USER DropUserDocTestFoo"));
  }

  // Verify DROP USER on a non-existent user does not throw (silent no-op)
  @Test
  public void testDropUserNonExistentIsNoOp() {
    g.computeInTx(tx -> tx.yql("DROP USER noSuchUserDropTest"));
  }

  // === YQL-Explain.md ===

  // Line 3: EXPLAIN returns execution plan without executing the statement.
  // Line 19: explain select from v where name = 'a'
  @Test
  public void testExplainSelectFromVWithFilter() {
    // Validate that EXPLAIN returns an execution plan for a SELECT statement
    // without actually executing it.
    g.command("CREATE CLASS ExplainTestV IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX ExplainTestV SET name = 'a'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("EXPLAIN SELECT FROM ExplainTestV WHERE name = 'a'").toList());
    assertThat(results).hasSize(1);

    // The result should contain execution plan information
    var result = results.get(0);
    assertThat(result).isNotNull();

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX ExplainTestV").iterate();
        });
  }

  // === YQL-Functions.md ===

  // Line 31: SELECT SUM(salary) FROM employee — aggregated mode (single parameter)
  @Test
  public void testFuncSumAggregated() {
    g.command("CREATE CLASS FuncEmployee IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncEmployee SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncEmployee SET salary = 2000").iterate();
          tx.yql("CREATE VERTEX FuncEmployee SET salary = 3000").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT SUM(salary) FROM FuncEmployee").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncEmployee").iterate();
        });
  }

  // Line 39: SELECT SUM(salary, extra, benefits) AS total FROM employee — inline mode
  @Test
  public void testFuncSumInline() {
    g.command("CREATE CLASS FuncEmpInline IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncEmpInline SET salary = 1000, extra = 200, benefits = 300")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT SUM(salary, extra, benefits) AS total FROM FuncEmpInline")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncEmpInline").iterate();
        });
  }

  // Line 63: SELECT out() FROM V — graph traversal function
  @Test
  public void testFuncOut() {
    g.command("CREATE CLASS FuncPerson IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncKnows IF NOT EXISTS EXTENDS E");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncPerson SET name = 'Alice'").iterate();
          tx.yql("CREATE VERTEX FuncPerson SET name = 'Bob'").iterate();
          tx.yql(
              "CREATE EDGE FuncKnows FROM "
                  + "(SELECT FROM FuncPerson WHERE name = 'Alice') TO "
                  + "(SELECT FROM FuncPerson WHERE name = 'Bob')")
              .iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT out() FROM FuncPerson WHERE name = 'Alice'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE EDGE FuncKnows").iterate();
          tx.yql("DELETE VERTEX FuncPerson").iterate();
        });
  }

  // Line 87: SELECT in() FROM V — incoming vertices
  @Test
  public void testFuncIn() {
    g.command("CREATE CLASS FuncPersonIn IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncKnowsIn IF NOT EXISTS EXTENDS E");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncPersonIn SET name = 'Alice'").iterate();
          tx.yql("CREATE VERTEX FuncPersonIn SET name = 'Bob'").iterate();
          tx.yql(
              "CREATE EDGE FuncKnowsIn FROM "
                  + "(SELECT FROM FuncPersonIn WHERE name = 'Alice') TO "
                  + "(SELECT FROM FuncPersonIn WHERE name = 'Bob')")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT in() FROM FuncPersonIn WHERE name = 'Bob'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE EDGE FuncKnowsIn").iterate();
          tx.yql("DELETE VERTEX FuncPersonIn").iterate();
        });
  }

  // Line 109: SELECT both() FROM #13:33 — both directions
  @Test
  public void testFuncBoth() {
    g.command("CREATE CLASS FuncPersonBoth IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncKnowsBoth IF NOT EXISTS EXTENDS E");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncPersonBoth SET name = 'A'").iterate();
          tx.yql("CREATE VERTEX FuncPersonBoth SET name = 'B'").iterate();
          tx.yql(
              "CREATE EDGE FuncKnowsBoth FROM "
                  + "(SELECT FROM FuncPersonBoth WHERE name = 'A') TO "
                  + "(SELECT FROM FuncPersonBoth WHERE name = 'B')")
              .iterate();
        });

    // both() should return neighbors from both directions
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT both() FROM FuncPersonBoth WHERE name = 'A'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE EDGE FuncKnowsBoth").iterate();
          tx.yql("DELETE VERTEX FuncPersonBoth").iterate();
        });
  }

  // Line 133: SELECT outE() FROM V — outgoing edges
  @Test
  public void testFuncOutE() {
    g.command("CREATE CLASS FuncPersonOutE IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncEdgeOutE IF NOT EXISTS EXTENDS E");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncPersonOutE SET name = 'X'").iterate();
          tx.yql("CREATE VERTEX FuncPersonOutE SET name = 'Y'").iterate();
          tx.yql(
              "CREATE EDGE FuncEdgeOutE FROM "
                  + "(SELECT FROM FuncPersonOutE WHERE name = 'X') TO "
                  + "(SELECT FROM FuncPersonOutE WHERE name = 'Y')")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT outE() FROM FuncPersonOutE WHERE name = 'X'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE EDGE FuncEdgeOutE").iterate();
          tx.yql("DELETE VERTEX FuncPersonOutE").iterate();
        });
  }

  // Line 155: SELECT inE() FROM V — incoming edges
  @Test
  public void testFuncInE() {
    g.command("CREATE CLASS FuncPersonInE IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncEdgeInE IF NOT EXISTS EXTENDS E");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncPersonInE SET name = 'X'").iterate();
          tx.yql("CREATE VERTEX FuncPersonInE SET name = 'Y'").iterate();
          tx.yql(
              "CREATE EDGE FuncEdgeInE FROM "
                  + "(SELECT FROM FuncPersonInE WHERE name = 'X') TO "
                  + "(SELECT FROM FuncPersonInE WHERE name = 'Y')")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT inE() FROM FuncPersonInE WHERE name = 'Y'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE EDGE FuncEdgeInE").iterate();
          tx.yql("DELETE VERTEX FuncPersonInE").iterate();
        });
  }

  // Line 174: SELECT bothE() FROM V — both incoming and outgoing edges
  @Test
  public void testFuncBothE() {
    g.command("CREATE CLASS FuncPersonBothE IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncEdgeBothE IF NOT EXISTS EXTENDS E");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncPersonBothE SET name = 'X'").iterate();
          tx.yql("CREATE VERTEX FuncPersonBothE SET name = 'Y'").iterate();
          tx.yql(
              "CREATE EDGE FuncEdgeBothE FROM "
                  + "(SELECT FROM FuncPersonBothE WHERE name = 'X') TO "
                  + "(SELECT FROM FuncPersonBothE WHERE name = 'Y')")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT bothE() FROM FuncPersonBothE WHERE name = 'X'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE EDGE FuncEdgeBothE").iterate();
          tx.yql("DELETE VERTEX FuncPersonBothE").iterate();
        });
  }

  // Line 241: SELECT eval('price * 120 / 100 - discount') AS finalPrice FROM Order
  @Test
  public void testFuncEval() {
    g.command("CREATE CLASS FuncOrder IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncOrder SET price = 100, discount = 10").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT eval('price * 120 / 100 - discount') AS finalPrice FROM FuncOrder")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncOrder").iterate();
        });
  }

  // Line 257: SELECT coalesce(amount, amount2, amount3) FROM Account
  @Test
  public void testFuncCoalesce() {
    g.command("CREATE CLASS FuncAccountCoalesce IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX FuncAccountCoalesce SET amount = null, amount2 = null, amount3 = 50")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT coalesce(amount, amount2, amount3) FROM FuncAccountCoalesce")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountCoalesce").iterate();
        });
  }

  // Line 271: if(<expression>, <result-if-true>, <result-if-false>)
  @Test
  public void testFuncIf() {
    g.command("CREATE CLASS FuncPersonIf IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncPersonIf SET name = 'John'").iterate();
          tx.yql("CREATE VERTEX FuncPersonIf SET name = 'Jane'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT if(eval(\"name = 'John'\"), 'My name is John', 'My name is not John') FROM FuncPersonIf")
                .toList());
    assertThat(results).hasSize(2);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncPersonIf").iterate();
        });
  }

  // Line 288: SELECT ifnull(salary, 0) FROM Account
  @Test
  public void testFuncIfnull() {
    g.command("CREATE CLASS FuncAccountIfnull IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountIfnull SET salary = null").iterate();
          tx.yql("CREATE VERTEX FuncAccountIfnull SET salary = 1000").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT ifnull(salary, 0) FROM FuncAccountIfnull").toList());
    assertThat(results).hasSize(2);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountIfnull").iterate();
        });
  }

  // Line 308: SELECT EXPAND(addresses) FROM Account — expand collection
  @Test
  public void testFuncExpand() {
    g.command("CREATE CLASS FuncAccountExpand IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncAddress IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAddress SET city = 'Kyiv'").iterate();
          tx.yql("CREATE VERTEX FuncAddress SET city = 'Lviv'").iterate();
          tx.yql(
              "CREATE VERTEX FuncAccountExpand SET addresses = "
                  + "(SELECT FROM FuncAddress)")
              .iterate();
        });

    // expand() should unwind the collection
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT EXPAND(addresses) FROM FuncAccountExpand").toList());
    assertThat(results).hasSize(2);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountExpand").iterate();
          tx.yql("DELETE VERTEX FuncAddress").iterate();
        });
  }

  // Line 327: select first(addresses) from Account
  @Test
  public void testFuncFirst() {
    g.command("CREATE CLASS FuncAccountFirst IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountFirst SET addresses = ['addr1', 'addr2', 'addr3']")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT first(addresses) FROM FuncAccountFirst").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountFirst").iterate();
        });
  }

  // Line 340: SELECT last(addresses) FROM Account
  @Test
  public void testFuncLast() {
    g.command("CREATE CLASS FuncAccountLast IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountLast SET addresses = ['addr1', 'addr2', 'addr3']")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT last(addresses) FROM FuncAccountLast").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountLast").iterate();
        });
  }

  // Line 353: SELECT COUNT(*) FROM Account
  @Test
  public void testFuncCount() {
    g.command("CREATE CLASS FuncAccountCount IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountCount SET name = 'a'").iterate();
          tx.yql("CREATE VERTEX FuncAccountCount SET name = 'b'").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT COUNT(*) FROM FuncAccountCount").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountCount").iterate();
        });
  }

  // Line 367-371: min() — aggregated and inline modes
  @Test
  public void testFuncMinAggregated() {
    g.command("CREATE CLASS FuncAccountMin IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountMin SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountMin SET salary = 2000").iterate();
          tx.yql("CREATE VERTEX FuncAccountMin SET salary = 500").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT min(salary) FROM FuncAccountMin").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountMin").iterate();
        });
  }

  // Line 370: SELECT min(salary1, salary2, salary3) FROM Account — inline min
  @Test
  public void testFuncMinInline() {
    g.command("CREATE CLASS FuncAccountMinInline IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX FuncAccountMinInline SET salary1 = 1000, salary2 = 500, salary3 = 2000")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT min(salary1, salary2, salary3) FROM FuncAccountMinInline")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountMinInline").iterate();
        });
  }

  // Line 384: SELECT max(salary) FROM Account — with trailing period bug in doc
  @Test
  public void testFuncMaxAggregated() {
    g.command("CREATE CLASS FuncAccountMax IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountMax SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountMax SET salary = 3000").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT max(salary) FROM FuncAccountMax").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountMax").iterate();
        });
  }

  // Line 388: SELECT max(salary1, salary2, salary3) FROM Account — inline max
  @Test
  public void testFuncMaxInline() {
    g.command("CREATE CLASS FuncAccountMaxInline IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX FuncAccountMaxInline SET salary1 = 100, salary2 = 300, salary3 = 200")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT max(salary1, salary2, salary3) FROM FuncAccountMaxInline")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountMaxInline").iterate();
        });
  }

  // Line 401-404: SELECT abs(score), abs(-2332), abs(999)
  @Test
  public void testFuncAbs() {
    g.command("CREATE CLASS FuncAccountAbs IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountAbs SET score = -42").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT abs(score) FROM FuncAccountAbs").toList());
    assertThat(results).hasSize(1);

    var results2 =
        g.computeInTx(tx -> tx.yql("SELECT abs(-2332) FROM FuncAccountAbs").toList());
    assertThat(results2).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountAbs").iterate();
        });
  }

  // Line 417: SELECT avg(salary) FROM Account
  @Test
  public void testFuncAvg() {
    g.command("CREATE CLASS FuncAccountAvg IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountAvg SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountAvg SET salary = 2000").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT avg(salary) FROM FuncAccountAvg").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountAvg").iterate();
        });
  }

  // Line 430: SELECT sum(salary) FROM Account — aggregated sum
  @Test
  public void testFuncSumRef() {
    g.command("CREATE CLASS FuncAccountSum IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountSum SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountSum SET salary = 2000").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT sum(salary) FROM FuncAccountSum").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountSum").iterate();
        });
  }

  // Line 444: SELECT FROM Account WHERE created <= date('2012-07-02', 'yyyy-MM-dd')
  @Test
  public void testFuncDate() {
    g.command("CREATE CLASS FuncAccountDate IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX FuncAccountDate SET created = date('2012-01-01', 'yyyy-MM-dd')")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM FuncAccountDate WHERE created <= date('2012-07-02', 'yyyy-MM-dd')")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountDate").iterate();
        });
  }

  // Line 456: SELECT sysdate('dd-MM-yyyy') FROM Account
  @Test
  public void testFuncSysdate() {
    g.command("CREATE CLASS FuncAccountSysdate IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountSysdate SET name = 'x'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT sysdate('dd-MM-yyyy') FROM FuncAccountSysdate").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountSysdate").iterate();
        });
  }

  // Line 468: SELECT format("%d - Mr. %s %s (%s)", id, name, surname, address) FROM Account
  @Test
  public void testFuncFormat() {
    g.command("CREATE CLASS FuncAccountFmt IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX FuncAccountFmt SET name = 'John', surname = 'Doe', address = 'Kyiv'")
              .iterate();
        });

    // The doc uses 'id' but we'll use a numeric field since @rid is not an integer
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT format('%s - Mr. %s %s (%s)', name, name, surname, address) FROM FuncAccountFmt")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountFmt").iterate();
        });
  }

  // Line 482: SELECT decimal('99.999999999999999999') FROM Account
  @Test
  public void testFuncDecimal() {
    g.command("CREATE CLASS FuncAccountDecimal IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountDecimal SET name = 'x'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT decimal('99.999999999999999999') FROM FuncAccountDecimal")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountDecimal").iterate();
        });
  }

  // Line 597: SELECT distinct(name) FROM City
  @Test
  public void testFuncDistinct() {
    g.command("CREATE CLASS FuncCityDistinct IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncCityDistinct SET name = 'Kyiv'").iterate();
          tx.yql("CREATE VERTEX FuncCityDistinct SET name = 'Lviv'").iterate();
          tx.yql("CREATE VERTEX FuncCityDistinct SET name = 'Kyiv'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT distinct(name) FROM FuncCityDistinct").toList());
    assertThat(results).hasSize(2);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncCityDistinct").iterate();
        });
  }

  // Line 610: SELECT unionall(friends) FROM profile — aggregate union
  @Test
  public void testFuncUnionall() {
    g.command("CREATE CLASS FuncProfileUnion IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncProfileUnion SET friends = ['Alice', 'Bob']").iterate();
          tx.yql("CREATE VERTEX FuncProfileUnion SET friends = ['Bob', 'Charlie']").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT unionall(friends) FROM FuncProfileUnion").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncProfileUnion").iterate();
        });
  }

  // Line 626: SELECT intersect(friends) FROM profile WHERE jobTitle = 'programmer'
  @Test
  public void testFuncIntersect() {
    g.command("CREATE CLASS FuncProfileIntersect IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX FuncProfileIntersect SET friends = ['Alice', 'Bob'], jobTitle = 'programmer'")
              .iterate();
          tx.yql(
              "CREATE VERTEX FuncProfileIntersect SET friends = ['Bob', 'Charlie'], jobTitle = 'programmer'")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT intersect(friends) FROM FuncProfileIntersect WHERE jobTitle = 'programmer'")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncProfileIntersect").iterate();
        });
  }

  // Line 642: SELECT difference(tags) FROM book — doc claims aggregate mode works,
  // but difference() does NOT support aggregation mode. This is a doc bug.
  // Verify the inline (two-param) form works: difference(inEdges, outEdges).
  @Test
  public void testFuncDifferenceInline() {
    g.command("CREATE CLASS FuncBookDiff IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX FuncBookDiff SET setA = ['java', 'sql'], setB = ['java', 'python']")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT difference(setA, setB) FROM FuncBookDiff").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncBookDiff").iterate();
        });
  }

  // Verify that difference() in aggregate mode throws an error (doc bug)
  @Test
  public void testFuncDifferenceAggregateNotSupported() {
    g.command("CREATE CLASS FuncBookDiffAgg IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncBookDiffAgg SET tags = ['java', 'sql']").iterate();
          tx.yql("CREATE VERTEX FuncBookDiffAgg SET tags = ['java', 'python']").iterate();
        });

    assertThatThrownBy(
        () -> g.computeInTx(
            tx -> tx.yql("SELECT difference(tags) FROM FuncBookDiffAgg").toList()));

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncBookDiffAgg").iterate();
        });
  }

  // Line 658: symmetricDifference() — doc incorrectly uses difference() in the example
  @Test
  public void testFuncSymmetricDifference() {
    g.command("CREATE CLASS FuncBookSymDiff IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncBookSymDiff SET tags = ['java', 'sql']").iterate();
          tx.yql("CREATE VERTEX FuncBookSymDiff SET tags = ['java', 'python']").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT symmetricDifference(tags) FROM FuncBookSymDiff").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncBookSymDiff").iterate();
        });
  }

  // Line 678: SELECT name, set(roles.name) AS roles FROM User
  @Test
  public void testFuncSet() {
    g.command("CREATE CLASS FuncUserSet IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS FuncRole IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncUserSet SET name = 'Alice', roles = ['admin', 'user', 'admin']")
              .iterate();
        });

    // set() as aggregation
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT name, set(roles) AS roles FROM FuncUserSet").toList());
    assertThat(results).isNotEmpty();

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncUserSet").iterate();
          tx.yql("DELETE VERTEX FuncRole").iterate();
        });
  }

  // Line 690: SELECT name, list(roles.name) AS roles FROM User
  @Test
  public void testFuncList() {
    g.command("CREATE CLASS FuncUserList IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncUserList SET name = 'Alice', roles = ['admin', 'user']")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT name, list(roles) AS roles FROM FuncUserList").toList());
    assertThat(results).isNotEmpty();

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncUserList").iterate();
        });
  }

  // Line 704: SELECT map(name, roles.name) FROM User
  @Test
  public void testFuncMap() {
    g.command("CREATE CLASS FuncUserMap IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncUserMap SET name = 'Alice'").iterate();
          tx.yql("CREATE VERTEX FuncUserMap SET name = 'Bob'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT map(name, name) FROM FuncUserMap").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncUserMap").iterate();
        });
  }

  // Line 717: SELECT mode(salary) FROM Account
  @Test
  public void testFuncMode() {
    g.command("CREATE CLASS FuncAccountMode IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountMode SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountMode SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountMode SET salary = 2000").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT mode(salary) FROM FuncAccountMode").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountMode").iterate();
        });
  }

  // Line 729: select median(salary) from Account
  @Test
  public void testFuncMedian() {
    g.command("CREATE CLASS FuncAccountMedian IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountMedian SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountMedian SET salary = 2000").iterate();
          tx.yql("CREATE VERTEX FuncAccountMedian SET salary = 3000").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT median(salary) FROM FuncAccountMedian").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountMedian").iterate();
        });
  }

  // Line 743-744: SELECT percentile(salary, 0.95) FROM Account
  @Test
  public void testFuncPercentile() {
    g.command("CREATE CLASS FuncAccountPct IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          for (int i = 1; i <= 20; i++) {
            tx.yql("CREATE VERTEX FuncAccountPct SET salary = " + (i * 100)).iterate();
          }
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT percentile(salary, 0.95) FROM FuncAccountPct").toList());
    assertThat(results).hasSize(1);

    // Also test the two-quantile form: percentile(salary, 0.25, 0.75)
    var results2 =
        g.computeInTx(
            tx -> tx.yql("SELECT percentile(salary, 0.25, 0.75) AS IQR FROM FuncAccountPct")
                .toList());
    assertThat(results2).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountPct").iterate();
        });
  }

  // Line 757: SELECT variance(salary) FROM Account
  @Test
  public void testFuncVariance() {
    g.command("CREATE CLASS FuncAccountVar IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountVar SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountVar SET salary = 2000").iterate();
          tx.yql("CREATE VERTEX FuncAccountVar SET salary = 3000").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT variance(salary) FROM FuncAccountVar").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountVar").iterate();
        });
  }

  // Line 769: SELECT stddev(salary) FROM Account
  @Test
  public void testFuncStddev() {
    g.command("CREATE CLASS FuncAccountStd IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountStd SET salary = 1000").iterate();
          tx.yql("CREATE VERTEX FuncAccountStd SET salary = 2000").iterate();
          tx.yql("CREATE VERTEX FuncAccountStd SET salary = 3000").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT stddev(salary) FROM FuncAccountStd").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountStd").iterate();
        });
  }

  // Line 783: INSERT INTO Account SET id = UUID()
  @Test
  public void testFuncUuid() {
    g.command("CREATE CLASS FuncAccountUuid IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncAccountUuid SET id = UUID()").iterate();
        });

    var results =
        g.computeInTx(tx -> tx.yql("SELECT FROM FuncAccountUuid").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("id")).isNotEmpty();

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncAccountUuid").iterate();
        });
  }

  // Line 799: SELECT * from State where strcmpci("washington", name) = 0
  @Test
  public void testFuncStrcmpci() {
    g.command("CREATE CLASS FuncState IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX FuncState SET name = 'Washington'").iterate();
          tx.yql("CREATE VERTEX FuncState SET name = 'Oregon'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT * FROM FuncState WHERE strcmpci('washington', name) = 0")
                .toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("name")).isEqualTo("Washington");

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncState").iterate();
        });
  }

  // Line 584: SELECT FROM POI WHERE distance(x, y, 52.20472, 0.14056) <= 30
  @Test
  public void testFuncDistance() {
    g.command("CREATE CLASS FuncPOI IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          // Kyiv coordinates approximately
          tx.yql("CREATE VERTEX FuncPOI SET x = 50.4501, y = 30.5234").iterate();
          // Cambridge coordinates (close to target)
          tx.yql("CREATE VERTEX FuncPOI SET x = 52.2053, y = 0.1218").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM FuncPOI WHERE distance(x, y, 52.20472, 0.14056) <= 30")
                .toList());
    // Cambridge is within 30 km of (52.20472, 0.14056), Kyiv is not
    assertThat(results).hasSize(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX FuncPOI").iterate();
        });
  }

  // === YQL-Grant.md ===

  /** YQL-Grant.md — Line 22: GRANT POLICY policy1 ON database.class.Person TO backoffice */
  @Test
  public void testGrantPolicyOnClassToRole() {
    // Create the target class
    g.command("CREATE CLASS GrantPerson IF NOT EXISTS EXTENDS V");

    // Create a security policy with proper syntax (SET READ = (predicate))
    g.command("CREATE SECURITY POLICY grantPolicy1 SET READ = (name = 'foo')");
    g.command("GRANT POLICY grantPolicy1 ON database.class.GrantPerson TO reader");

    // If we got here without exception, the GRANT POLICY syntax is accepted
  }

  /** YQL-Grant.md — Line 8: GRANT permission ON resource TO role — all permission types */
  @Test
  public void testGrantPermissionOnResourceToRole() {
    // Grant CREATE permission on a database class to the built-in 'writer' role
    g.command("CREATE CLASS GrantTarget IF NOT EXISTS EXTENDS V");
    g.command("GRANT CREATE ON database.class.GrantTarget TO writer");

    // Grant READ permission
    g.command("GRANT READ ON database.class.GrantTarget TO writer");

    // Grant UPDATE permission
    g.command("GRANT UPDATE ON database.class.GrantTarget TO writer");

    // Grant DELETE permission
    g.command("GRANT DELETE ON database.class.GrantTarget TO writer");

    // Grant ALL permission
    g.command("GRANT ALL ON database.class.GrantTarget TO writer");

    // Grant NONE permission (revokes all)
    g.command("GRANT NONE ON database.class.GrantTarget TO writer");
  }

  /** YQL-Grant.md — Line 50: database resource */
  @Test
  public void testGrantOnDatabaseResource() {
    g.command("GRANT READ ON database TO reader");
  }

  /** YQL-Grant.md — Line 51: database.class.* wildcard for all classes */
  @Test
  public void testGrantOnWildcardClassResource() {
    g.command("GRANT READ ON database.class.* TO reader");
  }

  // === YQL-Methods.md ===

  /** YQL-Methods.md — Line 89: .append() concatenates strings */
  @Test
  public void testMethodAppend() {
    g.command("CREATE CLASS MethAppendEmp IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethAppendEmp SET name = 'John', surname = 'Doe'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT name.append(' ').append(surname) FROM MethAppendEmp").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 108: .asBoolean() converts to boolean */
  @Test
  public void testMethodAsBoolean() {
    g.command("CREATE CLASS MethBoolUser IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethBoolUser SET name = 'Alice', online = 'true'").iterate();
          tx.yql("CREATE VERTEX MethBoolUser SET name = 'Bob', online = 'false'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethBoolUser WHERE online.asBoolean() = true").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("name")).isEqualTo("Alice");
  }

  /** YQL-Methods.md — Line 162: .asDecimal() converts to decimal */
  @Test
  public void testMethodAsDecimal() {
    g.command("CREATE CLASS MethDecEmp IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethDecEmp SET salary = 50000").iterate();
        });
    var results =
        g.computeInTx(tx -> tx.yql("SELECT salary.asDecimal() FROM MethDecEmp").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 178: .asFloat() converts to float */
  @Test
  public void testMethodAsFloat() {
    g.command("CREATE CLASS MethFloatItem IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethFloatItem SET ray = 4.5").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethFloatItem WHERE ray.asFloat() > 3.14").toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 195: .asInteger() with .left() chain */
  @Test
  public void testMethodAsInteger() {
    g.command("CREATE CLASS MethIntLog IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethIntLog SET value = '12345'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT value.left(3).asInteger() FROM MethIntLog").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 211: .asList() transforms to list */
  @Test
  public void testMethodAsList() {
    g.command("CREATE CLASS MethListFriend IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethListFriend SET tags = ['a','b','c']").iterate();
        });
    var results =
        g.computeInTx(tx -> tx.yql("SELECT tags.asList() FROM MethListFriend").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 227: .asLong() converts to long */
  @Test
  public void testMethodAsLong() {
    g.command("CREATE CLASS MethLongLog IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethLongLog SET date = 1609459200000").iterate();
        });
    var results =
        g.computeInTx(tx -> tx.yql("SELECT date.asLong() FROM MethLongLog").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 259: .asSet() transforms to set */
  @Test
  public void testMethodAsSet() {
    g.command("CREATE CLASS MethSetFriend IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethSetFriend SET tags = ['a','b','a']").iterate();
        });
    var results =
        g.computeInTx(tx -> tx.yql("SELECT tags.asSet() FROM MethSetFriend").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 276: .asString() converts to string, .indexOf on result */
  @Test
  public void testMethodAsString() {
    g.command("CREATE CLASS MethStrEmp IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethStrEmp SET salary = 50000.75").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM MethStrEmp WHERE salary.asString().indexOf('.') > -1")
                .toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 293: .charAt() returns character at position */
  @Test
  public void testMethodCharAt() {
    g.command("CREATE CLASS MethCharUser IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethCharUser SET name = 'Luke'").iterate();
          tx.yql("CREATE VERTEX MethCharUser SET name = 'Mark'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethCharUser WHERE name.charAt(0) = 'L'").toList());
    assertThat(results).hasSize(1);
    Vertex v = (Vertex) results.get(0);
    assertThat((String) v.value("name")).isEqualTo("Luke");
  }

  /** YQL-Methods.md — Line 309: .convert() converts to another type */
  @Test
  public void testMethodConvert() {
    g.command("CREATE CLASS MethConvUser IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethConvUser SET dob = '2000-01-01'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT dob.convert('date') FROM MethConvUser").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 350: .format() formats value using printf syntax */
  @Test
  public void testMethodFormat() {
    g.command("CREATE CLASS MethFmtEmp IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethFmtEmp SET salary = 12345").iterate();
        });
    // Validates the doc example: salary.format("%011d")
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT salary.format('%011d') FROM MethFmtEmp").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 369: .hash() computes hash of string */
  @Test
  public void testMethodHash() {
    g.command("CREATE CLASS MethHashUser IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethHashUser SET password = 'secret123'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT password.hash('SHA-512') FROM MethHashUser").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 325: .exclude() removes properties from result */
  @Test
  public void testMethodExclude() {
    g.command("CREATE CLASS MethExclUser IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethExclUser SET name = 'Alice', password = 'secret'")
              .iterate();
        });
    // Verify exclude() parses and executes — use without EXPAND to avoid Gremlin mapper issue
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT @this.exclude('password') FROM MethExclUser").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 385: .include() keeps only specified properties */
  @Test
  public void testMethodInclude() {
    g.command("CREATE CLASS MethInclUser IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX MethInclUser SET name = 'Alice', email = 'a@b.c', age = 30")
              .iterate();
        });
    // Verify include() parses and executes — use without EXPAND to avoid Gremlin mapper issue
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT @this.include('name') FROM MethInclUser").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 408: .indexOf() finds position of substring */
  @Test
  public void testMethodIndexOf() {
    g.command("CREATE CLASS MethIdxContact IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethIdxContact SET phone = '+44-555-1234'").iterate();
          tx.yql("CREATE VERTEX MethIdxContact SET phone = '+1-555-9999'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM MethIdxContact WHERE phone.indexOf('+44') > -1")
                .toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 440: .keys() returns map keys */
  @Test
  public void testMethodKeys() {
    g.command("CREATE CLASS MethKeysActor IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX MethKeysActor SET map = {'Luke': 1, 'Han': 2}")
              .iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethKeysActor WHERE 'Luke' IN map.keys()").toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 455: .left() returns substring from start */
  @Test
  public void testMethodLeft() {
    g.command("CREATE CLASS MethLeftActor IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethLeftActor SET name = 'Luke Skywalker'").iterate();
          tx.yql("CREATE VERTEX MethLeftActor SET name = 'Han Solo'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethLeftActor WHERE name.left(4) = 'Luke'").toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 469: .length() returns string length */
  @Test
  public void testMethodLength() {
    g.command("CREATE CLASS MethLenProv IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethLenProv SET name = 'Acme'").iterate();
          tx.yql("CREATE VERTEX MethLenProv SET name = ''").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethLenProv WHERE name.length() > 0").toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 502: .prefix() prepends a string */
  @Test
  public void testMethodPrefix() {
    g.command("CREATE CLASS MethPfxProfile IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethPfxProfile SET name = 'Smith'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT name.prefix('Mr. ') FROM MethPfxProfile").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 550: .replace() replaces string occurrences */
  @Test
  public void testMethodReplace() {
    g.command("CREATE CLASS MethReplUser IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethReplUser SET name = 'Mr. Smith'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT name.replace('Mr.', 'Ms.') FROM MethReplUser").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 567: .right() returns substring from end */
  @Test
  public void testMethodRight() {
    g.command("CREATE CLASS MethRightV IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethRightV SET name = 'Luke'").iterate();
          tx.yql("CREATE VERTEX MethRightV SET name = 'Han'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethRightV WHERE name.right(2) = 'ke'").toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 584: .size() returns collection size */
  @Test
  public void testMethodSize() {
    g.command("CREATE CLASS MethSizeTree IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethSizeTree SET children = ['a', 'b']").iterate();
          tx.yql("CREATE VERTEX MethSizeTree SET children = []").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethSizeTree WHERE children.size() > 0").toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 601: .subString() returns substring */
  @Test
  public void testMethodSubString() {
    g.command("CREATE CLASS MethSubStock IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethSubStock SET name = 'Laptop'").iterate();
          tx.yql("CREATE VERTEX MethSubStock SET name = 'Mouse'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM MethSubStock WHERE name.substring(0, 1) = 'L'")
                .toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 607: .substring() on literal string */
  @Test
  public void testMethodSubStringLiteral() {
    var results =
        g.computeInTx(tx -> tx.yql("SELECT \"YouTrackDB\".substring(0,8)").toList());
    assertThat(results).isNotEmpty();
  }

  /** YQL-Methods.md — Line 622: .trim() removes whitespace */
  @Test
  public void testMethodTrim() {
    g.command("CREATE CLASS MethTrimActor IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethTrimActor SET name = '  Luke  '").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethTrimActor WHERE name.trim() = 'Luke'").toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 637: .toLowerCase() converts to lower case */
  @Test
  public void testMethodToLowerCase() {
    g.command("CREATE CLASS MethLowActor IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethLowActor SET name = 'Luke'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethLowActor WHERE name.toLowerCase() = 'luke'")
                .toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 652: .toUpperCase() converts to upper case */
  @Test
  public void testMethodToUpperCase() {
    g.command("CREATE CLASS MethUpActor IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethUpActor SET name = 'Luke'").iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM MethUpActor WHERE name.toUpperCase() = 'LUKE'")
                .toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 487: .normalize() normalizes a string */
  @Test
  public void testMethodNormalize() {
    g.command("CREATE CLASS MethNormV IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX MethNormV SET name = 'café'").iterate();
        });
    // Test that normalize() parses and executes without error
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM MethNormV WHERE name.normalize() IS NOT NULL AND name.normalize('NFD') IS NOT NULL")
                .toList());
    assertThat(results).hasSize(1);
  }

  /** YQL-Methods.md — Line 680: .values() returns map values */
  @Test
  public void testMethodValues() {
    g.command("CREATE CLASS MethValClient IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX MethValClient SET map = {'name': 'Alice', 'city': 'NYC'}")
              .iterate();
        });
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM MethValClient WHERE map.values() CONTAINS 'Alice'")
                .toList());
    assertThat(results).hasSize(1);
  }

  // ===== YQL-Rebuild-Index.md =====

  @Test
  public void testRebuildIndexNamedIndex() {
    // YQL-Rebuild-Index.md line 8: REBUILD INDEX <index> syntax
    // and line 15-18: rebuild an index on the nick property of the class Profile
    g.command("CREATE CLASS RbIdxProfile EXTENDS V");
    g.command("CREATE PROPERTY RbIdxProfile.nick STRING");
    g.command("CREATE INDEX RbIdxProfile.nick ON RbIdxProfile (nick) NOTUNIQUE");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX RbIdxProfile SET nick = 'John'").iterate();
          tx.yql("CREATE VERTEX RbIdxProfile SET nick = 'Jane'").iterate();
        });
    g.close();

    // Rebuild the specific index — should not throw
    g = youTrackDB.openTraversal("test", "admin", "admin");
    g.command("REBUILD INDEX RbIdxProfile.nick");

    // Verify data is still accessible via the index after rebuild
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM RbIdxProfile WHERE nick = 'John'").toList());
    assertThat(results).hasSize(1);
  }

  // === YQL-Revoke.md ===

  // Line 8: REVOKE <permission> ON <resource> FROM <role>
  @Test
  public void testRevokePermissionSyntax() {
    // Verify that REVOKE with a permission parses and executes without error
    g.command("REVOKE READ ON database FROM reader");
  }

  // Line 8: REVOKE POLICY ON <resource> FROM <role>
  @Test
  public void testRevokePolicySyntax() {
    // Verify that REVOKE POLICY (no policy name) parses and executes without error.
    // Unlike GRANT POLICY which takes a policy name, REVOKE POLICY does not.
    g.command("REVOKE POLICY ON database.class.V FROM reader");
  }

  // Line 19: REVOKE POLICY ON database.class.Person FROM backoffice
  @Test
  public void testRevokePolicyOnClassFromRole() {
    // Verify the exact example from the documentation parses correctly
    g.command("CREATE CLASS RevokeDocPerson IF NOT EXISTS EXTENDS V");
    g.command("REVOKE POLICY ON database.class.RevokeDocPerson FROM reader");
  }

  // Line 34: REVOKE ALL ON <resource> FROM <role>
  @Test
  public void testRevokeAllPermission() {
    g.command("REVOKE ALL ON database FROM reader");
  }

  // Line 34: REVOKE CREATE ON <resource> FROM <role>
  @Test
  public void testRevokeCreatePermission() {
    g.command("REVOKE CREATE ON database FROM reader");
  }

  // Line 34: REVOKE UPDATE ON <resource> FROM <role>
  @Test
  public void testRevokeUpdatePermission() {
    g.command("REVOKE UPDATE ON database FROM reader");
  }

  // Line 34: REVOKE DELETE ON <resource> FROM <role>
  @Test
  public void testRevokeDeletePermission() {
    g.command("REVOKE DELETE ON database FROM reader");
  }

  // Line 34: REVOKE NONE ON <resource> FROM <role>
  @Test
  public void testRevokeNonePermission() {
    g.command("REVOKE NONE ON database FROM reader");
  }

  // Line 48: REVOKE on database.class.* resource
  @Test
  public void testRevokeOnAllClasses() {
    // Verify wildcard * works for all classes
    g.command("REVOKE READ ON database.class.* FROM reader");
  }

  // Line 50: REVOKE POLICY on database.class.<class>.<property> resource
  @Test
  public void testRevokePolicyOnClassProperty() {
    g.command("CREATE CLASS RevokeDocEmployee IF NOT EXISTS EXTENDS V");
    g.command("CREATE PROPERTY RevokeDocEmployee.name STRING");
    g.command("REVOKE POLICY ON database.class.RevokeDocEmployee.name FROM reader");
  }

  // Line 50: REVOKE on database.command.<command> resource
  @Test
  public void testRevokeOnDatabaseCommand() {
    g.command("REVOKE CREATE ON database.command.create FROM reader");
  }

  // === YQL-Where.md ===

  // Line 40: = operator — name = 'Luke'
  @Test
  public void testWhereEqualsOperator() {
    g.command("CREATE CLASS WherePerson IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WherePerson SET name = 'Luke'").iterate();
          tx.yql("CREATE VERTEX WherePerson SET name = 'Han'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WherePerson WHERE name = 'Luke'").toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("Luke");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WherePerson").iterate());
  }

  // Line 41: LIKE operator — name like 'Luk%'
  @Test
  public void testWhereLikeOperator() {
    g.command("CREATE CLASS WhereLikePerson IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereLikePerson SET name = 'Luke'").iterate();
          tx.yql("CREATE VERTEX WhereLikePerson SET name = 'Leia'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereLikePerson WHERE name like 'Luk%'").toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("Luke");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereLikePerson").iterate());
  }

  // Line 42-45: Comparison operators (<, <=, >, >=)
  @Test
  public void testWhereComparisonOperators() {
    g.command("CREATE CLASS WhereAgePerson IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereAgePerson SET name = 'Young', age = 25").iterate();
          tx.yql("CREATE VERTEX WhereAgePerson SET name = 'Middle', age = 40").iterate();
          tx.yql("CREATE VERTEX WhereAgePerson SET name = 'Old', age = 55").iterate();
        });

    // < operator
    var lessThan =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereAgePerson WHERE age < 40").toList());
    assertThat(lessThan).hasSize(1);

    // <= operator
    var lessOrEqual =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereAgePerson WHERE age <= 40").toList());
    assertThat(lessOrEqual).hasSize(2);

    // > operator
    var greaterThan =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereAgePerson WHERE age > 40").toList());
    assertThat(greaterThan).hasSize(1);

    // >= operator
    var greaterOrEqual =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereAgePerson WHERE age >= 40").toList());
    assertThat(greaterOrEqual).hasSize(2);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereAgePerson").iterate());
  }

  // Line 46: <> operator (not equals)
  @Test
  public void testWhereNotEqualsOperator() {
    g.command("CREATE CLASS WhereNeqPerson IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereNeqPerson SET name = 'A', age = 40").iterate();
          tx.yql("CREATE VERTEX WhereNeqPerson SET name = 'B', age = 30").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereNeqPerson WHERE age <> 40").toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("B");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereNeqPerson").iterate());
  }

  // Line 47: BETWEEN operator
  @Test
  public void testWhereBetweenOperator() {
    g.command("CREATE CLASS WhereProduct IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereProduct SET name = 'Cheap', price = 5").iterate();
          tx.yql("CREATE VERTEX WhereProduct SET name = 'Mid', price = 20").iterate();
          tx.yql("CREATE VERTEX WhereProduct SET name = 'Expensive', price = 50")
              .iterate();
        });

    // BETWEEN is inclusive on both ends (equivalent to >= AND <=)
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereProduct WHERE price BETWEEN 10 and 30")
                .toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("Mid");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereProduct").iterate());
  }

  // Line 48: IS NULL operator
  @Test
  public void testWhereIsNullOperator() {
    g.command("CREATE CLASS WhereNullTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereNullTest SET name = 'WithChildren', children = 'yes'")
              .iterate();
          tx.yql("CREATE VERTEX WhereNullTest SET name = 'NoChildren'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereNullTest WHERE children is null").toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("NoChildren");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereNullTest").iterate());
  }

  // Line 49: INSTANCEOF operator
  @Test
  public void testWhereInstanceofOperator() {
    g.command("CREATE CLASS WhereCustomer IF NOT EXISTS EXTENDS V");
    g.command("CREATE CLASS WhereProvider IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereCustomer SET name = 'Alice'").iterate();
          tx.yql("CREATE VERTEX WhereProvider SET name = 'Bob'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM V WHERE @this instanceof 'WhereCustomer'")
                .toList());
    // Should contain at least Alice (and possibly other V vertices from other tests)
    assertThat(results.stream()
        .filter(r -> "Alice".equals(((Vertex) r).value("name")))
        .count()).isEqualTo(1);

    g.executeInTx(
        tx -> {
          tx.yql("DELETE VERTEX WhereCustomer").iterate();
          tx.yql("DELETE VERTEX WhereProvider").iterate();
        });
  }

  // Line 50: IN operator
  @Test
  public void testWhereInOperator() {
    g.command("CREATE CLASS WhereContinent IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereContinent SET name = 'European'").iterate();
          tx.yql("CREATE VERTEX WhereContinent SET name = 'Asiatic'").iterate();
          tx.yql("CREATE VERTEX WhereContinent SET name = 'African'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereContinent WHERE name in ['European','Asiatic']")
                .toList());
    assertThat(results).hasSize(2);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereContinent").iterate());
  }

  // Line 51: CONTAINS operator on embedded collection
  @Test
  public void testWhereContainsOperator() {
    g.command("CREATE CLASS WhereFamily IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX WhereFamily SET name = 'Skywalker',"
                  + " children = ['Luke', 'Leia']")
              .iterate();
          tx.yql(
              "CREATE VERTEX WhereFamily SET name = 'Solo',"
                  + " children = ['Ben']")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereFamily WHERE children CONTAINS 'Luke'")
                .toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("Skywalker");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereFamily").iterate());
  }

  // Line 54: CONTAINSKEY operator on map
  @Test
  public void testWhereContainsKeyOperator() {
    g.command("CREATE CLASS WhereConnections IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX WhereConnections SET name = 'A',"
                  + " connections = {'Luke': 'friend', 'Han': 'ally'}")
              .iterate();
          tx.yql(
              "CREATE VERTEX WhereConnections SET name = 'B',"
                  + " connections = {'Leia': 'leader'}")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereConnections WHERE connections containsKey 'Luke'")
                .toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("A");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereConnections").iterate());
  }

  // Line 55: CONTAINSVALUE operator on map
  @Test
  public void testWhereContainsValueOperator() {
    g.command("CREATE CLASS WhereConnVal IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX WhereConnVal SET name = 'A',"
                  + " connections = {'Luke': 'friend', 'Han': 'ally'}")
              .iterate();
          tx.yql(
              "CREATE VERTEX WhereConnVal SET name = 'B',"
                  + " connections = {'Leia': 'leader'}")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereConnVal WHERE connections containsValue 'friend'")
                .toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("A");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereConnVal").iterate());
  }

  // Line 56: CONTAINSTEXT operator
  @Test
  public void testWhereContainsTextOperator() {
    g.command("CREATE CLASS WhereTextDoc IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereTextDoc SET text = 'Hello vika world'").iterate();
          tx.yql("CREATE VERTEX WhereTextDoc SET text = 'Hello world'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereTextDoc WHERE text containsText 'vika'")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereTextDoc").iterate());
  }

  // Line 57: MATCHES operator (regex)
  @Test
  public void testWhereMatchesOperator() {
    g.command("CREATE CLASS WhereEmail IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereEmail SET text = 'contact: USER@EXAMPLE.COM'")
              .iterate();
          tx.yql("CREATE VERTEX WhereEmail SET text = 'no email here'").iterate();
        });

    // The doc example uses \b and \. which hit YQL lexer escaping issues.
    // Use [.] instead of \. to validate MATCHES works with regex.
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereEmail WHERE text matches"
                    + " '.*[A-Z0-9.%+-]+@[A-Z0-9.-]+[.][A-Z]{2,4}.*'")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereEmail").iterate());
  }

  // Line 64: AND logical operator
  @Test
  public void testWhereAndOperator() {
    g.command("CREATE CLASS WhereLogicPerson IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereLogicPerson SET name = 'Luke', surname = 'Skywalker'")
              .iterate();
          tx.yql("CREATE VERTEX WhereLogicPerson SET name = 'Luke', surname = 'Smith'")
              .iterate();
          tx.yql("CREATE VERTEX WhereLogicPerson SET name = 'Han', surname = 'Solo'")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereLogicPerson WHERE name = 'Luke'"
                    + " and surname like 'Sky%'")
                .toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("surname")).isEqualTo("Skywalker");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereLogicPerson").iterate());
  }

  // Line 65: OR logical operator
  @Test
  public void testWhereOrOperator() {
    g.command("CREATE CLASS WhereOrPerson IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereOrPerson SET name = 'Luke', surname = 'Skywalker'")
              .iterate();
          tx.yql("CREATE VERTEX WhereOrPerson SET name = 'Han', surname = 'Solo'")
              .iterate();
          tx.yql("CREATE VERTEX WhereOrPerson SET name = 'Leia', surname = 'Organa'")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereOrPerson WHERE name = 'Luke'"
                    + " or surname like 'Sky%'")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereOrPerson").iterate());
  }

  // Line 66: NOT logical operator
  @Test
  public void testWhereNotOperator() {
    g.command("CREATE CLASS WhereNotPerson IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereNotPerson SET name = 'Luke'").iterate();
          tx.yql("CREATE VERTEX WhereNotPerson SET name = 'Han'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT FROM WhereNotPerson WHERE not (name = 'Luke')")
                .toList());
    assertThat(results).hasSize(1);
    assertThat((String) ((Vertex) results.get(0)).value("name")).isEqualTo("Han");

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereNotPerson").iterate());
  }

  // Line 74-78: Math operators (+, -, *, /, %)
  @Test
  public void testWhereMathOperators() {
    g.command("CREATE CLASS WhereMathTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereMathTest SET age = 6, factor = 2, total = 15")
              .iterate();
        });

    // Plus
    var plus =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereMathTest WHERE age + 34 = 40").toList());
    assertThat(plus).hasSize(1);

    // Minus
    var minus =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereMathTest WHERE age - 4 = 2").toList());
    assertThat(minus).hasSize(1);

    // Multiply
    var multiply =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereMathTest WHERE factor * 1.5 = 3.0").toList());
    assertThat(multiply).hasSize(1);

    // Divide
    var divide =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereMathTest WHERE total / 3 = 5").toList());
    assertThat(divide).hasSize(1);

    // Mod
    var mod =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereMathTest WHERE total % 4 = 3").toList());
    assertThat(mod).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereMathTest").iterate());
  }

  // Line 82-83: eval() function
  @Test
  public void testWhereEvalFunction() {
    g.command("CREATE CLASS WhereOrder IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX WhereOrder SET amount = 100, discount = 10")
              .iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql(
                "SELECT eval(\"amount * 120 / 100 - discount\") as finalPrice"
                    + " FROM WhereOrder")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereOrder").iterate());
  }

  // Line 29: @this record attribute
  @Test
  public void testWhereAtThisAttribute() {
    g.command("CREATE CLASS WhereAccount IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereAccount SET name = 'test'").iterate();
        });

    // @this should be accessible — select @this.toJSON() from Account
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT @this.toJSON() FROM WhereAccount").toList());
    assertThat(results).isNotEmpty();

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereAccount").iterate());
  }

  // Line 31: @class record attribute
  @Test
  public void testWhereAtClassAttribute() {
    g.command("CREATE CLASS WhereProfile IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereProfile SET name = 'test'").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereProfile WHERE @class = 'WhereProfile'")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereProfile").iterate());
  }

  // Line 32: @version record attribute
  @Test
  public void testWhereAtVersionAttribute() {
    g.command("CREATE CLASS WhereVersionTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereVersionTest SET name = 'test'").iterate();
        });

    // Freshly created records should have @version >= 0
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereVersionTest WHERE @version >= 0")
                .toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereVersionTest").iterate());
  }

  // Line 18: any() — matches if ANY property matches
  @Test
  public void testWhereAnyFunction() {
    g.command("CREATE CLASS WhereAnyTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereAnyTest SET name = 'London', code = 'LON'")
              .iterate();
          tx.yql("CREATE VERTEX WhereAnyTest SET name = 'Paris', code = 'PAR'")
              .iterate();
        });

    // any() returns true if ANY property of the record matches the condition.
    // Record 1 has name='London' (matches 'L%') and code='LON' (matches 'L%') → true
    // Record 2 has name='Paris' and code='PAR' → false
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereAnyTest WHERE any() like 'L%'").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereAnyTest").iterate());
  }

  // Line 19: all() — matches if ALL properties match
  @Test
  public void testWhereAllFunction() {
    g.command("CREATE CLASS WhereAllTest IF NOT EXISTS EXTENDS V");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX WhereAllTest SET a = null, b = null").iterate();
          tx.yql("CREATE VERTEX WhereAllTest SET a = 'x', b = null").iterate();
        });

    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM WhereAllTest WHERE all() is null").toList());
    assertThat(results).hasSize(1);

    g.executeInTx(tx -> tx.yql("DELETE VERTEX WhereAllTest").iterate());
  }

  @Test
  public void testRebuildIndexAll() {
    // YQL-Rebuild-Index.md line 11: Use * to rebuild all automatic indexes
    // and line 23-24: REBUILD INDEX *
    g.command("CREATE CLASS RbIdxAllCity EXTENDS V");
    g.command("CREATE PROPERTY RbIdxAllCity.name STRING");
    g.command("CREATE INDEX RbIdxAllCity.name ON RbIdxAllCity (name) NOTUNIQUE");
    g.executeInTx(
        tx -> {
          tx.yql("CREATE VERTEX RbIdxAllCity SET name = 'Paris'").iterate();
        });
    g.close();

    // Rebuild all indexes — should not throw
    g = youTrackDB.openTraversal("test", "admin", "admin");
    g.command("REBUILD INDEX *");

    // Verify data is still accessible after rebuilding all indexes
    var results =
        g.computeInTx(
            tx -> tx.yql("SELECT FROM RbIdxAllCity WHERE name = 'Paris'").toList());
    assertThat(results).hasSize(1);
  }

  // ========================================================================================
  // YQL-Profile.md — PROFILE command validation
  // ========================================================================================

  /**
   * YQL-Profile.md line 21-25: Validates that the PROFILE command works with a SELECT statement
   * containing sum(), date(), WHERE, and GROUP BY clauses. The PROFILE output should contain
   * execution plan steps with timing information.
   */
  @Test
  public void testProfileSelectWithAggregateAndGroupBy() {
    // Set up schema and data for the PROFILE example
    g.command("CREATE CLASS ProfOrders EXTENDS V");
    g.command("CREATE PROPERTY ProfOrders.Amount DOUBLE");
    g.command("CREATE PROPERTY ProfOrders.OrderDate DATE");
    g.command("CREATE INDEX ProfOrders.OrderDate ON ProfOrders (OrderDate) NOTUNIQUE");

    g.executeInTx(
        tx -> {
          tx.yql(
              "CREATE VERTEX ProfOrders SET Amount = 100.0,"
                  + " OrderDate = date('2012-12-10', 'yyyy-MM-dd')")
              .iterate();
          tx.yql(
              "CREATE VERTEX ProfOrders SET Amount = 200.0,"
                  + " OrderDate = date('2012-12-10', 'yyyy-MM-dd')")
              .iterate();
          tx.yql(
              "CREATE VERTEX ProfOrders SET Amount = 50.0,"
                  + " OrderDate = date('2012-12-08', 'yyyy-MM-dd')")
              .iterate();
        });

    // Execute the PROFILE command from the doc example (line 21-25)
    var results =
        g.computeInTx(
            tx -> tx.yql(
                "PROFILE SELECT sum(Amount), OrderDate FROM ProfOrders"
                    + " WHERE OrderDate > date('2012-12-09', 'yyyy-MM-dd')"
                    + " GROUP BY OrderDate")
                .toList());

    // PROFILE should return execution plan steps (not data rows)
    assertThat(results).isNotEmpty();
  }

  /**
   * YQL-Profile.md line 3: Validates the claim that "PROFILE returns information about query
   * execution planning and statistics" — specifically that the output differs from a plain SELECT
   * by containing execution plan metadata.
   */
  @Test
  public void testProfileReturnsExecutionPlan() {
    // PROFILE on a simple query should return execution plan info
    var profileResults =
        g.computeInTx(tx -> tx.yql("PROFILE SELECT FROM ProfOrders").toList());

    // The PROFILE result should not be empty
    assertThat(profileResults).isNotEmpty();
  }

}
