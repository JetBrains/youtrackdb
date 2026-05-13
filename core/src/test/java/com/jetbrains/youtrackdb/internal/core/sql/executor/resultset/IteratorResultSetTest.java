package com.jetbrains.youtrackdb.internal.core.sql.executor.resultset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Direct coverage for {@link IteratorResultSet}. The class is a public-API ResultSet
 * implementation that wraps a plain iterator and yields each value as a
 * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal} under the
 * property key {@code "value"} (hardcoded; not configurable per IteratorResultSet).
 *
 * <p>The assert statements in IteratorResultSet use {@code session.assertIfNotActive()}, which
 * is a no-op when the session is null and a liveness check when a real session is provided.
 * Both paths are exercised: a session-less standalone test for wrap semantics and a
 * {@link DbTestBase}-backed test that verifies the liveness assertion does not fail for an
 * active session. We extend {@link DbTestBase} so both paths share one fixture.
 */
public class IteratorResultSetTest extends DbTestBase {

  // =========================================================================
  // Session-less iterator (pure wrap semantics)
  // =========================================================================

  /**
   * With a null session, wrap each primitive value in a ResultInternal under the "value" key.
   */
  @Test
  public void iteratorWrapsPrimitivesUnderValueKey() {
    try (ResultSet rs = new IteratorResultSet(null, Arrays.asList(1, 2, 3).iterator())) {
      assertThat(rs.hasNext()).isTrue();
      assertThat(rs.next().<Integer>getProperty("value")).isEqualTo(1);
      assertThat(rs.next().<Integer>getProperty("value")).isEqualTo(2);
      assertThat(rs.next().<Integer>getProperty("value")).isEqualTo(3);
      assertThat(rs.hasNext()).isFalse();
    }
  }

  /**
   * {@code next()} on an exhausted iterator throws {@link NoSuchElementException}
   * (NOT IllegalStateException — this is a public-API ResultSet contract).
   */
  @Test
  public void nextOnExhaustedThrowsNoSuchElementException() {
    try (ResultSet rs = new IteratorResultSet(null, Collections.emptyIterator())) {
      assertThatThrownBy(rs::next).isInstanceOf(NoSuchElementException.class);
    }
  }

  /**
   * {@code close()} is idempotent and flips {@code isClosed()}. After close, {@code hasNext}
   * short-circuits to false even if the underlying iterator still has elements.
   */
  @Test
  public void closeIsIdempotentAndShortCircuitsHasNext() {
    var rs = new IteratorResultSet(null, Arrays.asList(1, 2, 3).iterator());
    assertThat(rs.isClosed()).isFalse();
    rs.close();
    assertThat(rs.isClosed()).isTrue();
    assertThat(rs.hasNext()).isFalse();

    // Idempotent: second close does not throw and does not change state.
    rs.close();
    assertThat(rs.isClosed()).isTrue();
  }

  /**
   * {@code trySplit} always returns null (stream is not splittable) and
   * {@code estimateSize} returns Long.MAX_VALUE (size unknown).
   */
  @Test
  public void spliteratorCharacteristicsAreOrderedUnknownSize() {
    try (var rs = new IteratorResultSet(null, Arrays.asList(1, 2).iterator())) {
      assertThat((Object) rs.trySplit()).isNull();
      assertThat(rs.estimateSize()).isEqualTo(Long.MAX_VALUE);
      assertThat(rs.characteristics()).isEqualTo(ResultSet.ORDERED);
    }
  }

  /**
   * {@code tryAdvance} returns true and invokes the action when a result is available,
   * false when exhausted (without invoking the action).
   */
  @Test
  public void tryAdvanceAdvancesAndStopsAtEnd() {
    try (var rs = new IteratorResultSet(null, Arrays.asList(42).iterator())) {
      var captured = new AtomicInteger(-1);
      assertThat(rs.tryAdvance(r -> captured.set(r.<Integer>getProperty("value")))).isTrue();
      assertThat(captured.get()).isEqualTo(42);

      // Action must not be invoked on exhaustion.
      var actionCalls = new int[1];
      assertThat(rs.tryAdvance(r -> actionCalls[0]++)).isFalse();
      assertThat(actionCalls[0]).isZero();
    }
  }

  /**
   * {@code forEachRemaining} drains all remaining values in order.
   */
  @Test
  public void forEachRemainingDrainsInOrder() {
    try (var rs = new IteratorResultSet(null, Arrays.asList("x", "y", "z").iterator())) {
      var drained = new ArrayList<String>();
      rs.forEachRemaining(r -> drained.add(r.getProperty("value")));
      assertThat(drained).containsExactly("x", "y", "z");
    }
  }

  /**
   * {@code getExecutionPlan} is always null for IteratorResultSet (no underlying plan).
   */
  @Test
  public void getExecutionPlanIsAlwaysNull() {
    try (var rs = new IteratorResultSet(null, Collections.emptyIterator())) {
      assertThat(rs.getExecutionPlan()).isNull();
    }
  }

  /**
   * With a null session, {@code getBoundToSession} returns null.
   */
  @Test
  public void boundToSessionReturnsNullWhenNoSession() {
    try (var rs = new IteratorResultSet(null, Collections.emptyIterator())) {
      assertThat(rs.getBoundToSession()).isNull();
    }
  }

  // =========================================================================
  // Active session path — exercises the session.assertIfNotActive() assertion chain
  // =========================================================================

  /**
   * When a live session is attached, hasNext/next still work and the assert does not
   * fire. This exercises the "session != null" branch in every assert call.
   */
  @Test
  public void activeSessionPathDrainsWithoutAssertionFailure() {
    try (var rs = new IteratorResultSet(session, Arrays.asList("a", "b").iterator())) {
      assertThat(rs.getBoundToSession()).isSameAs(session);
      assertThat(rs.hasNext()).isTrue();
      assertThat(rs.next().<String>getProperty("value")).isEqualTo("a");
      assertThat(rs.next().<String>getProperty("value")).isEqualTo("b");
      assertThat(rs.hasNext()).isFalse();
    }
  }
}
