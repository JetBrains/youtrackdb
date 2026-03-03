package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH benchmark measuring the full BTree.get() path (lock acquire, tree traversal with
 * in-buffer comparison, page load, value retrieval, lock release). Uses a MEMORY database
 * to avoid disk I/O noise so that the measurement focuses purely on the CPU cost of the
 * B-tree search.
 *
 * <p>Run with async-profiler to profile allocations:
 * <pre>
 *   java -jar benchmarks.jar BTreeGetBenchmark \
 *     -prof "async:event=alloc;libPath=/opt/async-profiler/lib/libasyncProfiler.so;
 *            dir=/tmp/alloc-profiles"
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class BTreeGetBenchmark {

  @Param({"10000", "100000"})
  public int keyCount;

  @Param({"INTEGER", "UTF8", "COMPOSITE_INT_STRING"})
  public KeyType keyType;

  private YouTrackDBImpl youTrackDB;
  private AbstractStorage storage;
  private AtomicOperationsManager atomicOperationsManager;
  private BTree<Object> tree;

  // Pre-generated lookup keys (all exist in the tree)
  private Object[] lookupKeys;
  private Random random;

  @SuppressWarnings("unchecked")
  @Setup(Level.Trial)
  public void setUp() throws Exception {
    random = new Random(42);

    // Create a MEMORY database to eliminate disk I/O
    var buildDir = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath()
        .resolve("databases")
        .resolve("BTreeGetBenchmark");
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDir);

    var dbName = "benchDb_" + keyType + "_" + keyCount;
    if (youTrackDB.exists(dbName)) {
      youTrackDB.drop(dbName);
    }
    youTrackDB.create(dbName, DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));

    try (var session = youTrackDB.open(dbName, "admin", "adminpwd")) {
      storage = session.getStorage();
    }

    atomicOperationsManager = storage.getAtomicOperationsManager();

    // Create and populate the B-tree
    tree = new BTree<>("benchTree", ".sbt", ".nbt", storage);
    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> tree.create(
            atomicOperation,
            keyType.serializer(),
            keyType.keyTypes(),
            keyType.keySize()));

    // Insert keys in batches of 1000 inside atomic operations
    var keys = keyType.generateKeys(keyCount, random);
    lookupKeys = keys;

    var batchSize = 1000;
    for (var batchStart = 0; batchStart < keys.length; batchStart += batchSize) {
      final var start = batchStart;
      final var end = Math.min(batchStart + batchSize, keys.length);
      atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
        for (var i = start; i < end; i++) {
          tree.put(atomicOperation, keys[i],
              new RecordId(i % 32000, i));
        }
      });
    }

    System.out.println("[Setup] keyType=" + keyType
        + ", keyCount=" + keyCount
        + ", treeSize=" + getTreeSize());
  }

  private long getTreeSize() throws Exception {
    final long[] size = {0};
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      size[0] = tree.size(atomicOperation);
    });
    return size[0];
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    youTrackDB.close();
  }

  // =====================================================================
  // Benchmark: full BTree.get() path
  // =====================================================================

  /**
   * Measures the full BTree.get() path: lock acquire -> key preprocessing ->
   * key serialization -> findBucketSerialized (in-buffer comparison) ->
   * getValue -> lock release.
   */
  @Benchmark
  public void btreeGet(Blackhole bh) throws Exception {
    var idx = random.nextInt(lookupKeys.length);
    final var key = lookupKeys[idx];
    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      RID result = tree.get(key, atomicOperation);
      bh.consume(result);
    });
  }

  // =====================================================================
  // Key type configuration
  // =====================================================================

  @SuppressWarnings("unchecked")
  public enum KeyType {
    INTEGER {
      @Override
      <K> com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<K>
      serializer() {
        return (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<K>)
            (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<?>)
                IntegerSerializer.INSTANCE;
      }

      @Override
      PropertyTypeInternal[] keyTypes() {
        return null;
      }

      @Override
      int keySize() {
        return 1;
      }

      @Override
      Object[] generateKeys(int count, Random rng) {
        // Generate unique integer keys
        var set = new java.util.HashSet<Integer>(count * 2);
        while (set.size() < count) {
          set.add(rng.nextInt());
        }
        return set.toArray();
      }
    },

    UTF8 {
      @Override
      <K> com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<K>
      serializer() {
        return (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<K>)
            (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<?>)
                UTF8Serializer.INSTANCE;
      }

      @Override
      PropertyTypeInternal[] keyTypes() {
        return null;
      }

      @Override
      int keySize() {
        return 1;
      }

      @Override
      Object[] generateKeys(int count, Random rng) {
        var set = new java.util.HashSet<String>(count * 2);
        while (set.size() < count) {
          set.add(randomString(rng, 8 + rng.nextInt(8)));
        }
        return set.toArray();
      }
    },

    COMPOSITE_INT_STRING {
      @Override
      <K> com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<K>
      serializer() {
        return (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<K>)
            (com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<?>)
                CompositeKeySerializer.INSTANCE;
      }

      @Override
      PropertyTypeInternal[] keyTypes() {
        return new PropertyTypeInternal[]{
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
      }

      @Override
      int keySize() {
        return 2;
      }

      @Override
      Object[] generateKeys(int count, Random rng) {
        var set = new java.util.HashSet<CompositeKey>(count * 2);
        while (set.size() < count) {
          set.add(new CompositeKey(
              rng.nextInt(count / 10 + 1),
              randomString(rng, 6 + rng.nextInt(4))));
        }
        return set.toArray();
      }
    };

    abstract <K>
    com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer<K> serializer();

    abstract PropertyTypeInternal[] keyTypes();

    abstract int keySize();

    abstract Object[] generateKeys(int count, Random rng);

    static String randomString(Random rng, int len) {
      var sb = new StringBuilder(len);
      for (var i = 0; i < len; i++) {
        sb.append((char) ('a' + rng.nextInt(26)));
      }
      return sb.toString();
    }
  }

  // =====================================================================
  // Runner
  // =====================================================================

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(BTreeGetBenchmark.class.getSimpleName())
        .addProfiler(AsyncProfiler.class,
            "event=alloc;dir=/tmp/claude-code-btree-alloc-profiles;"
                + "libPath=/opt/async-profiler/lib/libasyncProfiler.so")
        .build();
    new Runner(opt).run();
  }
}
