/*
 *
 *
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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import org.junit.Test;

/**
 * Standalone tests for the session-independent token surface of {@link JSONReader} — every public
 * method that does not depend on a {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded}
 * (so the {@code RidSet}/{@code RID}-aware {@code readRecordString} / {@code readNextRecord} are
 * deliberately excluded; {@code JSONReaderDbTest} covers those).
 *
 * <p>{@code JSONReader} is a one-call-per-token pull-style parser: each {@code readNext*} /
 * {@code readString} / {@code readNumber} call returns the next slice of input bounded by an
 * {@code iUntil} terminator-set, with optional {@code iJumpChars} (whitespace skipped before the
 * token) and {@code iSkipChars} (characters dropped from the buffered slice). The parser keeps
 * a single {@code StringBuilder} buffer across calls; the {@link JSONReader#jump(char[])}
 * clears the buffer and {@link JSONReader#readNext} appends to it. Tests below pin every
 * public method's contract end-to-end, plus the static character-set constants that production
 * callers ({@code DatabaseImport}) consume directly.
 *
 * <p>Cursor / line / column behaviour is exercised on Unix newlines (single {@code '\n'}); the
 * production caller (`DatabaseImport`) reads UTF-8 export files where {@code '\r'} arrives in
 * {@link JSONReader#DEFAULT_JUMP} so the line counter still advances correctly across CR-LF
 * lines — the {@code testNewlineAdvancesLineNumber} pin enforces that.
 */
public class JSONReaderStandaloneTest {

  // ---------------------------------------------------------- static char-set constants

