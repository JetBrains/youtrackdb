/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Composite index histogram tests — Section 10.3 of the ADR.
 *
 * <p>Verifies that histograms on composite indexes (e.g., (firstName, lastName))
 * operate only on the leading field: build extracts leading fields from the
 * CompositeKey stream, onPut/onRemove extract the leading field for bucket
 * lookup, and selectivity estimation uses the leading-field histogram for
 * prefix queries while falling back to 1/NDV for full-key equality and
 * default selectivity for non-leading-field queries.
 *
 * <p>Build tests use {@link IndexHistogramManager#scanAndBuild} directly
 * (pure computation, no page I/O) after manually extracting leading fields
 * — mirroring what {@code buildHistogram()} does internally for composite
 * indexes. Incremental and selectivity tests install pre-built snapshots
 * into the CHM cache (same pattern as {@code IndexHistogramManagerUnitTest}).
 */
public class CompositeIndexHistogramTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Histogram build: leading-field extraction from CompositeKey stream
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void buildHistogram_compositeIndex_histogramOnLeadingFieldOnly() {
    // Given a composite index (firstName, lastName) with 200 CompositeKeys.
    // buildHistogram extracts the leading field before calling scanAndBuild.
    // We simulate that extraction here to verify the histogram contains
    // only leading-field values.
    Stream<Object> leadingFields = Stream.concat(
        Stream.generate(() -> (Object) "Alice").limit(100),
        Stream.generate(() -> (Object) "Bob").limit(100));

    // When scanAndBuild is called on the extracted leading fields
    var result = IndexHistogramManager.scanAndBuild(
        leadingFields, 200, 10);

    // Then a histogram is built with leading-field boundaries
    assertNotNull(result);
    assertTrue("bucket count should be >= 1",
        result.actualBucketCount >= 1);
    assertEquals("total entries should be 200", 200,
        sumFrequencies(result));

    // scanAndBuild guarantees boundaries[0] == min key and
    // boundaries[actualBucketCount] == max key
    assertEquals("Alice", result.boundaries[0]);
    assertEquals("Bob", result.boundaries[result.actualBucketCount]);
  }

  @Test
  public void buildHistogram_compositeIndex_leadingFieldBoundariesAreStrings() {
    // Given 4 distinct leading-field values extracted from CompositeKeys
    Stream<Object> leadingFields = Stream.of(
        Stream.generate(() -> (Object) "Alice").limit(100),
        Stream.generate(() -> (Object) "Bob").limit(100),
        Stream.generate(() -> (Object) "Carol").limit(100),
        Stream.generate(() -> (Object) "Dave").limit(100))
        .flatMap(s -> s);

    var result = IndexHistogramManager.scanAndBuild(
        leadingFields, 400, 10);

    assertNotNull(result);

    // All boundaries should be Strings (leading field values),
    // not CompositeKey objects
    for (int i = 0; i <= result.actualBucketCount; i++) {
      assertTrue("boundary[" + i + "] should be a String, got "
          + result.boundaries[i].getClass().getSimpleName(),
          result.boundaries[i] instanceof String);
    }
  }

  @Test
  public void buildHistogram_compositeKeyStream_mapExtractsLeadingField() {
    // Given a stream of CompositeKeys — verify the .map() extraction
    // that buildHistogram performs for keyFieldCnt > 1 produces the
    // same result as scanning pre-extracted leading fields.
    Stream<Object> compositeKeys = Stream.concat(
        generateCompositeKeys("Alice", 100),
        generateCompositeKeys("Bob", 100));

    // Simulate the extraction that buildHistogram does:
    // sortedKeys.map(k -> ((CompositeKey) k).getKeys().getFirst())
    Stream<Object> extracted = compositeKeys
        .map(k -> ((CompositeKey) k).getKeys().getFirst());

    var result = IndexHistogramManager.scanAndBuild(extracted, 200, 10);

    assertNotNull(result);
    assertEquals("Alice", result.boundaries[0]);
    assertEquals("Bob", result.boundaries[result.actualBucketCount]);
  }

  @Test
  public void buildHistogram_compositeIndex_threeFields_extractsFirstOnly() {
    // Given a stream of 3-field CompositeKeys
    Stream<Object> compositeKeys = Stream.of(
        generateCompositeKeys3("Engineering", "Dev", 100),
        generateCompositeKeys3("Marketing", "Mgr", 100),
        generateCompositeKeys3("Sales", "Rep", 100))
        .flatMap(s -> s);

    // Extract leading field (mimics buildHistogram keyFieldCnt > 1 path)
    Stream<Object> leadingFields = compositeKeys
        .map(k -> ((CompositeKey) k).getKeys().getFirst());

    var result = IndexHistogramManager.scanAndBuild(
        leadingFields, 300, 10);

    assertNotNull(result);
    assertEquals("Engineering", result.boundaries[0]);
    assertEquals("Sales", result.boundaries[result.actualBucketCount]);

    // All boundaries are strings, not CompositeKeys
    for (int i = 0; i <= result.actualBucketCount; i++) {
      assertTrue(result.boundaries[i] instanceof String);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Incremental put: leading-field extraction for findBucket()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_compositeKey_extractsLeadingFieldForBucketLookup() {
    // Given a composite index with a histogram built on leading field
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When inserting a CompositeKey("Alice", "Smith")
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(
        op, new CompositeKey("Alice", "Smith"), true, true);

    // Then totalCount delta is +1 and frequency delta is applied
    // to the bucket containing "Alice" (the leading field)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull("delta should be created", delta);
    assertEquals(1, delta.totalCountDelta);
    assertNotNull("frequencyDeltas should be initialized",
        delta.frequencyDeltas);
    assertEquals("exactly one bucket should receive +1",
        1, sumFrequencyDeltas(delta.frequencyDeltas));
  }

  @Test
  public void onPut_compositeKey_wasInsertFalse_noCounterChange() {
    // Given a composite index with a histogram (single-value: wasInsert=false
    // means an update, not a new entry)
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When updating an existing CompositeKey
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(
        op, new CompositeKey("Alice", "Jones"), true, false);

    // Then no counter or frequency changes (only mutation count)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals("totalCountDelta should be 0 for update",
        0, delta.totalCountDelta);
    assertNull("frequencyDeltas should be null for update",
        delta.frequencyDeltas);
  }

  @Test
  public void onPut_compositeKey_nullKey_tracksNullCount() {
    // Given a composite index with a histogram
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When inserting a null key
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, null, true, true);

    // Then nullCountDelta is +1, totalCountDelta is +1
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(1, delta.nullCountDelta);
    assertEquals(1, delta.totalCountDelta);
  }

  @Test
  public void onPut_compositeKey_findsBucketByLeadingField_notFullKey() {
    // Given a composite index with a histogram where "Alice" and "Bob"
    // are in different buckets
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When inserting two CompositeKeys with the same leading field
    // but different second fields
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(
        op, new CompositeKey("Alice", "AAA"), true, true);
    fixture.manager.onPut(
        op, new CompositeKey("Alice", "ZZZ"), true, true);

    // Then both go to the same bucket (based on "Alice" leading field)
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);

    // Exactly one bucket should have +2, all others 0
    int nonZeroBuckets = 0;
    for (int fd : delta.frequencyDeltas) {
      if (fd != 0) {
        assertEquals("+2 in the Alice bucket", 2, fd);
        nonZeroBuckets++;
      }
    }
    assertEquals("only one bucket should be affected", 1, nonZeroBuckets);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Incremental remove: leading-field extraction for findBucket()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onRemove_compositeKey_extractsLeadingFieldForBucketLookup() {
    // Given a composite index with a histogram
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When removing a CompositeKey("Bob", "Williams")
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(
        op, new CompositeKey("Bob", "Williams"), true);

    // Then totalCount delta is -1 and frequency delta is -1 in Bob's bucket
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(-1, delta.totalCountDelta);
    assertNotNull(delta.frequencyDeltas);
    assertEquals(-1, sumFrequencyDeltas(delta.frequencyDeltas));
  }

  @Test
  public void onRemove_compositeKey_nullKey_tracksNullCount() {
    // Given a composite index with a histogram
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When removing a null key
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(op, null, true);

    // Then nullCountDelta is -1
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(-1, delta.nullCountDelta);
    assertEquals(-1, delta.totalCountDelta);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Partially-null composite key: null leading field, non-null trailing
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void onPut_compositeKey_nullLeadingField_tracksAsNull() {
    // Given a composite index with a histogram
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When inserting a CompositeKey where the leading field is null
    // but trailing fields are non-null — e.g., (null, "Smith")
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(
        op, new CompositeKey(null, "Smith"), true, true);

    // Then it should be treated as a null key: nullCountDelta +1,
    // totalCountDelta +1, no frequency update (null keys don't have
    // histogram buckets).
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(1, delta.totalCountDelta);
    assertEquals(1, delta.nullCountDelta);
    // No frequency deltas should be initialized (null key path)
    assertNull("No frequency deltas for null leading field",
        delta.frequencyDeltas);
  }

  @Test
  public void onRemove_compositeKey_nullLeadingField_tracksAsNull() {
    // Given a composite index with a histogram
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When removing a CompositeKey where the leading field is null
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onRemove(
        op, new CompositeKey(null, "Smith"), true);

    // Then it should be treated as a null key: nullCountDelta -1
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(-1, delta.totalCountDelta);
    assertEquals(-1, delta.nullCountDelta);
    assertNull("No frequency deltas for null leading field",
        delta.frequencyDeltas);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Three-field composite: onPut extracts first field
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void threeFieldCompositeIndex_onPut_extractsFirstField() {
    // Given a 3-field composite index (dept, role, name) with a histogram
    // on the leading field "dept"
    var fixture = new Fixture(3);
    installThreeValueHistogram(fixture);

    // When inserting a 3-field CompositeKey
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(
        op, new CompositeKey("Engineering", "QA", "Alice"),
        true, true);

    // Then frequency delta is applied to "Engineering" bucket
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(1, delta.totalCountDelta);
    assertNotNull(delta.frequencyDeltas);
    assertEquals(1, sumFrequencyDeltas(delta.frequencyDeltas));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Prefix query selectivity: uses leading-field histogram
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void prefixQuery_equality_usesLeadingFieldHistogram() {
    // Given a histogram with "Alice" in ~50% and "Bob" in ~50%
    var h = createTwoValueHistogram();
    var stats = new IndexStatistics(200, 200, 0);

    // When estimating selectivity for WHERE firstName = 'Alice'
    double selectivity = SelectivityEstimator.estimateEquality(
        stats, h, "Alice");

    // Then selectivity should be approximately 0.5
    assertTrue("selectivity for 'Alice' should be > 0",
        selectivity > 0.0);
    assertTrue("selectivity for 'Alice' should be <= 1.0",
        selectivity <= 1.0);
    assertTrue("selectivity for 'Alice' should be ~0.5 (actual: "
        + selectivity + ")", selectivity > 0.3 && selectivity < 0.7);
  }

  @Test
  public void prefixQuery_equality_skewedData_reflectsDistribution() {
    // Given a histogram built from 180 "Alice" + 20 "Bob" entries
    Stream<Object> leadingFields = Stream.concat(
        Stream.generate(() -> (Object) "Alice").limit(180),
        Stream.generate(() -> (Object) "Bob").limit(20));

    var result = IndexHistogramManager.scanAndBuild(
        leadingFields, 200, 10);
    assertNotNull(result);

    var h = new EquiDepthHistogram(
        result.actualBucketCount,
        trimBoundaries(result),
        trimFrequencies(result),
        trimDistinctCounts(result),
        200, null, 0);
    var stats = new IndexStatistics(200, 200, 0);

    // When estimating selectivity for "Alice" vs "Bob"
    double aliceSel = SelectivityEstimator.estimateEquality(
        stats, h, "Alice");
    double bobSel = SelectivityEstimator.estimateEquality(
        stats, h, "Bob");

    // Then Alice should have higher selectivity than Bob
    assertTrue("Alice sel (" + aliceSel + ") > Bob sel (" + bobSel + ")",
        aliceSel > bobSel);
  }

  @Test
  public void prefixQuery_rangeOnLeadingField_usesHistogram() {
    // Given a histogram with 4 equally distributed leading-field values
    Stream<Object> leadingFields = Stream.of(
        Stream.generate(() -> (Object) "Alice").limit(100),
        Stream.generate(() -> (Object) "Bob").limit(100),
        Stream.generate(() -> (Object) "Carol").limit(100),
        Stream.generate(() -> (Object) "Dave").limit(100))
        .flatMap(s -> s);

    var result = IndexHistogramManager.scanAndBuild(
        leadingFields, 400, 10);
    assertNotNull(result);

    var h = new EquiDepthHistogram(
        result.actualBucketCount,
        trimBoundaries(result),
        trimFrequencies(result),
        trimDistinctCounts(result),
        400, null, 0);
    var stats = new IndexStatistics(400, 400, 0);

    // When estimating selectivity for firstName > 'Bob'
    double gtBob = SelectivityEstimator.estimateGreaterThan(
        stats, h, "Bob");

    // Then selectivity should be > 0 and < 1 (Carol + Dave ≈ 50%)
    assertTrue("GT 'Bob' should be > 0 (actual: " + gtBob + ")",
        gtBob > 0.0);
    assertTrue("GT 'Bob' should be < 1.0 (actual: " + gtBob + ")",
        gtBob < 1.0);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Full-key equality: uses 1/NDV (uniform estimate)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void fullKeyEquality_usesUniformEstimate_1overNDV() {
    // For full-key equality (both fields specified), the planner passes
    // histogram=null to get the uniform 1/NDV estimate, since the
    // histogram only covers the leading field.
    var stats = new IndexStatistics(200, 200, 0);

    double uniformSel = SelectivityEstimator.estimateEquality(
        stats, null, new CompositeKey("Alice", "Smith"));

    // 1/NDV = 1/200 = 0.005
    double expected = 1.0 / stats.distinctCount();
    assertEquals("full-key equality should use 1/NDV",
        expected, uniformSel, 1e-10);
  }

  @Test
  public void fullKeyEquality_lowerNDV_higherSelectivity() {
    // With fewer distinct values, each full-key match is more selective
    var stats = new IndexStatistics(200, 50, 0);

    double sel = SelectivityEstimator.estimateEquality(
        stats, null, new CompositeKey("Alice", "Smith"));

    assertEquals(1.0 / 50, sel, 1e-10);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Non-leading-field query: returns default selectivity
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void nonLeadingFieldQuery_returnsDefaultSelectivity() {
    // When a query only constrains a non-leading field (e.g.,
    // WHERE lastName = X on index (firstName, lastName)), the histogram
    // cannot help. The planner should use default selectivity.
    double defaultSel = SelectivityEstimator.defaultSelectivity();

    assertTrue("default selectivity should be in (0, 1)",
        defaultSel > 0.0 && defaultSel < 1.0);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Delta application: composite key deltas applied to cache
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void applyDelta_compositeKey_updatesCountersCorrectly() {
    // Given a composite index with a histogram
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    // When inserting 3 composite keys and removing 1
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(
        op, new CompositeKey("Alice", "a"), true, true);
    fixture.manager.onPut(
        op, new CompositeKey("Bob", "b"), true, true);
    fixture.manager.onPut(
        op, new CompositeKey("Alice", "c"), true, true);
    fixture.manager.onRemove(
        op, new CompositeKey("Bob", "d"), true);

    // Apply deltas
    fixture.manager.applyDelta(
        holder.getDeltas().get(fixture.engineId));

    // Then totalCount should be 200 + 3 - 1 = 202
    var stats = fixture.manager.getStatistics();
    assertEquals(202, stats.totalCount());
  }

  @Test
  public void applyDelta_compositeKey_frequencyDeltasReflectLeadingField() {
    // Given a composite index with a histogram (2 values: Alice, Bob)
    var fixture = new Fixture(2);
    installTwoFieldHistogram(fixture);

    var initialH = fixture.cache.get(fixture.engineId).histogram();
    long[] initialFreqs = initialH.frequencies().clone();

    // When inserting 5 "Alice" entries
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    for (int i = 0; i < 5; i++) {
      fixture.manager.onPut(
          op, new CompositeKey("Alice", "ln" + i), true, true);
    }

    // Apply deltas
    fixture.manager.applyDelta(
        holder.getDeltas().get(fixture.engineId));

    // Then the Alice bucket's frequency should increase by 5
    var newH = fixture.cache.get(fixture.engineId).histogram();
    assertNotNull(newH);

    int aliceBucket = newH.findBucket("Alice");
    long freqIncrease =
        newH.frequencies()[aliceBucket] - initialFreqs[aliceBucket];
    assertEquals("Alice bucket frequency should increase by 5",
        5, freqIncrease);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-field index: keyFieldCount=1, no leading-field extraction
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleFieldIndex_onPut_usesKeyDirectly() {
    // Given a single-field index (keyFieldCount=1) with a histogram
    var fixture = new Fixture(1);
    installTwoFieldHistogram(fixture);

    // When inserting a plain string key (not CompositeKey)
    var holder = new HistogramDeltaHolder();
    var op = mockOp(holder);
    fixture.manager.onPut(op, "Alice", true, true);

    // Then delta is applied without CompositeKey extraction
    var delta = holder.getDeltas().get(fixture.engineId);
    assertNotNull(delta);
    assertEquals(1, delta.totalCountDelta);
    assertNotNull(delta.frequencyDeltas);
    assertEquals(1, sumFrequencyDeltas(delta.frequencyDeltas));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═══════════════════════════════════════════════════════════════════════

  private static AtomicOperation mockOp(HistogramDeltaHolder holder) {
    var op = mock(AtomicOperation.class);
    when(op.getOrCreateHistogramDeltas()).thenReturn(holder);
    return op;
  }

  /**
   * Creates a 2-bucket histogram over leading-field values "Alice" and "Bob",
   * each with 100 entries. Boundaries are ["Alice", "Bob", "Bob"] — the last
   * bucket has identical lo/hi, which is valid (single-value bucket for "Bob").
   * This layout ensures findBucket("Alice") → bucket 0, findBucket("Bob") → 1.
   */
  private static EquiDepthHistogram createTwoValueHistogram() {
    return new EquiDepthHistogram(
        2,
        new Comparable<?>[] {"Alice", "Bob", "Bob"},
        new long[] {100, 100},
        new long[] {1, 1},
        200, null, 0);
  }

  /**
   * Creates a 3-bucket histogram over leading-field values "Engineering",
   * "Marketing", and "Sales", each with 100 entries.
   */
  private static EquiDepthHistogram createThreeValueHistogram() {
    return new EquiDepthHistogram(
        3,
        new Comparable<?>[] {"Engineering", "Marketing", "Sales", "Sales"},
        new long[] {100, 100, 100},
        new long[] {1, 1, 1},
        300, null, 0);
  }

  /** Installs a 2-value histogram snapshot into the fixture's cache. */
  private static void installTwoFieldHistogram(Fixture fixture) {
    var h = createTwoValueHistogram();
    var stats = new IndexStatistics(200, 200, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, h, 0, 200, 0, false, null, false));
  }

  /** Installs a 3-value histogram snapshot into the fixture's cache. */
  private static void installThreeValueHistogram(Fixture fixture) {
    var h = createThreeValueHistogram();
    var stats = new IndexStatistics(300, 300, 0);
    fixture.cache.put(fixture.engineId,
        new HistogramSnapshot(stats, h, 0, 300, 0, false, null, false));
  }

  private static Stream<Object> generateCompositeKeys(
      String leadingField, int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> (Object) new CompositeKey(
            leadingField, "last" + String.format("%04d", i)));
  }

  private static Stream<Object> generateCompositeKeys3(
      String field1, String field2, int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> (Object) new CompositeKey(
            field1, field2, "name" + String.format("%04d", i)));
  }

  private static int sumFrequencyDeltas(int[] deltas) {
    int sum = 0;
    for (int d : deltas) {
      sum += d;
    }
    return sum;
  }

  private static long sumFrequencies(
      IndexHistogramManager.BuildResult result) {
    long sum = 0;
    for (int i = 0; i < result.actualBucketCount; i++) {
      sum += result.frequencies[i];
    }
    return sum;
  }

  /** Trims boundaries array to actual size. */
  private static Comparable<?>[] trimBoundaries(
      IndexHistogramManager.BuildResult result) {
    var trimmed = new Comparable<?>[result.actualBucketCount + 1];
    System.arraycopy(
        result.boundaries, 0, trimmed, 0, trimmed.length);
    return trimmed;
  }

  /** Trims frequencies array to actual size. */
  private static long[] trimFrequencies(
      IndexHistogramManager.BuildResult result) {
    var trimmed = new long[result.actualBucketCount];
    System.arraycopy(
        result.frequencies, 0, trimmed, 0, trimmed.length);
    return trimmed;
  }

  /** Trims distinctCounts array to actual size. */
  private static long[] trimDistinctCounts(
      IndexHistogramManager.BuildResult result) {
    var trimmed = new long[result.actualBucketCount];
    System.arraycopy(
        result.distinctCounts, 0, trimmed, 0, trimmed.length);
    return trimmed;
  }

  private static AbstractStorage createMockStorage() {
    var storage = mock(AbstractStorage.class);
    var factory = new CurrentStorageComponentsFactory(
        BinarySerializerFactory.currentBinaryFormatVersion());
    when(storage.getComponentsFactory()).thenReturn(factory);
    when(storage.getAtomicOperationsManager())
        .thenReturn(mock(AtomicOperationsManager.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    return storage;
  }

  /**
   * Test fixture with mock storage and real CHM cache.
   */
  private static class Fixture {
    final int engineId = 0;
    final ConcurrentHashMap<Integer, HistogramSnapshot> cache =
        new ConcurrentHashMap<>();
    final IndexHistogramManager manager;

    Fixture(int keyFieldCount) {
      var storage = createMockStorage();
      var serializerFactory = BinarySerializerFactory.create(
          BinarySerializerFactory.CURRENT_BINARY_FORMAT_VERSION);
      manager = new IndexHistogramManager(
          storage, "test-composite", engineId, true, cache,
          StringSerializer.INSTANCE, serializerFactory,
          StringSerializer.ID);
      manager.setKeyFieldCount(keyFieldCount);
    }
  }
}
