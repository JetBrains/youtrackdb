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

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import org.junit.Test;

/**
 * Dead-code shape pin for {@link ManualIndexesAreProhibited}.
 *
 * <p>PSI find-usages over the project scope confirms <strong>zero production throw / catch /
 * instanceof / new sites</strong> for this exception type. The class persists for binary
 * compatibility on the remote-protocol exception-deserialisation surface but is unreachable from
 * any live execution path. Pin the shape so a deletion commit either removes this file in
 * lockstep or fails at compile time.
 */
public class ManualIndexesAreProhibitedDeadCodeTest {

  /**
   * The {@code (String dbName, String message)} ctor must round-trip both fields through
   * {@link CoreException}'s decorating {@code getMessage()} override.
   */
  @Test
  public void dbNameAndMessageConstructorRoundTripsBoth() {
    var ex = new ManualIndexesAreProhibited("dbX", "manual indexes are prohibited");
    assertThat(ex.getMessage())
        .contains("manual indexes are prohibited")
        .contains("DB Name=\"dbX\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /** The copy ctor preserves message and dbName via the {@link BaseException} chain. */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var original = new ManualIndexesAreProhibited("dbX", "manual indexes are prohibited");
    var copy = new ManualIndexesAreProhibited(original);

    assertThat(copy.getMessage()).contains("manual indexes are prohibited");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }

  /**
   * {@link ManualIndexesAreProhibited} implements {@link HighLevelException} which is the
   * dispatch marker for "stop wrapping, propagate as-is" through {@link
   * BaseException#wrapException}. Pin the marker so a future refactor that drops the interface
   * fails loudly — the wrapping behaviour is observable through {@code wrapException}.
   */
  @Test
  public void implementsHighLevelException() {
    var ex = new ManualIndexesAreProhibited("dbX", "boom");
    assertThat(ex).isInstanceOf(HighLevelException.class);
    assertThat(ex).isInstanceOf(CoreException.class);
  }
}
