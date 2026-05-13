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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.junit.Test;

/**
 * Dead-code shape pin for {@link LiveQueryInterruptedException}.
 *
 * <p>PSI find-usages over the project scope confirms <strong>zero production throw / catch /
 * instanceof / new sites</strong> for this exception type. The class persists for binary
 * compatibility on the remote-protocol exception-deserialisation surface but is unreachable from
 * any live execution path. Until a future tracker issue absorbs the deletion item (or
 * declares the type intentionally part of the exception API surface), this pin guarantees the
 * shape stays observable so a deletion commit either removes this file in lockstep or fails at
 * compile time.
 *
 * <p>The pin pattern matches the established {@code *DeadCodeTest} convention codified by
 * earlier tracks: round-trip every public ctor and every accessor, asserting falsifiable
 * observables rather than just checking the type signature.
 */
public class LiveQueryInterruptedExceptionDeadCodeTest {

  /**
   * The {@code (DatabaseSessionEmbedded db, String message)} ctor delegates through
   * {@link CoreException} which extracts the dbName from the session and stamps it into
   * {@link BaseException}'s {@code dbName}. Pin both observables.
   */
  @Test
  public void sessionMessageConstructorPullsDbNameFromSession() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var ex = new LiveQueryInterruptedException(session, "interrupted");

    assertThat(ex.getMessage()).contains("interrupted");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The session-message ctor must accept null session without NPE — pin the null-guard arm.
   */
  @Test
  public void sessionMessageConstructorAcceptsNullSession() {
    var ex = new LiveQueryInterruptedException(null, "interrupted");
    assertThat(ex.getMessage()).contains("interrupted");
    assertThat(ex.getDbName()).isNull();
  }

  /**
   * The {@code (CoreException exception)} copy ctor — note: this is the {@code CoreException}
   * variant (not the more idiomatic {@code (LiveQueryInterruptedException exception)}). Pin the
   * round-trip from a CoreException source.
   */
  @Test
  public void coreExceptionCopyConstructorPreservesMessageAndDbName() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var source = new LiveQueryInterruptedException(session, "interrupted");
    var copy = new LiveQueryInterruptedException(source);

    assertThat(copy.getMessage()).contains("interrupted");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }

  /**
   * {@link LiveQueryInterruptedException} is a {@link CoreException} which is a {@link
   * BaseException} which is a {@link RuntimeException} — pin the chain so a future refactor that
   * re-parents to a checked exception (or to {@link Exception} directly) breaks loudly.
   */
  @Test
  public void exceptionTypeChainIsRuntimeException() {
    var ex = new LiveQueryInterruptedException(null, "interrupted");
    assertThat(ex).isInstanceOf(CoreException.class);
    assertThat(ex).isInstanceOf(BaseException.class);
    assertThat(ex).isInstanceOf(RuntimeException.class);
  }
}
