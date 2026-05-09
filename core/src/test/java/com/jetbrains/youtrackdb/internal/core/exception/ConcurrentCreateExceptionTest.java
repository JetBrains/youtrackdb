/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Bespoke tests for {@link ConcurrentCreateException} — outside the parameterized fan because the
 * primary public ctor takes RIDs rather than message strings, and the class implements its own
 * {@code equals}/{@code hashCode} on the (expectedRid, actualRid) pair. The exception is thrown
 * by distributed-insert paths when the receiving node assigns a different RID than the client
 * predicted, signalling that the operation must be retried.
 */
public class ConcurrentCreateExceptionTest {

  /**
   * The primary ctor must build a deterministic message describing the RID mismatch and store
   * both RIDs for {@code getExpectedRid}/{@code getActualRid} access. Pin both the message
   * substrings and the accessors so a future refactor that inverts the order or drops the helper
   * fails loudly.
   */
  @Test
  public void primaryConstructorRecordsRidsAndBuildsMessage() {
    var expected = new RecordId(5, 10);
    var actual = new RecordId(5, 11);
    var ex = new ConcurrentCreateException("dbA", expected, actual);

    assertThat(ex.getExpectedRid()).isEqualTo(expected);
    assertThat(ex.getActualRid()).isEqualTo(actual);
    assertThat(ex.getMessage())
        .contains(expected.toString())
        .contains(actual.toString())
        .contains("Cannot create the record");
    assertThat(ex.getDbName()).isEqualTo("dbA");
  }

  /**
   * The copy ctor — used by remote-protocol deserialisation — must propagate both RIDs. Pin the
   * round-trip identity-preservation observable so a future refactor that drops a copy field
   * fails loudly.
   */
  @Test
  public void copyConstructorPreservesBothRids() {
    var expected = new RecordId(7, 1);
    var actual = new RecordId(7, 2);
    var original = new ConcurrentCreateException("dbA", expected, actual);
    var copy = new ConcurrentCreateException(original);

    assertThat(copy.getExpectedRid()).isEqualTo(expected);
    assertThat(copy.getActualRid()).isEqualTo(actual);
    // The dbName must propagate via the BaseException copy ctor.
    assertThat(copy.getDbName()).isEqualTo("dbA");
  }

  /**
   * {@link ConcurrentCreateException} extends {@link NeedRetryException} — the
   * caller-retry contract. Pin the class hierarchy because retry handling code uses {@code
   * instanceof NeedRetryException} as the dispatch discriminator.
   */
  @Test
  public void extendsNeedRetryExceptionForRetryDispatch() {
    var ex =
        new ConcurrentCreateException("db", new RecordId(1, 1), new RecordId(1, 2));
    assertThat(ex).isInstanceOf(NeedRetryException.class);
  }

  /**
   * The class overrides {@code equals} on the (expectedRid, actualRid) pair (NOT on the message).
   * Two exceptions with the same RID pair compare equal even with different dbNames — pin the
   * shape so a future refactor that changes the equality semantics fails loudly. This also
   * verifies the {@code instanceof} short-circuit at the top of {@code equals}.
   */
  @Test
  public void equalsComparesOnlyOnRidPair() {
    var a = new ConcurrentCreateException("dbA", new RecordId(1, 1), new RecordId(1, 2));
    var b = new ConcurrentCreateException("dbB", new RecordId(1, 1), new RecordId(1, 2));
    var c = new ConcurrentCreateException("dbA", new RecordId(1, 1), new RecordId(1, 3));

    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(c);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  /**
   * {@code equals} returns false for non-{@code ConcurrentCreateException} arguments — the type
   * narrowing in the {@code if (!(obj instanceof ConcurrentCreateException other))} pattern. Pin
   * the false-branch with a string and a null argument.
   */
  @Test
  public void equalsRejectsForeignTypes() {
    var ex =
        new ConcurrentCreateException("db", new RecordId(1, 1), new RecordId(1, 2));
    assertThat(ex.equals("not an exception")).isFalse();
    assertThat(ex.equals(null)).isFalse();
  }
}
