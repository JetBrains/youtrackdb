package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.junit.Test;

/**
 * Unit tests for {@link StepStreamCursor}, the {@link StepCursor} implementation the walker drives.
 * They pin the mechanics recognisers rely on: transparent (barrier) steps are skipped and counted as
 * consumed at the head, interleaved, and trailing; matching is by exact class; {@code takeIf} leaves a
 * non-matching step; {@code takeWhile} stops at the first non-match; {@code peek} / {@code peek(int)}
 * read ahead without consuming; and {@code position()} tracks consumption.
 *
 * <p>Steps are real TinkerPop instances built against a host traversal admin (the constructors {@link
 * HasStep} and {@link NoOpBarrierStep} accept one), so exact-class matching sees the same runtime
 * classes production does. The transparent set is {@code NoOpBarrierStep}, mirroring the walker.
 */
public class StepStreamCursorTest extends GraphBaseTest {

  private static final Set<Class<?>> TRANSPARENT = Set.of(NoOpBarrierStep.class);

  /**
   * {@code peek} and {@code take} skip a leading run of transparent steps and count them as consumed:
   * the first significant step is returned, and {@code position()} has advanced past the skipped
   * barriers plus the taken step.
   */
  @Test
  public void peekAndTake_skipLeadingBarriers() {
    var admin = graph.traversal().V().asAdmin();
    var barrier1 = new NoOpBarrierStep<>(admin);
    var barrier2 = new NoOpBarrierStep<>(admin);
    var has = new HasStep<>(admin, new HasContainer("k", P.eq(1)));
    var cursor = new StepStreamCursor(List.of(barrier1, barrier2, has), TRANSPARENT);

    assertThat(cursor.peek()).as("peek skips the two leading barriers to the has step")
        .isSameAs(has);
    assertThat(cursor.take()).as("take returns the same significant head").isSameAs(has);
    assertThat(cursor.position())
        .as("two barriers plus the has step are all consumed")
        .isEqualTo(3);
    assertThat(cursor.peek()).as("nothing significant remains").isNull();
  }

  /**
   * {@code takeWhile} consumes the leading run of the exact class, skips a barrier interleaved in the
   * run, and stops at (and leaves) the first step that is not of the class.
   */
  @Test
  public void takeWhile_consumesRunSkipsInterleavedBarrierStopsAtNonMatch() {
    var admin = graph.traversal().V().asAdmin();
    var has1 = new HasStep<>(admin, new HasContainer("a", P.eq(1)));
    var barrier = new NoOpBarrierStep<>(admin);
    var has2 = new HasStep<>(admin, new HasContainer("b", P.eq(2)));
    var vertexStep = vertexStep();
    var cursor = new StepStreamCursor(List.of(has1, barrier, has2, vertexStep), TRANSPARENT);

    var run = cursor.takeWhile(HasStep.class);

    assertThat(run).as("the has run is returned, the interleaved barrier is not").containsExactly(
        has1, has2);
    assertThat(cursor.peek()).as("the run stops at the non-matching vertex step")
        .isSameAs(vertexStep);
  }

  /** {@code takeWhile} returns an empty list and consumes nothing when the head does not match. */
  @Test
  public void takeWhile_returnsEmptyWhenHeadDoesNotMatch() {
    var admin = graph.traversal().V().asAdmin();
    var vertexStep = vertexStep();
    var has = new HasStep<>(admin, new HasContainer("k", P.eq(1)));
    var cursor = new StepStreamCursor(List.of(vertexStep, has), TRANSPARENT);

    var run = cursor.takeWhile(HasStep.class);

    assertThat(run).as("no leading has step").isEmpty();
    assertThat(cursor.peek()).as("the head is left in place").isSameAs(vertexStep);
  }

  /**
   * {@code takeIf} consumes a matching head and leaves a non-matching one. Exact-class matching means a
   * class token the head is not an instance of leaves the head for the next read.
   */
  @Test
  public void takeIf_consumesOnMatchLeavesOnMismatch() {
    var admin = graph.traversal().V().asAdmin();
    var has = new HasStep<>(admin, new HasContainer("k", P.eq(1)));
    var vertexStep = vertexStep();
    var cursor = new StepStreamCursor(List.of(has, vertexStep), TRANSPARENT);

    // Bind takeIf's result to a typed local: its raw generic return (T = HasStep) would otherwise
    // degrade AssertJ's assertThat to a raw Object assert with no isSameAs / isNull.
    Step<?, ?> consumedHas = cursor.takeIf(HasStep.class);
    assertThat(consumedHas).as("the has head is consumed").isSameAs(has);
    Step<?, ?> notAHas = cursor.takeIf(HasStep.class);
    assertThat(notAHas).as("the vertex head is not a HasStep, so takeIf leaves it").isNull();
    assertThat(cursor.peek()).as("the left step is still the head").isSameAs(vertexStep);
    Step<?, ?> consumedVertex = cursor.takeIf(VertexStep.class);
    assertThat(consumedVertex).as("its exact class matches now").isSameAs(vertexStep);
  }