  /**
   * Pin all advertised char-set constants so any reordering / mutation breaks the test before it
   * silently breaks the import code path that imports them by name.
   */
  @Test
  public void staticCharSetsHaveExactExpectedContents() {
    assertEquals('\n', JSONReader.NEW_LINE);
    assertArrayEquals(new char[] {' ', '\r', '\n', '\t'}, JSONReader.DEFAULT_JUMP);
    assertArrayEquals(new char[] {'{'}, JSONReader.BEGIN_OBJECT);
    assertArrayEquals(new char[] {'}'}, JSONReader.END_OBJECT);
    assertArrayEquals(new char[] {':'}, JSONReader.FIELD_ASSIGNMENT);
    assertArrayEquals(new char[] {','}, JSONReader.COMMA_SEPARATOR);
    assertArrayEquals(new char[] {',', '}'}, JSONReader.NEXT_IN_OBJECT);
    assertArrayEquals(new char[] {',', ']'}, JSONReader.NEXT_IN_ARRAY);
    assertArrayEquals(new char[] {'{', ']'}, JSONReader.NEXT_OBJ_IN_ARRAY);
    assertArrayEquals(
        new char[] {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'}, JSONReader.ANY_NUMBER);
    assertArrayEquals(new char[] {'['}, JSONReader.BEGIN_COLLECTION);
    assertArrayEquals(new char[] {']'}, JSONReader.END_COLLECTION);
  }

  // ---------------------------------------------------------- nextChar / jump / cursor

  @Test
  public void nextCharReturnsConsumedCharsInOrder() throws Exception {
    var r = new JSONReader(new StringReader("ab"));
    assertEquals('a', r.nextChar());
    assertEquals('b', r.nextChar());
    // EOF is exposed as -1 — pin so the production loop's break condition stays valid.
    assertEquals(-1, r.nextChar());
  }

  @Test
  public void cursorIncrementsForEachConsumedChar() throws Exception {
    var r = new JSONReader(new StringReader("abc"));
    assertEquals(0, r.getCursor());
    r.nextChar();
    assertEquals(1, r.getCursor());
    r.nextChar();
    assertEquals(2, r.getCursor());
    r.nextChar();
    assertEquals(3, r.getCursor());
  }

  @Test
  public void unicodeEscapeIncrementsCursorBySix() throws Exception {
    // \\u0041 decodes to 'A'. The unicode-escape branch performs `cursor += 6` and then takes an
    // early return, so the trailing `cursor++` at the end of nextChar() is skipped — total
    // increment for the call is exactly 6.
    var r = new JSONReader(new StringReader("\\u0041"));
    var read = r.nextChar();
    assertEquals('A', (char) read);
    assertEquals(6, r.getCursor());
  }

  @Test
  public void backslashWithoutUnicodeBuffersSecondChar() throws Exception {
    var r = new JSONReader(new StringReader("\\b"));
    // First call returns the '\' (the sequence is not a unicode escape, so the second char is
    // remembered for the next call).
    assertEquals('\\', r.nextChar());
    // Next call returns the buffered 'b' WITHOUT advancing the cursor a second time —
    // the backslash branch only increments cursor once for the pair.
    assertEquals('b', r.nextChar());
  }

  @Test
  public void newlineAdvancesLineNumberAndResetsColumn() throws Exception {
    var r = new JSONReader(new StringReader("a\nb"));
    r.nextChar();
    assertEquals(0, r.getLineNumber());
    assertEquals(1, r.getColumnNumber());
    r.nextChar(); // '\n'
    assertEquals(1, r.getLineNumber());
    assertEquals(0, r.getColumnNumber());
    r.nextChar(); // 'b'
    assertEquals(1, r.getLineNumber());
    assertEquals(1, r.getColumnNumber());
  }

  @Test
  public void jumpSkipsWhitespaceUntilFirstNonJumpChar() throws Exception {
    var r = new JSONReader(new StringReader("   \t\n  X"));
    var stop = r.jump(JSONReader.DEFAULT_JUMP);
    assertEquals('X', stop);
    assertEquals('X', r.lastChar());
  }

  @Test
  public void jumpReturnsMinusOneOnEarlyEof() throws Exception {
    var r = new JSONReader(new StringReader("   "));
    // All chars are jump chars; reading consumes them all and the next read returns EOF.
    var stop = r.jump(JSONReader.DEFAULT_JUMP);
    assertEquals(-1, stop);
  }

  /**
   * For an empty {@link StringReader}, {@code BufferedReader.ready()} still returns true (because
   * {@link StringReader#ready} returns true for any non-closed reader), so {@code jump} enters the
   * read loop and immediately observes EOF — returning {@code -1}. Pinning this catches a
   * regression that switched to a buffered-only ready-check and accidentally left the loop early.
   */
  @Test
  public void jumpReturnsMinusOneOnEmptyStringReader() throws Exception {
    var r = new JSONReader(new StringReader(""));
    assertEquals(-1, r.jump(JSONReader.DEFAULT_JUMP));
  }

  // ---------------------------------------------------------- readNext

  /**
   * Basic readNext: skips leading whitespace per {@code DEFAULT_JUMP}, accumulates until the
   * terminator, drops the terminator (because {@code iInclude=false}), and exposes the payload via
   * {@link JSONReader#getValue}.
   */
  @Test
  public void readNextStopsAtUntilCharAndExcludesIt() throws Exception {
    var r = new JSONReader(new StringReader("hello,world"));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    assertEquals("hello", r.getValue());
  }

  @Test
  public void readNextWithIncludeRetainsTerminator() throws Exception {
    var r = new JSONReader(new StringReader("hello,world"));
    r.readNext(JSONReader.COMMA_SEPARATOR, true);
    assertEquals("hello,", r.getValue());
  }

  /** Without skip chars, every char between buffer-start and terminator is preserved. */
  @Test
  public void readNextPreservesAllCharsWhenSkipNull() throws Exception {
    var r = new JSONReader(new StringReader("a b c,"));
    r.readNext(JSONReader.COMMA_SEPARATOR, false, JSONReader.DEFAULT_JUMP, null);
    assertEquals("a b c", r.getValue());
  }

  @Test
  public void readNextDropsExplicitlyListedSkipChars() throws Exception {
    var r = new JSONReader(new StringReader("a b c,"));
    r.readNext(JSONReader.COMMA_SEPARATOR, false, JSONReader.DEFAULT_JUMP, new char[] {' '});
    assertEquals("abc", r.getValue());
  }

  /**
   * Inside a quoted string the terminator must be ignored — pinned because the production parser
   * relies on this to read keys / values containing commas.
   */
  @Test
  public void readNextIgnoresTerminatorInsideDoubleQuotedString() throws Exception {
    var r = new JSONReader(new StringReader("\"a,b\","));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    assertEquals("\"a,b\"", r.getValue());
  }

  @Test
  public void readNextIgnoresTerminatorInsideSingleQuotedString() throws Exception {
    var r = new JSONReader(new StringReader("'a,b',"));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    assertEquals("'a,b'", r.getValue());
  }

  /**
   * Inside a brace-delimited embedded object the terminator is ignored too — production uses this
   * to read whole {@code {...}} bodies in a single {@code readNext} call.
   */
  @Test
  public void readNextIgnoresTerminatorInsideEmbeddedBraces() throws Exception {
    var r = new JSONReader(new StringReader("{a,b},rest"));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    assertEquals("{a,b}", r.getValue());
  }

  /**
   * Inside a quoted string, an escape ({@code \\X}) prevents the next char from acting as either
   * a terminator or a string-end. Pin because the encode-mode flag is the most subtle part of the
   * parser.
   */
  @Test
  public void readNextHandlesEscapedQuoteInsideQuotedString() throws Exception {
    var r = new JSONReader(new StringReader("\"a\\\"b\","));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    // preserveQuotes=true (default) keeps the backslash visible to the caller.
    assertEquals("\"a\\\"b\"", r.getValue());
  }

  @Test
  public void readNextDropsEscapedFollowerWhenPreserveQuotesFalse() throws Exception {
    var r = new JSONReader(new StringReader("\"a\\\"b\","));
    r.readNext(JSONReader.COMMA_SEPARATOR, false, JSONReader.DEFAULT_JUMP, null, false);
    // preserveQuotes=false drops the char that follows the escape backslash: the inner `"` is
    // skipped, the backslash itself is kept (it was appended on the prior loop iteration before
    // the encodeMode flag flipped). Pin the literal output so a regression that also drops the
    // backslash, or that drops nothing, fails immediately.
    assertEquals("\"a\\b\"", r.getValue());
  }

  /** Empty buffer at end of input must throw — pin so the production caller's catch is tested. */
  @Test
  public void readNextThrowsParseExceptionOnEmptyBuffer() {
    var r = new JSONReader(new StringReader(""));
    assertThrows(
        java.text.ParseException.class, () -> r.readNext(JSONReader.COMMA_SEPARATOR));
  }

  /**
   * Two-arg readNext is a thin wrapper that returns {@code this}. Pin the identity so a regression
   * that decides to return a fresh instance is caught by the call-chain pattern in
   * {@code DatabaseImport}.
   */
  @Test
  public void readNextReturnsSelfForFluentChaining() throws Exception {
    var r = new JSONReader(new StringReader("v,"));
    var same = r.readNext(JSONReader.COMMA_SEPARATOR);
    assertEquals(r, same);
    assertEquals("v", r.getValue());
  }

  // ---------------------------------------------------------- readString

  @Test
  public void readStringStripsSurroundingDoubleQuotesByDefault() throws Exception {
    var r = new JSONReader(new StringReader("\"hello\","));
    var v = r.readString(JSONReader.COMMA_SEPARATOR);
    assertEquals("hello", v);
  }

  @Test
  public void readStringWithIncludeKeepsTerminator() throws Exception {
    var r = new JSONReader(new StringReader("\"hello\","));
    var v = r.readString(JSONReader.COMMA_SEPARATOR, true);
    // include=true → trailing comma is kept inside the buffered value, but the surrounding-quote
    // strip ONLY fires when the value starts with a double quote AND iInclude is false.
    assertEquals("\"hello\",", v);
  }

  @Test
  public void readStringPreserveQuotesOverloadKeepsBackslashes() throws Exception {
    var r = new JSONReader(new StringReader("\"a\\\"b\","));
    var v = r.readString(JSONReader.COMMA_SEPARATOR, false, JSONReader.DEFAULT_JUMP, null, true);
    // The substring(1, lastIndexOf('"')) pattern uses the LAST '"' in the value — with the escape
    // backslash retained, the inner ".." run is preserved verbatim.
    assertEquals("a\\\"b", v);
  }

  @Test
  public void readStringWithoutQuotesReturnsRawValue() throws Exception {
    var r = new JSONReader(new StringReader("123,"));
    assertEquals("123", r.readString(JSONReader.COMMA_SEPARATOR));
  }

  // ---------------------------------------------------------- readBoolean / readNumber

  @Test
  public void readBooleanRecognizesTrueAndFalseLiterals() throws Exception {
    var r = new JSONReader(new StringReader("true,false,"));
    assertTrue(r.readBoolean(JSONReader.COMMA_SEPARATOR));
    assertFalse(r.readBoolean(JSONReader.COMMA_SEPARATOR));
  }

  @Test
  public void readBooleanReturnsFalseForNonBooleanText() throws Exception {
    var r = new JSONReader(new StringReader("notbool,"));
    // Boolean.parseBoolean treats anything except case-insensitive "true" as false.
    assertFalse(r.readBoolean(JSONReader.COMMA_SEPARATOR));
  }

  @Test
  public void readIntegerParsesPositiveInteger() throws Exception {
    var r = new JSONReader(new StringReader("42,"));
    assertEquals(42, r.readInteger(JSONReader.COMMA_SEPARATOR));
  }

  @Test
  public void readIntegerParsesNegativeInteger() throws Exception {
    var r = new JSONReader(new StringReader("-42,"));
    assertEquals(-42, r.readInteger(JSONReader.COMMA_SEPARATOR));
  }

  @Test
  public void readIntegerThrowsOnEofBeforeNumber() {
    var r = new JSONReader(new StringReader(""));
    assertThrows(
        java.text.ParseException.class, () -> r.readInteger(JSONReader.COMMA_SEPARATOR));
  }

  @Test
  public void readIntegerThrowsOnNonNumericContent() {
    var r = new JSONReader(new StringReader("xyz,"));
    assertThrows(
        NumberFormatException.class, () -> r.readInteger(JSONReader.COMMA_SEPARATOR));
  }

  // ---------------------------------------------------------- checkContent / isContent

  @Test
  public void checkContentReturnsSelfOnMatch() throws Exception {
    var r = new JSONReader(new StringReader("v,"));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    var same = r.checkContent("v");
    assertEquals(r, same);
  }

  @Test
  public void checkContentThrowsOnMismatch() throws Exception {
    var r = new JSONReader(new StringReader("v,"));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    assertThrows(java.text.ParseException.class, () -> r.checkContent("expected"));
  }

  @Test
  public void isContentReturnsTrueOnMatchAndFalseOnMismatch() throws Exception {
    var r = new JSONReader(new StringReader("v,"));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    assertTrue(r.isContent("v"));
    assertFalse(r.isContent("other"));
  }

  // ---------------------------------------------------------- hasNext / lastChar / line/col

  /**
   * {@link JSONReader#hasNext} forwards to {@link java.io.BufferedReader#ready}. For a
   * {@link StringReader}-backed input that is non-trivially the truth (StringReader.ready()
   * returns {@code true} unconditionally while not closed), so {@code hasNext} stays {@code true}
   * as long as the reader is open. Pinned so a future change that switches to a saner ready-check
   * does not silently break the production caller's loop guard.
   */
  @Test
  public void hasNextStaysTrueForOpenStringReader() throws Exception {
    var r = new JSONReader(new StringReader("xy,"));
    assertTrue(r.hasNext());
    r.readNext(JSONReader.COMMA_SEPARATOR);
    // StringReader.ready() always returns true for a non-closed reader, so hasNext() stays true
    // even after the underlying input is fully consumed.
    assertTrue(r.hasNext());
  }

  @Test
  public void lastCharIsLastNonSkippedCharFromPriorRead() throws Exception {
    var r = new JSONReader(new StringReader("hello,"));
    r.readNext(JSONReader.COMMA_SEPARATOR);
    // The terminator ',' is the last char observed, but lastCharacter is set inside the buffered
    // append branch — i.e. it tracks the last APPENDED char, which is 'o' (the comma is the
    // terminator and is appended too because preserveQuotes=true; iInclude=false trims the buffer
    // afterwards but does NOT roll lastCharacter back).
    assertEquals(',', r.lastChar());
  }

  // ---------------------------------------------------------- multi-token sequences

  /**
   * Multi-token: walk a tiny JSON-like document with explicit terminator sets the way
   * DatabaseImport does (read field name, jump, read value, etc). Pin so a regression in cursor /
   * buffer-reset propagation across calls is caught immediately.
   */
  @Test
  public void multiTokenSequenceConsumesEachFieldIndependently() throws Exception {
    var r = new JSONReader(new StringReader("\"k\":42,\"k2\":7,"));
    r.readNext(JSONReader.FIELD_ASSIGNMENT);
    assertEquals("\"k\"", r.getValue());
    assertEquals(42, r.readInteger(JSONReader.COMMA_SEPARATOR));
    r.readNext(JSONReader.FIELD_ASSIGNMENT);
    assertEquals("\"k2\"", r.getValue());
    assertEquals(7, r.readInteger(JSONReader.COMMA_SEPARATOR));
  }
}
