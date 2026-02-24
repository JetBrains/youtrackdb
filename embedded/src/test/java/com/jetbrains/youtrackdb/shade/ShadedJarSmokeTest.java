package com.jetbrains.youtrackdb.shade;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import org.junit.Test;

/**
 * Unit-level smoke test that verifies the core database functionality works.
 * This runs during the {@code test} phase (before shading) and proves SPI
 * discovery, ServiceLoader, class loading, and Gremlin traversals all function.
 */
public class ShadedJarSmokeTest {

  /**
   * Creates an in-memory database, adds a vertex via Gremlin, queries it back,
   * and verifies the result. This proves SPI, ServiceLoader, and class loading
   * all work correctly.
   */
  @Test
  public void embeddedInMemoryDatabaseRoundTrip() {
    try (YouTrackDB db = YourTracks.instance(".")) {
      db.createIfNotExists("shadetest", DatabaseType.MEMORY,
          "admin", "admin", "admin");

      try (YTDBGraphTraversalSource g =
               db.openTraversal("shadetest", "admin", "admin")) {
        // Add a vertex inside a transaction
        g.executeInTx(
            tx -> tx.addV("Person").property("name", "Alice").next());

        // Query the vertex back and verify
        long count = g.computeInTx(
            tx -> tx.V().hasLabel("Person")
                .has("name", "Alice")
                .count().next());
        assertEquals("Expected exactly one vertex", 1L, count);
      }

      db.drop("shadetest");
    }
  }
}
