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
 * Dead-code shape pin for {@link InternalErrorException}.
 *
 * <p>PSI find-usages over the project scope confirms <strong>zero production {@code new
 * InternalErrorException(...)} or {@code throw new InternalErrorException(...)} sites</strong>.
 * The class persists because four call sites in {@code AbstractStorage.java} (lines 1608, 3465,
 * 5619, 5663) use {@code instanceof InternalErrorException} as a multi-catch discriminator —
 * the type is on the API surface but is never produced by any production code path. Pin the
 * shape so a deletion commit either removes this file in lockstep or fails at compile time.
 *
 * <p>The {@code instanceof}-only usage means the class is "API but not implementation" — a
 * commit that re-introduces an instantiation site MUST be tested with a falsifiable observable
 * (which is what this pin provides via the dbName + message round-trip).
 */
public class InternalErrorExceptionDeadCodeTest {

  /**
   * The {@code (DatabaseSessionEmbedded db, String string)} ctor delegates through {@link
   * CoreException} to extract the dbName and stamp the message.
   */
  @Test
  public void sessionMessageConstructorPullsDbNameFromSession() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var ex = new InternalErrorException(session, "internal error");

    assertThat(ex.getMessage()).contains("internal error");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /** The session ctor must accept null without NPE. */
  @Test
  public void sessionMessageConstructorAcceptsNullSession() {
    var ex = new InternalErrorException(null, "internal error");
    assertThat(ex.getMessage()).contains("internal error");
    assertThat(ex.getDbName()).isNull();
  }

  /** The copy ctor must propagate the message and dbName. */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var source = new InternalErrorException(session, "internal error");
    var copy = new InternalErrorException(source);

    assertThat(copy.getMessage()).contains("internal error");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }

  /**
   * Pin the type chain — {@link CoreException} → {@link BaseException} → {@link
   * RuntimeException}. The four {@code instanceof InternalErrorException} sites in
   * {@code AbstractStorage} rely on this hierarchy.
   */
  @Test
  public void exceptionTypeChainIsRuntimeException() {
    var ex = new InternalErrorException(null, "x");
    assertThat(ex).isInstanceOf(CoreException.class);
    assertThat(ex).isInstanceOf(BaseException.class);
    assertThat(ex).isInstanceOf(RuntimeException.class);
  }
}
