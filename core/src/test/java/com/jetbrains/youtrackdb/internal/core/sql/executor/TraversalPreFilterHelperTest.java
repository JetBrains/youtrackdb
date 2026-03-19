package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for the adaptive abort guards in {@link TraversalPreFilterHelper}.
 *
 * <p>The guard methods ({@code shouldAbort}, {@code passesRatioCheck}) are
 * pure functions that depend only on integer/double arithmetic, so they can
 * be tested without a database context.
 */
public class TraversalPreFilterHelperTest {

  @After
  public void restoreDefaults() {
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(100_000);
    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.8);
    GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE.setValue(50);
  }

  // =========================================================================
  // shouldAbort — absolute maxRidSetSize cap
  // =========================================================================

  /**
   * When the accumulated count exceeds {@link TraversalPreFilterHelper#maxRidSetSize()},
   * the build must abort regardless of link bag size.
   */
  @Test
  public void shouldAbort_exceedsAbsoluteCap_abortsEvenWithUnknownLinkBag() {
    int count = TraversalPreFilterHelper.maxRidSetSize() + 1;
    assertThat(TraversalPreFilterHelper.shouldAbort(
        count, RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)).isTrue();
  }

  /**
   * When the accumulated count exceeds {@link TraversalPreFilterHelper#maxRidSetSize()},
   * the build must abort even if the link bag is larger.
   */
  @Test
  public void shouldAbort_exceedsAbsoluteCap_abortsEvenWithLargeLinkBag() {
    int count = TraversalPreFilterHelper.maxRidSetSize() + 1;
    int largeLinkBag = TraversalPreFilterHelper.maxRidSetSize() * 2;
    assertThat(TraversalPreFilterHelper.shouldAbort(count, largeLinkBag)).isTrue();
  }

  // =========================================================================
  // shouldAbort — linkBagSize comparison
  // =========================================================================

  /**
   * When the accumulated count exceeds the actual link bag size, the
   * pre-filter contains more entries than the link bag itself, making
   * it useless — abort.
   */
  @Test
  public void shouldAbort_countExceedsLinkBagSize_aborts() {
    assertThat(TraversalPreFilterHelper.shouldAbort(2000, 1000)).isTrue();
  }

  /**
   * When the accumulated count is smaller than the link bag, the build
   * should continue — the filter is still selective.
   */
  @Test
  public void shouldAbort_countBelowLinkBagSize_continues() {
    assertThat(TraversalPreFilterHelper.shouldAbort(500, 10_000)).isFalse();
  }

  /**
   * When the link bag size is unknown (sentinel value), only the
   * absolute cap applies. A moderate count should not abort.
   */
  @Test
  public void shouldAbort_unknownLinkBagSize_onlyAbsoluteCapApplies() {
    assertThat(TraversalPreFilterHelper.shouldAbort(
        50_000, RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)).isFalse();
  }

  /**
   * At the exact maxRidSetSize boundary, the count is not yet exceeded
   * (guard uses strict {@code >}).
   */
  @Test
  public void shouldAbort_exactlyAtAbsoluteCap_doesNotAbort() {
    assertThat(TraversalPreFilterHelper.shouldAbort(
        TraversalPreFilterHelper.maxRidSetSize(), 200_000)).isFalse();
  }

  // =========================================================================
  // passesRatioCheck — selectivity ratio
  // =========================================================================

  /**
   * A RidSet that is small relative to the link bag passes the ratio
   * check (the filter is highly selective).
   */
  @Test
  public void passesRatioCheck_smallRidSetVsLargeLinkBag_passes() {
    assertThat(TraversalPreFilterHelper.passesRatioCheck(100, 10_000)).isTrue();
  }

  /**
   * A RidSet that covers more than {@link TraversalPreFilterHelper#maxSelectivityRatio()}
   * of the link bag fails — the filter rejects too few elements.
   */
  @Test
  public void passesRatioCheck_ridSetTooLargeRelativeToLinkBag_fails() {
    int linkBag = 1000;
    int ridSetSize =
        (int) (linkBag * TraversalPreFilterHelper.maxSelectivityRatio()) + 1;
    assertThat(TraversalPreFilterHelper.passesRatioCheck(ridSetSize, linkBag))
        .isFalse();
  }

  /**
   * When the link bag size is unknown (negative), the ratio check always
   * passes — no comparison can be made.
   */
  @Test
  public void passesRatioCheck_unknownLinkBagSize_passes() {
    assertThat(TraversalPreFilterHelper.passesRatioCheck(
        90_000, RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE)).isTrue();
  }

  /**
   * When the link bag size is zero, the ratio check passes (avoids
   * division by zero; zero-size link bag means no records anyway).
   */
  @Test
  public void passesRatioCheck_zeroLinkBag_passes() {
    assertThat(TraversalPreFilterHelper.passesRatioCheck(0, 0)).isTrue();
  }

  /**
   * A RidSet exactly at the ratio boundary passes (guard uses {@code <=}).
   */
  @Test
  public void passesRatioCheck_exactlyAtBoundary_passes() {
    int linkBag = 1000;
    int ridSetSize =
        (int) (linkBag * TraversalPreFilterHelper.maxSelectivityRatio());
    assertThat(TraversalPreFilterHelper.passesRatioCheck(ridSetSize, linkBag))
        .isTrue();
  }

  /**
   * An empty RidSet always passes the ratio check.
   */
  @Test
  public void passesRatioCheck_emptyRidSet_passes() {
    assertThat(TraversalPreFilterHelper.passesRatioCheck(0, 10_000)).isTrue();
  }

  // =========================================================================
  // Defaults sanity
  // =========================================================================

  /**
   * The three adaptive-abort defaults must have their documented values.
   */
  @Test
  public void defaults_haveExpectedValues() {
    assertThat(TraversalPreFilterHelper.maxRidSetSize()).isEqualTo(100_000);
    assertThat(TraversalPreFilterHelper.maxSelectivityRatio()).isEqualTo(0.8);
    assertThat(TraversalPreFilterHelper.minLinkBagSize()).isEqualTo(50);
  }

  /**
   * The unknown link bag size sentinel must be negative.
   */
  @Test
  public void unknownLinkBagSizeSentinel_isNegative() {
    assertThat(RidFilterDescriptor.UNKNOWN_LINKBAG_SIZE).isNegative();
  }

  // =========================================================================
  // Runtime override via GlobalConfiguration
  // =========================================================================

  /**
   * Verifies that changing configuration at runtime is immediately
   * reflected by the getter methods and affects guard behaviour.
   */
  @Test
  public void runtimeOverride_affectsGuardBehaviour() {
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(500);
    assertThat(TraversalPreFilterHelper.maxRidSetSize()).isEqualTo(500);
    assertThat(TraversalPreFilterHelper.shouldAbort(501, 10_000)).isTrue();
    assertThat(TraversalPreFilterHelper.shouldAbort(499, 10_000)).isFalse();

    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.5);
    assertThat(TraversalPreFilterHelper.maxSelectivityRatio()).isEqualTo(0.5);
    assertThat(TraversalPreFilterHelper.passesRatioCheck(600, 1000)).isFalse();
    assertThat(TraversalPreFilterHelper.passesRatioCheck(500, 1000)).isTrue();

    GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE.setValue(200);
    assertThat(TraversalPreFilterHelper.minLinkBagSize()).isEqualTo(200);
  }
}
