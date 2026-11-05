package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5GeneratedFixtureTest {
  @Rule
  public TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();

  @Test
  public void topologyMatchesPinnedArchiveGeneratorForBothDistributions() throws Exception {
    // Golden bytes come from the archived GraphData source, not this fixture's output.
    for (String distribution : List.of("uniform", "skewed")) {
      var d = new T5GeneratedFixture(200, 20261020L, distribution);
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        for (int i = 0; i < d.n; i++) {
          out.writeUTF(d.type(i));
          out.writeInt(d.bucket(i));
          out.writeInt(d.outgoing[i]);
          out.writeInt(d.incoming[i]);
          for (int j = 0; j < d.targets[i].length; j++) {
            out.writeInt(d.targets[i][j]);
            out.writeByte(d.labels[i][j]);
          }
        }
      }
      assertEquals(distribution.equals("uniform")
          ? "56aa148a89d6f3cc0b906689b413c7b900b81bd3e45e0ad70d4c471b89017885"
          : "558c4de4c4887c41e545dec15577832e092010b14fea628f8bec65b9be70b2aa",
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
    }
    assertThrows(IllegalArgumentException.class, () -> new T5GeneratedFixture(199, 1, "uniform"));
    assertThrows(IllegalArgumentException.class, () -> new T5GeneratedFixture(200, 1, "other"));
  }

  @Test
  public void archivedScaleSkewedTopologyHasTenThousandEdgeHubAndFiveEdgesPerVertexBudget() {
    // The archived n20000-skewed graph reserves a 10,000-edge hub within 100,000 edges.
    // Inspect only topology arrays so this scale check does not need database population.
    var d = new T5GeneratedFixture(20_000, 20261020L, "skewed");
    assertEquals(10_000, java.util.Arrays.stream(d.outgoing).max().orElseThrow());
    assertEquals(100_000, java.util.Arrays.stream(d.outgoing).sum());
    assertEquals(100_000, java.util.Arrays.stream(d.incoming).sum());
  }

  @Test
  public void populatedCreationDatesIncludeDayTwoHundredAndWrapAfterDayThreeHundredSixtyFour()
      throws Exception {
    var d = new T5GeneratedFixture(367, 20261020L, "uniform");
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("dates", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("dates", "admin", "admin")) {
        d.populate(g, 73);
        g.executeInTx(t -> {
          // Fixed archive epoch dates straddle both the 200-day decoy and the annual wrap.
          var dates = java.util.Map.of(199, 1717193600000L, 200, 1717280000000L,
              364, 1731449600000L, 365, 1700000000000L, 366, 1700086400000L);
          for (var entry : dates.entrySet()) {
            assertEquals("creationDate at logical id " + entry.getKey(),
                List.of(entry.getValue()),
                t.V(d.rid(entry.getKey())).values("creationDate").toList());
          }
        });
      }
    }
  }

  @Test
  public void populatedGraphHasAsymmetricEdgesPropertiesAndExactIndexes() throws Exception {
    var d = new T5GeneratedFixture(200, 20261020L, "uniform");
    assertThrows(NullPointerException.class, () -> d.rid(0));
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("fixture", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("fixture", "admin", "admin")) {
        assertThrows(IllegalArgumentException.class, () -> d.populate(g, 0));
        d.populate(g, 73);
        g.executeInTx(t -> {
          assertEquals(200L, t.V().count().next().longValue());
          assertEquals(1000L, t.E().count().next().longValue());
          for (int i = 0; i < d.n; i++) {
            for (int j = 0; j < d.targets[i].length; j++) {
              assertEquals(List.of((long) d.targets[i][j]), t.V(d.rid(i))
                  .out(T5GeneratedFixture.LABELS[d.labels[i][j]])
                  .has("id", (long) d.targets[i][j]).values("id").toList());
            }
          }
          assertEquals(List.of(35L, 132L), t.V(d.rid(0)).out("KNOWS")
              .values("id").order().toList());
          assertEquals(List.of(0L), t.V(d.rid(8)).out("CONTAINER_OF")
              .has("id", 0L).values("id").toList());
          assertEquals(List.of(), t.V(d.rid(0)).out("REPLY_OF").toList());
          assertEquals(List.of("name0"), t.V(d.rid(0)).values("lastName").toList());
          assertEquals(List.of(16), t.V(d.rid(0)).values("score").toList());
          assertEquals(List.of("cohort1"), t.V(d.rid(8)).values("cohort").toList());
          assertEquals(List.of(1701641600000L), t.V(d.rid(19)).values("creationDate").toList());
          var session = ((YTDBGraphInternal) t.getGraph()).tx().getDatabaseSession();
          for (String type : List.of("Person", "Post", "Comment", "Tag", "Company", "Forum")) {
            var c =
                (com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal) session
                    .getMetadata().getSchema().getClass(type);
            assertEquals(java.util.Map.of("id", "LONG", "lastName", "STRING",
                "creationDate", "LONG", "cohort", "STRING", "score", "INTEGER"),
                c.getDeclaredProperties().stream().collect(java.util.stream.Collectors.toMap(
                    p -> p.getName(), p -> p.getType().name())));
            // Enumerate actual indexes, not indexes involved in an empty field lookup.
            for (var index : c.getClassIndexesInternal()) {
              assertEquals(List.of(index.getName().substring(type.length() + 1)),
                  index.getDefinition().getProperties());
            }
            assertEquals(type.equals("Person") || type.equals("Post") ? 2 : 1,
                c.getClassIndexesInternal().size());
            assertEquals("UNIQUE", c.getClassIndex(session, type + ".id").getType());
            if (type.equals("Person") || type.equals("Post")) {
              assertEquals("NOTUNIQUE", c.getClassIndex(session, type + (type.equals("Person")
                  ? ".lastName" : ".creationDate")).getType());
            }
          }
        });
      }
    }
  }
}
