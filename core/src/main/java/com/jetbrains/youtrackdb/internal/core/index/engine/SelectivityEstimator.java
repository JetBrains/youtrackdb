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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 * Three-tier selectivity estimation for cost-based query optimization.
 *
 * <p>Tiers:
 * <ol>
 *   <li><b>Empty</b> — {@code totalCount == 0}: all estimates return 0</li>
 *   <li><b>Uniform</b> — index has entries but no histogram
 *       ({@code nonNullCount < HISTOGRAM_MIN_SIZE}): uses summary counters
 *       from {@link IndexStatistics} with uniform-distribution formulas</li>
 *   <li><b>Histogram</b> — full equi-depth histogram available: uses
 *       bucket-level interpolation with MCV short-circuit, out-of-range
 *       equality short-circuit, and single-value bucket optimization</li>
 * </ol>
 *
 * <p>All formulas clamp their result to [0.0, 1.0] before returning.
 *
 * <p>This class provides per-predicate-type estimation methods. The top-level
 * dispatcher that interprets {@code SQLBooleanExpression} predicates is wired
 * in Steps 7-8 (SQLWhereClause and IndexSearchDescriptor integration).
 */
public final class SelectivityEstimator {

  /**
   * Returns the default selectivity for non-indexed or unknown predicates.
   * Reads from {@link GlobalConfiguration#QUERY_STATS_DEFAULT_SELECTIVITY}
   * at each call so runtime changes take effect without restart.
   */
  public static double defaultSelectivity() {
    return GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY
        .getValueAsDouble();
  }

  /** Standard PostgreSQL default for unbounded range (e.g., {@code f > X}). */
  private static final double UNIFORM_RANGE_SELECTIVITY = 1.0 / 3.0;

  private static final DefaultComparator COMPARATOR = DefaultComparator.INSTANCE;

  private SelectivityEstimator() {
  }

  // ── Equality: f = X ───────────────────────────────────────────────

