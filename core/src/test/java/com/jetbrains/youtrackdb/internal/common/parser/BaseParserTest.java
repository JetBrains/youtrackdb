package com.jetbrains.youtrackdb.internal.common.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

/**
 * Tests for {@link BaseParser} — abstract command parser providing word/token parsing with support
 * for quoted strings, escape sequences, bracket nesting, and keyword matching. Uses a minimal
 * concrete subclass ({@code TestableBaseParser}) that throws IllegalArgumentException on syntax
 * errors.
 */
public class BaseParserTest {

  /**
   * Minimal concrete subclass for testing — throws IllegalArgumentException on syntax errors.
   */
  private static class TestableBaseParser extends BaseParser {

    TestableBaseParser(String text) {
      this.parserText = text;
      this.parserTextUpperCase = text.toUpperCase();
    }

    @Override
    protected void throwSyntaxErrorException(String dbName, String iText) {
      throw new IllegalArgumentException(iText);
    }
  }

  private TestableBaseParser parser(String text) {
    return new TestableBaseParser(text);
  }

  // ---------------------------------------------------------------------------
  // Static: nextWord
  // ---------------------------------------------------------------------------

  /** Extracts the first word using default separators. */
  @Test
  public void nextWordExtractsFirstWord() {
    StringBuilder word = new StringBuilder();
    int pos = BaseParser.nextWord("SELECT name FROM", "SELECT NAME FROM", 0, word, false);
    assertThat(word.toString()).isEqualTo("SELECT");
    assertThat(pos).isEqualTo(6);
  }

  /** With iForceUpperCase=true, uses the uppercase version. */
  @Test
  public void nextWordForcesUpperCase() {
    StringBuilder word = new StringBuilder();
    BaseParser.nextWord("select name", "SELECT NAME", 0, word, true);
    assertThat(word.toString()).isEqualTo("SELECT");
  }

  /** Skips leading whitespace before the word. */
  @Test
  public void nextWordSkipsLeadingWhitespace() {
    StringBuilder word = new StringBuilder();
    int pos = BaseParser.nextWord("  hello", "  HELLO", 0, word, false);
    assertThat(word.toString()).isEqualTo("hello");
    assertThat(pos).isEqualTo(7);
  }

  /** Returns -1 when the text is all whitespace (no word found). */
  @Test
  public void nextWordAllWhitespaceReturnsMinusOne() {
    StringBuilder word = new StringBuilder();
    int pos = BaseParser.nextWord("   ", "   ", 0, word, false);
    assertThat(pos).isEqualTo(-1);
    assertThat(word.toString()).isEmpty();
  }

