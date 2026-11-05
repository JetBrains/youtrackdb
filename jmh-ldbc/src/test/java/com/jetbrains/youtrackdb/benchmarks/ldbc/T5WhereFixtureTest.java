package com.jetbrains.youtrackdb.benchmarks.ldbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinPlanCache;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinTranslationMetrics;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class T5WhereFixtureTest {
  @Rule
  public TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();

  @Test
  public void archivedScaleTargetsMatchIndependentDegreeAndSingletonTable() throws Exception {
    // Values come from B-f0/fixture.tsv. Exercise every branch without a full database load.
    // SHA-256 of all 100,000 signed big-endian int targets from the archived harness.
    // Unlike the degree table, this also distinguishes degree-preserving item swaps.
    var mapping = java.nio.ByteBuffer.allocate(400_000);
    int[] counts = new int[1000];
    for (int i = 0; i < 100_000; i++) {
      int c = T5WhereFixture.target(i);
      mapping.putInt(c);
      if (c >= 0) {
        counts[c]++;
      }
    }
    assertEquals("0c64b82c2c4970837120c8516e30bae67e3b7435b914ca6feacadc9e11dbe0f6",
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(mapping.array())));
    var bytes = java.nio.ByteBuffer.allocate(4000);
    IntStream.of(counts).forEach(bytes::putInt);
    assertEquals("78f6eb24e688651f2f581d9c2b4d1b0b97b3d4ea305532f1aab54621e991a26c",
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes.array())));
    assertEquals(35999, counts[0]);
    assertEquals(18000, counts[1]);
    assertEquals(1, counts[997]);
    assertEquals(1, counts[998]);
    assertEquals(0, counts[999]);
    assertEquals(90000, IntStream.of(counts).sum());
    assertEquals(-1, T5WhereFixture.target(9));
    assertEquals(997, T5WhereFixture.target(1));
    assertEquals(998, T5WhereFixture.target(99998));
  }

  @Test
  public void exactBindingsTopologyIndexesAndOrderedAnswersSurviveIndexRemoval() throws Exception {
    var enabled = GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.getValue();
    var collections = GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValue();
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(false);
    try (var db = YourTracks.instance(temporary.newFolder().toString())) {
      db.create("where", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var g = db.openTraversal("where", "admin", "admin")) {
        var f = new T5WhereFixture();
        int[] items = IntStream.concat(IntStream.range(0, 1030),
            IntStream.of(39999, 40000, 59999, 60000, 60001, 80000, 99998, 99999)).toArray();
        // Observe actual creation calls without mocking the database or reading fixture fields.
        // RIDs can be assigned out of order at commit. Keep the same live identity objects.
        var users = new ArrayList<Object>();
        var graph = (YTDBGraphInternal) g.getGraph();
        var observed = (YTDBGraphInternal) Proxy.newProxyInstance(
            YTDBGraphInternal.class.getClassLoader(), new Class<?>[] {YTDBGraphInternal.class},
            (proxy, method, args) -> {
              try {
                var result = method.invoke(graph, args);
                if (method.getName().equals("addVertex") && result instanceof Vertex vertex
                    && vertex.label().equals("HubUser")) {
                  users.add(vertex.id());
                }
                return result;
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
        try (var source = new YTDBGraphTraversalSource(observed, g.getStrategies())) {
          f.populate(source, items);
        }
        assertEquals(collections, GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValue());
        for (String variant : List.of("indexed", "no-index")) {
          g.executeInTx(t -> {
            ((YTDBGraphInternal) t.getGraph()).tx().readWrite();
            var session = ((YTDBGraphInternal) t.getGraph()).tx().getDatabaseSession();
            assertNotNull(((SchemaClassInternal) session.getMetadata().getSchema()
                .getClass("Category")).getClassIndex(session, "Category.uid"));
            for (String index : List.of("Article.slug", "JPEmailContact.email",
                "OrderedItem.rank")) {
              assertEquals(variant, variant.equals("indexed"),
                  ((SchemaClassInternal) session.getMetadata().getSchema()
                      .getClass(index.substring(0, index.indexOf('.'))))
                      .getClassIndex(session, index)
                      != null);
            }
            if (variant.equals("indexed")) {
              for (String type : List.of("Article", "Category", "JPEmailContact", "OrderedItem")) {
                var schema = (SchemaClassInternal) session.getMetadata().getSchema().getClass(type);
                assertEquals(1, schema.getClassIndexesInternal().size());
                assertEquals(type.equals("OrderedItem") ? "NOTUNIQUE" : "UNIQUE",
                    schema.getClassIndexesInternal().iterator().next().getType());
              }
            }
            for (String property : List.of("Article.slug", "Category.uid",
                "JPEmailContact.email", "JPEmailContact.verified", "OrderedItem.rank")) {
              int dot = property.indexOf('.');
              assertEquals(property, property.endsWith("rank") ? PropertyType.INTEGER
                  : property.endsWith("verified") ? PropertyType.BOOLEAN : PropertyType.STRING,
                  session.getMetadata().getSchema().getClass(property.substring(0, dot))
                      .getProperty(property.substring(dot + 1)).getType());
            }
            // Each user has no logical property. Its identity is the ith observed HubUser creation.
            assertEquals(1000, users.size());
            assertEquals(1000, new HashSet<>(users).size());
            assertEquals(new HashSet<>(users),
                new HashSet<>(t.V().hasLabel("HubUser").id().toList()));
            var categoryIds = IntStream.range(0, 1000)
                .mapToObj(
                    i -> t.V().hasLabel("Category").has("uid", "u" + i).id().toList().getFirst())
                .toList();
            int activeQueries = session.getActiveQueries().size();
            for (int i : items) {
              String item = variant + "/item-" + i;
              // Exhaust the lookup so its result set closes, including on a successful match.
              assertEquals(item, List.of(i % 3 != 0), t.V().hasLabel("JPEmailContact")
                  .has("email", "e" + i + "@test").values("verified").toList());
              int c = archivedSmallTarget(i);
              assertEquals(item + "/category", c < 0 ? List.of() : List.of(categoryIds.get(c)),
                  t.V().hasLabel("Article").has("slug", "s" + i)
                      .out("category_link").id().toList());
              assertEquals(item + "/user", c < 0 ? List.of() : List.of(users.get(c)),
                  t.V().hasLabel("JPEmailContact").has("email", "e" + i + "@test")
                      .out("user_link").id().toList());
              assertEquals(item + "/a", i % 10 == 0 ? List.of(categoryIds.get(0)) : List.of(),
                  t.V().hasLabel("Article").has("slug", "s" + i).out("a").id().toList());
              assertEquals(item + "/b", i % 13 == 0 ? List.of(categoryIds.get(1)) : List.of(),
                  t.V().hasLabel("Article").has("slug", "s" + i).out("b").id().toList());
              assertEquals(item + "/closed lookups", activeQueries,
                  session.getActiveQueries().size());
            }
            for (var c : T5BenchmarkCatalogue.loadWhere()) {
              if (!c.variant().equals(variant)) {
                continue;
              }
              try (var q = f.traversal(t, c)) {
                // Pin archived RID keys and nesting before strategies can normalize the DSL.
                // The expected ids come from this replay, not archive RIDs or fixture fields.
                if (c.q().equals("5")) {
                  try (var expected = t.V().has("slug", c.slug())
                      .where(__.out("category_link").hasId(categoryIds.get(c.cat())))
                      .hasLabel("Article")) {
                    assertEquals(c.identity(), expected.asAdmin().getBytecode(),
                        q.asAdmin().getBytecode());
                  }
                } else if (c.q().equals("H") || c.q().equals("U2")) {
                  try (var expected = t.V().and(
                      __.where(__.out("user_link").hasId(users.get(c.cat()))),
                      __.has("email", c.email()), __.has("verified", true))
                      .hasLabel("JPEmailContact")) {
                    assertEquals(c.identity(), expected.asAdmin().getBytecode(),
                        q.asAdmin().getBytecode());
                  }
                }
                var actual = q.toList().stream().map(row -> {
                  if (!(row instanceof Vertex v)) {
                    return row.toString();
                  }
                  String key = switch (v.label()) {
                    case "Article" -> "slug";
                    case "JPEmailContact" -> "email";
                    case "Category" -> "uid";
                    default -> "rank";
                  };
                  return v.label() + "/" + v.value(key);
                }).toList();
                var expected = c.q().equals("U1") ? IntStream.of(items).boxed()
                    .sorted(java.util.Comparator.reverseOrder()).limit(20)
                    .map(i -> "OrderedItem/" + i).toList() : c.expected();
                assertEquals(c.identity(),
                    c.q().equals("CONTROL") ? expected.stream().sorted().toList()
                        : expected,
                    c.q().equals("CONTROL") ? actual.stream().sorted().toList() : actual);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            }
            assertEquals(variant + "/closed case traversals", activeQueries,
                session.getActiveQueries().size());
          });
          if (variant.equals("indexed")) {
            f.dropIndexes(g);
          }
        }
        // A setup error must roll back its active work and restore process-wide configuration.
        assertThrows(RuntimeException.class, () -> new T5WhereFixture().populate(g, items));
        assertFalse(((YTDBGraphInternal) g.getGraph()).tx().isOpen());
        assertEquals(collections, GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValue());
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(true);
        g.executeInTx(t -> {
          ((YTDBGraphInternal) t.getGraph()).tx().readWrite();
          var session = ((YTDBGraphInternal) t.getGraph()).tx().getDatabaseSession();
          var cache = GremlinPlanCache.instance(session);
          var metrics = GremlinTranslationMetrics.of(session);
          cache.invalidate();
          var control = T5BenchmarkCatalogue.loadWhere().stream()
              .filter(c -> c.q().equals("CONTROL")).findFirst().orElseThrow();
          for (int call = 0; call < 2; call++) {
            long hits = cache.getTranslationHits();
            long nativeCalls = metrics.getRuleNative();
            try (var q = f.traversal(t, control)) {
              q.asAdmin().applyStrategies();
              assertEquals(0, GremlinTraversalShapes.countBoundarySteps(q.asAdmin()));
              assertEquals(1000, q.toList().size());
              assertEquals(nativeCalls + 1, metrics.getRuleNative());
              assertEquals(hits + call, cache.getTranslationHits());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          var c = T5BenchmarkCatalogue.loadWhere().stream().filter(x -> x.q().equals("LONG"))
              .findFirst().orElseThrow();
          long declines = metrics.getDeclines();
          try (var q = f.traversal(t, c)) {
            // Pin the emitted LONG, not a separately callable prefix helper.
            try (var expected = t.V().hasLabel("Category").has("uid", "u997")) {
              for (int pair = 0; pair < 12; pair++) {
                expected.in("category_link").out("category_link");
              }
              var instructions = q.asAdmin().getBytecode().getStepInstructions();
              assertEquals(expected.asAdmin().getBytecode().getStepInstructions(),
                  instructions.subList(0, instructions.size() - 1));
              assertEquals("fold", instructions.getLast().getOperator());
              assertEquals(2, instructions.getLast().getArguments().length);
              assertEquals(0, instructions.getLast().getArguments()[0]);
            }
            assertEquals(27, q.asAdmin().getSteps().size());
            var fold = (FoldStep<?, Integer>) q.asAdmin().getEndStep();
            assertFalse(fold.isListFold());
            assertEquals(Integer.valueOf(0), fold.getSeedSupplier().get());
            assertEquals(Integer.valueOf(8), fold.getBiOperator().apply(7, 42));
            // Derive admission evidence from this very traversal by removing only its fold.
            try (var prefix = q.asAdmin().clone()) {
              prefix.removeStep(prefix.getSteps().size() - 1);
              assertEquals(26, prefix.getSteps().size());
              prefix.applyStrategies();
              assertEquals(1, GremlinTraversalShapes.countBoundarySteps(prefix));
              assertEquals(1, prefix.toList().size());
            }
            q.asAdmin().applyStrategies();
            assertEquals(0, GremlinTraversalShapes.countBoundarySteps(q.asAdmin()));
            assertEquals(List.of(1), q.toList());
            assertEquals(declines + 1, metrics.getDeclines());
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
      }
    } finally {
      GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(enabled);
    }
  }

  private static int archivedSmallTarget(int item) {
    // Independent literal targets for the sampled archive items. No production target() call.
    return switch (item) {
      case 1 -> 997;
      case 40000 -> 1;
      case 60000 -> 146;
      case 60001 -> 300;
      case 80000 -> 8;
      case 99998 -> 998;
      default -> item % 10 == 9 ? -1 : 0;
    };
  }
}
