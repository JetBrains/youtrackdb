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
 * Dead-code shape pin for the abstract {@link RetryQueryException}.
 *
 * <p>The class is abstract and PSI find-usages confirms there are no concrete subclasses or
 * production {@code throw new RetryQueryException(...)} sites. There is a single production
 * catch site (in {@code Function.java:262} multi-catch); the exception persists as part of the
 * exception API surface but is otherwise unreachable.
 *
 * <p>Pinned via a minimal local subclass so the abstract-state delegation through {@link
 * CoreException} stays observable. A future refactor that adds a concrete production subclass
 * either removes this file in lockstep with the new class's tests or graduates the deletion to
 * the issue tracker per the cluster-classification table in {@code track-22a.md}.
 */
public class RetryQueryExceptionDeadCodeTest {

  /** Local concrete subclass for direct {@link RetryQueryException} ctor testing. */
  static class ConcreteRetryQueryException extends RetryQueryException {

    public ConcreteRetryQueryException(RetryQueryException exception) {
      super(exception);
    }

    public ConcreteRetryQueryException(DatabaseSessionEmbedded db, String message) {
      super(db, message);
    }
  }

  /**
   * The {@code (DatabaseSessionEmbedded, String message)} ctor delegates through {@link
   * CoreException} to extract the dbName and stamp the message. Pin both observables.
   */
  @Test
  public void sessionMessageConstructorPullsDbNameFromSession() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var ex = new ConcreteRetryQueryException(session, "retry needed");

    assertThat(ex.getMessage()).contains("retry needed");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The session ctor must accept null without NPE.
   */
  @Test
  public void sessionMessageConstructorAcceptsNullSession() {
    var ex = new ConcreteRetryQueryException(null, "retry needed");
    assertThat(ex.getMessage()).contains("retry needed");
    assertThat(ex.getDbName()).isNull();
  }

  /** The copy ctor must propagate the message and dbName. */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var source = new ConcreteRetryQueryException(session, "retry needed");
    var copy = new ConcreteRetryQueryException(source);

    assertThat(copy.getMessage()).contains("retry needed");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }

  /**
   * The class is abstract — {@code Modifier.isAbstract} on the type returns true. Pin the
   * abstract-class shape so a future refactor that flips it to concrete fails loudly.
   */
  @Test
  public void retryQueryExceptionIsAbstract() {
    assertThat(java.lang.reflect.Modifier.isAbstract(RetryQueryException.class.getModifiers()))
        .as("RetryQueryException must remain abstract")
        .isTrue();
  }
}