  /** Custom separator chars stop the word. */
  @Test
  public void nextWordCustomSeparators() {
    StringBuilder word = new StringBuilder();
    int pos = BaseParser.nextWord("a:b", "A:B", 0, word, false, ":");
    assertThat(word.toString()).isEqualTo("a");
    assertThat(pos).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // Static: getWordStatic
  // ---------------------------------------------------------------------------

  /** Extracts a word stopping at the separator. */
  @Test
  public void getWordStaticExtractsWord() {
    StringBuilder buffer = new StringBuilder();
    BaseParser.getWordStatic("name = value", 0, " =", buffer);
    assertThat(buffer.toString()).isEqualTo("name");
  }

  /** Skips leading separator characters. */
  @Test
  public void getWordStaticSkipsLeadingSeparators() {
    StringBuilder buffer = new StringBuilder();
    BaseParser.getWordStatic("  name", 0, " ", buffer);
    assertThat(buffer.toString()).isEqualTo("name");
  }

  /** Quoted strings within a word are preserved without splitting at separators. */
  @Test
  public void getWordStaticPreservesQuotedContent() {
    StringBuilder buffer = new StringBuilder();
    BaseParser.getWordStatic("'hello world' rest", 0, " ", buffer);
    assertThat(buffer.toString()).isEqualTo("'hello world'");
  }

  /** Double-quoted strings are handled the same as single-quoted. */
  @Test
  public void getWordStaticDoubleQuotedContent() {
    StringBuilder buffer = new StringBuilder();
    BaseParser.getWordStatic("\"hello world\" rest", 0, " ", buffer);
    assertThat(buffer.toString()).isEqualTo("\"hello world\"");
  }

  /** Backtick-delimited identifiers are handled like strings. */
  @Test
  public void getWordStaticBacktickDelimitedIdentifier() {
    StringBuilder buffer = new StringBuilder();
    BaseParser.getWordStatic("`my field` rest", 0, " ", buffer);
    assertThat(buffer.toString()).isEqualTo("`my field`");
  }

  /** Empty input produces empty buffer. */
  @Test
  public void getWordStaticEmptyInput() {
    StringBuilder buffer = new StringBuilder();
    BaseParser.getWordStatic("", 0, " ", buffer);
    assertThat(buffer.toString()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Instance: parserOptionalWord
  // ---------------------------------------------------------------------------

  /** Parses the next word. */
  @Test
  public void parserOptionalWordReturnsWord() {
    TestableBaseParser p = parser("hello world");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("hello");
  }

  /** Returns uppercase when iUpperCase=true. */
  @Test
  public void parserOptionalWordUpperCase() {
    TestableBaseParser p = parser("hello world");
    String word = p.parserOptionalWord(true);
    assertThat(word).isEqualTo("HELLO");
  }

  /** Returns null at end of text. */
  @Test
  public void parserOptionalWordAtEndReturnsNull() {
    TestableBaseParser p = parser("hello");
    p.parserOptionalWord(false); // consumes "hello"
    String word = p.parserOptionalWord(false);
    assertThat(word).isNull();
  }

  // ---------------------------------------------------------------------------
  // Instance: parseOptionalWord
  // ---------------------------------------------------------------------------

  /** Returns the word when it matches one of the expected keywords. */
  @Test
  public void parseOptionalWordMatchesKeyword() {
    TestableBaseParser p = parser("SELECT name");
    String word = p.parseOptionalWord("testDb", true, "SELECT", "INSERT");
    assertThat(word).isEqualTo("SELECT");
  }

  /** Returns empty string when end of text (no word found), with empty iWords. */
  @Test
  public void parseOptionalWordEndOfTextReturnsEmpty() {
    TestableBaseParser p = parser("done");
    p.parserOptionalWord(false); // consumes "done"
    String word = p.parseOptionalWord("testDb", true);
    assertThat(word).isEmpty();
  }

  /** Returns null when end of text and keywords are provided. */
  @Test
  public void parseOptionalWordEndOfTextWithKeywordsReturnsNull() {
    TestableBaseParser p = parser("done");
    p.parserOptionalWord(false); // consumes "done"
    String word = p.parseOptionalWord("testDb", true, "SELECT");
    assertThat(word).isNull();
  }

  /** Throws when parsed word doesn't match any expected keyword. */
  @Test
  public void parseOptionalWordMismatchThrows() {
    TestableBaseParser p = parser("DELETE name");
    assertThatThrownBy(() -> p.parseOptionalWord("testDb", true, "SELECT", "INSERT"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Backtick-delimited identifiers without spaces have backticks stripped. */
  @Test
  public void parseOptionalWordStripsBackticks() {
    TestableBaseParser p = parser("`myField` rest");
    String word = p.parseOptionalWord("testDb", false);
    assertThat(word).isEqualTo("myField");
  }

  // ---------------------------------------------------------------------------
  // Instance: parserRequiredWord
  // ---------------------------------------------------------------------------

  /** Parses the next word successfully. */
  @Test
  public void parserRequiredWordReturnsWord() {
    TestableBaseParser p = parser("SELECT name");
    String word = p.parserRequiredWord("testDb", false);
    assertThat(word).isEqualTo("SELECT");
  }

  /** Throws syntax error when no word is found (end of text). */
  @Test
  public void parserRequiredWordAtEndThrows() {
    TestableBaseParser p = parser("done");
    p.parserOptionalWord(false); // consumes "done"
    assertThatThrownBy(() -> p.parserRequiredWord("testDb", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Custom error message is passed to the exception. */
  @Test
  public void parserRequiredWordCustomMessage() {
    TestableBaseParser p = parser("done");
    p.parserOptionalWord(false); // consumes "done"
    assertThatThrownBy(() -> p.parserRequiredWord(false, "Expected more", "testDb"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected more");
  }

  /** Backtick-delimited identifiers without spaces have backticks stripped. */
  @Test
  public void parserRequiredWordStripsBackticks() {
    TestableBaseParser p = parser("`fieldName`");
    String word = p.parserRequiredWord("testDb", false);
    assertThat(word).isEqualTo("fieldName");
  }

  // ---------------------------------------------------------------------------
  // Instance: parserRequiredKeyword
  // ---------------------------------------------------------------------------

  /** Matches one of the expected keywords. */
  @Test
  public void parserRequiredKeywordMatches() {
    TestableBaseParser p = parser("SELECT name");
    p.parserRequiredKeyword("testDb", "SELECT", "INSERT");
    assertThat(p.parserGetLastWord()).isEqualTo("SELECT");
  }

  /** Throws when the word doesn't match any expected keyword. */
  @Test
  public void parserRequiredKeywordMismatchThrows() {
    TestableBaseParser p = parser("DELETE name");
    assertThatThrownBy(() -> p.parserRequiredKeyword("testDb", "SELECT", "INSERT"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Throws when no word found (end of text). */
  @Test
  public void parserRequiredKeywordEndOfTextThrows() {
    TestableBaseParser p = parser("done");
    p.parserOptionalWord(false); // consumes "done"
    assertThatThrownBy(() -> p.parserRequiredKeyword("testDb", "SELECT"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------
  // Instance: parserOptionalKeyword
  // ---------------------------------------------------------------------------

  /** Returns true when keyword matches. */
  @Test
  public void parserOptionalKeywordMatchesReturnsTrue() {
    TestableBaseParser p = parser("SELECT name");
    boolean found = p.parserOptionalKeyword("testDb", "SELECT", "INSERT");
    assertThat(found).isTrue();
  }

  /** Returns false when no word found (end of text). */
  @Test
  public void parserOptionalKeywordEndOfTextReturnsFalse() {
    TestableBaseParser p = parser("done");
    p.parserOptionalWord(false); // consumes "done"
    boolean found = p.parserOptionalKeyword("testDb", "SELECT");
    assertThat(found).isFalse();
  }

  /** Throws when word is found but doesn't match any expected keyword. */
  @Test
  public void parserOptionalKeywordMismatchThrows() {
    TestableBaseParser p = parser("DELETE name");
    assertThatThrownBy(() -> p.parserOptionalKeyword("testDb", "SELECT", "INSERT"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** With empty iWords array, any word matches (returns true). */
  @Test
  public void parserOptionalKeywordEmptyWordsMatchesAny() {
    TestableBaseParser p = parser("anything");
    boolean found = p.parserOptionalKeyword("testDb");
    assertThat(found).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Instance: position management
  // ---------------------------------------------------------------------------

  /** Initial position is 0. */
  @Test
  public void parserGetCurrentPositionInitialIsZero() {
    TestableBaseParser p = parser("hello");
    assertThat(p.parserGetCurrentPosition()).isEqualTo(0);
  }

  /** Position advances as words are parsed. */
  @Test
  public void parserGetCurrentPositionAdvancesAfterParsing() {
    TestableBaseParser p = parser("hello world");
    p.parserOptionalWord(false);
    // After parsing "hello", position is at the separator (space at index 5)
    assertThat(p.parserGetCurrentPosition()).isEqualTo(5);
  }

  /** parserSetCurrentPosition moves to a new position. */
  @Test
  public void parserSetCurrentPositionValid() {
    TestableBaseParser p = parser("hello world");
    boolean notEnded = p.parserSetCurrentPosition(6);
    assertThat(notEnded).isTrue();
    assertThat(p.parserGetCurrentPosition()).isEqualTo(6);
  }

  /** parserSetCurrentPosition beyond end sets position to -1. */
  @Test
  public void parserSetCurrentPositionBeyondEnd() {
    TestableBaseParser p = parser("hi");
    boolean notEnded = p.parserSetCurrentPosition(100);
    assertThat(notEnded).isFalse();
    assertThat(p.parserIsEnded()).isTrue();
  }

  /** parserGoBack restores the previous position. */
  @Test
  public void parserGoBackRestoresPreviousPosition() {
    TestableBaseParser p = parser("hello world");
    p.parserOptionalWord(false); // at position 5
    int prevPos = p.parserGetPreviousPosition();
    assertThat(prevPos).isEqualTo(0);
    p.parserGoBack();
    assertThat(p.parserGetCurrentPosition()).isEqualTo(0);
  }

  /** parserMoveCurrentPosition moves by offset. */
  @Test
  public void parserMoveCurrentPositionForward() {
    TestableBaseParser p = parser("hello world");
    p.parserMoveCurrentPosition(3);
    assertThat(p.parserGetCurrentPosition()).isEqualTo(3);
  }

  /** parserMoveCurrentPosition returns false when position is -1. */
  @Test
  public void parserMoveCurrentPositionWhenEndedReturnsFalse() {
    TestableBaseParser p = parser("hi");
    p.parserSetEndOfText();
    boolean result = p.parserMoveCurrentPosition(1);
    assertThat(result).isFalse();
  }

  /** parserIsEnded returns true when position is -1. */
  @Test
  public void parserIsEndedReturnsTrueAtEnd() {
    TestableBaseParser p = parser("hi");
    p.parserSetEndOfText();
    assertThat(p.parserIsEnded()).isTrue();
  }

  /** parserIsEnded returns false when not at end. */
  @Test
  public void parserIsEndedReturnsFalseBeforeEnd() {
    TestableBaseParser p = parser("hello");
    assertThat(p.parserIsEnded()).isFalse();
  }

  /** parserGetCurrentChar returns the character at current position. */
  @Test
  public void parserGetCurrentCharReturnsCorrectChar() {
    TestableBaseParser p = parser("hello");
    assertThat(p.parserGetCurrentChar()).isEqualTo('h');
  }

  /** parserGetCurrentChar returns space when ended. */
  @Test
  public void parserGetCurrentCharReturnsSpaceWhenEnded() {
    TestableBaseParser p = parser("hi");
    p.parserSetEndOfText();
    assertThat(p.parserGetCurrentChar()).isEqualTo(' ');
  }

  /** parserGetLastWord returns the last parsed word. */
  @Test
  public void parserGetLastWordAfterParsing() {
    TestableBaseParser p = parser("hello world");
    p.parserOptionalWord(false);
    assertThat(p.parserGetLastWord()).isEqualTo("hello");
  }

  /** parserGetLastSeparator / parserSetLastSeparator roundtrip. */
  @Test
  public void parserLastSeparatorRoundtrip() {
    TestableBaseParser p = parser("test");
    assertThat(p.parserGetLastSeparator()).isEqualTo(' ');
    p.parserSetLastSeparator(',');
    assertThat(p.parserGetLastSeparator()).isEqualTo(',');
  }

  /** parserSkipWhiteSpaces advances past whitespace. */
  @Test
  public void parserSkipWhiteSpacesAdvancesPastWhitespace() {
    TestableBaseParser p = parser("  hello");
    boolean notEnded = p.parserSkipWhiteSpaces();
    assertThat(notEnded).isTrue();
    assertThat(p.parserGetCurrentPosition()).isEqualTo(2);
  }

  /** parserSkipWhiteSpaces returns false when position is -1. */
  @Test
  public void parserSkipWhiteSpacesReturnsFalseWhenEnded() {
    TestableBaseParser p = parser("hi");
    p.parserSetEndOfText();
    boolean notEnded = p.parserSkipWhiteSpaces();
    assertThat(notEnded).isFalse();
  }

  /** parserSetEndOfText marks parser as ended. */
  @Test
  public void parserSetEndOfTextSetsMinusOne() {
    TestableBaseParser p = parser("hello");
    p.parserSetEndOfText();
    assertThat(p.parserGetCurrentPosition()).isEqualTo(-1);
    assertThat(p.parserIsEnded()).isTrue();
  }

  /** getSyntax returns default "?". */
  @Test
  public void getSyntaxReturnsDefault() {
    TestableBaseParser p = parser("test");
    assertThat(p.getSyntax()).isEqualTo("?");
  }

  // ---------------------------------------------------------------------------
  // Instance: parserNextWord — escape sequences
  // ---------------------------------------------------------------------------

  /** Escape sequence \n is translated to newline. */
  @Test
  public void parserNextWordEscapeNewline() {
    TestableBaseParser p = parser("a" + "\\" + "nb");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("a\nb");
  }

  /** Escape sequence \t is translated to tab. */
  @Test
  public void parserNextWordEscapeTab() {
    TestableBaseParser p = parser("a" + "\\" + "tb");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("a\tb");
  }

  /** Escape sequence \r is translated to carriage return. */
  @Test
  public void parserNextWordEscapeCarriageReturn() {
    TestableBaseParser p = parser("a" + "\\" + "rb");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("a\rb");
  }

  /** Escape sequence \b is translated to backspace. */
  @Test
  public void parserNextWordEscapeBackspace() {
    TestableBaseParser p = parser("a" + "\\" + "bb");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("a\bb");
  }

  /** Escape sequence \f is translated to form-feed. */
  @Test
  public void parserNextWordEscapeFormFeed() {
    TestableBaseParser p = parser("a" + "\\" + "fb");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("a\fb");
  }

  /** Unicode escape (backslash-u followed by 4 hex digits) is decoded. */
  @Test
  public void parserNextWordUnicodeEscape() {
    TestableBaseParser p = parser("\\" + "u0041rest");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("Arest");
  }

  /** Non-special escape character is appended as-is. */
  @Test
  public void parserNextWordEscapeNonSpecialChar() {
    TestableBaseParser p = parser("\\" + "xrest");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("xrest");
  }

  // ---------------------------------------------------------------------------
  // Instance: parserNextWord — quoted strings
  // ---------------------------------------------------------------------------

  /** Single-quoted string is returned as a single word including quotes. */
  @Test
  public void parserNextWordSingleQuotedString() {
    TestableBaseParser p = parser("'hello world' rest");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("'hello world'");
  }

  /** Double-quoted string is returned as a single word including quotes. */
  @Test
  public void parserNextWordDoubleQuotedString() {
    TestableBaseParser p = parser("\"hello world\" rest");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("\"hello world\"");
  }

  /** Unclosed single-quoted string throws IllegalStateException. */
  @Test
  public void parserNextWordUnclosedQuoteThrows() {
    TestableBaseParser p = parser("'unclosed");
    assertThatThrownBy(() -> p.parserOptionalWord(false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing closed string");
  }

  // ---------------------------------------------------------------------------
  // Instance: parserNextWord — bracket/brace/parenthesis nesting
  // ---------------------------------------------------------------------------

  /**
   * Parenthesis '(' is a default separator, so a standalone parenthesized expression is treated as a
   * separator prefix. The nesting counter only applies once inside a word. Test with custom
   * separators that exclude '(' and ')'.
   */
  @Test
  public void parserNextWordParenthesizedExpressionWithCustomSeparators() {
    TestableBaseParser p = parser("func(a, b) rest");
    // Use custom separators that exclude '(' and ')'
    String word = p.parserNextWord(false, " =><\r\n");
    assertThat(word).isEqualTo("func(a, b)");
  }

  /** Bracketed expression is kept as a single word. */
  @Test
  public void parserNextWordBracketedExpression() {
    TestableBaseParser p = parser("[a, b] rest");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("[a, b]");
  }

  /** Brace expression is kept as a single word. */
  @Test
  public void parserNextWordBraceExpression() {
    TestableBaseParser p = parser("{a, b} rest");
    String word = p.parserOptionalWord(false);
    assertThat(word).isEqualTo("{a, b}");
  }

  /** Unclosed bracket throws IllegalStateException. */
  @Test
  public void parserNextWordUnclosedBracketThrows() {
    TestableBaseParser p = parser("[unclosed");
    assertThatThrownBy(() -> p.parserOptionalWord(false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing closed braket");
  }

  /** Unclosed brace throws IllegalStateException. */
  @Test
  public void parserNextWordUnclosedBraceThrows() {
    TestableBaseParser p = parser("{unclosed");
    assertThatThrownBy(() -> p.parserOptionalWord(false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing closed graph");
  }

  /**
   * Unclosed parenthesis throws IllegalStateException. Since '(' is a default separator, use custom
   * separators that exclude it so the nesting counter is exercised.
   */
  @Test
  public void parserNextWordUnclosedParenthesisThrows() {
    TestableBaseParser p = parser("func(unclosed");
    assertThatThrownBy(() -> p.parserNextWord(false, " =><\r\n"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing closed parenthesis");
  }

  // ---------------------------------------------------------------------------
  // Instance: parserNextWord — separator tracking
  // ---------------------------------------------------------------------------

  /** The separator character is tracked in parserGetLastSeparator. */
  @Test
  public void parserNextWordTracksSeparator() {
    TestableBaseParser p = parser("a=b");
    p.parserOptionalWord(false); // parses "a", stops at '='
    assertThat(p.parserGetLastSeparator()).isEqualTo('=');
  }

  /** At end of text, separator resets to space. */
  @Test
  public void parserNextWordSeparatorResetsAtEndOfText() {
    TestableBaseParser p = parser("single");
    p.parserOptionalWord(false); // parses "single", reaches end
    assertThat(p.parserGetLastSeparator()).isEqualTo(' ');
  }

  // ---------------------------------------------------------------------------
  // Instance: parserNextWord — preserveEscapes
  // ---------------------------------------------------------------------------

  /** With preserveEscapes=true, escape sequences are kept as literal text. */
  @Test
  public void parserNextWordPreserveEscapesKeepsBackslashSequences() {
    TestableBaseParser p = parser("a" + "\\" + "nb rest");
    String word = p.parserNextWord(false, " =><(),\r\n", true);
    assertThat(word).isEqualTo("a" + "\\" + "nb");
  }

  // ---------------------------------------------------------------------------
  // Instance: parserNextChars
  // ---------------------------------------------------------------------------

  /** Matches a candidate word by character-by-character comparison. */
  @Test
  public void parserNextCharsMatchesCandidateWord() {
    TestableBaseParser p = parser(">=rest");
    int index = p.parserNextChars("testDb", false, false, ">=", "<=", "!=");
    assertThat(index).isEqualTo(0);
  }

  /** Returns -1 when no candidate matches and not mandatory. */
  @Test
  public void parserNextCharsNoMatchReturnsMinusOne() {
    TestableBaseParser p = parser("abc rest");
    int index = p.parserNextChars("testDb", false, false, ">=", "<=");
    assertThat(index).isEqualTo(-1);
  }

  /** Throws when mandatory and no candidate matches. */
  @Test
  public void parserNextCharsMandatoryNoMatchThrows() {
    TestableBaseParser p = parser("abc rest");
    assertThatThrownBy(() -> p.parserNextChars("testDb", false, true, ">=", "<="))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------
  // Instance: getLastWordLength with escape sequences
  // ---------------------------------------------------------------------------

  /** getLastWordLength accounts for escape sequence count. */
  @Test
  public void getLastWordLengthIncludesEscapeSequenceCount() {
    TestableBaseParser p = parser("\\" + "u0041rest");
    p.parserOptionalWord(false); // unicode escape: 6 chars input → 5 chars word + 5 escape count
    // parserLastWord = "Arest" (5 chars), parserEscapeSequenceCount = 5
    assertThat(p.getLastWordLength()).isEqualTo(10);
  }

  /** getLastWordLength with non-special escape (1 char consumed for \x → just 'x'). */
  @Test
  public void getLastWordLengthWithNonSpecialEscape() {
    TestableBaseParser p = parser("\\" + "x");
    p.parserOptionalWord(false); // \x → 'x', escape count = 1
    // parserLastWord = "x" (1 char), parserEscapeSequenceCount = 1
    assertThat(p.getLastWordLength()).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // Instance: sequential word parsing
  // ---------------------------------------------------------------------------

  /** Parses multiple words in sequence. */
  @Test
  public void sequentialWordParsing() {
    TestableBaseParser p = parser("SELECT name FROM class");
    assertThat(p.parserOptionalWord(true)).isEqualTo("SELECT");
    assertThat(p.parserOptionalWord(false)).isEqualTo("name");
    assertThat(p.parserOptionalWord(true)).isEqualTo("FROM");
    assertThat(p.parserOptionalWord(false)).isEqualTo("class");
    assertThat(p.parserOptionalWord(false)).isNull();
    assertThat(p.parserIsEnded()).isTrue();
  }
}
