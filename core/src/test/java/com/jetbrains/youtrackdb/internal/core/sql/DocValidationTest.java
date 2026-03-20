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
}
