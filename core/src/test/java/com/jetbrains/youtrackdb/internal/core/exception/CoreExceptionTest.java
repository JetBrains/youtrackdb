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

import com.jetbrains.youtrackdb.internal.common.exception.ErrorCode;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.junit.Test;

/**
 * Abstract-state pins for {@link CoreException} — the abstract intermediate base extending {@link
 * BaseException} with three additional facets:
 * <ul>
 *   <li>An immutable {@link ErrorCode} field that decorates {@code getMessage()} when non-null.
 *   <li>A mutable {@code componentName} field that decorates {@code getMessage()} when non-null.
 *   <li>A custom {@code getMessage()} override that appends "DB Name=…", "Component Name=…",
 *       "Error Code=…" lines when each respective field is set.
 * </ul>
 *
 * <p>The minimal concrete subclass below is package-private and inert.
 */
public class CoreExceptionTest {

  /** Minimal concrete subclass for direct {@link CoreException} ctor testing. */
  static class ConcreteCoreException extends CoreException {

    public ConcreteCoreException(String message) {
      super(message);
    }

    public ConcreteCoreException(String dbName, String message) {
      super(dbName, message);
    }

    public ConcreteCoreException(DatabaseSessionEmbedded session, String message) {
      super(session, message);
    }

    public ConcreteCoreException(String dbName, String message, String componentName) {
      super(dbName, message, componentName);
    }

    public ConcreteCoreException(
        String dbName, String message, String componentName, ErrorCode errorCode) {
      super(dbName, message, componentName, errorCode);
    }

    public ConcreteCoreException(CoreException exception) {
      super(exception);
    }

    public ConcreteCoreException(CoreException exception, ErrorCode errorCode) {
      super(exception, errorCode);
    }
  }

  /**
   * The {@code (String message)} ctor delegates to the four-arg ctor with all extras null. Pin
   * that the message round-trips and the extras stay null.
   */
  @Test
  public void messageOnlyConstructorLeavesExtrasNull() {
    var ex = new ConcreteCoreException("hello");
    assertThat(ex.getMessage()).isEqualTo("hello");
    assertThat(ex.getDbName()).isNull();
    assertThat(ex.getErrorCode()).isNull();
  }

  /**
   * The {@code (String dbName, String message)} ctor decorates {@code getMessage()} with a
   * "DB Name=…" line. Pin the decoration shape because callers parse the trailing context
   * lines.
   */
  @Test
  public void dbNameAndMessageConstructorDecoratesGetMessage() {
    var ex = new ConcreteCoreException("dbX", "hello");
    assertThat(ex.getMessage()).contains("hello").contains("DB Name=\"dbX\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The {@code (DatabaseSessionEmbedded session, String message)} ctor delegates to the dbName
   * variant after pulling the session's database name. Pin both the live-session path and the
   * null-session path.
   */
  @Test
  public void sessionMessageConstructorPullsDbNameFromSession() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbY");
    var ex = new ConcreteCoreException(session, "hello");
    assertThat(ex.getDbName()).isEqualTo("dbY");

    // Null session — dbName must remain null.
    var ex2 = new ConcreteCoreException((DatabaseSessionEmbedded) null, "hello");
    assertThat(ex2.getDbName()).isNull();
  }

  /**
   * The {@code (dbName, message, componentName)} ctor sets the {@code componentName} which is
   * appended to {@code getMessage()} as "Component Name=…". Pin both via assertions.
   */
  @Test
  public void componentNameConstructorDecoratesGetMessage() {
    var ex = new ConcreteCoreException("dbX", "hello", "comp-1");
    assertThat(ex.getMessage())
        .contains("hello")
        .contains("DB Name=\"dbX\"")
        .contains("Component Name=\"comp-1\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The four-arg ctor stores an {@link ErrorCode} that decorates {@code getMessage()} as
   * "Error Code=…" with the numeric code value. Pin the round-trip plus the decoration.
   */
  @Test
  public void fullConstructorStoresErrorCodeAndDecoratesGetMessage() {
    var ex = new ConcreteCoreException("dbX", "hello", "comp-1", ErrorCode.QUERY_PARSE_ERROR);
    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.QUERY_PARSE_ERROR);
    assertThat(ex.getMessage())
        .contains("hello")
        .contains("DB Name=\"dbX\"")
        .contains("Component Name=\"comp-1\"")
        .contains("Error Code=\"" + ErrorCode.QUERY_PARSE_ERROR.getCode() + "\"");
  }

  /**
   * The {@code setComponentName} mutator must update the field and the resulting decoration on
   * subsequent {@code getMessage()} calls — pin both the round-trip and the message-decoration
   * change.
   */
  @Test
  public void setComponentNameMutatesFieldAndMessageDecoration() {
    var ex = new ConcreteCoreException("dbX", "hello", "old");
    assertThat(ex.getMessage()).contains("Component Name=\"old\"");

    ex.setComponentName("new");
    assertThat(ex.getMessage()).contains("Component Name=\"new\"");
  }

  /**
   * The simple copy ctor — {@code (CoreException exception)} — propagates the message, dbName,
   * and componentName from the original. The errorCode is set to null by this ctor (delegating
   * to the (exception, null) variant). Pin all three fields' round-trip plus the null errorCode.
   */
  @Test
  public void simpleCopyConstructorClearsErrorCode() {
    var original =
        new ConcreteCoreException("dbX", "hello", "comp", ErrorCode.QUERY_PARSE_ERROR);

    var copy = new ConcreteCoreException(original);

    assertThat(copy.getMessage()).contains("hello").contains("Component Name=\"comp\"");
    assertThat(copy.getDbName()).isEqualTo("dbX");
    // The simple copy ctor delegates with errorCode=null — pin that semantic.
    assertThat(copy.getErrorCode()).isNull();
  }

  /**
   * The two-arg copy ctor — {@code (CoreException exception, ErrorCode)} — preserves the message
   * and component but stamps a new error code onto the copy.
   */
  @Test
  public void copyConstructorWithErrorCodeStampsNewCode() {
    var original =
        new ConcreteCoreException("dbX", "hello", "comp", ErrorCode.GENERIC_ERROR);

    var copy = new ConcreteCoreException(original, ErrorCode.QUERY_PARSE_ERROR);

    assertThat(copy.getDbName()).isEqualTo("dbX");
    assertThat(copy.getErrorCode()).isEqualTo(ErrorCode.QUERY_PARSE_ERROR);
  }

  /**
   * When all three optional fields are null, {@code getMessage()} must return only the bare
   * message — pin the no-decoration arm to ensure no spurious "\r\n\t" prefixes leak.
   */
  @Test
  public void getMessageWithoutOptionalFieldsReturnsBareMessage() {
    var ex = new ConcreteCoreException("hello");
    assertThat(ex.getMessage()).isEqualTo("hello");
  }

  /**
   * When the underlying message is null, the StringBuilder seeded with "" must not NPE — pin
   * the empty-message arm. The ctor allows null messages via {@code super(null)}.
   */
  @Test
  public void getMessageHandlesNullUnderlyingMessage() {
    var ex = new ConcreteCoreException(null, null, "comp", null);
    assertThat(ex.getMessage()).contains("Component Name=\"comp\"");
  }
}
