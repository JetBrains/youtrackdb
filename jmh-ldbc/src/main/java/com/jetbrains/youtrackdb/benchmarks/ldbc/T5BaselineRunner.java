package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinPlanCache;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinTranslationMetrics;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.T5PreparedShapeProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Element;

/** Internal correctness-only process harness. It never measures execution time. */
public final class T5BaselineRunner {
  public static final String BASELINE = "6f180c31ee4de9ddd61e88267efc65f5d03289f5";
  static final ObjectMapper JSON = new ObjectMapper();
  static final List<String> ARMS = List.of("native", "baseline");

  /** A provider resolves its feeds once in the parent and replays those exact values in children.
   * Providers own database setup and must not install test-only translation controls. */
  public interface Workload extends AutoCloseable {
    List<Case> resolve() throws Exception;

    default void prepare(Case query) throws Exception {
    }

    YTDBGraphTraversalSource source();

    Traversal<?, ?> traversal(Case query);

    default boolean wholeNative(Case query) {
      return false;
    }

    T5ResultComparison.ElementKey logicalKey(Element element);

    @Override
    void close() throws Exception;
  }

  public record Case(String identity, String binding, Map<String, T5FreezeCapture.Value> feeds,
      T5ResultComparison.Order order, boolean stepLabelFixDifference) {
  }

  public record Request(String provider, List<Case> cases, Map<String, String> inputHashes,
      String nativeRevision) {
  }

  public record Arm(String name, String revision, String classpath) {
  }

  public static List<T5FreezeCapture.Required> inventory(Request request) {
    if (request.provider().equals(T5CatalogueWorkload.class.getName())) {
      T5CatalogueWorkload.validate(request.cases());
    }
    var result = new ArrayList<T5FreezeCapture.Required>();
    for (var c : request.cases()) {
      for (String arm : ARMS) {
        for (var regime : T5FreezeCapture.Regime.values()) {
          result.add(new T5FreezeCapture.Required(key(c, arm, regime), c.feeds().keySet()));
        }
      }
    }
    return List.copyOf(result);
  }

  private static T5FreezeCapture.Key key(Case c, String arm, T5FreezeCapture.Regime regime) {
    return new T5FreezeCapture.Key(c.identity(), c.binding(), arm, regime);
  }

  /** Parent-only feed resolution. Derived dates must already be represented as typed values. */
  public static Request resolve(String provider, Map<String, String> hashes, String revision)
      throws Exception {
    try (var workload = open(provider)) {
      var request = new Request(provider, List.copyOf(workload.resolve()), Map.copyOf(hashes),
          revision);
      if (request.cases().isEmpty()) {
        throw new IllegalArgumentException("Empty workload");
      }
      // Refuse unsupported values before launching either child.
      for (var c : request.cases()) {
        c.feeds().values().forEach(T5FreezeCapture.Value::decode);
      }
      return request;
    }
  }

  private static Workload open(String provider) throws Exception {
    return (Workload) Class.forName(provider).getConstructor().newInstance();
  }

