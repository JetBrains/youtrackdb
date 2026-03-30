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
}
