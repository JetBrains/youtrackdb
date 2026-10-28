package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Focused end-to-end detached-check benchmark. Each operation constructs a fresh query and drains
 * every result, including duplicate boundary vertices. Trial setup validates the executed plan and
 * exact result multiset without adding observation to the timed path. Run main with JMH arguments.
 *
 * <p>The hash threshold is also the future result-memory entry limit. Nested cases keep it positive
 * and force nested evaluation through the upstream minimum instead. The low-match case uses the
 * normal cost guards and has more distinct keys than that limit but fewer matching keys.
 *
 * <p>OR expectations live in DetachedChecksBenchmarkWitness.expectTranslatedOr. Change that one
 * expectation when OR translation lands. Keep the workloads and timing settings unchanged across
 * compared builds. The track 1 baseline measures translation work, not option A acceptance.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
public class DetachedChecksBenchmark extends DbTestBase {

  @Param({"EXISTS", "NOT", "SQL_NOT", "OR", "NOT_OR"})
  public String kind;

  @Param({"repeated-nested", "repeated-hash", "distinct-nested", "distinct-hash",
      "few", "limit", "low-match"})
  public String scenario;

  static final long KEY_LIMIT = 256;
  private YTDBGraphInternal graph;
  private Object oldThreshold;
  private Object oldMinimum;
  private int vertices;
  private int repetitions;
  private int matching;
  private final Map<Integer, Long> expected = new HashMap<>();
  private String sql;
  private long executionRowCount;
  DetachedChecksBenchmarkWitness witness;

