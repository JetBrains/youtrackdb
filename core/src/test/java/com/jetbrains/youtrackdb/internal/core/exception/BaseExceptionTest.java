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

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.junit.Test;

/**
 * Abstract-state pins for {@link BaseException} — the abstract root of the {@code
 * core/exception/} hierarchy. The class is abstract, so all coverage runs through subclass
 * instantiation; this fan pins:
 * <ul>
 *   <li>The four constructor shapes ({@code (String message)}, {@code (String, String)},
 *       {@code (String, DatabaseSessionEmbedded)}, copy) and how they fill in the {@code dbName}
 *       field.
 *   <li>The {@code setDbName} mutators (one with String, one with DatabaseSessionEmbedded).
 *   <li>The static {@code wrapException} helpers — the only path used in production for
 *       attaching causes while preserving {@code dbName} provenance.
 * </ul>
 *
 * <p>The minimal concrete subclass below is package-private and inert — it adds no behaviour
 * beyond {@link BaseException}'s own.
 */
public class BaseExceptionTest {

  /** Minimal concrete subclass for direct {@link BaseException} ctor testing. */
  static class ConcreteBaseException extends BaseException {

    public ConcreteBaseException(String message) {
      super(message);
    }

    public ConcreteBaseException(String message, String dbName) {
      super(message, dbName);
    }

    public ConcreteBaseException(String message, DatabaseSessionEmbedded session) {
      super(message, session);
    }

    public ConcreteBaseException(BaseException exception) {
      super(exception);
    }
  }

  /**
   * Subclass implementing {@link HighLevelException} for the wrap-shortcut branch of
   * {@link BaseException#wrapException}.
   */
  static class HighLevelConcrete extends BaseException implements HighLevelException {

    public HighLevelConcrete(String message) {
      super(message);
    }
  }

  /** The bare {@code (message)} ctor stores the message and leaves dbName null. */
  @Test
  public void messageOnlyConstructorLeavesDbNameNull() {
    var ex = new ConcreteBaseException("hello");
    assertThat(ex.getMessage()).isEqualTo("hello");
    assertThat(ex.getDbName()).isNull();
  }

  /** The {@code (message, String dbName)} ctor stores both fields. */
  @Test
  public void messageDbNameConstructorRoundTripsBoth() {
    var ex = new ConcreteBaseException("hello", "dbX");
    assertThat(ex.getMessage()).isEqualTo("hello");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The {@code (message, DatabaseSessionEmbedded session)} ctor pulls the dbName from {@link
   * DatabaseSessionEmbedded#getDatabaseName()}. Pin that path with a Mockito session.
   */
  @Test
  public void messageSessionConstructorPullsDbNameFromSession() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbY");
    var ex = new ConcreteBaseException("hello", session);
    assertThat(ex.getDbName()).isEqualTo("dbY");
  }

  /** Passing null session to the session ctor must NOT NPE — pin the null guard. */
  @Test
  public void messageSessionConstructorAcceptsNullSession() {
    var ex = new ConcreteBaseException("hello", (DatabaseSessionEmbedded) null);
    assertThat(ex.getMessage()).isEqualTo("hello");
    assertThat(ex.getDbName()).isNull();
  }

  /** The copy ctor must propagate both the message and the dbName. */
  @Test
  public void copyConstructorPropagatesMessageAndDbName() {
    var original = new ConcreteBaseException("hello", "dbX");
    var copy = new ConcreteBaseException(original);
    assertThat(copy.getMessage()).isEqualTo("hello");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }

  /**
   * {@link BaseException#setDbName(String)} must mutate the dbName in place. Pin both the new
   * value and the case of overwriting an existing value.
   */
  @Test
  public void setDbNameStringMutatesInPlace() {
    var ex = new ConcreteBaseException("hello");
    assertThat(ex.getDbName()).isNull();

    ex.setDbName("dbX");
    assertThat(ex.getDbName()).isEqualTo("dbX");

    // Overwrite with another value.
    ex.setDbName("dbY");
    assertThat(ex.getDbName()).isEqualTo("dbY");
  }

  /**
   * {@link BaseException#setDbName(DatabaseSessionEmbedded)} only sets the dbName when it was
   * previously null — it pulls {@link DatabaseSessionEmbedded#getURL()} (NOT getDatabaseName()
   * — pin the URL pull explicitly because the implementation deliberately differs from the
   * ctor). Pin both the null-then-set case and the already-set-no-overwrite case.
   */
  @Test
  public void setDbNameSessionDoesNotOverwriteExistingValue() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getURL()).thenReturn("url://server/dbZ");