  /**
   * Estimates the selectivity of an equality predicate {@code f = key}.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @param key       the equality key (non-null)
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateEquality(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      Object key) {
    if (stats.totalCount() == 0) {
      return 0.0;
    }
    if (histogram != null) {
      return estimateEqualityHistogram(histogram, key);
    }
    return estimateEqualityUniform(stats);
  }

  private static double estimateEqualityUniform(IndexStatistics stats) {
    if (stats.distinctCount() <= 0) {
      return 0.0;
    }
    // All entries are null: no non-null key can match.
    long nonNull = stats.totalCount() - stats.nullCount();
    if (nonNull <= 0) {
      return 0.0;
    }
    return clamp(1.0 / stats.distinctCount());
  }

  private static double estimateEqualityHistogram(
      EquiDepthHistogram h, Object key) {
    long nonNull = h.nonNullCount();
    if (nonNull <= 0) {
      return 0.0;
    }

    // Out-of-range short-circuit: key is outside [min, max] of the index.
    // Returns minimal non-zero value to avoid division-by-zero in cost models.
    if (COMPARATOR.compare(key, h.boundaries()[0]) < 0
        || COMPARATOR.compare(key, h.boundaries()[h.bucketCount()]) > 0) {
      return clamp(1.0 / nonNull);
    }

    // MCV short-circuit: if key matches the most common value, use its
    // exact frequency instead of the bucket-averaged estimate.
    if (h.mcvValue() != null && COMPARATOR.compare(key, h.mcvValue()) == 0) {
      return clamp((double) h.mcvFrequency() / nonNull);
    }

    int b = h.findBucket(key);
    if (h.frequencies()[b] <= 0 || h.distinctCounts()[b] <= 0) {
      return 0.0;
    }
    return clamp(
        (1.0 / h.distinctCounts()[b]) * ((double) h.frequencies()[b] / nonNull));
  }

  // ── Greater-than: f > X ───────────────────────────────────────────

  /**
   * Estimates the selectivity of {@code f > key}.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @param key       the comparison key (non-null)
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateGreaterThan(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      Object key) {
    if (stats.totalCount() == 0) {
      return 0.0;
    }
    if (histogram != null) {
      return estimateGreaterThanHistogram(histogram, key);
    }
    return UNIFORM_RANGE_SELECTIVITY;
  }

  private static double estimateGreaterThanHistogram(
      EquiDepthHistogram h, Object key) {
    long nonNull = h.nonNullCount();
    if (nonNull <= 0) {
      return 0.0;
    }

    int b = h.findBucket(key);
    double fraction = fractionOf(key, b, h, FractionMode.STRICT_ABOVE);
    double remainingInB = (1.0 - fraction) * Math.max(h.frequencies()[b], 0);
    double aboveBuckets = sumFrequencies(h, b + 1, h.bucketCount());
    return clamp((remainingInB + aboveBuckets) / nonNull);
  }

  // ── Less-than: f < X ──────────────────────────────────────────────

  /**
   * Estimates the selectivity of {@code f < key}.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @param key       the comparison key (non-null)
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateLessThan(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      Object key) {
    if (stats.totalCount() == 0) {
      return 0.0;
    }
    if (histogram != null) {
      return estimateLessThanHistogram(histogram, key);
    }
    return UNIFORM_RANGE_SELECTIVITY;
  }

  private static double estimateLessThanHistogram(
      EquiDepthHistogram h, Object key) {
    long nonNull = h.nonNullCount();
    if (nonNull <= 0) {
      return 0.0;
    }

    int b = h.findBucket(key);
    double fraction = fractionOf(key, b, h, FractionMode.STRICT_BELOW);
    double partialB = fraction * Math.max(h.frequencies()[b], 0);
    double belowBuckets = sumFrequencies(h, 0, b);
    return clamp((belowBuckets + partialB) / nonNull);
  }

  // ── Greater-or-equal: f >= X ──────────────────────────────────────

  /**
   * Estimates the selectivity of {@code f >= key}.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @param key       the comparison key (non-null)
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateGreaterOrEqual(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      Object key) {
    if (stats.totalCount() == 0) {
      return 0.0;
    }
    if (histogram != null) {
      return estimateGreaterOrEqualHistogram(histogram, key);
    }
    return UNIFORM_RANGE_SELECTIVITY;
  }

  private static double estimateGreaterOrEqualHistogram(
      EquiDepthHistogram h, Object key) {
    // Combined: range above + equality, using single findBucket() call.
    long nonNull = h.nonNullCount();
    if (nonNull <= 0) {
      return 0.0;
    }

    int b = h.findBucket(key);
    double fraction = fractionOf(key, b, h, FractionMode.STRICT_ABOVE);
    double remainingInB = (1.0 - fraction) * Math.max(h.frequencies()[b], 0);
    double aboveBuckets = sumFrequencies(h, b + 1, h.bucketCount());
    // Add equality contribution for the bucket containing key
    double eqContrib = equalityFraction(b, h);
    return clamp((remainingInB + aboveBuckets + eqContrib) / nonNull);
  }

  // ── Less-or-equal: f <= X ─────────────────────────────────────────

  /**
   * Estimates the selectivity of {@code f <= key}.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @param key       the comparison key (non-null)
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateLessOrEqual(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      Object key) {
    if (stats.totalCount() == 0) {
      return 0.0;
    }
    if (histogram != null) {
      return estimateLessOrEqualHistogram(histogram, key);
    }
    return UNIFORM_RANGE_SELECTIVITY;
  }

  private static double estimateLessOrEqualHistogram(
      EquiDepthHistogram h, Object key) {
    long nonNull = h.nonNullCount();
    if (nonNull <= 0) {
      return 0.0;
    }

    int b = h.findBucket(key);
    double fraction = fractionOf(key, b, h, FractionMode.STRICT_BELOW);
    double partialB = fraction * Math.max(h.frequencies()[b], 0);
    double belowBuckets = sumFrequencies(h, 0, b);
    double eqContrib = equalityFraction(b, h);
    return clamp((belowBuckets + partialB + eqContrib) / nonNull);
  }

  // ── Range: X <= f <= Y (inclusive on both ends) ───────────────────

  /**
   * Estimates the selectivity of a range predicate.
   *
   * <p>The {@code fromInclusive} and {@code toInclusive} parameters are
   * accepted for API completeness but do not currently affect the result.
   * Continuous interpolation within buckets makes the inclusive/exclusive
   * distinction negligible (the probability mass at an exact point is
   * effectively zero for continuous-ish distributions). This matches
   * PostgreSQL's approach. For discrete distributions with few distinct
   * values per bucket, the error is bounded by
   * {@code 1/distinctCounts[B]} per endpoint.
   *
   * @param stats         index statistics (never null)
   * @param histogram     equi-depth histogram, or null if not yet built
   * @param fromKey       lower bound (non-null)
   * @param toKey         upper bound (non-null)
   * @param fromInclusive true if lower bound is inclusive (informational)
   * @param toInclusive   true if upper bound is inclusive (informational)
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateRange(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      Object fromKey, Object toKey,
      boolean fromInclusive, boolean toInclusive) {
    if (stats.totalCount() == 0) {
      return 0.0;
    }
    if (histogram != null) {
      return estimateRangeHistogram(histogram, fromKey, toKey);
    }
    return UNIFORM_RANGE_SELECTIVITY;
  }

  @SuppressWarnings("unchecked")
  private static double estimateRangeHistogram(
      EquiDepthHistogram h, Object fromKey, Object toKey) {
    long nonNull = h.nonNullCount();
    if (nonNull <= 0) {
      return 0.0;
    }

    // Empty range: fromKey > toKey → no matching rows.
    int cmp = COMPARATOR.compare(fromKey, toKey);
    if (cmp > 0) {
      return 0.0;
    }

    // Degenerate range: BETWEEN X AND X → equivalent to equality (f = X).
    // The continuous interpolation model produces fracY - fracX = 0 for
    // identical keys, yielding a wrong zero estimate. Delegate to equality.
    if (cmp == 0) {
      return estimateEqualityHistogram(h, fromKey);
    }

    int bx = h.findBucket(fromKey);
    int by = h.findBucket(toKey);

    double matchingRows;
    if (bx == by) {
      // X and Y in the same bucket — interpolate the sub-range
      double fracX = fractionOf(fromKey, bx, h, FractionMode.RANGE);
      double fracY = fractionOf(toKey, bx, h, FractionMode.RANGE);
      double rangeFraction = fracY - fracX;
      matchingRows = rangeFraction * Math.max(h.frequencies()[bx], 0);
    } else {
      // Partial lower bucket (fraction of bx above fromKey)
      double fractionX = fractionOf(fromKey, bx, h, FractionMode.RANGE);
      double lowerPart =
          (1.0 - fractionX) * Math.max(h.frequencies()[bx], 0);

      // Full middle buckets
      double middlePart = sumFrequencies(h, bx + 1, by);

      // Partial upper bucket (fraction of by up to toKey)
      double fractionY = fractionOf(toKey, by, h, FractionMode.RANGE);
      double upperPart = fractionY * Math.max(h.frequencies()[by], 0);

      matchingRows = lowerPart + middlePart + upperPart;
    }

    return clamp(matchingRows / nonNull);
  }

  // ── IS NULL ───────────────────────────────────────────────────────

  /**
   * Estimates the selectivity of {@code f IS NULL}.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateIsNull(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram) {
    long total = computeTotal(stats, histogram);
    if (total <= 0) {
      return 0.0;
    }
    return clamp((double) stats.nullCount() / total);
  }

  // ── IS NOT NULL ───────────────────────────────────────────────────

  /**
   * Estimates the selectivity of {@code f IS NOT NULL}.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateIsNotNull(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram) {
    long total = computeTotal(stats, histogram);
    if (total <= 0) {
      return 0.0;
    }
    long nonNull = histogram != null
        ? histogram.nonNullCount()
        : stats.totalCount() - stats.nullCount();
    return clamp((double) nonNull / total);
  }

  // ── IN: f IN (v1, v2, ..., vN) ───────────────────────────────────

  /**
   * Estimates the selectivity of {@code f IN (values)}.
   *
   * <p>Assumes distinct values. If duplicates exist, the result
   * overestimates (double-counts). The clamp to [0, 1] bounds the error.
   *
   * @param stats     index statistics (never null)
   * @param histogram equi-depth histogram, or null if not yet built
   * @param values    the set of values to match
   * @return selectivity in [0.0, 1.0]
   */
  public static double estimateIn(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      Collection<?> values) {
    if (stats.totalCount() == 0 || values.isEmpty()) {
      return 0.0;
    }
    if (histogram != null) {
      double sum = 0.0;
      for (Object v : values) {
        sum += estimateEqualityHistogram(histogram, v);
      }
      return clamp(sum);
    }
    return estimateInUniform(stats, values.size());
  }