  /** Runs sequential isolated JVMs and publishes only a checked, merged capture. */
  public static T5FreezeCapture.Capture run(Request request, List<Arm> arms, Path directory,
      Path output, Duration timeout) throws Exception {
    if (arms.size() != 2 || !arms.get(0).name().equals("native")
        || !arms.get(1).name().equals("baseline")
        || !arms.get(0).revision().equals(request.nativeRevision())
        || !arms.get(1).revision().equals(BASELINE)) {
      throw new IllegalArgumentException("Expected current native and pinned baseline arms");
    }
    Files.createDirectories(directory);
    Path input = Files.createTempFile(directory, "request-", ".json");
    var files = new ArrayList<Path>();
    files.add(input);
    try {
      JSON.writeValue(input.toFile(), request);
      var items = new ArrayList<T5FreezeCapture.Item>();
      for (var arm : arms) {
        Path result = Files.createTempFile(directory, arm.name(), ".json");
        Files.delete(result);
        Path log = Files.createTempFile(directory, arm.name(), ".log");
        files.add(result);
        files.add(log);
        var command = javaCommand(arm.classpath());
        command.addAll(List.of(T5BaselineRunner.class.getName(), "child", input.toString(),
            arm.name(), arm.revision(), result.toString()));
        execute(command, log, timeout);
        var capture = T5FreezeCapture.read(result);
        requireComplete(capture, inventory(request).stream()
            .filter(r -> r.key().arm().equals(arm.name())).toList());
        if (!capture.revisions().equals(Map.of(arm.name(), arm.revision()))
            || !capture.inputHashes().equals(request.inputHashes())) {
          throw new IOException("Child provenance mismatch");
        }
        items.addAll(capture.items());
      }
      items = markDifferences(request, items);
      var capture = new T5FreezeCapture.Capture(1, true,
          Map.of("native", request.nativeRevision(), "baseline", BASELINE),
          request.inputHashes(), items);
      requireComplete(capture, inventory(request));
      T5FreezeCapture.write(output, capture);
      return capture;
    } finally {
      for (Path file : files) {
        Files.deleteIfExists(file);
      }
    }
  }

  static ArrayList<String> javaCommand(String classpath) {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(List.of("-ea", "-Xmx1g", "-Dyoutrackdb.security.createDefaultUsers=false",
        "-Dyoutrackdb.storage.diskCache.bufferSize=128"));
    for (String pkg : List.of("java.lang", "java.lang.reflect", "java.lang.invoke", "java.io",
        "java.nio", "java.util", "java.util.concurrent", "java.util.concurrent.atomic",
        "java.net", "sun.nio.cs", "sun.security.x509")) {
      command.add("--add-opens=java.base/" + pkg + "=ALL-UNNAMED");
    }
    command.add("--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED");
    for (String property : List.of("t5.dbRoot", "t5.inputs")) {
      if (System.getProperty(property) != null) {
        command.add("-D" + property + "=" + System.getProperty(property));
      }
    }
    command.addAll(List.of("-cp", classpath));
    return command;
  }

