package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Measures whether database-local null placement costs more to read than process-global placement.
 * Only the three resolution arms answer this question.
 *
 * <p>This command produces a quotable result with the declared harness settings.
 *
 * <pre>{@code
 * ./mvnw -pl core exec:exec -Dexec.classpathScope=test -Dexec.executable=java \
 *   -Dexec.args='-cp %classpath com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsResolutionBenchmark'
 * }</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(5)
@Threads(1)
public class OrderByNullsResolutionBenchmark {

  private static final String DB_NAME = "orderByNullsBenchDb";
  private static final String CLASS_NAME = "SortableRecord";
  private static final String ORDERED_QUERY =
      "SELECT score FROM " + CLASS_NAME + " ORDER BY score ASC";
  private static final int RECORD_COUNT = 8;

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private ContextConfiguration localConfig;
  private ContextConfiguration globalFallbackConfig;
  private Object previousAscending;
  private Object previousDescending;
  private boolean ascendingWasChanged;
  private boolean descendingWasChanged;
  private boolean globalsSaved;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    saveGlobals();
    globalsSaved = true;
    try {
      // Deliberately oppose the layers so a missing local lookup fails this setup check.
      GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC.setValue(OrderByNullsPlacement.LAST);
      GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC.setValue(OrderByNullsPlacement.FIRST);

      youTrackDB = (YouTrackDBImpl) YourTracks.instance(
          DbTestBase.getBaseDirectoryPath(OrderByNullsResolutionBenchmark.class));
      createDatabase();
      session = youTrackDB.open(DB_NAME, "admin", "adminpwd");
      createFixture();

      localConfig = session.getConfiguration();
      localConfig.setValue(
          GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC, OrderByNullsPlacement.FIRST);
      localConfig.setValue(
          GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC, OrderByNullsPlacement.LAST);
      globalFallbackConfig = new ContextConfiguration();
      verifySentinelPlacements();
    } catch (Throwable failure) {
      try {
        cleanup();
      } catch (Throwable cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throwFailure(failure);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    cleanup();
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void resolveWithLocalValues(Blackhole bh) {
    bh.consume(OrderByNullsUtil.resolvePlacements(localConfig));
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void resolveWithGlobalFallback(Blackhole bh) {
    bh.consume(OrderByNullsUtil.resolvePlacements(globalFallbackConfig));
  }

  /** This floor still performs two process-global volatile reads. It is not a zero-read path. */
  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void resolveWithoutLocalLayer(Blackhole bh) {
    bh.consume(OrderByNullsUtil.resolvePlacements(null));
  }

  /**
   * Provides whole-query context. This arm cannot discriminate placement sources because their cost
   * is far below query run-to-run noise.
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public void orderedQuery(Blackhole bh) {
    var tx = session.begin();
    try (var result = session.query(ORDERED_QUERY)) {
      while (result.hasNext()) {
        bh.consume(result.next());
      }
      tx.commit();
    }
  }

  private void saveGlobals() {
    previousAscending = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC.getValue();
    previousDescending = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC.getValue();
    ascendingWasChanged = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC.isChanged();
    descendingWasChanged = GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC.isChanged();
  }

  private void verifySentinelPlacements() {
    var local = OrderByNullsUtil.resolvePlacements(localConfig);
    var globalFallback = OrderByNullsUtil.resolvePlacements(globalFallbackConfig);
    var withoutLocalLayer = OrderByNullsUtil.resolvePlacements(null);
    if (local.equals(globalFallback)
        || !ResolvedOrderByNullsPlacement.SHIPPED.equals(local)
        || !ResolvedOrderByNullsPlacement.REVERSED.equals(globalFallback)
        || !ResolvedOrderByNullsPlacement.REVERSED.equals(withoutLocalLayer)) {
      throw new IllegalStateException(
          "Null placement sentinels did not resolve through their expected layers");
    }
  }

  private void cleanup() throws Exception {
    Throwable failure = null;
    var sessionToClose = session;
    session = null;
    if (sessionToClose != null) {
      try {
        sessionToClose.close();
      } catch (Throwable closeFailure) {
        failure = closeFailure;
      }
    }

    var instanceToClose = youTrackDB;
    youTrackDB = null;
    if (instanceToClose != null) {
      try {
        instanceToClose.close();
      } catch (Throwable closeFailure) {
        failure = addFailure(failure, closeFailure);
      }
    }

    if (globalsSaved) {
      globalsSaved = false;
      try {
        restoreGlobal(
            GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_ASC,
            previousAscending,
            ascendingWasChanged);
      } catch (Throwable restoreFailure) {
        failure = addFailure(failure, restoreFailure);
      }
      try {
        restoreGlobal(
            GlobalConfiguration.QUERY_ORDER_BY_NULLS_PLACEMENT_DESC,
            previousDescending,
            descendingWasChanged);
      } catch (Throwable restoreFailure) {
        failure = addFailure(failure, restoreFailure);
      }
    }

    if (failure != null) {
      throwFailure(failure);
    }
  }

  private static Throwable addFailure(Throwable failure, Throwable nextFailure) {
    if (failure == null) {
      return nextFailure;
    }
    failure.addSuppressed(nextFailure);
    return failure;
  }

  private static void throwFailure(Throwable failure) throws Exception {
    if (failure instanceof Exception exception) {
      throw exception;
    }
    throw (Error) failure;
  }

  private static void restoreGlobal(
      GlobalConfiguration configuration, Object previousValue, boolean wasChanged) {
    if (wasChanged) {
      configuration.setValue(previousValue);
    } else {
      configuration.resetToDefault();
    }
  }

  private void createDatabase() {
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(
        DB_NAME,
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
  }

  private void createFixture() {
    session.getMetadata().getSchema().createClass(CLASS_NAME);
    session.begin();
    for (var i = 0; i < RECORD_COUNT; i++) {
      var record = session.newEntity(CLASS_NAME);
      if (i % 2 == 0) {
        record.setProperty("score", i);
      }
    }
    session.commit();
  }

  public static void main(String[] args) throws Exception {
    var options = new OptionsBuilder()
        .parent(new CommandLineOptions(args))
        .include(OrderByNullsResolutionBenchmark.class.getSimpleName())
        .build();
    new Runner(options).run();
  }
}
