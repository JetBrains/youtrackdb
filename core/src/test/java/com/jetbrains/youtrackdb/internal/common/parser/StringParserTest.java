package com.jetbrains.youtrackdb.internal.common.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Tests for {@link StringParser} — static utility class for string tokenization, word extraction,
 * splitting, and helper methods (unicode decoding, replaceAll, startsWithIgnoreCase).
 */
public class StringParserTest {

  // ---------------------------------------------------------------------------
  // getWords — 2-arg overload (separators only, no string sep inclusion)
  // ---------------------------------------------------------------------------

  /** Splitting by comma produces one token per field. */
  @Test
  public void getWordsSplitsByComma() {
    String[] result = StringParser.getWords("a,b,c", ",");
    assertThat(result).containsExactly("a", "b", "c");
  }

  /** Leading/trailing whitespace on the input string is trimmed. */
  @Test
  public void getWordsTrimsSurroundingWhitespace() {
    String[] result = StringParser.getWords("  hello world  ", " ");
    assertThat(result).containsExactly("hello", "world");
  }

  /** Empty input yields an empty array. */
  @Test
  public void getWordsEmptyInputReturnsEmpty() {
    String[] result = StringParser.getWords("", ",");
    assertThat(result).isEmpty();
  }

  /** Input with only separators yields an empty array. */
  @Test
  public void getWordsOnlySeparatorsReturnsEmpty() {
    String[] result = StringParser.getWords(",,,", ",");
    assertThat(result).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // getWords — 3-arg overload (with iIncludeStringSep)
  // ---------------------------------------------------------------------------

  /** When iIncludeStringSep=true, quote characters are kept in the output. */
  @Test
  public void getWordsIncludesStringQuotesWhenFlagIsTrue() {
    String[] result = StringParser.getWords("'hello','world'", ",", true);
    assertThat(result).containsExactly("'hello'", "'world'");
  }

  /** When iIncludeStringSep=false, quote characters are stripped. */
  @Test
  public void getWordsStripsQuotesWhenFlagIsFalse() {
    String[] result = StringParser.getWords("'hello','world'", ",", false);
    assertThat(result).containsExactly("hello", "world");
  }

  /** Double-quoted strings are handled the same as single-quoted. */
  @Test
  public void getWordsDoubleQuotedStrings() {
    String[] result = StringParser.getWords("\"hello\",\"world\"", ",", true);
    assertThat(result).containsExactly("\"hello\"", "\"world\"");
  }

  // ---------------------------------------------------------------------------
  // getWords — 4-arg overload (with custom jump chars)
  // ---------------------------------------------------------------------------

  /** Custom jump chars cause those characters to be skipped outside strings. */
  @Test
  public void getWordsCustomJumpCharsSkipsSpecifiedChars() {
    // Use '-' as a jump char — dashes between words are skipped
    String[] result = StringParser.getWords("a-b-c", ",", "-", false);
    assertThat(result).containsExactly("abc");
  }

  // ---------------------------------------------------------------------------
  // getWords — escape sequence handling
  // ---------------------------------------------------------------------------

  /** Unicode escape is decoded inside getWords. */
  @Test
  public void getWordsDecodesUnicodeEscape() {
    // Build the input with literal backslash-u sequences to avoid Java source-level unicode escapes
    String input = "\\" + "u0041" + "\\" + "u0042";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("AB");
  }

  /** Backslash-n outside a string is translated to a literal newline. */
  @Test
  public void getWordsEscapeNewlineOutsideString() {
    String input = "a" + "\\" + "nb";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("a\nb");
  }

  /** Backslash-n inside a quoted string preserves the backslash. */
  @Test
  public void getWordsEscapeNewlineInsideString() {
    String input = "'a" + "\\" + "nb'";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("a\\" + "\nb");
  }

  /** Backslash-r outside string becomes carriage return. */
  @Test
  public void getWordsEscapeCarriageReturn() {
    String input = "a" + "\\" + "rb";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("a\rb");
  }

  /** Backslash-t outside string becomes tab. */
  @Test
  public void getWordsEscapeTab() {
    String input = "a" + "\\" + "tb";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("a\tb");
  }

  /** Backslash-f outside string becomes form-feed. */
  @Test
  public void getWordsEscapeFormFeed() {
    String input = "a" + "\\" + "fb";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("a\fb");
  }

  /** Escaped quote inside a string is preserved literally. */
  @Test
  public void getWordsEscapedQuoteInsideString() {
    String input = "'can" + "\\" + "'t'";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("can\\" + "'t");
  }

  /** A backslash followed by a non-special char is treated as a literal backslash. */
  @Test
  public void getWordsBackslashFollowedByNormalChar() {
    String input = "a" + "\\" + "bc";
    String[] result = StringParser.getWords(input, ",", false);
    assertThat(result).containsExactly("a" + "\\" + "bc");
  }

  // ---------------------------------------------------------------------------
  // getWords — bracket and brace nesting
  // ---------------------------------------------------------------------------

  /** Separators inside brackets are not treated as word separators. */
  @Test
  public void getWordsBracketNestingPreventsSplit() {
    String[] result = StringParser.getWords("[a,b],c", ",", false);
    assertThat(result).containsExactly("[a,b]", "c");
  }

  /** Separators inside braces are not treated as word separators. */
  @Test
  public void getWordsBraceNestingPreventsSplit() {
    String[] result = StringParser.getWords("{a,b},c", ",", false);
    assertThat(result).containsExactly("{a,b}", "c");
  }

  // ---------------------------------------------------------------------------
  // split
  // ---------------------------------------------------------------------------

  /** Basic split by comma. */
  @Test
  public void splitBasicByComma() {
    String[] result = StringParser.split("a,b,c", ',', " ");
    assertThat(result).containsExactly("a", "b", "c");
  }

  /** Split preserves content inside quoted strings — commas inside quotes are not delimiters. */
  @Test
  public void splitRespectsQuotedStrings() {
    String[] result = StringParser.split("'a,b',c", ',', " ");
    assertThat(result).containsExactly("'a,b'", "c");
  }

  /** Leading jump characters in each segment are stripped. */
  @Test
  public void splitStripsLeadingJumpChars() {
    String[] result = StringParser.split("a, b, c", ',', " ");
    assertThat(result).containsExactly("a", "b", "c");
  }

  /** Unicode escape within split is decoded. */
  @Test
  public void splitDecodesUnicodeEscape() {
    String input = "\\" + "u0041,B";
    String[] result = StringParser.split(input, ',', " ");
    assertThat(result).containsExactly("A", "B");
  }

  /** Empty input yields empty array. */
  @Test
  public void splitEmptyInput() {
    String[] result = StringParser.split("", ',', " ");
    assertThat(result).isEmpty();
  }

  /** Double-quoted strings also prevent split on the delimiter. */
  @Test
  public void splitDoubleQuotedStringsPreventSplit() {
    String[] result = StringParser.split("\"a,b\",c", ',', " ");
    assertThat(result).containsExactly("\"a,b\"", "c");
  }

  /** Backslash escape followed by a non-unicode char preserves the backslash. */
  @Test
  public void splitEscapeNonUnicode() {
    String input = "a" + "\\" + "bc,d";
    String[] result = StringParser.split(input, ',', " ");
    assertThat(result).containsExactly("a" + "\\" + "bc", "d");
  }

  /** Trailing delimiter does not produce a trailing empty field. */
  @Test
  public void splitTrailingDelimiterDropsEmptyField() {
    String[] result = StringParser.split("a,b,", ',', " ");
    assertThat(result).containsExactly("a", "b");
  }

  /** Leading delimiter produces a leading empty field. */
  @Test
  public void splitLeadingDelimiterProducesEmptyField() {
    String[] result = StringParser.split(",a,b", ',', " ");
    assertThat(result).containsExactly("", "a", "b");
  }

  /** Consecutive delimiters produce empty fields between them. */
  @Test
  public void splitConsecutiveDelimitersProduceEmptyFields() {
    String[] result = StringParser.split("a,,b", ',', " ");
    assertThat(result).containsExactly("a", "", "b");
  }

  // ---------------------------------------------------------------------------
  // indexOfOutsideStrings
  // ---------------------------------------------------------------------------

  /** Finds a character at its first occurrence (forward search). */
  @Test
  public void indexOfOutsideStringsFindsChar() {
    int pos = StringParser.indexOfOutsideStrings("hello,world", ',', 0, -1);
    assertThat(pos).isEqualTo(5);
  }

  /** Character inside a single-quoted string is not found. */
  @Test
  public void indexOfOutsideStringsSkipsSingleQuotedString() {
    int pos = StringParser.indexOfOutsideStrings("'a,b',c", ',', 0, -1);
    assertThat(pos).isEqualTo(5);
  }

  /** Character inside a double-quoted string is not found. */
  @Test
  public void indexOfOutsideStringsSkipsDoubleQuotedString() {
    int pos = StringParser.indexOfOutsideStrings("\"a,b\",c", ',', 0, -1);
    assertThat(pos).isEqualTo(5);
  }

  /** Returns -1 when the character is not present outside strings. */
  @Test
  public void indexOfOutsideStringsNotFound() {
    int pos = StringParser.indexOfOutsideStrings("'a,b'", ',', 0, -1);
    assertThat(pos).isEqualTo(-1);
  }

  /**
   * iFrom == -1 resolves to the end of the string. When iFrom > iTo, the search goes backward, but
   * the backward loop condition (--i < iFrom) exits after checking only the starting position.
   * Pre-existing limitation: backward search only inspects one character.
   */
  @Test
  public void indexOfOutsideStringsFromMinusOneBackwardSearchChecksOnlyStartPosition() {
    // iFrom=-1 → 10 (last char 'd'), iTo=0. Since iFrom(10) > iTo(0), backward search.
    // Only checks position 10 ('d'), which is not ',', so returns -1.
    int pos = StringParser.indexOfOutsideStrings("hello,world", ',', -1, 0);
    assertThat(pos).isEqualTo(-1);
  }

  /** When iFrom == -1 and the last character is the target, backward search finds it. */
  @Test
  public void indexOfOutsideStringsFromMinusOneFindsLastChar() {
    int pos = StringParser.indexOfOutsideStrings("hello,", ',', -1, 0);
    assertThat(pos).isEqualTo(5);
  }

  /** Forward search within a bounded range. */
  @Test
  public void indexOfOutsideStringsBoundedRange() {
    // Search from 0 to 3 in "hello,world" — comma is at 5, outside range
    int pos = StringParser.indexOfOutsideStrings("hello,world", ',', 0, 3);
    assertThat(pos).isEqualTo(-1);
  }

  /**
   * Escaped quote inside a quoted string does not terminate the string. Input: 'can\'t',x — the
   * backslash-escaped quote at position 5 is not a closing quote. The real closing quote is at
   * position 7, and the comma is at position 8.
   */
  @Test
  public void indexOfOutsideStringsEscapedQuoteInsideString() {
    // Characters: ' c a n \ ' t ' , x  (positions 0-9)
    String input = "'can" + "\\" + "'t',x";
    int pos = StringParser.indexOfOutsideStrings(input, ',', 0, -1);
    assertThat(pos).isEqualTo(8);
  }

  /** When iFrom == iTo, exactly one position is checked — finds char if present. */
  @Test
  public void indexOfOutsideStringsSameFromAndToFindsChar() {
    int pos = StringParser.indexOfOutsideStrings("hello,world", ',', 5, 5);
    assertThat(pos).isEqualTo(5);
  }

  /** When iFrom == iTo and char is not at that position, returns -1. */
  @Test
  public void indexOfOutsideStringsSameFromAndToMissReturnsMinusOne() {
    int pos = StringParser.indexOfOutsideStrings("hello,world", ',', 3, 3);
    assertThat(pos).isEqualTo(-1);
  }

  // ---------------------------------------------------------------------------
  // jumpWhiteSpaces
  // ---------------------------------------------------------------------------

  /** Skips spaces, carriage returns, and newlines at start. */
  @Test
  public void jumpWhiteSpacesSkipsLeadingWhitespace() {
    int pos = StringParser.jumpWhiteSpaces("  \r\nhello", 0, -1);
    assertThat(pos).isEqualTo(4);
  }

  /** Returns -1 when the entire string is whitespace. */
  @Test
  public void jumpWhiteSpacesAllWhitespace() {
    int pos = StringParser.jumpWhiteSpaces("   \n\r", 0, -1);
    assertThat(pos).isEqualTo(-1);
  }

  /** Returns current position when it's already a non-whitespace character. */
  @Test
  public void jumpWhiteSpacesNoLeadingWhitespace() {
    int pos = StringParser.jumpWhiteSpaces("hello", 0, -1);
    assertThat(pos).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // jump
  // ---------------------------------------------------------------------------

  /** Skips specified jump characters. */
  @Test
  public void jumpSkipsSpecifiedChars() {
    int pos = StringParser.jump("---hello", 0, -1, "-");
    assertThat(pos).isEqualTo(3);
  }

  /** Negative position returns -1. */
  @Test
  public void jumpNegativePositionReturnsMinusOne() {
    int pos = StringParser.jump("hello", -1, -1, " ");
    assertThat(pos).isEqualTo(-1);
  }

  /** Returns -1 if all characters are jump characters. */
  @Test
  public void jumpAllCharsAreJumpChars() {
    int pos = StringParser.jump("---", 0, -1, "-");
    assertThat(pos).isEqualTo(-1);
  }

  /** Respects iMaxPosition boundary. */
  @Test
  public void jumpRespectsMaxPosition() {
    // "   hello" — jump spaces, but max position is 2 (before finding 'h')
    int pos = StringParser.jump("   hello", 0, 2, " ");
    assertThat(pos).isEqualTo(-1);
  }

  /** Empty jump chars means no characters to skip — returns current position. */
  @Test
  public void jumpEmptyJumpCharsReturnsCurrentPosition() {
    int pos = StringParser.jump("hello", 0, -1, "");
    assertThat(pos).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // readUnicode (String overload)
  // ---------------------------------------------------------------------------

  /** Decodes a 4-digit hex unicode sequence into a character. */
  @Test
  public void readUnicodeDecodesHex() {
    StringBuilder buffer = new StringBuilder();
    // "0041" = 'A'
    int lastPos = StringParser.readUnicode("0041rest", 0, buffer);
    assertThat(buffer.toString()).isEqualTo("A");
    // Returns position of last consumed char (index 3)
    assertThat(lastPos).isEqualTo(3);
  }

  /** Decodes a non-ASCII unicode character. */
  @Test
  public void readUnicodeNonAscii() {
    StringBuilder buffer = new StringBuilder();
    // "00E9" = 'é'
    int lastPos = StringParser.readUnicode("00E9", 0, buffer);
    assertThat(buffer.toString()).isEqualTo("\u00E9");
    assertThat(lastPos).isEqualTo(3);
  }

  /** Truncated unicode input (fewer than 4 hex digits) throws StringIndexOutOfBoundsException. */
  @Test(expected = StringIndexOutOfBoundsException.class)
  public void readUnicodeTruncatedInputThrows() {
    StringBuilder buffer = new StringBuilder();
    StringParser.readUnicode("0A", 0, buffer);
  }

  /** Invalid hex characters throw NumberFormatException. */
  @Test(expected = NumberFormatException.class)
  public void readUnicodeInvalidHexThrows() {
    StringBuilder buffer = new StringBuilder();
    StringParser.readUnicode("GHIJ", 0, buffer);
  }

  // ---------------------------------------------------------------------------
  // readUnicode (char[] overload)
  // ---------------------------------------------------------------------------

  /** Char-array overload decodes the same way. */
  @Test
  public void readUnicodeCharArrayDecodes() {
    StringBuilder buffer = new StringBuilder();
    char[] text = "0042rest".toCharArray();
    int lastPos = StringParser.readUnicode(text, 0, buffer);
    assertThat(buffer.toString()).isEqualTo("B");
    assertThat(lastPos).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------
  // replaceAll
  // ---------------------------------------------------------------------------

  /** Simple replacement of a substring. */
  @Test
  public void replaceAllSimpleReplacement() {
    String result = StringParser.replaceAll("hello world", "world", "there");
    assertThat(result).isEqualTo("hello there");
  }

  /** Multiple occurrences are all replaced. */
  @Test
  public void replaceAllMultipleOccurrences() {
    String result = StringParser.replaceAll("aaa", "a", "bb");
    assertThat(result).isEqualTo("bbbbbb");
  }

  /** No match returns the original string unchanged. */
  @Test
  public void replaceAllNoMatch() {
    String result = StringParser.replaceAll("hello", "xyz", "abc");
    assertThat(result).isEqualTo("hello");
  }

  /** Null input returns null. */
  @Test
  public void replaceAllNullInput() {
    String result = StringParser.replaceAll(null, "a", "b");
    assertThat(result).isNull();
  }

  /** Empty input returns empty string. */
  @Test
  public void replaceAllEmptyInput() {
    String result = StringParser.replaceAll("", "a", "b");
    assertThat(result).isEmpty();
  }

  /** Null search string returns original text. */
  @Test
  public void replaceAllNullSearchReturnsOriginal() {
    String result = StringParser.replaceAll("hello", null, "b");
    assertThat(result).isEqualTo("hello");
  }

  /** Empty search string returns original text. */
  @Test
  public void replaceAllEmptySearchReturnsOriginal() {
    String result = StringParser.replaceAll("hello", "", "b");
    assertThat(result).isEqualTo("hello");
  }

  /** Null replacement value appends literal "null" (StringBuffer behavior). */
  @Test
  public void replaceAllNullReplacementAppendsLiteralNull() {
    String result = StringParser.replaceAll("abc", "b", null);
    assertThat(result).isEqualTo("anullc");
  }

  // ---------------------------------------------------------------------------
  // startsWithIgnoreCase
  // ---------------------------------------------------------------------------

  /** Exact match returns true. */
  @Test
  public void startsWithIgnoreCaseExactMatch() {
    assertThat(StringParser.startsWithIgnoreCase("Hello", "Hello")).isTrue();
  }

  /** Case-insensitive match returns true. */
  @Test
  public void startsWithIgnoreCaseDifferentCase() {
    assertThat(StringParser.startsWithIgnoreCase("HELLO world", "hello")).isTrue();
  }

  /** Non-matching prefix returns false. */
  @Test
  public void startsWithIgnoreCaseMismatch() {
    assertThat(StringParser.startsWithIgnoreCase("world", "hello")).isFalse();
  }

  /** Prefix longer than text returns false. */
  @Test
  public void startsWithIgnoreCasePrefixLongerThanText() {
    assertThat(StringParser.startsWithIgnoreCase("hi", "hello")).isFalse();
  }

  /** Empty prefix matches any string. */
  @Test
  public void startsWithIgnoreCaseEmptyPrefixMatchesAny() {
    assertThat(StringParser.startsWithIgnoreCase("hello", "")).isTrue();
  }

  /** Both empty strings returns true. */
  @Test
  public void startsWithIgnoreCaseBothEmptyReturnsTrue() {
    assertThat(StringParser.startsWithIgnoreCase("", "")).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Boundary tests (review findings)
  // ---------------------------------------------------------------------------

  /** Input without any separator match yields a single-element array. */
  @Test
  public void getWordsSingleWordNoSeparatorMatch() {
    String[] result = StringParser.getWords("hello", ",");
    assertThat(result).containsExactly("hello");
  }

  /** Custom jump chars are skipped but separators still split tokens. */
  @Test
  public void getWordsCustomJumpCharsWithSeparators() {
    String[] result = StringParser.getWords("a-b,c-d", ",", "-", false);
    assertThat(result).containsExactly("ab", "cd");
  }

  /** Trailing backslash at end of input is treated as a literal character. */
  @Test
  public void getWordsTrailingBackslashIsLiteral() {
    String[] result = StringParser.getWords("abc" + "\\", ",", false);
    assertThat(result).containsExactly("abc" + "\\");
  }
}
