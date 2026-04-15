package com.jetbrains.youtrackdb.internal.common.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

/**
 * Tests for {@link AnsiCode} — ANSI escape code enum and format method that resolves $ANSI{code
 * text} placeholders using VariableParser.
 */
public class AnsiCodeTest {

  // ---------------------------------------------------------------------------
  // Enum values
  // ---------------------------------------------------------------------------

  /** All non-NULL codes contain the ANSI escape sequence prefix. */
  @Test
  public void allCodesContainAnsiEscapePrefix() {
    for (AnsiCode code : AnsiCode.values()) {
      if (code != AnsiCode.NULL) {
        assertThat(code.toString()).startsWith("\u001B[");
      }
    }
  }

  /** NULL code has empty toString. */
  @Test
  public void nullCodeHasEmptyToString() {
    assertThat(AnsiCode.NULL.toString()).isEmpty();
  }

  /** RESET code contains the standard ANSI reset sequence. */
  @Test
  public void resetCodeContainsResetSequence() {
    assertThat(AnsiCode.RESET.toString()).contains("[0m");
  }

  // ---------------------------------------------------------------------------
  // format — with colors disabled
  // ---------------------------------------------------------------------------

  /** With colors disabled, ANSI markers are stripped and only text is kept. */
  @Test
  public void formatColorsDisabledStripsMarkers() {
    String result = AnsiCode.format("$ANSI{red Error occurred}", false);
    assertThat(result).isEqualTo("Error occurred");
  }

  /** No markers produces passthrough. */
  @Test
  public void formatColorsDisabledNoMarkersPassthrough() {
    String result = AnsiCode.format("plain text", false);
    assertThat(result).isEqualTo("plain text");
  }

  /** Multiple markers are all stripped with colors disabled. */
  @Test
  public void formatColorsDisabledMultipleMarkers() {
    String result = AnsiCode.format("$ANSI{red Error} and $ANSI{green OK}", false);
    assertThat(result).isEqualTo("Error and OK");
  }

  // ---------------------------------------------------------------------------
  // format — with colors enabled
  // ---------------------------------------------------------------------------

  /** With colors enabled, ANSI codes are inserted and RESET is appended. */
  @Test
  public void formatColorsEnabledInsertsAnsiCodes() {
    String result = AnsiCode.format("$ANSI{red Error occurred}", true);
    assertThat(result).contains(AnsiCode.RED.toString());
    assertThat(result).contains("Error occurred");
    assertThat(result).contains(AnsiCode.RESET.toString());
  }

  /** Code-only marker (no text) inserts the ANSI code without RESET. */
  @Test
  public void formatColorsEnabledCodeOnlyNoReset() {
    String result = AnsiCode.format("$ANSI{red}", true);
    assertThat(result).contains(AnsiCode.RED.toString());
    // No RESET should be appended because there's no text (pos == -1)
    assertThat(result).doesNotContain(AnsiCode.RESET.toString());
  }

  /** Multiple colon-separated codes are all applied. */
  @Test
  public void formatColorsEnabledMultipleCodes() {
    String result = AnsiCode.format("$ANSI{red:high_intensity Bold red}", true);
    assertThat(result).contains(AnsiCode.RED.toString());
    assertThat(result).contains(AnsiCode.HIGH_INTENSITY.toString());
    assertThat(result).contains("Bold red");
  }

  /** Unknown ANSI code name throws IllegalArgumentException from Enum.valueOf. */
  @Test
  public void formatColorsEnabledUnknownCodeThrows() {
    assertThatThrownBy(() -> AnsiCode.format("$ANSI{unknowncode text}", true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
