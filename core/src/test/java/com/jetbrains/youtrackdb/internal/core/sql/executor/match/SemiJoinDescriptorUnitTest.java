package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Unit tests for the three {@link SemiJoinDescriptor} record implementations.
 *
 * <p>The interface methods ({@code joinMode}, {@code backRefAlias}) are small
 * dispatch points wired into {@link BackRefHashJoinStep} and
 * {@link MatchExecutionPlanner}. Exercising them directly avoids relying on a
 * full end-to-end MATCH test to reach every descriptor variant — integration
 * coverage is patchy for Pattern D (NOT IN anti-join) because it requires a
 * specific WHERE shape that other tests do not always construct.
 *
 * <p>Each test asserts on every record component (not just the interface
 * methods) so that a silent mis-wiring of a constructor argument — for
 * example, swapping {@code sourceAlias} and {@code backRefAlias} — is caught
 * at the record boundary rather than surviving to runtime.
 */
public class SemiJoinDescriptorUnitTest {

  /**
   * {@link SingleEdgeSemiJoin} (Pattern A) reports SEMI_JOIN and exposes
   * every record component exactly as passed to the constructor. The
   * distinct-value matrix (each argument is a unique string) verifies that
   * no two accessors return the same underlying field — a common mistake in
   * hand-written records.
   */
  @Test
  public void singleEdgeSemiJoinExposesAllComponents() {
    var descriptor = new SingleEdgeSemiJoin(
        "KNOWS", "out", null,
        "sourceAlias", "anchorAlias", "targetAlias", null);

    assertSame(JoinMode.SEMI_JOIN, descriptor.joinMode());
    assertEquals("KNOWS", descriptor.edgeClass());
    assertEquals("out", descriptor.direction());
    assertNull(descriptor.backRefExpression());
    assertEquals("sourceAlias", descriptor.sourceAlias());
    assertEquals("anchorAlias", descriptor.backRefAlias());
    assertEquals("targetAlias", descriptor.targetAlias());
    assertNull(descriptor.targetFilter());
  }

  /**
   * {@link ChainSemiJoin} (Pattern B) reports SEMI_JOIN and exposes every
   * record component. Verifies the three alias fields
   * ({@code sourceAlias}, {@code backRefAlias}, {@code intermediateAlias},
   * {@code targetAlias}) are independently addressable — a wrong
   * constructor-argument order would leak into the semi-join descriptor.
   */
  @Test
  public void chainSemiJoinExposesAllComponents() {
    var descriptor = new ChainSemiJoin(
        "HAS_MEMBER", "out", null,
        "sourceAlias", "anchorAlias",
        "intermediateAlias", "targetAlias", null, null);

    assertSame(JoinMode.SEMI_JOIN, descriptor.joinMode());
    assertEquals("HAS_MEMBER", descriptor.edgeClass());
    assertEquals("out", descriptor.direction());
    assertNull(descriptor.backRefExpression());
    assertEquals("sourceAlias", descriptor.sourceAlias());
    assertEquals("anchorAlias", descriptor.backRefAlias());
    assertEquals("intermediateAlias", descriptor.intermediateAlias());
    assertEquals("targetAlias", descriptor.targetAlias());
    assertNull(descriptor.indexFilter());
    assertNull(descriptor.edgeFilter());
  }

  /**
   * {@link AntiSemiJoin} (Pattern D) reports ANTI_JOIN and — unlike the other
   * two variants — overrides {@code backRefAlias()} to return
   * {@code anchorAlias()}. Asserting both values separately guards against
   * a future refactor that stores a separate {@code backRefAlias} field and
   * forgets to forward it through the override.
   */
  @Test
  public void antiSemiJoinReportsAntiJoinAndAnchorAsBackRef() {
    var descriptor = new AntiSemiJoin(
        "anchorAlias", "HAS_MEMBER", "out", "targetAlias", null);

    assertSame(JoinMode.ANTI_JOIN, descriptor.joinMode());
    assertEquals("anchorAlias", descriptor.anchorAlias());
    assertEquals("HAS_MEMBER", descriptor.traversalEdgeClass());
    assertEquals("out", descriptor.traversalDirection());
    assertEquals("targetAlias", descriptor.targetAlias());
    assertNull(descriptor.notInCondition());
    // backRefAlias() is an interface method override on AntiSemiJoin —
    // unlike the other two variants it does not take the value from a
    // dedicated constructor argument. It must mirror anchorAlias().
    assertEquals("anchorAlias", descriptor.backRefAlias());
    assertEquals(descriptor.anchorAlias(), descriptor.backRefAlias());
  }
}