  private static double estimateInUniform(IndexStatistics stats, int n) {
    if (stats.distinctCount() <= 0) {
      return 0.0;
    }
    return clamp((double) n / stats.distinctCount());
  }

  // ── Helpers ───────────────────────────────────────────────────────

  /**
   * Mode for single-value bucket fraction computation. Only affects
   * the result when the query value equals the bucket's sole value.
   */
  private enum FractionMode {
    /**
     * For {@code f > X} / {@code f >= X}: fraction = 1.0 when
     * {@code X == bucketVal} (nothing strictly above).
     */
    STRICT_ABOVE,
    /**
     * For {@code f < X} / {@code f <= X}: fraction = 0.0 when
     * {@code X == bucketVal} (nothing strictly below).
     */
    STRICT_BELOW,
    /**
     * For range interpolation: fraction = 0.5 (discrete midpoint).
     */
    RANGE
  }

  /**
   * Computes the fractional position of a value within a bucket.
   *
   * <p>Uses single-value bucket optimization when
   * {@code distinctCounts[b] == 1}: the continuous interpolation model
   * breaks down (all entries are at a single point, not uniformly
   * distributed). Switches to discrete logic instead, with behavior at
   * the equality point determined by {@code mode}:
   * <ul>
   *   <li>{@code STRICT_ABOVE}: fraction = 1.0 (for GT/GTE direction,
   *       meaning nothing is strictly above)</li>
   *   <li>{@code STRICT_BELOW}: fraction = 0.0 (for LT/LTE direction,
   *       meaning nothing is strictly below)</li>
   *   <li>{@code RANGE}: fraction = 0.5 (discrete midpoint for range
   *       interpolation)</li>
   * </ul>
   *
   * <p>For non-single-value buckets and the degenerate case where all
   * three scalar values are identical (e.g., unknown types returning
   * 0.5), the degenerate-bucket guard returns 0.5.
   *
   * @param value the value to locate
   * @param b     the bucket index
   * @param h     the histogram
   * @param mode  controls single-value bucket equality behavior
   * @return a fraction in [0, 1]
   */
  private static double fractionOf(
      Object value, int b, EquiDepthHistogram h, FractionMode mode) {
    // Single-value bucket optimization: when distinctCounts[b] == 1,
    // the bucket contains duplicates of a single value. Use discrete logic.
    if (h.distinctCounts()[b] == 1 && h.frequencies()[b] > 0) {
      Object bucketVal = h.boundaries()[b];
      int cmp = COMPARATOR.compare(value, bucketVal);
      if (cmp < 0) {
        return 0.0; // value below the single value → everything is above
      }
      if (cmp > 0) {
        return 1.0; // value above the single value → everything is below
      }
      // value equals the single value — behavior depends on mode
      return switch (mode) {
        case STRICT_ABOVE -> 1.0; // nothing strictly above
        case STRICT_BELOW -> 0.0; // nothing strictly below
        case RANGE -> 0.5;        // discrete midpoint for interpolation
      };
    }

    double scaledV = ScalarConversion.scalarize(
        value, h.boundaries()[b], h.boundaries()[b + 1]);
    double scaledLo = ScalarConversion.scalarize(
        h.boundaries()[b], h.boundaries()[b], h.boundaries()[b + 1]);
    double scaledHi = ScalarConversion.scalarize(
        h.boundaries()[b + 1], h.boundaries()[b], h.boundaries()[b + 1]);

    // Degenerate bucket (both boundaries scalarize to the same value).
    // This also handles the case where scalarize returns the same fallback
    // (e.g., 0.5) for all three inputs (unknown type).
    if (scaledHi == scaledLo) {
      return 0.5;
    }
    return clamp((scaledV - scaledLo) / (scaledHi - scaledLo));
  }

