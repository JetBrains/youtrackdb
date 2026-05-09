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

import org.junit.Test;

/**
 * Bespoke tests for {@link CommandScriptException} — outside the parameterized fan because the
 * primary public ctor takes a {@code (dbName, message, text, position)} tuple that builds a
 * visual-pointer message rather than passing the message verbatim. The {@code makeMessage}
 * helper carries a non-trivial branch on null text and a position-clamping rule that we pin
 * here.
 */
public class CommandScriptExceptionTest {

  /**
   * The simple {@code (String dbName, String iMessage)} ctor delegates to {@code CoreException},
   * passing the message through verbatim.
   */
  @Test
  public void dbNameAndMessageConstructorRoundTripsBoth() {
    var ex = new CommandScriptException("dbA", "script error");
    assertThat(ex.getMessage()).contains("script error");
    assertThat(ex.getDbName()).isEqualTo("dbA");
  }

  /**
   * The {@code (dbName, iMessage, iText, iPosition)} ctor builds a visual-pointer message
   * containing the decorated header, the script body, and a {@code ^} pointer at the position.
   */
  @Test
  public void textPositionConstructorBuildsVisualPointer() {
    var ex =
        new CommandScriptException("dbA", "unexpected $", "select from foo $", 16);
    assertThat(ex.getMessage())
        .contains("Error on parsing script at position #16")
        .contains("unexpected $")
        .contains("select from foo $")
        .contains("^");
  }

  /**
   * When {@code iText} is null, {@code makeMessage} falls back to the raw error message — no
   * visual-pointer decoration. Pin the null-text arm so a future refactor that NPEs on null
   * text fails loudly.
   */
  @Test
  public void textPositionConstructorWithNullTextFallsBackToRawMessage() {
    var ex = new CommandScriptException("dbA", "raw error", null, 5);
    // The text is null, so the message must not contain "Script:" header from makeMessage.
    assertThat(ex.getMessage()).contains("raw error").doesNotContain("Script:");
  }

  /**
   * The position clamp ({@code Math.max(iPosition, 0)}) must guard against negative positions —
   * pin the negative-position arm so a future refactor that drops the clamp produces an
   * IndexOutOfBoundsException on the {@code "-".repeat()} call.
   */
  @Test
  public void textPositionConstructorClampsNegativePositionToZero() {
    var ex =
        new CommandScriptException("dbA", "error", "script", -10);
    assertThat(ex.getMessage())
        .contains("Error on parsing script at position #0")
        .contains("script");
  }

  /**
   * The copy ctor must propagate the message, dbName, and the bespoke {@code text} / {@code
   * position} fields. Pin via a round-trip from a built (text, position) instance.
   */
  @Test
  public void copyConstructorPreservesAllFields() {
    var original =
        new CommandScriptException("dbA", "error", "script body", 5);
    var copy = new CommandScriptException(original);

    // Message must still contain the visual-pointer header (the copy ctor preserves the
    // already-formatted message via super(exception)).
    assertThat(copy.getMessage()).contains("Error on parsing script");
    assertThat(copy.getDbName()).isEqualTo("dbA");
  }
}
