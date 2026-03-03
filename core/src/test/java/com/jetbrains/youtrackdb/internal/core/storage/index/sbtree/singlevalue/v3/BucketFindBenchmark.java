package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.ShortSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.UTF8Serializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.impl.index.CompositeKeySerializer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import java.util.Random;
import java.util.TreeSet;
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
 * JMH benchmark comparing the old deserialization-based bucket find (find(K, ...)) against the
 * new zero-allocation in-buffer find (find(byte[], ...)). Each benchmark populates a single
 * leaf page bucket with sorted keys, then performs repeated lookups of random existing keys.
 *
 * <p>Run with async-profiler alloc event to verify allocation elimination:
 * <pre>
 *   java -jar benchmarks.jar BucketFindBenchmark \
 *     -prof "async:event=alloc;output=flamegraph;dir=/tmp/alloc-profiles"
 * </pre>
 *
 * <p>Or use the main() method which configures async-profiler automatically.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class BucketFindBenchmark {

  /**
   * Key type to benchmark. Each type exercises a different compareInByteBuffer() override.
   */
  @Param({"INTEGER", "STRING", "UTF8", "COMPOSITE_INT_STRING"})
  public KeyType keyType;

  // --- Bucket state (shared across iterations within a fork) ---
  private CachePointer cachePointer;
  private CacheEntry cacheEntry;
  private CellBTreeSingleValueBucketV3<Object> bucket;
  private BinarySerializerFactory serializerFactory;

  // Keys stored in the bucket, used for lookups
  private Object[] lookupKeys;
  // Pre-serialized versions of the same keys
  private byte[][] serializedLookupKeys;
  // The serializer for the key type
  private BinarySerializer<Object> keySerializer;
  // Key type hints (for composite keys)
  private Object[] keyTypeHints;

  // Random index for each invocation
  private Random random;

  @SuppressWarnings("unchecked")
  @Setup(Level.Trial)
  public void setUp() {
    serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
    random = new Random(42);

    keySerializer = (BinarySerializer<Object>) keyType.serializer();
    keyTypeHints = keyType.hints();

    // Allocate a direct memory page for the bucket
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();

    bucket = new CellBTreeSingleValueBucketV3<>(cacheEntry);
    bucket.init(true); // leaf bucket

    // Generate sorted unique keys and populate the bucket
    var sortedKeys = keyType.generateSortedKeys(random);
    var ridBytes = new byte[ShortSerializer.SHORT_SIZE + com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer.LONG_SIZE];

    var insertedCount = 0;
    for (var key : sortedKeys) {
      var serializedKey = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, keyTypeHints);

      // Serialize a dummy RID value
      ShortSerializer.INSTANCE.serializeNative((short) 0, ridBytes, 0);
      com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer.serializeNative(
          insertedCount, ridBytes, ShortSerializer.SHORT_SIZE);

      if (!bucket.addLeafEntry(insertedCount, serializedKey, ridBytes)) {
        break; // page is full
      }
      insertedCount++;
    }

    // Prepare lookup keys (all keys that were actually inserted)
    lookupKeys = new Object[insertedCount];
    serializedLookupKeys = new byte[insertedCount][];
    var iter = sortedKeys.iterator();
    for (var i = 0; i < insertedCount; i++) {
      var key = iter.next();
      lookupKeys[i] = key;
      serializedLookupKeys[i] = keySerializer.serializeNativeAsWhole(
          serializerFactory, key, keyTypeHints);
    }

    System.out.println("[Setup] keyType=" + keyType + ", keysInBucket=" + insertedCount);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    cacheEntry.releaseExclusiveLock();
    cachePointer.decrementReferrer();
  }

  // =====================================================================
  // Benchmarks
  // =====================================================================

  /**
   * OLD path: deserialize every key during binary search (find(K, ...)).
   * This creates a Java object (Integer, String, CompositeKey) for each comparison.
   */
  @Benchmark
  public void findDeserializing(Blackhole bh) {
    var idx = random.nextInt(lookupKeys.length);
    bh.consume(bucket.find(lookupKeys[idx], keySerializer, serializerFactory));
  }

  /**
   * NEW path: compare directly in the page buffer (find(byte[], ...)).
   * No Java objects are created during the binary search.
   */
  @Benchmark
  public void findInBuffer(Blackhole bh) {
    var idx = random.nextInt(lookupKeys.length);
    bh.consume(bucket.find(serializedLookupKeys[idx], keySerializer, serializerFactory));
  }

  // =====================================================================
  // Key type configuration
  // =====================================================================

  public enum KeyType {
    INTEGER {
      @Override
      BinarySerializer<?> serializer() {
        return IntegerSerializer.INSTANCE;
      }

      @Override
      Object[] hints() {
        return null;
      }

      @Override
      TreeSet<Object> generateSortedKeys(Random rng) {
        var keys = new TreeSet<>();
        // Fill a page-worth of integer keys. Ints are 4 bytes + RID is 10 bytes = 14 bytes
        // per entry, plus 4 bytes for the pointer. ~450 keys per 8KB page.
        while (keys.size() < 500) {
          keys.add(rng.nextInt());
        }
        return keys;
      }
    },

    STRING {
      @Override
      BinarySerializer<?> serializer() {
        return StringSerializer.INSTANCE;
      }

      @Override
      Object[] hints() {
        return null;
      }

      @Override
      TreeSet<Object> generateSortedKeys(Random rng) {
        var keys = new TreeSet<>();
        // Generate ~8 char strings. Each serialized: 4 (len) + 16 (chars) = 20 bytes.
        // With RID (10) + pointer (4) = ~34 bytes per entry. ~240 keys per page.
        while (keys.size() < 300) {
          keys.add(randomString(rng, 8));
        }
        return keys;
      }
    },

    UTF8 {
      @Override
      BinarySerializer<?> serializer() {
        return UTF8Serializer.INSTANCE;
      }

      @Override
      Object[] hints() {
        return null;
      }

      @Override
      TreeSet<Object> generateSortedKeys(Random rng) {
        var keys = new TreeSet<>();
        // UTF8: 2 (len short) + ~8 bytes (ASCII) = ~10 bytes key.
        // With RID (10) + pointer (4) = ~24 bytes per entry. ~340 keys per page.
        while (keys.size() < 400) {
          keys.add(randomString(rng, 8));
        }
        return keys;
      }
    },

    COMPOSITE_INT_STRING {
      @Override
      BinarySerializer<?> serializer() {
        return CompositeKeySerializer.INSTANCE;
      }

      @Override
      Object[] hints() {
        return new PropertyTypeInternal[]{
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING};
      }

      @Override
      @SuppressWarnings("unchecked")
      TreeSet<Object> generateSortedKeys(Random rng) {
        var keys = new TreeSet<>();
        // Composite: 4 (total size) + 4 (num keys) + 1+4 (int field) + 1+4+12 (string field)
        //          = ~30 bytes key. With RID (10) + pointer (4) = ~44 bytes. ~185 keys per page.
        while (keys.size() < 200) {
          var ck = new CompositeKey(rng.nextInt(100), randomString(rng, 6));
          keys.add(ck);
        }
        return keys;
      }
    };

    abstract BinarySerializer<?> serializer();

    abstract Object[] hints();

    abstract TreeSet<Object> generateSortedKeys(Random rng);

    static String randomString(Random rng, int len) {
      var sb = new StringBuilder(len);
      for (var i = 0; i < len; i++) {
        sb.append((char) ('a' + rng.nextInt(26)));
      }
      return sb.toString();
    }
  }

  // =====================================================================
  // Runner with async-profiler alloc event
  // =====================================================================

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(BucketFindBenchmark.class.getSimpleName())
        .addProfiler(AsyncProfiler.class,
            "event=alloc;output=flamegraph;dir=/tmp/claude-code-alloc-profiles")
        .build();
    new Runner(opt).run();
  }
}