  static void execute(List<String> command, Path log, Duration timeout) throws Exception {
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Positive child timeout required");
    }
    var process = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(log.toFile()).start();
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException("Timed out child: " + log);
      }
      if (process.exitValue() != 0) {
        throw new IOException("Failed child exit=" + process.exitValue() + ": "
            + Files.readString(log));
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  static String loadedRevision(String expected) throws Exception {
    Path core = Path.of(GremlinToMatchStrategy.class.getProtectionDomain().getCodeSource()
        .getLocation().toURI());
    try (var jar = new JarFile(core.toFile())) {
      if (!"YouTrackDB Core".equals(jar.getManifest().getMainAttributes()
          .getValue("Implementation-Title"))) {
        throw new IOException("Expected an unshaded core jar: " + core);
      }
      String actual = jar.getManifest().getMainAttributes().getValue("Implementation-Build");
      if (!expected.equals(actual)) {
        throw new IOException(
            "Core revision mismatch: expected " + expected + ", loaded " + actual);
      }
      return actual;
    }
  }

  static void requireUnforced() throws Exception {
    var field = GremlinToMatchStrategy.class.getDeclaredField("FORCE_MATCH_FOR_TESTS");
    field.setAccessible(true);
    if (((ThreadLocal<?>) field.get(null)).get() != null) {
      throw new IllegalStateException("Forced MATCH is forbidden in baseline arms");
    }
  }

  static void child(Request request, String arm, String expected, Path output) throws Exception {
    if (!ARMS.contains(arm) || !(arm.equals("native") ? request.nativeRevision() : BASELINE)
        .equals(expected)) {
      throw new IllegalArgumentException("Unknown arm or revision");
    }
    String revision = loadedRevision(expected);
    requireUnforced();
    GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED.setValue(arm.equals("baseline"));
    try (var workload = open(request.provider())) {
      var items = new ArrayList<T5FreezeCapture.Item>();
      inventory(request);
      for (var query : request.cases()) {
        workload.prepare(query);
        for (var regime : T5FreezeCapture.Regime.values()) {
          var graph = (YTDBGraphInternal) workload.source().getGraph();
          graph.tx().readWrite();
          try {
            items.add(observe(workload, query, arm, revision, regime));
          } finally {
            // Every pass is read-only apart from the disposable schema-veto witness.
            graph.tx().rollback();
          }
        }
      }
      var capture = new T5FreezeCapture.Capture(1, true, Map.of(arm, revision),
          request.inputHashes(), items);
      requireComplete(capture, inventory(request).stream()
          .filter(r -> r.key().arm().equals(arm)).toList());
      T5FreezeCapture.write(output, capture);
    }
  }

  static T5FreezeCapture.Item observe(Workload workload, Case query, String arm, String revision,
      T5FreezeCapture.Regime regime) throws Exception {
    requireUnforced();
    var graph = (YTDBGraphInternal) workload.source().getGraph();
    graph.tx().readWrite();
    var session = graph.tx().getDatabaseSession();
    boolean enabled = session.getConfiguration().getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    if (enabled != arm.equals("baseline")) {
      throw new IllegalStateException("Provider changed translator arm setting");
    }
    var cache = GremlinPlanCache.instance(session);
    var metrics = GremlinTranslationMetrics.of(session);
    cache.invalidate();
    long generation = cache.getGeneration();
    // Schema state is an existing, explicit cache veto. Rollback belongs to the provider's source.
    if (regime == T5FreezeCapture.Regime.UNCACHEABLE) {
      session.createVertexClass("T5UncacheableWitness" + generation);
      if (session.getTxSchemaState() == null) {
        throw new IllegalStateException("Schema veto did not engage");
      }
    }
    if (regime == T5FreezeCapture.Regime.WARM) {
      try (var warm = execution(workload, query)) {
        warm.toList();
      }
    }
    long hits = cache.getTranslationHits();
    long misses = cache.getTranslationMisses();
    long errors = metrics.getErrors();
    long successes = metrics.getSuccesses();
    long declines = metrics.getDeclines();
    long nativeCalls = metrics.getRuleNative();
    List<T5FreezeCapture.Value> rows;
    int boundaries;
    try (var traversal = execution(workload, query)) {
      traversal.asAdmin().applyStrategies();
      boundaries = GremlinTraversalShapes.countBoundarySteps(traversal.asAdmin());
      rows = traversal.toList().stream().map(v -> T5FreezeCapture.Value.of(logical(v, workload)))
          .toList();
    }
    if (metrics.getErrors() != errors || arm.equals("native") && boundaries != 0) {
      throw new IllegalStateException("Translation error or native MATCH boundary");
    }
    long hitDelta = cache.getTranslationHits() - hits;
    long missDelta = cache.getTranslationMisses() - misses;
    String bypass = arm.equals("native") ? "translator-disabled; zero translation lookups"
        : regime == T5FreezeCapture.Regime.UNCACHEABLE ? "schema-transaction veto"
            : "no translation lookup for this shape";
    var observation = observed(hitDelta, missDelta);
    if ((arm.equals("native") || regime == T5FreezeCapture.Regime.UNCACHEABLE)
        && observation != T5FreezeCapture.Observation.BYPASS) {
      throw new IllegalStateException("Cache veto unexpectedly performed a lookup");
    }
    String outcome = metrics.getSuccesses() > successes ? "match"
        : metrics.getRuleNative() > nativeCalls ? "rule-native"
            : metrics.getDeclines() > declines ? "decline" : "native";
    var declaration = workload.wholeNative(query)
        ? new T5FreezeCapture.Declaration(
            T5FreezeCapture.Value.of("whole-native-record-identifiers"),
            Map.of(), Map.of("all", "Record-identifier-bearing member"))
        : arm.equals("native") ? declare(workload, query)
            : new T5FreezeCapture.Declaration(T5FreezeCapture.Value.of("current-arm-only"),
                Map.of(),
                Map.of("all", "Prepared declarations require current core"));
    return new T5FreezeCapture.Item(key(query, arm, regime), query.feeds(), query.order(), rows,
        outcome, (workload.wholeNative(query) ? "whole-native record identifiers; " : "")
            + "boundary-count=" + boundaries,
        new T5FreezeCapture.Cache(revision,
            "invalidate generation=" + generation + "; hits=" + hitDelta + "; misses=" + missDelta,
            observation, observation == T5FreezeCapture.Observation.BYPASS ? bypass : ""),
        declaration, false, "", "");
  }

  private static Traversal<?, ?> execution(Workload workload, Case query) {
    var traversal = workload.traversal(query);
    if (workload.wholeNative(query)) {
      // Physical ids must not enter prepared identities or a translated prefix.
      var strategies = traversal.asAdmin().getStrategies().clone();
      strategies.removeStrategies(GremlinToMatchStrategy.class);
      traversal.asAdmin().setStrategies(strategies);
    }
    return traversal;
  }

  static T5FreezeCapture.Observation observed(long hits, long misses) {
    if (hits < 0 || misses < 0 || hits > 0 && misses > 0) {
      throw new IllegalStateException("Ambiguous cache observation");
    }
    return hits > 0 ? T5FreezeCapture.Observation.HIT
        : misses > 0 ? T5FreezeCapture.Observation.MISS : T5FreezeCapture.Observation.BYPASS;
  }

  static T5FreezeCapture.Declaration declare(Workload workload, Case query) throws Exception {
    var captured = new ArrayList<T5PreparedShapeProbe.Declaration>();
    try (var traversal = workload.traversal(query)) {
      // Clone strategies without closing a source that owns the provider's shared graph.
      var strategies = workload.source().getStrategies().clone();
      strategies.addStrategies(new T5PreparedShapeProbe(captured::add));
      traversal.asAdmin().setStrategies(strategies);
      traversal.asAdmin().applyStrategies();
    }
    if (captured.size() != 1) {
      throw new IllegalStateException("Missing or duplicate current declaration");
    }
    var d = captured.getFirst();
    var cuts = new LinkedHashMap<String, String>();
    var refused = new LinkedHashMap<String, String>();
    for (int cut = 1; cut < d.steps().size(); cut++) {
      if (d.completedUnits().contains(cut)) {
        cuts.put(Integer.toString(cut), "declared-not-validated");
      } else {
        refused.put(Integer.toString(cut), "inside recogniser unit or outside recognised prefix");
      }
    }
    return new T5FreezeCapture.Declaration(T5FreezeCapture.Value.of(List.of(
        d.steps().stream().map(T5BaselineRunner::identity).toList(), d.completedUnits())),
        cuts, refused);
  }

  private static Object identity(Object value) {
    if (value instanceof T5PreparedShapeProbe.Identity i) {
      return List.of(i.kind(), i.parts().stream().map(T5BaselineRunner::identity).toList());
    }
    return value;
  }

  static Object logical(Object value, Workload workload) {
    if (value instanceof Element e) {
      var key = workload.logicalKey(e);
      if (!key.label().equals(e.label())) {
        throw new IllegalArgumentException("Logical label mismatch");
      }
      return key;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(v -> logical(v, workload)).toList();
    }
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<Object, Object>();
      map.forEach((k, v) -> {
        Object key = logical(k, workload);
        if (result.containsKey(key)) {
          throw new IllegalArgumentException("Logical key collision");
        }
        result.put(key, logical(v, workload));
      });
      return result;
    }
    return value;
  }

  static ArrayList<T5FreezeCapture.Item> markDifferences(Request request,
      List<T5FreezeCapture.Item> items) {
    var result = new ArrayList<T5FreezeCapture.Item>();
    for (var item : items) {
      var query = request.cases().stream().filter(c -> c.identity().equals(item.key().identity())
          && c.binding().equals(item.key().binding())).findFirst().orElseThrow();
      if (!query.feeds().equals(item.feeds()) || query.order() != item.order()) {
        throw new IllegalArgumentException("Child changed resolved feeds or result order");
      }
      var cold = items.stream().filter(i -> i.key().equals(new T5FreezeCapture.Key(
          item.key().identity(), item.key().binding(), item.key().arm(),
          T5FreezeCapture.Regime.COLD)))
          .findFirst().orElseThrow();
      T5ResultComparison.requireEqual(cold.expected(), item.expected().rows(), e -> {
        throw new IllegalStateException("Physical element in capture");
      });
      boolean different = false;
      if (item.key().arm().equals("baseline")) {
        var reference = items.stream().filter(i -> i.key().equals(new T5FreezeCapture.Key(
            item.key().identity(), item.key().binding(), "native", item.key().regime())))
            .findFirst().orElseThrow();
        try {
          T5ResultComparison.requireEqual(reference.expected(), item.expected().rows(), e -> {
            throw new IllegalStateException("Physical element in capture");
          });
        } catch (AssertionError mismatch) {
          if (request.cases().stream().noneMatch(c -> c.identity().equals(item.key().identity())
              && c.binding().equals(item.key().binding()) && c.stepLabelFixDifference())) {
            throw mismatch;
          }
          different = true;
        }
      }
      result.add(new T5FreezeCapture.Item(item.key(), item.feeds(), item.order(), item.answers(),
          item.outcome(), item.reason(), item.cache(), item.declaration(), different,
          different ? "Pinned 6f180c31ee lacks T5.6 hasLabel step-label fix" : "", item.failure()));
    }
    return result;
  }

  static void requireComplete(T5FreezeCapture.Capture capture,
      List<T5FreezeCapture.Required> inventory) throws IOException {
    var check = T5FreezeCapture.check(capture, inventory);
    if (!check.complete()) {
      throw new IOException(String.join("\n", check.problems()));
    }
  }

  /** CLI: check capture request, or run request native-classpath baseline-classpath scratch output. */
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("Expected capture, check, run or child");
    }
    if (args[0].equals("capture") && args.length == 10) {
      System.setProperty("t5.dbRoot", args[1]);
      System.setProperty("t5.inputs", args[2]);
      var hashes = T5CatalogueWorkload.hashes(Path.of(args[2]), args[3]);
      var request = resolve(T5CatalogueWorkload.class.getName(), hashes, args[9]);
      inventory(request);
      JSON.writeValue(Path.of(args[8]).toFile(), request);
      run(request, List.of(new Arm("native", request.nativeRevision(), args[4]),
          new Arm("baseline", BASELINE, args[5])), Path.of(args[6]), Path.of(args[7]),
          Duration.ofHours(24));
    } else if (args[0].equals("check") && args.length == 3) {
      var request = JSON.readValue(Path.of(args[2]).toFile(), Request.class);
      requireComplete(T5FreezeCapture.read(Path.of(args[1])), inventory(request));
    } else if (args[0].equals("child") && args.length == 5) {
      child(JSON.readValue(Path.of(args[1]).toFile(), Request.class), args[2], args[3],
          Path.of(args[4]));
    } else if (args[0].equals("run") && args.length == 6) {
      var request = JSON.readValue(Path.of(args[1]).toFile(), Request.class);
      run(request, List.of(new Arm("native", request.nativeRevision(), args[2]),
          new Arm("baseline", BASELINE, args[3])), Path.of(args[4]), Path.of(args[5]),
          Duration.ofHours(2));
    } else {
      throw new IllegalArgumentException("Invalid command or arguments");
    }
  }

  private T5BaselineRunner() {
  }
}
