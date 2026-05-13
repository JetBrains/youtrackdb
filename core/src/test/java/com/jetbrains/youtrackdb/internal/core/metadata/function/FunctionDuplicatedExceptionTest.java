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
package com.jetbrains.youtrackdb.internal.core.metadata.function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import org.junit.Test;

/**
 * Standalone shape pin for {@link FunctionDuplicatedException}. The exception is thrown by
 * {@link FunctionLibraryImpl#createFunction(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 * String)} when an underlying {@code RecordDuplicatedException} surfaces — covered indirectly
 * by {@code FunctionLibraryTest#testDuplicateFunctionCreate}, which leaves the constructor
 * line covered but no assertion on the message contract or the {@link BaseException} parent.
 *
 * <p>The four tests below pin: (1) the public constructor preserves the supplied message
 * verbatim; (2) the exception participates in the {@link BaseException} hierarchy; (3) the
 * empty-message branch (defensive) is acceptable; and (4) {@code initCause(null)} leaves the
 * cause null (no auto-wrap into a synthetic chain).
 */
public class FunctionDuplicatedExceptionTest {

  /**
   * Constructor preserves the supplied message exactly — pinning this catches a future
   * formatter that would prepend a fixed prefix.
   */
  @Test
  public void constructorPreservesMessageExactly() {
    var exception = new FunctionDuplicatedException("Function with name 'foo' already exist");
    assertEquals("Function with name 'foo' already exist", exception.getMessage());
  }

  /**
   * The exception participates in the project's {@link BaseException} hierarchy — pinning the
   * superclass-link catches a future refactor that switches the parent to a different
   * exception base.
   */
  @Test
  public void exceptionExtendsBaseException() {
    var exception = new FunctionDuplicatedException("any message");
    assertTrue(exception instanceof BaseException);
  }

  /**
   * Empty-string message is preserved as-is (no auto-fallback to a synthetic message).
   */
  @Test
  public void emptyMessageIsPreserved() {
    var exception = new FunctionDuplicatedException("");
    assertNotNull(exception.getMessage());
    assertEquals("", exception.getMessage());
  }

  /**
   * No cause is implicitly attached; the exception is a leaf when constructed with the single
   * message arg. Pin so a future "auto-wrap" change is a deliberate, visible event.
   */
  @Test
  public void causeIsNullByDefault() {
    var exception = new FunctionDuplicatedException("msg");
    assertNull(exception.getCause());
  }
}
