package com.jetbrains.youtrackdb.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
}