  @Setup
  public void setup() throws Exception {
    oldThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    oldMinimum = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(KEY_LIMIT);
    // Hash-labelled pairs isolate execution paths. Low-match deliberately keeps real cost guards.
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(
        scenario.endsWith("hash") ? 0L : scenario.equals("low-match") ? 5L : Long.MAX_VALUE);
    vertices = scenario.equals("few") ? 4 : scenario.equals("low-match") ? 512
        : scenario.startsWith("distinct") ? 64 : 32;
    repetitions = scenario.equals("few") || scenario.startsWith("distinct") ? 1 : 16;
    matching = scenario.equals("low-match") ? 4 : vertices / 2;
    youTrackDB = createContext();
    databaseName = "detachedChecksBenchmark";
    dbType = calculateDbType();
    createDatabase(dbType);
    session.createVertexClass("BenchOuter");
    session.createVertexClass("BenchBoundary");
    session.createVertexClass("BenchTarget");
    session.createEdgeClass("BenchFan");
    session.createEdgeClass("BenchCheck");
    session.begin();
    var target = session.newVertex("BenchTarget");
    target.setProperty("x", 1);
    var rejected = session.newVertex("BenchTarget");
    rejected.setProperty("x", 0);
    expected.clear();
    for (int i = 0; i < vertices; i++) {
      var boundary = session.newVertex("BenchBoundary");
      boundary.setProperty("n", i);
      boundary.setProperty("name", i % 2 == 0 ? "Paris" : "London");
      if (scenario.equals("low-match")) {
        // Failed candidates make probing costly while the matching-key set stays small.
        for (int edge = 0; edge < 4; edge++) {
          boundary.addEdge(rejected, "BenchCheck");
        }
      }
      if (i < matching) {
        // Several matching edges expose accidental multiplication by a translated child.
        for (int edge = 0; edge < 3; edge++) {
          boundary.addEdge(target, "BenchCheck");
        }
      }
      for (int repeat = 0; repeat < repetitions; repeat++) {
        session.newVertex("BenchOuter").addEdge(boundary, "BenchFan");
      }
      boolean passes = switch (kind) {
        case "EXISTS" -> i < matching;
        case "NOT", "SQL_NOT" -> i >= matching;
        case "OR" -> i < matching || i % 2 == 0;
        case "NOT_OR" -> !(i < matching || i % 2 == 0);
        default -> throw new IllegalArgumentException(kind);
      };
      if (passes) {
        expected.put(i, (long) repetitions);
      }
    }
    session.commit();
    session.close();
    graph = (YTDBGraphInternal) pool.asGraph();
    graph.tx().open();
    session = graph.tx().getDatabaseSession();
    session.getConfiguration().setValue(
        GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
    sql = "MATCH {class:BenchOuter,as:o}.out('BenchFan'){class:BenchBoundary,as:b},"
        + " NOT {as:b}.out('BenchCheck'){as:t,where:(x=1)} RETURN b.n as n"
        + (scenario.equals("limit") ? " LIMIT 1" : "");
    validateFixture();
    witness = observe();
    witness.verify(kind, scenario, expected, scenario.equals("limit"));
    System.out.println("DETACHED_WITNESS " + kind + "/" + scenario + " " + witness);
  }

  /** Prove actual fan-in, rather than infer repetition from the fixture construction loop. */
  private void validateFixture() throws Exception {
    var frequencies = new HashMap<Integer, Long>();
    try (var traversal = graph.traversal().V().hasLabel("BenchOuter").out("BenchFan")
        .values("n")) {
      while (traversal.hasNext()) {
        frequencies.merge(((Number) traversal.next()).intValue(), 1L, Long::sum);
      }
    }
    assertThat(frequencies).hasSize(vertices);
    assertThat(frequencies.values()).containsOnly((long) repetitions);
    if (scenario.startsWith("repeated") || scenario.equals("low-match")) {
      assertThat(repetitions).isGreaterThanOrEqualTo(10);
    }
    if (scenario.equals("low-match")) {
      assertThat(vertices).isGreaterThan((int) KEY_LIMIT);
      assertThat(matching).isLessThan((int) KEY_LIMIT);
      assertThat((double) matching / vertices).isLessThan(0.01);
    }
  }

  private Traversal.Admin<?, ?> traversal() {
    var traversal = graph.traversal().V().hasLabel("BenchOuter").out("BenchFan")
        .hasLabel("BenchBoundary");
    switch (kind) {
      case "EXISTS" -> traversal.where(__.out("BenchCheck").has("x", 1));
      case "NOT" -> traversal.not(__.out("BenchCheck").has("x", 1));
      case "OR" -> traversal.or(__.out("BenchCheck").has("x", 1), __.has("name", "Paris"));
      case "NOT_OR" -> traversal.not(__.or(__.out("BenchCheck").has("x", 1),
          __.has("name", "Paris")));
      default -> throw new IllegalArgumentException(kind);
    }
    if (scenario.equals("limit")) {
      traversal.limit(1);
    }
    return traversal.values("n").asAdmin();
  }

  private DetachedChecksBenchmarkWitness observe() throws Exception {
    if (kind.equals("SQL_NOT")) {
      try (var results = session.query(sql)) {
        var rows = new HashMap<Integer, Long>();
        assertThat(results.hasNext()).isTrue();
        var observed = DetachedChecksBenchmarkWitness.sql(
            results.getExecutionPlan(), rows);
        while (results.hasNext()) {
          Number n = results.next().getProperty("n");
          rows.merge(n.intValue(), 1L, Long::sum);
          observed.observeMemory();
        }
        return observed;
      }
    }
    try (var traversal = traversal()) {
      traversal.applyStrategies();
      var rows = new HashMap<Integer, Long>();
      assertThat(traversal.hasNext()).isTrue();
      var observed = DetachedChecksBenchmarkWitness.gremlin(traversal, rows);
      while (traversal.hasNext()) {
        rows.merge(((Number) traversal.next()).intValue(), 1L, Long::sum);
        observed.observeMemory();
      }
      return observed;
    }
  }

  /** Validate timed results independently from the observation drain, without assuming row order. */
  void verifyExecutionChecksum(long checksum) {
    if (scenario.equals("limit")) {
      assertThat(executionRowCount).as("timed row count").isEqualTo(1);
      assertThat(expected.keySet().stream().mapToLong(DetachedChecksBenchmark::mixRow).toArray())
          .as("hash of one fixture-valid row").contains(checksum);
    } else {
      assertThat(executionRowCount).as("timed row count")
          .isEqualTo(expected.values().stream().mapToLong(Long::longValue).sum());
      long expectedChecksum = expected.entrySet().stream()
          .mapToLong(entry -> mixRow(entry.getKey()) * entry.getValue())
          .sum();
      assertThat(checksum).as("timed multiset hash").isEqualTo(expectedChecksum);
    }
  }

  /**
   * SplitMix64 mixing makes balanced value substitutions visible, with negligible collision risk.
   * Adding the seed also gives zero-valued rows a nonzero contribution. Hash sums wrap deliberately
   * and preserve multiplicity without depending on result order.
   */
  private static long mixRow(long value) {
    long mixed = value + 0x9e3779b97f4a7c15L;
    mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
    mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
    return mixed ^ (mixed >>> 31);
  }

  /** Time query construction, cached planning, execution and draining, with no witness machinery. */
  @Benchmark
  public long execute() throws Exception {
    long checksum = 0;
    long rowCount = 0;
    if (kind.equals("SQL_NOT")) {
      try (var results = session.query(sql)) {
        while (results.hasNext()) {
          Number n = results.next().getProperty("n");
          checksum += mixRow(n.longValue());
          rowCount++;
        }
      }
    } else {
      try (var traversal = traversal()) {
        while (traversal.hasNext()) {
          checksum += mixRow(((Number) traversal.next()).longValue());
          rowCount++;
        }
      }
    }
    executionRowCount = rowCount;
    return checksum;
  }

  @TearDown
  public void teardown() {
    try {
      if (graph != null) {
        graph.tx().rollback();
        graph.close();
      }
      afterTest();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(oldThreshold);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(oldMinimum);
    }
  }

  public static void main(String[] args) throws Exception {
    new Runner(new OptionsBuilder().parent(new CommandLineOptions(args))
        .include(DetachedChecksBenchmark.class.getSimpleName() + ".execute")
        .shouldFailOnError(true).build()).run();
  }
}
