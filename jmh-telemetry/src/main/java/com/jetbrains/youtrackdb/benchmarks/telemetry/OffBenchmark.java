package com.jetbrains.youtrackdb.benchmarks.telemetry;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinToMatchStrategy;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.GremlinTranslationMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

/// Complete read calls on deterministic, unindexed vertices. Each worker owns its database.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(3)
public class OffBenchmark {

  static final String SQL = "select from Fixture";
  static final String LABEL = "Fixture";

  @Benchmark
  public long sql(Blackhole blackhole, Fixture fixture) throws Exception {
    return fixture.sql(blackhole);
  }

  @Benchmark
  public long translated(Blackhole blackhole, PathCheck check) throws Exception {
    return check.fixture.gremlin(true, blackhole);
  }

  @Benchmark
  public long fallback(Blackhole blackhole, PathCheck check) throws Exception {
    return check.fixture.gremlin(false, blackhole);
  }

  static void requirePath(boolean translated, long boundaries) {
    if (boundaries != (translated ? 1 : 0)) {
      throw new IllegalStateException("Unexpected translation boundary count: " + boundaries);
    }
  }

  static void requireCount(long expected, long actual) {
    if (expected != actual) {
      throw new IllegalStateException("Expected " + expected + " rows, got " + actual);
    }
  }

  // The iterator and its owning resource are separate because SQL and Gremlin expose
  // different resource types. try-with-resources also closes on iteration failure.
  static long consume(Iterator<?> iterator, AutoCloseable resource, Blackhole blackhole)
      throws Exception {
    try (resource) {
      long count = 0;
      while (iterator.hasNext()) {
        blackhole.consume(iterator.next());
        count++;
      }
      return count;
    }
  }

  /// Explicit database state shared by entry points and their iteration checks.
  @State(Scope.Thread)
  public static class Fixture {

    @Param({"off"})
    public String telemetryScenario;

    // One-row tiny query and a full scan use the same schema and operation on all paths.
    @Param({"1", "10000"})
    public int rows;

    private Path directory;
    private YouTrackDB db;
    private YTDBGraphTraversalSource g;
    private YTDBTransaction tx;
    private GraphTraversal<?, ?> executedTraversal;
    private GremlinTranslationMetrics translationMetrics;

    @Setup
    public void setup() throws Exception {
      if (!"off".equals(telemetryScenario)) {
        throw new IllegalArgumentException("Only the off scenario exists");
      }
      if (GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean()) {
        throw new IllegalStateException("Off requires the global query-result cache disabled");
      }
      directory = Files.createTempDirectory("ytdb-telemetry-");
      try {
        db = YourTracks.instance(directory.toString());
        db.create("fixture", DatabaseType.MEMORY, "admin", "admin", "admin");
        g = db.openTraversal("fixture", "admin", "admin");
        tx = (YTDBTransaction) g.tx();
        for (int i = 0; i < rows; i++) {
          g.addV(LABEL).property("ordinal", i).iterate();
        }
        tx.commit();
        tx.open();
        var session = tx.getDatabaseSession();
        translationMetrics = GremlinTranslationMetrics.of(session);
        session.getConfiguration().setValue(
            GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, true);
        if (tx.isQueryMetricsEnabled() || tx.isTransactionMetricsEnabled()) {
          throw new IllegalStateException("Off requires no monitoring listeners");
        }
        tx.rollback();
        var blackhole = new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        requireCount(rows, sql(blackhole));
        requireCount(rows, gremlin(true, blackhole));
        verifyExecutedPath(true);
        requireCount(rows, gremlin(false, blackhole));
        verifyExecutedPath(false);
        executedTraversal = null;
      } catch (Exception failure) {
        close();
        throw failure;
      }
    }

    GraphTraversal<?, ?> traversal(boolean translated) {
      var source = translated ? g : g.withoutStrategies(GremlinToMatchStrategy.class);
      return source.V().hasLabel(LABEL);
    }

    // Inspect the exact consumed traversal after timing, not a separately compiled sample.
    void verifyExecutedPath(boolean translated) {
      if (executedTraversal == null) {
        throw new IllegalStateException("No Gremlin call executed");
      }
      long boundaries = executedTraversal.asAdmin().getSteps().stream()
          .filter(YTDBMatchPlanStep.class::isInstance).count();
      requirePath(translated, boundaries);
    }

    boolean isTransactionOpen() {
      return tx.isOpen();
    }

    record Cursor(Iterator<?> iterator, AutoCloseable resource) {
    }

    Cursor sqlResult() {
      var result = tx.getDatabaseSession().query(SQL);
      return new Cursor(result, result);
    }

    long sql(Blackhole blackhole) throws Exception {
      tx.open();
      try {
        var result = sqlResult();
        return consume(result.iterator(), result.resource(), blackhole);
      } finally {
        tx.rollback();
      }
    }

    private long gremlin(boolean translated, Blackhole blackhole) throws Exception {
      tx.open();
      try {
        var traversal = traversal(translated);
        executedTraversal = traversal;
        return consume(traversal, traversal, blackhole);
      } finally {
        tx.rollback();
      }
    }

    @TearDown
    public void close() throws Exception {
      try {
        if (g != null) {
          g.close();
        }
      } finally {
        if (db != null) {
          db.close();
        }
        if (directory != null) {
          try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
              Files.delete(path);
            }
          }
        }
      }
    }
  }

  /// Only Gremlin benchmarks request this state. SQL has no path-validation fixtures.
  @State(Scope.Thread)
  public static class PathCheck {

    private Fixture fixture;
    private boolean translated;
    private long successes;
    private long declines;
    private long errors;

    @Setup(Level.Iteration)
    public void setup(Fixture fixture, BenchmarkParams params) {
      start(fixture, params.getBenchmark().endsWith(".translated"));
    }

    void start(Fixture fixture, boolean translated) {
      this.fixture = fixture;
      this.translated = translated;
      fixture.executedTraversal = null;
      var metrics = fixture.translationMetrics;
      successes = metrics.getSuccesses();
      declines = metrics.getDeclines();
      errors = metrics.getErrors();
    }

    // Existing database-local strategy counters retain transient failures even if the last
    // call succeeds. Reading them once per iteration adds no measured per-call bookkeeping.
    @TearDown(Level.Iteration)
    public void verify() {
      var metrics = fixture.translationMetrics;
      requireCount(declines, metrics.getDeclines());
      requireCount(errors, metrics.getErrors());
      if (!translated) {
        requireCount(successes, metrics.getSuccesses());
      }
      fixture.verifyExecutedPath(translated);
    }
  }

}
