package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH benchmark for direct {@code engine.get()} on a UNIQUE single-value index.
 *
 * <p>Populates an index with {@code indexSize} entries, then measures random-access
 * point lookups via the engine API, bypassing SQL parsing and transaction overhead.
 * This isolates the raw index lookup cost (getVisible on HEAD vs stream pipeline
 * on earlier commits).
 *
 * <p>Usage:
 * <pre>
 * # Build and run
 * ./mvnw -pl core -am clean package -DskipTests
 * java -jar core/target/benchmarks.jar IndexGetBenchmark
 *
 * # Or via Maven exec
 * ./mvnw -pl core -am compile exec:exec \
 *   -Dexec.args="IndexGetBenchmark"
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 3, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.x509=ALL-UNNAMED",
    "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
})
public class IndexGetBenchmark {

  @State(Scope.Benchmark)
  public static class IndexState {

    @Param({"1000000"})
    int indexSize;

    @Param({"DISK"})
    String storageType;

    YouTrackDBImpl youTrackDB;
    DatabaseSessionEmbedded db;
    V1IndexEngine engine;
    AtomicOperationsManager atomicMgr;

    // Pre-shuffled key array for random access without ThreadLocalRandom per op
    int[] shuffledKeys;
    int keyIndex;

    @Setup(Level.Trial)
    public void setup() throws Exception {
      var dbType = DatabaseType.valueOf(storageType);
      var dbPath = Path.of(System.getProperty("buildDirectory", "./target"))
          .toAbsolutePath()
          .resolve("databases")
          .resolve("IndexGetBenchmark-" + System.nanoTime());

      youTrackDB = (YouTrackDBImpl) YourTracks.instance(dbPath.toString());
      youTrackDB.create("bench", dbType,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
      db = youTrackDB.open("bench", "admin", "adminpwd");

      // Schema: vertex class with a UNIQUE indexed integer property
      var counter = db.createVertexClass("Counter");
      counter.createProperty("likes", PropertyType.INTEGER);
      counter.createIndex(
          "CounterLikesIdx",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[] {"likes"});

      // Populate in batches of 1000
      for (int batch = 0; batch < indexSize; batch += 1000) {
        var tx = db.begin();
        int end = Math.min(batch + 1000, indexSize);
        for (int i = batch; i < end; i++) {
          var v = tx.newVertex(counter);
          v.setProperty("likes", i);
        }
        tx.commit();
      }

      // Get direct references
      var storage = db.getStorage();
      var idx = (IndexAbstract) db.getIndex("CounterLikesIdx");
      engine = (V1IndexEngine) storage.getIndexEngine(idx.getIndexId());
      atomicMgr = storage.getAtomicOperationsManager();

      // Build shuffled key array
      var keys = new ArrayList<Integer>(indexSize);
      for (int i = 0; i < indexSize; i++) {
        keys.add(i);
      }
      Collections.shuffle(keys);
      shuffledKeys = keys.stream().mapToInt(Integer::intValue).toArray();
      keyIndex = 0;

      // Warm up JIT on the engine.get() path
      atomicMgr.executeInsideAtomicOperation(atomicOp -> {
        for (int i = 0; i < Math.min(10_000, indexSize); i++) {
          try (Stream<RID> result = engine.get(shuffledKeys[i], atomicOp)) {
            result.findFirst().orElseThrow();
          }
        }
      });
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      if (db != null) {
        db.close();
      }
      if (youTrackDB != null) {
        youTrackDB.close();
      }
    }

    int nextKey() {
      // Wrap around the shuffled array — each invocation gets a different key
      int idx = keyIndex++;
      if (idx >= shuffledKeys.length) {
        keyIndex = 1;
        idx = 0;
      }
      return shuffledKeys[idx];
    }
  }

  /**
   * Single random-access engine.get() lookup inside an atomic operation.
   * Each invocation does one lookup with a random key from the pre-shuffled array.
   */
  @Benchmark
  public void engineGet(IndexState state, Blackhole bh) throws Exception {
    state.atomicMgr.executeInsideAtomicOperation(atomicOp -> {
      int key = state.nextKey();
      try (Stream<RID> result = state.engine.get(key, atomicOp)) {
        bh.consume(result.findFirst().orElseThrow());
      }
    });
  }

  /**
   * Batch of 1000 engine.get() lookups inside a single atomic operation.
   * Amortizes atomic operation overhead to measure pure lookup throughput.
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public void engineGetBatch1k(IndexState state, Blackhole bh) throws Exception {
    state.atomicMgr.executeInsideAtomicOperation(atomicOp -> {
      for (int i = 0; i < 1000; i++) {
        int key = state.nextKey();
        try (Stream<RID> result = state.engine.get(key, atomicOp)) {
          bh.consume(result.findFirst().orElseThrow());
        }
      }
    });
  }

  public static void main(String[] args) throws RunnerException {
    new Runner(
        new OptionsBuilder()
            .include(IndexGetBenchmark.class.getSimpleName())
            .build())
        .run();
  }
}