    // Case 1: dbName was null, must be set to the session URL.
    var ex1 = new ConcreteBaseException("hello");
    ex1.setDbName(session);
    assertThat(ex1.getDbName()).isEqualTo("url://server/dbZ");

    // Case 2: dbName already non-null, must NOT be overwritten.
    var ex2 = new ConcreteBaseException("hello", "preset");
    ex2.setDbName(session);
    assertThat(ex2.getDbName()).isEqualTo("preset");
  }

  /**
   * {@link BaseException#wrapException(BaseException, Throwable, String)} returns a {@link
   * BaseException} with the cause attached and the dbName resolved. Pin the typical cause-chain
   * scenario where the wrapping exception had no dbName but the explicit dbName arg fills it.
   */
  @Test
  public void wrapExceptionAttachesCauseAndFillsDbNameFromArgument() {
    var wrapper = new ConcreteBaseException("outer");
    var cause = new RuntimeException("inner");

    var result = BaseException.wrapException(wrapper, cause, "dbX");

    assertThat(result).isSameAs(wrapper);
    assertThat(result.getCause()).isSameAs(cause);
    assertThat(result.getDbName()).isEqualTo("dbX");
  }

  /**
   * When the cause is a {@link HighLevelException}, {@code wrapException} short-circuits and
   * returns the cause itself rather than the wrapper. This is how high-level exceptions
   * propagate without being re-wrapped at every layer.
   */
  @Test
  public void wrapExceptionReturnsHighLevelCauseUnchanged() {
    var wrapper = new ConcreteBaseException("outer");
    var cause = new HighLevelConcrete("high-level inner");

    var result = BaseException.wrapException(wrapper, cause, "dbX");

    assertThat(result).isSameAs(cause);
  }

  /**
   * When the cause is a {@link BaseException} with a null dbName, {@code wrapException}
   * back-fills the cause's dbName from the explicit dbName arg. This preserves provenance when
   * a generic helper (e.g. {@link com.jetbrains.youtrackdb.internal.core.engine.memory.EngineMemory#createStorage})
   * wraps a deeper exception and only the wrapper knows the URL.
   */
  @Test
  public void wrapExceptionBackFillsCauseDbNameWhenNull() {
    var wrapper = new ConcreteBaseException("outer");
    var cause = new ConcreteBaseException("inner");
    assertThat(cause.getDbName()).isNull();

    BaseException.wrapException(wrapper, cause, "dbX");

    assertThat(cause.getDbName()).isEqualTo("dbX");
  }

  /**
   * The session-arg overload of {@code wrapException} pulls the dbName from the session and
   * delegates. Pin both the non-null and null session paths.
   */
  @Test
  public void wrapExceptionSessionOverloadPullsDbNameFromSession() {
    var wrapper = new ConcreteBaseException("outer");
    var cause = new RuntimeException("inner");
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    BaseException.wrapException(wrapper, cause, session);
    assertThat(wrapper.getDbName()).isEqualTo("dbX");

    // Null session must yield null dbName behaviour.
    var wrapper2 = new ConcreteBaseException("outer2");
    BaseException.wrapException(wrapper2, cause, (DatabaseSessionEmbedded) null);
    assertThat(wrapper2.getDbName()).isNull();
  }
}
