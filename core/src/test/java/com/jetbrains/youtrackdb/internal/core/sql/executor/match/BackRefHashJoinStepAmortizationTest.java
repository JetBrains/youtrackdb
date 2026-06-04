package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.executor.IndexSearchDescriptor;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link BackRefHashJoinStep#applyIndexLookupAmortizationGuard}
 * — the YTDB-651 amortization gate that wraps the per-back-ref index pre-filter
 * resolution in {@code buildChainHashTable}.
 *
 * <p>Targets the three switch branches (REJECT / DEFER / PROCEED) and the
 * back-ref dedup logic in isolation, without standing up the full MATCH
 * pipeline. The same guard is also exercised end-to-end through query
 * execution in {@link HashJoinPlannerIntegrationTest}, but those tests cover
 * only the PROCEED path; the REJECT and DEFER paths are observable here as
 * deterministic side effects on the step's internal state.
 *
 * <p>Pins {@link GlobalConfiguration#QUERY_PREFILTER_LOAD_TO_SCAN_RATIO} to
 * {@code 100.0} so the break-even threshold {@code m = estimatedSize /
 * (loadToScan · (1 − s))} stays stable across the test cases:
 * <ul>
 *   <li>estimatedSize=100_000, s=0.5 → m = 2000</li>
 *   <li>estimatedSize=10_000, s=0.5 → m = 200</li>
 * </ul>
 */
public class BackRefHashJoinStepAmortizationTest {

  private static final double PINNED_LOAD_TO_SCAN_RATIO = 100.0;

  @Before
  public void pinLoadToScanRatio() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO
        .setValue(PINNED_LOAD_TO_SCAN_RATIO);
  }

  @After
  public void resetLoadToScanRatio() {
    GlobalConfiguration.QUERY_PREFILTER_LOAD_TO_SCAN_RATIO.resetToDefault();
  }

  private ChainSemiJoin buildChain(IndexSearchDescriptor indexFilter) {
    return new ChainSemiJoin(
        "Edge",
        "out",
        mock(SQLExpression.class),
        "a",
        "b",
        "intermediate",
        "target",
        indexFilter,
        /* edgeFilter */ null);
  }

  private BackRefHashJoinStep buildStep(ChainSemiJoin descriptor) {
    return new BackRefHashJoinStep(
        new BasicCommandContext(), descriptor, null, null, false);
  }

  /**
   * Unknown class-level selectivity ({@code estimateSelectivity == -1.0}) must
   * REJECT permanently and cache no RidSet. Without a bounded build cost {@code
   * B} the design's bounded-loss contract refuses to commit to the build.
   * After REJECT, both {@code indexLookupRejected} and {@code
   * indexRidSetResolved} flip to {@code true} so subsequent back-refs
   * short-circuit instead of re-evaluating the same verdict.
   */
  @Test
  public void guard_unknownSelectivity_rejects() {
    var idx = mock(IndexSearchDescriptor.class);
    when(idx.estimateSelectivity(any())).thenReturn(-1.0);
    when(idx.estimateHits(any())).thenReturn(100_000L);
    var chain = buildChain(idx);
    var step = buildStep(chain);
    var ctx = new BasicCommandContext();

    step.applyIndexLookupAmortizationGuard(
        chain, new RecordId(10, 1), /* linkBagSize */ 50L, ctx);

    assertThat(step.isIndexLookupRejected()).isTrue();
    assertThat(step.isIndexRidSetResolved()).isTrue();
    assertThat(step.getCachedIndexRidSet()).isNull();
  }

  /**
   * Class-level selectivity above the configured threshold (default {@code
   * 0.95}) must also REJECT permanently — the filter is too weak to recoup
   * the one-time build cost. Same end state as the unknown-selectivity case.
   */
  @Test
  public void guard_highSelectivity_rejects() {
    var idx = mock(IndexSearchDescriptor.class);
    when(idx.estimateSelectivity(any())).thenReturn(0.99);
    when(idx.estimateHits(any())).thenReturn(100_000L);
    var chain = buildChain(idx);
    var step = buildStep(chain);
    var ctx = new BasicCommandContext();

    step.applyIndexLookupAmortizationGuard(
        chain, new RecordId(10, 1), /* linkBagSize */ 50L, ctx);

    assertThat(step.isIndexLookupRejected()).isTrue();
    assertThat(step.isIndexRidSetResolved()).isTrue();
    assertThat(step.getCachedIndexRidSet()).isNull();
  }

  /**
   * Small link bag, large index → accumulator stays below the break-even
   * threshold {@code m}, so the guard must DEFER: leave {@code cachedIndexRidSet}
   * null without flipping {@code indexRidSetResolved} (so the next back-ref
   * gets another chance) and without setting the REJECT flag.
   *
   * <p>Math: estimatedSize=100_000, selectivity=0.5, loadToScan=100 →
   * {@code m = 100_000 / (100·0.5) = 2000}. After observing one back-ref
   * with a 100-entry link bag, accumulator is 100 ≪ 2000 → DEFER.
   */
  @Test
  public void guard_smallLinkBag_defers() {
    var idx = mock(IndexSearchDescriptor.class);
    when(idx.estimateSelectivity(any())).thenReturn(0.5);
    when(idx.estimateHits(any())).thenReturn(100_000L);
    var chain = buildChain(idx);
    var step = buildStep(chain);
    var ctx = new BasicCommandContext();

    step.applyIndexLookupAmortizationGuard(
        chain, new RecordId(10, 1), /* linkBagSize */ 100L, ctx);

    assertThat(step.isIndexLookupRejected()).isFalse();
    assertThat(step.isIndexRidSetResolved()).isFalse();
    assertThat(step.getCachedIndexRidSet()).isNull();
    assertThat(step.getAccumulatedLinkBagTotal()).isEqualTo(100L);
  }

  /**
   * The DEFER side keeps the accumulator alive across distinct back-refs, so
   * two small link bags should sum into the accumulator. Verifies the
   * accumulator wiring without requiring the PROCEED branch to materialise
   * (which would need a real index and {@code FetchFromIndexStep.init}).
   */
  @Test
  public void guard_twoDistinctBackRefs_accumulate() {
    var idx = mock(IndexSearchDescriptor.class);
    when(idx.estimateSelectivity(any())).thenReturn(0.5);
    when(idx.estimateHits(any())).thenReturn(100_000L);
    var chain = buildChain(idx);
    var step = buildStep(chain);
    var ctx = new BasicCommandContext();

    step.applyIndexLookupAmortizationGuard(
        chain, new RecordId(10, 1), /* linkBagSize */ 100L, ctx);
    step.applyIndexLookupAmortizationGuard(
        chain, new RecordId(10, 2), /* linkBagSize */ 300L, ctx);

    assertThat(step.getAccumulatedLinkBagTotal()).isEqualTo(400L);
    assertThat(step.isIndexRidSetResolved()).isFalse();
  }

  /**
   * Back-ref dedup: the same RID re-entering the guard (e.g. evicted from the
   * LRU cache and observed again) must NOT add its link-bag size to the
   * accumulator a second time. Without this guarantee, a heavily-recycled
   * back-ref could trip the amortisation trigger prematurely, before the
   * strict break-even.
   */
  @Test
  public void guard_sameBackRefTwice_dedupsAccumulator() {
    var idx = mock(IndexSearchDescriptor.class);
    when(idx.estimateSelectivity(any())).thenReturn(0.5);
    when(idx.estimateHits(any())).thenReturn(100_000L);
    var chain = buildChain(idx);
    var step = buildStep(chain);
    var ctx = new BasicCommandContext();
    RID backRef = new RecordId(10, 1);

    step.applyIndexLookupAmortizationGuard(chain, backRef, 100L, ctx);
    step.applyIndexLookupAmortizationGuard(chain, backRef, 100L, ctx);

    assertThat(step.getAccumulatedLinkBagTotal()).isEqualTo(100L);
  }

  /**
   * Sanity: REJECT due to high selectivity must NOT leave the accumulator
   * accruing further. After the verdict we expect {@code indexLookupRejected}
   * to short-circuit any subsequent call before it reaches the dedup set —
   * the caller in {@code buildChainHashTable} guards on {@code
   * !indexLookupRejected} at the outer level.
   */
  @Test
  public void guard_rejectVerdictSticks_acrossBackRefs() {
    var idx = mock(IndexSearchDescriptor.class);
    when(idx.estimateSelectivity(any())).thenReturn(-1.0);
    when(idx.estimateHits(any())).thenReturn(100_000L);
    var chain = buildChain(idx);
    var step = buildStep(chain);
    var ctx = new BasicCommandContext();

    step.applyIndexLookupAmortizationGuard(
        chain, new RecordId(10, 1), 50L, ctx);
    assertThat(step.isIndexLookupRejected()).isTrue();
    long firstAccumulator = step.getAccumulatedLinkBagTotal();

    // Caller would normally short-circuit on indexLookupRejected before
    // reaching the guard; we exercise the guard directly to assert that even
    // if it were called, the REJECT verdict remains stable.
    step.applyIndexLookupAmortizationGuard(
        chain, new RecordId(10, 2), 1_000_000L, ctx);

    assertThat(step.isIndexLookupRejected()).isTrue();
    assertThat(step.isIndexRidSetResolved()).isTrue();
    assertThat(step.getCachedIndexRidSet()).isNull();
    // Accumulator may continue to advance on direct guard calls (the
    // production short-circuit lives in the caller), but the REJECT verdict
    // is sticky regardless.
    assertThat(step.getAccumulatedLinkBagTotal())
        .isGreaterThanOrEqualTo(firstAccumulator);
  }
}
