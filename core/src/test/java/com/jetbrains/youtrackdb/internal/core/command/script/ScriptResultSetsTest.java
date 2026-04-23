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
package com.jetbrains.youtrackdb.internal.core.command.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.resultset.ResultSetTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;
import org.junit.Test;

/**
 * Tests for the {@link ScriptResultSets} static factory and the {@link ScriptResultSet} iterator it
 * returns. The factory has two methods: {@link ScriptResultSets#empty(DatabaseSessionEmbedded)}
 * wraps an empty iterator; {@link ScriptResultSets#singleton(DatabaseSessionEmbedded, Object,
 * ScriptTransformer)} wraps a one-element iterator and applies the supplied transformer on
 * {@code next()}. Tests run under {@link DbTestBase} because {@link ScriptResultSet#next()} calls
 * {@code session.assertIfNotActive()} which requires a live database session bound to the current
 * thread.
 */
public class ScriptResultSetsTest extends DbTestBase {

  // ==========================================================================
  // empty(db) — session is passed through; iterator is the shared empty-list
  // iterator; transformer is null (empty iterator never invokes it).
  // ==========================================================================

  /**
   * {@code empty(db)} must return a non-null {@link ScriptResultSet} that reports
   * {@code hasNext() == false} on construction. This pins the zero-element contract.
   */
  @Test
  public void emptyReturnsResultSetWithNoElements() {
    try (ResultSet rs = ScriptResultSets.empty(session)) {
      assertNotNull(rs);
      assertFalse(rs.hasNext());
    }
  }

  /**
   * Calling {@code next()} on an empty {@link ScriptResultSet} must throw
   * {@link NoSuchElementException}. This pins the "drained iterator" shape and matches
   * {@link java.util.Iterator#next()} semantics — the transformer is never invoked.
   */
  @Test
  public void emptyNextThrowsNoSuchElement() {
    try (ResultSet rs = ScriptResultSets.empty(session)) {
      assertThrows(NoSuchElementException.class, rs::next);
    }
  }

  /**
   * {@code empty(db)} must bind the supplied session to the underlying
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.IteratorResultSet#getBoundToSession()}
   * so script consumers can recover the session. The transformer argument is null on the
   * empty path — asserted indirectly via no crash on construction.
   */
  @Test
  public void emptyBindsSuppliedSession() {
    try (var rs = ScriptResultSets.empty(session)) {
      assertSame(
          "empty() must bind the supplied session", session, rs.getBoundToSession());
    }
  }

  // ==========================================================================
  // singleton(db, entity, transformer) — exactly one element; the transformer
  // is invoked exactly once on next(); iteration is exhausted afterwards.
  // ==========================================================================

  /**
   * {@code singleton(db, value, transformer)} must report {@code hasNext() == true} initially
   * and {@code hasNext() == false} after a single {@link ResultSet#next()} call. This pins the
   * one-element contract.
   */
  @Test
  public void singletonHasExactlyOneElement() {
    final var transformer = new CountingTransformer();
    try (var rs = ScriptResultSets.singleton(session, "hello", transformer)) {
      assertTrue(rs.hasNext());
      final var first = rs.next();
      assertNotNull(first);
      // Exhausted after one call — no second element.
      assertFalse(rs.hasNext());
    }
    // Transformer.toResult must be called exactly once (one element, one conversion).
    assertEquals(1, transformer.toResultCalls);
  }

  /**
   * The transformer's {@link ScriptTransformer#toResult(DatabaseSessionEmbedded, Object)} must
   * be invoked with the entity passed to {@link ScriptResultSets#singleton}. This pins the
   * delegation contract: {@link ScriptResultSet#next()} calls {@code transformer.toResult(session,
   * element)} and returns its output verbatim.
   */
  @Test
  public void singletonDelegatesToTransformerOnNext() {
    final var payload = "script-output";
    final var expected = new ResultInternal(session);
    expected.setProperty("value", payload);

    final var transformer = new FixedTransformer(expected);
    try (var rs = ScriptResultSets.singleton(session, payload, transformer)) {
      final var actual = rs.next();
      assertSame(
          "singleton.next() must return the transformer's output verbatim", expected, actual);
    }
    assertEquals(
        "transformer must see the entity passed to singleton()", payload, transformer.lastInput);
  }

