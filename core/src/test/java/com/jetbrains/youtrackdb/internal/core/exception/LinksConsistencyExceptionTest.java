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
import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import org.junit.Test;

/**
 * Bespoke test for {@link LinksConsistencyException} — outside the parameterized fan because the
 * only public non-copy ctor takes {@code (DatabaseSessionEmbedded, String)} and unconditionally
 * dereferences the session via {@code session.getDatabaseName()}. The class is the dispatch
 * marker thrown when records contain dangling links; both {@link NeedRetryException} (caller
 * retry hint) and {@link HighLevelException} (wrapping short-circuit) are mixed in.
 */
public class LinksConsistencyExceptionTest {

  /**
   * The {@code (DatabaseSessionEmbedded, String message)} ctor must propagate the dbName from
   * the session and decorate {@code getMessage()} via {@link CoreException}. Pin both
   * observables.
   */
  @Test
  public void sessionMessageConstructorPullsDbNameFromSession() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var ex = new LinksConsistencyException(session, "links inconsistent");

    assertThat(ex.getMessage()).contains("links inconsistent").contains("DB Name=\"dbX\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The copy ctor must propagate the message and dbName via the {@link BaseException} chain.
   * Pin via a round-trip from a built-from-session instance.
   */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");

    var original = new LinksConsistencyException(session, "links inconsistent");
    var copy = new LinksConsistencyException(original);

    assertThat(copy.getMessage()).contains("links inconsistent");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }

  /**
   * {@link LinksConsistencyException} is both a {@link NeedRetryException} (caller retry hint)
   * and a {@link HighLevelException} (wrap short-circuit). Pin both markers because the
   * combination is what makes the exception self-propagating through retry-and-wrap chains.
   */
  @Test
  public void implementsNeedRetryAndHighLevelExceptions() {
    var session = mock(DatabaseSessionEmbedded.class);
    when(session.getDatabaseName()).thenReturn("dbX");
    var ex = new LinksConsistencyException(session, "msg");

    assertThat(ex).isInstanceOf(NeedRetryException.class);
    assertThat(ex).isInstanceOf(HighLevelException.class);
  }
}
