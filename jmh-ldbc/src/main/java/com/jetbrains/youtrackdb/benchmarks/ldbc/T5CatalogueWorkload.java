package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;

/** Complete correctness inventory. Graphs are populated one at a time, never selected by timing. */
final class T5CatalogueWorkload implements T5BaselineRunner.Workload {
  static final String CURATED_SHA =
      "a32c482c97899ddafe90dbe9635e4ac9732fa5c19feec90c732767ac73212f18";
  private final List<T5BenchmarkCatalogue.Form> generated = T5BenchmarkCatalogue.load();
  private final List<T5BenchmarkCatalogue.WhereForm> where = T5BenchmarkCatalogue.loadWhere();
  private final List<T5BenchmarkCatalogue.LdbcForm> ldbc = T5BenchmarkCatalogue.loadLdbc();
  private YouTrackDB db;
  private YTDBGraphTraversalSource g;
  private Path temporary;
  private String graph = "";
  private T5GeneratedFixture a;
  private T5WhereFixture b;

  public T5CatalogueWorkload() {
  }

  static Map<String, String> hashes(Path inputs, String factorHash) throws Exception {
    var result = new LinkedHashMap<String, String>();
    for (String name : List.of("curated-params-v3.json", "factor-tables.json")) {
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(Files.readAllBytes(inputs.resolve(name))));
      String expected = name.startsWith("curated") ? CURATED_SHA : factorHash;
      if (!actual.equals(expected)) {
        throw new IllegalArgumentException("Input hash mismatch: " + name);
      }
      result.put(name, actual);
    }
    for (String name : List.of("generated-gate.json", "where-gate.json", "ldbc-gate.json")) {
      try (var in = T5CatalogueWorkload.class.getResourceAsStream("/t5/" + name)) {
        result.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(java.util.Objects.requireNonNull(in).readAllBytes())));
      }
    }
    return Map.copyOf(result);
  }

  static List<T5BaselineRunner.Case> cases(
      BiFunction<T5BenchmarkCatalogue.LdbcForm, Integer,
          Map<String, T5FreezeCapture.Value>> feeds) {
    var result = new ArrayList<T5BaselineRunner.Case>();
    for (var form : T5BenchmarkCatalogue.load()) {
      for (var p : form.parameters()) {
        result.add(new T5BaselineRunner.Case("A/" + form.identity(), Integer.toString(p.slot()),
            Map.of("rootId", T5FreezeCapture.Value.of(p.rootId()),
                "endpointId", T5FreezeCapture.Value.of(p.endpointId()),
                "bucket", T5FreezeCapture.Value.of(p.bucket())),
            form.definition().terminal().equals("orderLimit")
                || form.definition().terminal().equals("limit")
                    && form.definition().deterministicCaps()
                        ? T5ResultComparison.Order.SEQUENCE : T5ResultComparison.Order.MULTISET,
            false));
      }
    }
    for (var form : T5BenchmarkCatalogue.loadWhere()) {
      result.add(new T5BaselineRunner.Case(form.identity(), "archive",
          Map.of("cat", T5FreezeCapture.Value.of(form.cat()),
              "slug", T5FreezeCapture.Value.of(form.slug()),
              "email", T5FreezeCapture.Value.of(form.email())),
          T5BenchmarkCatalogue.expected(form).order(), form.q().equals("5")));
    }
    for (var form : T5BenchmarkCatalogue.loadLdbc()) {
      for (int slot = 0; slot < 64; slot++) {
        var values = feeds.apply(form, slot);
        var names = form.arguments().stream().map(s -> s.split("\\+")[0])
            .collect(java.util.stream.Collectors.toSet());
        if (!values.keySet().equals(names)) {
          throw new IllegalArgumentException("Missing or unexpected feed for " + form.identity());
        }
        values.values().forEach(T5FreezeCapture.Value::decode);
        result.add(new T5BaselineRunner.Case("C/" + form.identity(), Integer.toString(slot),
            values, form.order(), false));
      }
    }
    return List.copyOf(result);
  }

  static void validate(List<T5BaselineRunner.Case> actual) {
    var byKey = new LinkedHashMap<String, T5BaselineRunner.Case>();
    for (var c : actual) {
      if (byKey.put(c.identity() + ":" + c.binding(), c) != null) {
        throw new IllegalArgumentException("Duplicate catalogue binding");
      }
    }
    var expected = cases((form, slot) -> {
      var c = byKey.get("C/" + form.identity() + ":" + slot);
      if (c == null) {
        throw new IllegalArgumentException("Missing catalogue member " + form.identity());
      }
      return c.feeds();
    });
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("Incomplete or changed catalogue inventory");
    }
  }

  @Override
  public List<T5BaselineRunner.Case> resolve() throws Exception {
    select("C");
    Path inputs = Path.of(System.getProperty("t5.inputs"));
    var feeds = new T5RunnerFeeds(inputs.resolve("curated-params-v3.json"),
        inputs.resolve("factor-tables.json"), new T5RunnerFeeds.Lookup() {
          public boolean exists(String label, long id) {
            return g.V().hasLabel(label).has("id", id).hasNext();
          }

          public String place(long person) {
            return g.V().hasLabel("Person").has("id", person).out("IS_LOCATED_IN")
                .<String>values("name").tryNext().orElse(null);
          }

          public long[] dates(long person) {
            var min = g.V().hasLabel("Person").has("id", person).out("KNOWS")
                .in("HAS_CREATOR").values("creationDate").min().tryNext();
            if (min.isEmpty()) {
              return null;
            }
            var max = g.V().hasLabel("Person").has("id", person).out("KNOWS")
                .in("HAS_CREATOR").values("creationDate").max().next();
            return new long[] {millis(min.get()), millis(max)};
          }
        });
    ((YTDBGraphInternal) g.getGraph()).tx().rollback();
    return cases(feeds::feeds);
  }

  private static long millis(Object value) {
    return value instanceof java.util.Date date ? date.getTime() : ((Number) value).longValue();
  }

  @Override
  public void prepare(T5BaselineRunner.Case query) throws Exception {
    String selected = query.identity().startsWith("C/") ? "C"
        : query.identity().substring(0, query.identity().indexOf('/', 2));
    select(selected);
  }

  private void select(String selected) throws Exception {
    if (selected.equals(graph)) {
      return;
    }
    close();
    if (selected.equals("C")) {
      Path root = Path.of(System.getProperty("t5.dbRoot"));
      if (!Files.isDirectory(root.resolve("ldbc_benchmark"))) {
        throw new IllegalArgumentException("Existing LDBC database required");
      }
      db = YourTracks.instance(root.toString());
      if (!db.exists("ldbc_benchmark")) {
        throw new IllegalArgumentException("Existing LDBC database required");
      }
      g = db.openTraversal("ldbc_benchmark", "admin", "admin");
    } else {
      temporary = Files.createTempDirectory("t5-catalogue-");
      db = YourTracks.instance(temporary.toString());
      db.create("fixture", DatabaseType.MEMORY, "admin", "admin", "admin");
      g = db.openTraversal("fixture", "admin", "admin");
      if (selected.startsWith("A/")) {
        String[] parts = selected.substring(3).split("-");
        a = new T5GeneratedFixture(Integer.parseInt(parts[0]), 20261020L, parts[1]);
        a.populate(g, 1000);
      } else if (List.of("B/indexed", "B/no-index").contains(selected)) {
        b = new T5WhereFixture();
        b.populate(g);
        if (selected.equals("B/no-index")) {
          b.dropIndexes(g);
        }
      } else {
        throw new IllegalArgumentException("Unknown graph " + selected);
      }
    }
    graph = selected;
  }

  @Override
  public YTDBGraphTraversalSource source() {
    return java.util.Objects.requireNonNull(g, "Prepare a query first");
  }

  @Override
  public Traversal<?, ?> traversal(T5BaselineRunner.Case query) {
    if (query.identity().startsWith("A/")) {
      var form = generated.stream().filter(f -> ("A/" + f.identity()).equals(query.identity()))
          .findFirst().orElseThrow();
      var parameter = form.parameters().stream()
          .filter(p -> Integer.toString(p.slot()).equals(query.binding())).findFirst()
          .orElseThrow();
      return form.traversal(g, parameter, a::rid);
    }
    if (query.identity().startsWith("B/")) {
      return b.traversal(g, where.stream().filter(f -> f.identity().equals(query.identity()))
          .findFirst().orElseThrow());
    }
    var form = ldbc.stream().filter(f -> ("C/" + f.identity()).equals(query.identity()))
        .findFirst().orElseThrow();
    return form.traversal(g, name -> {
      Object value = java.util.Objects.requireNonNull(query.feeds().get(name), name).decode();
      // The transport stores logical ids only. Physical RIDs are resolved in each arm's graph.
      return name.equals("arm.personRid")
          ? g.V().hasLabel("Person").has("id", value).next().id() : value;
    });
  }

  @Override
  public boolean wholeNative(T5BaselineRunner.Case query) {
    if (query.identity().startsWith("A/")) {
      var form = generated.stream().filter(f -> ("A/" + f.identity()).equals(query.identity()))
          .findFirst().orElseThrow();
      return form.definition().start().equals("rid")
          || form.definition().filter().equals("knownEndpoint");
    }
    if (query.identity().startsWith("B/")) {
      return where.stream().filter(f -> f.identity().equals(query.identity()))
          .anyMatch(f -> List.of("5", "H", "U2").contains(f.q()));
    }
    return query.feeds().containsKey("arm.personRid");
  }

  @Override
  public T5ResultComparison.ElementKey logicalKey(Element element) {
    String property = switch (element.label()) {
      case "Article" -> "slug";
      case "Category" -> "uid";
      case "JPEmailContact" -> "email";
      case "OrderedItem" -> "rank";
      default -> "id";
    };
    return new T5ResultComparison.ElementKey("vertex", element.label(), element.value(property));
  }

  @Override
  public void close() throws Exception {
    try {
      if (g != null) {
        g.close();
      }
    } finally {
      g = null;
      if (db != null) {
        db.close();
        db = null;
      }
      if (temporary != null) {
        try (var paths = Files.walk(temporary)) {
          for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(path);
          }
        }
        temporary = null;
      }
      graph = "";
    }
  }
}
