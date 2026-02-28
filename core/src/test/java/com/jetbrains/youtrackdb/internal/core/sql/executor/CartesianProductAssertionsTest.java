package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tests both branches of each assertion helper in {@link CartesianProductAssertions}
 * to ensure full branch coverage (JaCoCo requires both the success and failure
 * paths to be exercised).
 */
public class CartesianProductAssertionsTest {

  /**
   * checkStreamBuilt returns true when the stream is non-null (normal case).
   */
  @Test
  public void checkStreamBuiltReturnsTrueForNonNullStream() {
    assertThat(CartesianProductAssertions.checkStreamBuilt(Stream.empty())).isTrue();
  }

  /**
   * checkStreamBuilt throws AssertionError when the stream is null,
   * indicating that no sub-plans were added before building the Cartesian product.
   */
  @Test
  public void checkStreamBuiltThrowsForNullStream() {
    assertThatThrownBy(() -> CartesianProductAssertions.checkStreamBuilt(null))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("sub-plan list must not be empty");
  }

  /**
   * checkSubPlanNotNull returns true when the sub-plan is non-null (normal case).
   */
  @Test
  public void checkSubPlanNotNullReturnsTrueForNonNullPlan() {
    var plan = new SelectExecutionPlan(null);
    assertThat(CartesianProductAssertions.checkSubPlanNotNull(plan)).isTrue();
  }

  /**
   * checkSubPlanNotNull throws AssertionError when the sub-plan is null.
   */
  @Test
  public void checkSubPlanNotNullThrowsForNullPlan() {
    assertThatThrownBy(() -> CartesianProductAssertions.checkSubPlanNotNull(null))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("sub-plan must not be null");
  }
}