  /**
   * Computes the equality contribution for a bucket — the probability
   * of matching a single distinct value within the bucket.
   */
  private static double equalityFraction(int b, EquiDepthHistogram h) {
    if (h.frequencies()[b] <= 0 || h.distinctCounts()[b] <= 0) {
      return 0.0;
    }
    // frequencies[b] is guaranteed > 0 after the guard above
    return (1.0 / h.distinctCounts()[b]) * h.frequencies()[b];
  }

  /**
   * Sums clamped frequencies for buckets in range [fromBucket, toBucket).
   */
  private static double sumFrequencies(
      EquiDepthHistogram h, int fromBucket, int toBucket) {
    double sum = 0.0;
    for (int i = fromBucket; i < toBucket; i++) {
      sum += Math.max(h.frequencies()[i], 0);
    }
    return sum;
  }

  /**
   * Computes the total count (nonNull + null) for IS NULL / IS NOT NULL
   * estimation. When a histogram is present, uses its nonNullCount (which
   * is the sum of clamped bucket frequencies) instead of
   * {@code totalCount - nullCount} to maintain consistency with the
   * histogram's own drift.
   */
  private static long computeTotal(
      IndexStatistics stats, @Nullable EquiDepthHistogram histogram) {
    if (histogram != null) {
      return histogram.nonNullCount() + stats.nullCount();
    }
    return stats.totalCount();
  }

  /**
   * Clamps a value to [0.0, 1.0]. Guards against residual drift from
   * incremental maintenance producing out-of-range values.
   */
  static double clamp(double v) {
    return Math.min(Math.max(v, 0.0), 1.0);
  }
}