  /** {@code takeIf} with a predicate leaves the step when the predicate is false and consumes it when
   *  the predicate is true. */
  @Test
  public void takeIf_withPredicate_respectsCondition() {
    var admin = graph.traversal().V().asAdmin();
    var has = new HasStep<>(admin, new HasContainer("k", P.eq(1)));
    var cursor = new StepStreamCursor(List.of(has), TRANSPARENT);

    // Bind to a typed local — see takeIf_consumesOnMatchLeavesOnMismatch for the raw-return reason.
    Step<?, ?> notTaken = cursor.takeIf(HasStep.class, h -> false);
    assertThat(notTaken).as("a false predicate leaves the step").isNull();
    Step<?, ?> taken = cursor.takeIf(HasStep.class, h -> true);
    assertThat(taken).as("a true predicate consumes the same step").isSameAs(has);
  }

  /**
   * {@code peek(int)} reads ahead across barriers and the head without consuming: {@code peek(0)} is
   * the head, higher indices skip transparent steps and step over earlier significant steps, and an
   * index past the end returns {@code null}. A following {@code take} still returns the head, proving
   * {@code peek} left the position untouched.
   */
  @Test
  public void peekAhead_readsPastBarriersWithoutConsuming() {
    var admin = graph.traversal().V().asAdmin();
    var has1 = new HasStep<>(admin, new HasContainer("a", P.eq(1)));
    var barrier = new NoOpBarrierStep<>(admin);
    var has2 = new HasStep<>(admin, new HasContainer("b", P.eq(2)));
    var vertexStep = vertexStep();
    var cursor = new StepStreamCursor(List.of(has1, barrier, has2, vertexStep), TRANSPARENT);

    assertThat(cursor.peek(0)).as("peek(0) is the head").isSameAs(has1);
    assertThat(cursor.peek(1)).as("peek(1) skips the barrier to the second has step")
        .isSameAs(has2);
    assertThat(cursor.peek(2)).as("peek(2) is the vertex step").isSameAs(vertexStep);
    assertThat(cursor.peek(3)).as("peek past the last significant step is null").isNull();
    assertThat(cursor.position()).as("peek consumes nothing").isZero();
    assertThat(cursor.take()).as("take still returns the head").isSameAs(has1);
  }

  /** A negative lookahead is a programming error, not a normal path. */
  @Test
  public void peekNegative_throws() {
    var cursor = new StepStreamCursor(List.of(), TRANSPARENT);

    assertThatThrownBy(() -> cursor.peek(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * A trailing run of transparent steps is skipped by {@code peek} and counted as consumed, so once
   * the significant steps are gone {@code peek} is {@code null} and {@code position()} has reached the
   * end of the list — the invariant the walker relies on for "every step recognised".
   */
  @Test
  public void trailingBarriers_areSkippedSoPositionReachesEnd() {
    var admin = graph.traversal().V().asAdmin();
    var has = new HasStep<>(admin, new HasContainer("k", P.eq(1)));
    var barrier1 = new NoOpBarrierStep<>(admin);
    var barrier2 = new NoOpBarrierStep<>(admin);
    var cursor = new StepStreamCursor(List.of(has, barrier1, barrier2), TRANSPARENT);

    assertThat(cursor.take()).isSameAs(has);
    assertThat(cursor.peek()).as("only trailing barriers remain").isNull();
    assertThat(cursor.position())
        .as("the trailing barriers are skipped, so the position reaches the end")
        .isEqualTo(3);
  }

  /** {@code take} past the end is a recogniser bug, so it throws rather than returning null. */
  @Test
  public void takePastEnd_throws() {
    var cursor = new StepStreamCursor(List.<Step<?, ?>>of(), TRANSPARENT);

    assertThatThrownBy(cursor::take).isInstanceOf(NoSuchElementException.class);
  }

  /** A real bare-hop {@link VertexStep}, whose exact runtime class is {@code VertexStep}. */
  private VertexStep<?> vertexStep() {
    return (VertexStep<?>) graph.traversal().V().out("knows").asAdmin().getSteps().get(1);
  }
}
