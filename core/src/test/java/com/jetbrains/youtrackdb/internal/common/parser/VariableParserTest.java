package com.jetbrains.youtrackdb.internal.common.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

/**
 * Tests for {@link VariableParser} — resolves placeholder variables in text using a pluggable
 * listener (strategy pattern). Supports nested variables and default values.
 */
public class VariableParserTest {

  private static final String BEGIN = "${";
  private static final String END = "}";

  // ---------------------------------------------------------------------------
  // resolveVariables — basic resolution
  // ---------------------------------------------------------------------------

  /** A simple variable is replaced by the listener's resolved value. */
  @Test
  public void resolveSimpleVariable() {
    Object result =
        VariableParser.resolveVariables(
            "Hello ${name}!", BEGIN, END, var -> "World");
    assertThat(result).isEqualTo("Hello World!");
  }

  /** Text without variables is returned as-is. */
  @Test
  public void resolveNoVariablesReturnsSameText() {
    Object result =
        VariableParser.resolveVariables(
            "no variables here", BEGIN, END, var -> "unused");
    assertThat(result).isEqualTo("no variables here");
  }

  /** Multiple variables in the same string are all resolved. */
  @Test
  public void resolveMultipleVariables() {
    Object result =
        VariableParser.resolveVariables(
            "${a} and ${b}",
            BEGIN,
            END,
            var -> {
              if ("a".equals(var))
                return "X";
              if ("b".equals(var))
                return "Y";
              return null;
            });
    assertThat(result).isEqualTo("X and Y");
  }

  /**
   * Nested variables: the inner variable is resolved first because lastIndexOf finds the innermost
   * begin marker.
   */
  @Test
  public void resolveNestedVariables() {
    // "${a${b}}" — first resolves "b" → "c", then "ac" → "result"
    Object result =
        VariableParser.resolveVariables(
            "${a${b}}",
            BEGIN,
            END,
            var -> {
              if ("b".equals(var))
                return "c";
              if ("ac".equals(var))
                return "result";
              return null;
            });
    assertThat(result).isEqualTo("result");
  }

  // ---------------------------------------------------------------------------
  // resolveVariables — null resolution behavior
  // ---------------------------------------------------------------------------

  /**
   * When the listener returns null for a variable and no default is provided, the variable is
   * replaced with empty string in the result.
   */
  @Test
  public void resolveUndefinedVariableWithoutDefaultProducesEmptyString() {
    Object result =
        VariableParser.resolveVariables("Hello ${unknown}!", BEGIN, END, var -> null);
    assertThat(result).isEqualTo("Hello !");
  }

  /**
   * When the listener returns null but a default value is provided, the default is used.
   */
  @Test
  public void resolveUndefinedVariableWithDefaultUsesDefault() {
    Object result =
        VariableParser.resolveVariables(
            "Hello ${unknown}!", BEGIN, END, var -> null, "fallback");
    assertThat(result).isEqualTo("Hello fallback!");
  }

  // ---------------------------------------------------------------------------
  // resolveVariables — variable is the entire text (no pre/post)
  // ---------------------------------------------------------------------------

  /**
   * When the variable spans the entire text (no prefix or suffix), the resolved object is returned
   * directly — not necessarily as a String.
   */
  @Test
  public void resolveVariableSpanningEntireTextReturnsResolvedObject() {
    Object result =
        VariableParser.resolveVariables("${num}", BEGIN, END, var -> 42);
    assertThat(result).isEqualTo(42);
  }

  /** A null-resolved variable spanning the entire text returns null. */
  @Test
  public void resolveVariableSpanningEntireTextReturnsNull() {
    Object result =
        VariableParser.resolveVariables("${unknown}", BEGIN, END, var -> null);
    assertThat(result).isNull();
  }

  // ---------------------------------------------------------------------------
  // resolveVariables — edge cases with markers
  // ---------------------------------------------------------------------------

  /** Partial begin marker (no close) returns text as-is. */
  @Test
  public void resolvePartialBeginMarkerReturnsText() {
    Object result =
        VariableParser.resolveVariables("Hello ${noend", BEGIN, END, var -> "X");
    assertThat(result).isEqualTo("Hello ${noend");
  }

  /** End marker without begin marker returns text as-is. */
  @Test
  public void resolveEndMarkerWithoutBeginReturnsText() {
    Object result =
        VariableParser.resolveVariables("Hello nobegin}", BEGIN, END, var -> "X");
    assertThat(result).isEqualTo("Hello nobegin}");
  }

  /** Custom begin/end markers work. */
  @Test
  public void resolveCustomMarkers() {
    Object result =
        VariableParser.resolveVariables("Hello <<name>>!", "<<", ">>", var -> "World");
    assertThat(result).isEqualTo("Hello World!");
  }

  // ---------------------------------------------------------------------------
  // resolveVariables — null listener
  // ---------------------------------------------------------------------------

  /** Null listener throws IllegalArgumentException. */
  @Test
  public void resolveNullListenerThrowsException() {
    assertThatThrownBy(
        () -> VariableParser.resolveVariables("${x}", BEGIN, END, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("listener");
  }

  // ---------------------------------------------------------------------------
  // resolveVariables — 4-arg overload delegates to 5-arg with null default
  // ---------------------------------------------------------------------------

  /** The 4-arg overload works the same way (null default). */
  @Test
  public void resolveFourArgOverload() {
    Object result =
        VariableParser.resolveVariables("${x}", BEGIN, END, var -> "value");
    assertThat(result).isEqualTo("value");
  }
}
