package com.jetbrains.youtrack.db.internal.lucene.benchmark;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import java.io.IOException;
import java.io.InputStream;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Measurement(iterations = 3, batchSize = 1)
@Warmup(iterations = 3, batchSize = 1)
@Fork(3)
public class FulltextIndexFunctionBenchmark {

  public static void main(String[] args) throws RunnerException {
    final var opt =
        new OptionsBuilder()
            .include("FulltextIndexFunctionBenchmark.*")
            // .addProfiler(StackProfiler.class, "detailLine=true;excludePackages=true;period=1")
            .jvmArgs("-server", "-XX:+UseConcMarkSweepGC", "-Xmx4G", "-Xms1G")
            // .result("target" + "/" + "results.csv")
            // .param("offHeapMessages", "true""
            // .resultFormat(ResultFormatType.CSV)
            .build();
    new Runner(opt).run();
  }

  private DatabaseSessionInternal db;
  private YouTrackDB context;
  private DatabaseType type;

  private final String name = "lucene-benchmark";

  @Setup(Level.Iteration)
  public void setup() {
    this.setupDatabase();

    final var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");
    db.runScript("sql", getScriptFromStream(stream));
    db.execute("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE ");
    db.execute("create index Song.author on Song (author) FULLTEXT ENGINE LUCENE ");
    db.execute(
        "create index Song.lyrics_description on Song (lyrics,description) FULLTEXT ENGINE LUCENE"
            + " ");
  }

  private void setupDatabase() {
    final var config =
        System.getProperty("youtrackdb.test.env", DatabaseType.MEMORY.name().toLowerCase());
    var path = DbTestBase.embeddedDBUrl(getClass());
    if ("ci".equals(config) || "release".equals(config)) {
      type = DatabaseType.DISK;
    } else {
      type = DatabaseType.MEMORY;
    }
    context = new YouTrackDBImpl(path, YouTrackDBConfig.defaultConfig());

    if (context.exists(name)) {
      context.drop(name);
    }

    context.execute(
        "create database " + name + " disk users ( admin identified by 'admin' role admin)");

    db = (DatabaseSessionInternal) context.open(name, "admin", "admin");
  }

  private String getScriptFromStream(final InputStream scriptStream) {
    try {
      return IOUtils.readStreamAsString(scriptStream);
    } catch (final IOException e) {
      throw new RuntimeException("Could not read script stream.", e);
    }
  }

  @TearDown(Level.Iteration)
  public void tearDown() {
    db.activateOnCurrentThread();
    context.drop(name);
  }

  @Benchmark
  public void searchOnSingleField() {
    final var resultSet =
        db.query("SELECT from Song where SEARCH_FIELDS(['title'], 'BELIEVE') = true");
    resultSet.close();
  }

  @Benchmark
  public void searhOnTwoFieldsInOR() {
    final var resultSet =
        db.query(
            "SELECT from Song where SEARCH_FIELDS(['title'], 'BELIEVE') = true OR"
                + " SEARCH_FIELDS(['author'], 'Bob') = true ");
    resultSet.close();
  }

  @Benchmark
  public void searhOnTwoFieldsInAND() throws Exception {
    final var resultSet =
        db.query(
            "SELECT from Song where SEARCH_FIELDS(['title'], 'tambourine') = true AND"
                + " SEARCH_FIELDS(['author'], 'Bob') = true ");
    resultSet.close();
  }
}