  /**
   * {@code singleton} must bind the supplied session so consumers can close the result set
   * against the originating session. Matches the {@code empty()} contract.
   */
  @Test
  public void singletonBindsSuppliedSession() {
    try (var rs = ScriptResultSets.singleton(session, "x", new CountingTransformer())) {
      assertSame(session, rs.getBoundToSession());
    }
  }

  /**
   * After calling {@code next()} exactly once, a subsequent {@code next()} must throw
   * {@link NoSuchElementException} — the iterator is drained. This pins the exhaustion shape
   * across the {@link ScriptResultSet#next()} override which delegates to
   * {@code iterator.hasNext()} before reading.
   */
  @Test
  public void singletonNextAfterDrainThrowsNoSuchElement() {
    final var transformer = new CountingTransformer();
    try (var rs = ScriptResultSets.singleton(session, "only", transformer)) {
      rs.next();
      assertThrows(NoSuchElementException.class, rs::next);
    }
  }

  /**
   * {@code singleton} must accept a {@code null} entity — the underlying
   * {@link java.util.Collections#singletonList} permits nulls and forwards them to the
   * transformer verbatim. This pins the null-element tolerance.
   */
  @Test
  public void singletonAcceptsNullEntity() {
    final var expected = new ResultInternal(session);
    final var transformer = new FixedTransformer(expected);
    try (var rs = ScriptResultSets.singleton(session, null, transformer)) {
      assertTrue(rs.hasNext());
      final var first = rs.next();
      assertSame(expected, first);
      assertNull("transformer must see the null element", transformer.lastInput);
    }
  }

  // ==========================================================================
  // Test fixtures — synthetic ScriptTransformer implementations that record
  // invocations without pulling in ScriptTransformerImpl (out of Step 4b scope).
  // ==========================================================================

  /**
   * Counts invocations without producing meaningful {@link Result}s. Used when only the
   * iteration contract is under test.
   */
  private static final class CountingTransformer implements ScriptTransformer {

    int toResultCalls = 0;

    @Nullable @Override
    public ResultSet toResultSet(DatabaseSessionEmbedded db, Object value) {
      return null;
    }

    @Override
    public Result toResult(DatabaseSessionEmbedded db, Object value) {
      toResultCalls++;
      return new ResultInternal(db);
    }

    @Override
    public boolean doesHandleResult(Object value) {
      return false;
    }

    @Override
    public void registerResultTransformer(Class clazz, ResultTransformer t) {
      // no-op — not exercised by ScriptResultSet.
    }

    @Override
    public void registerResultSetTransformer(Class clazz, ResultSetTransformer t) {
      // no-op.
    }
  }

  /**
   * Returns a fixed {@link Result} on every {@code toResult(...)} call and records the last
   * input for equality assertions.
   */
  private static final class FixedTransformer implements ScriptTransformer {

    private final Result fixed;
    Object lastInput;

    FixedTransformer(Result fixed) {
      this.fixed = fixed;
    }

    @Nullable @Override
    public ResultSet toResultSet(DatabaseSessionEmbedded db, Object value) {
      return null;
    }

    @Override
    public Result toResult(DatabaseSessionEmbedded db, Object value) {
      lastInput = value;
      return fixed;
    }

    @Override
    public boolean doesHandleResult(Object value) {
      return false;
    }

    @Override
    public void registerResultTransformer(Class clazz, ResultTransformer t) {
      // no-op.
    }

    @Override
    public void registerResultSetTransformer(Class clazz, ResultSetTransformer t) {
      // no-op.
    }
  }
}
