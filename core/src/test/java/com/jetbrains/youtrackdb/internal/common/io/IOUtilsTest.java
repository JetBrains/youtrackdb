package com.jetbrains.youtrackdb.internal.common.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Calendar;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IOUtilsTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void shouldGetTimeAsMilis() {
    assertGetTimeAsMilis("2h", 2 * 3600 * 1000);
    assertGetTimeAsMilis("500ms", 500);
    assertGetTimeAsMilis("4d", 4 * 24 * 3600 * 1000);
    assertGetTimeAsMilis("6w", 6L * 7 * 24 * 3600 * 1000);
  }

  private void assertGetTimeAsMilis(String data, long expected) {
    assertEquals(IOUtils.getTimeAsMillisecs(data), expected);
  }

  @Test
  public void shoudGetRightTimeFromString() throws ParseException {
    var calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, 5);
    calendar.set(Calendar.MINUTE, 10);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    var d = IOUtils.getTodayWithTime("05:10:00");
    assertEquals(calendar.getTime().getTime(), d.getTime());
  }

  @Test
  public void shouldReadFileAsString() throws IOException {
    // UTF-8
    var path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_utf8.txt");

    var asString = IOUtils.readFileAsString(path.toFile());

    assertThat(asString).isEqualToIgnoringCase("utf-8 :: èàòì€");

    // ISO-8859-1
    path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_iso-8859-1.txt");

    asString = IOUtils.readFileAsString(path.toFile());

    assertThat(asString).isNotEqualToIgnoringCase("iso-8859-1 :: èàòì?");
  }

  @Test
  public void shouldReadFileAsStringWithGivenCharset() throws IOException {
    // UTF-8
    var path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_utf8.txt");

    var asString = IOUtils.readFileAsString(path.toFile(), StandardCharsets.UTF_8);

    assertThat(asString).isEqualToIgnoringCase("utf-8 :: èàòì€");

    // ISO-8859-1
    path = Paths.get("./src/test/resources/", getClass().getSimpleName() + "_iso-8859-1.txt");

    asString = IOUtils.readFileAsString(path.toFile(), StandardCharsets.ISO_8859_1);

    assertThat(asString).isEqualToIgnoringCase("iso-8859-1 :: èàòì?");
  }

  // ---------------------------------------------------------------------------
  // getTimeAsString
  // ---------------------------------------------------------------------------

  /** Milliseconds are formatted with ms suffix. */
  @Test
  public void getTimeAsStringMilliseconds() {
    assertThat(IOUtils.getTimeAsString(500)).isEqualTo("500ms");
  }

  /** Exact seconds are formatted with s suffix. */
  @Test
  public void getTimeAsStringSeconds() {
    assertThat(IOUtils.getTimeAsString(3000)).isEqualTo("3s");
  }

  /** Exact minutes are formatted with m suffix. */
  @Test
  public void getTimeAsStringMinutes() {
    assertThat(IOUtils.getTimeAsString(2 * 60 * 1000)).isEqualTo("2m");
  }

  /** Exact hours are formatted with h suffix. */
  @Test
  public void getTimeAsStringHours() {
    assertThat(IOUtils.getTimeAsString(3 * 3600 * 1000)).isEqualTo("3h");
  }

  /** Exact days are formatted with d suffix. */
  @Test
  public void getTimeAsStringDays() {
    assertThat(IOUtils.getTimeAsString(2L * 24 * 3600 * 1000)).isEqualTo("2d");
  }

  /** Exact weeks are formatted with w suffix. */
  @Test
  public void getTimeAsStringWeeks() {
    assertThat(IOUtils.getTimeAsString(2L * 7 * 24 * 3600 * 1000)).isEqualTo("2w");
  }

  /** Zero is formatted as milliseconds. */
  @Test
  public void getTimeAsStringZero() {
    assertThat(IOUtils.getTimeAsString(0)).isEqualTo("0ms");
  }

  /** Exact multiple of years is formatted with y suffix. */
  @Test
  public void getTimeAsStringYears() {
    assertThat(IOUtils.getTimeAsString(2L * IOUtils.YEAR)).isEqualTo("2y");
  }

  /** Exactly SECOND (1000ms) is formatted as ms, not s (strict > boundary). */
  @Test
  public void getTimeAsStringExactSecondFallsToMs() {
    assertThat(IOUtils.getTimeAsString(IOUtils.SECOND)).isEqualTo("1000ms");
  }

  /** Exactly YEAR falls through to days (strict > boundary, YEAR % WEEK != 0). */
  @Test
  public void getTimeAsStringExactYearFallsToDays() {
    assertThat(IOUtils.getTimeAsString(IOUtils.YEAR)).isEqualTo("365d");
  }

  /** Unrecognized time suffix throws IllegalArgumentException. */
  @Test
  public void getTimeAsMillisecsUnrecognizedSuffixThrows() {
    assertThatThrownBy(() -> IOUtils.getTimeAsMillisecs("100Z"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------
  // encode
  // ---------------------------------------------------------------------------

  /** Strings have backslashes and quotes escaped, non-ASCII converted to unicode. */
  @Test
  public void encodeEscapesStringValue() {
    // encode("a\"b") → replace \ with \\ (no-op) → replace " with \" → java2unicode (no-op)
    Object result = IOUtils.encode("a\"b");
    assertThat(result).isEqualTo("a\\\"b");
  }

  /** Non-string values pass through unchanged. */
  @Test
  public void encodeNonStringPassthrough() {
    assertThat(IOUtils.encode(42)).isEqualTo(42);
    assertThat(IOUtils.encode(null)).isNull();
  }

  // ---------------------------------------------------------------------------
  // java2unicode
  // ---------------------------------------------------------------------------

  /** ASCII characters pass through unchanged. */
  @Test
  public void java2unicodeAsciiPassthrough() {
    assertThat(IOUtils.java2unicode("hello")).isEqualTo("hello");
  }

  /** Non-ASCII characters are converted to unicode escape sequences. */
  @Test
  public void java2unicodeNonAsciiConverted() {
    assertThat(IOUtils.java2unicode("\u00E9")).isEqualTo("\\u00e9");
  }

  /** Mixed ASCII and non-ASCII input. */
  @Test
  public void java2unicodeMixed() {
    assertThat(IOUtils.java2unicode("caf\u00E9")).isEqualTo("caf\\u00e9");
  }

  // ---------------------------------------------------------------------------
  // encodeJsonString
  // ---------------------------------------------------------------------------

  /** Backslash is doubled in JSON encoding. */
  @Test
  public void encodeJsonStringBackslash() {
    assertThat(IOUtils.encodeJsonString("a\\b")).isEqualTo("a\\\\b");
  }

  /** Quotes are escaped with backslash. */
  @Test
  public void encodeJsonStringQuotes() {
    assertThat(IOUtils.encodeJsonString("a\"b")).isEqualTo("a\\\"b");
  }

  /** Control characters are converted to unicode escapes. */
  @Test
  public void encodeJsonStringControlChars() {
    assertThat(IOUtils.encodeJsonString("a\nb")).isEqualTo("a\\u000ab");
  }

  /** Tab is converted to unicode escape. */
  @Test
  public void encodeJsonStringTab() {
    assertThat(IOUtils.encodeJsonString("a\tb")).isEqualTo("a\\u0009b");
  }

  /** Plain ASCII passes through unchanged. */
  @Test
  public void encodeJsonStringPlainAscii() {
    assertThat(IOUtils.encodeJsonString("hello")).isEqualTo("hello");
  }

  // ---------------------------------------------------------------------------
  // isStringContent
  // ---------------------------------------------------------------------------

  /** Single-quoted string is detected. */
  @Test
  public void isStringContentSingleQuoted() {
    assertThat(IOUtils.isStringContent("'hello'")).isTrue();
  }

  /** Double-quoted string is detected. */
  @Test
  public void isStringContentDoubleQuoted() {
    assertThat(IOUtils.isStringContent("\"hello\"")).isTrue();
  }

  /** Unquoted string returns false. */
  @Test
  public void isStringContentUnquoted() {
    assertThat(IOUtils.isStringContent("hello")).isFalse();
  }

  /** Null returns false. */
  @Test
  public void isStringContentNull() {
    assertThat(IOUtils.isStringContent(null)).isFalse();
  }

  /** Single character is too short for quoted content. */
  @Test
  public void isStringContentSingleChar() {
    assertThat(IOUtils.isStringContent("'")).isFalse();
  }

  /** Empty quoted string (just the delimiters) is detected. */
  @Test
  public void isStringContentEmptyQuoted() {
    assertThat(IOUtils.isStringContent("''")).isTrue();
  }

  // ---------------------------------------------------------------------------
  // getStringContent
  // ---------------------------------------------------------------------------

  /** Strips single quotes from a quoted string. */
  @Test
  public void getStringContentSingleQuoted() {
    assertThat(IOUtils.getStringContent("'hello'")).isEqualTo("hello");
  }

  /** Strips double quotes. */
  @Test
  public void getStringContentDoubleQuoted() {
    assertThat(IOUtils.getStringContent("\"hello\"")).isEqualTo("hello");
  }

  /** Strips backtick delimiters. */
  @Test
  public void getStringContentBacktickDelimited() {
    assertThat(IOUtils.getStringContent("`field`")).isEqualTo("field");
  }

  /** Unquoted string is returned as-is. */
  @Test
  public void getStringContentUnquoted() {
    assertThat(IOUtils.getStringContent("hello")).isEqualTo("hello");
  }

  /** Null returns null. */
  @Test
  public void getStringContentNull() {
    assertThat(IOUtils.getStringContent(null)).isNull();
  }

  // ---------------------------------------------------------------------------
  // wrapStringContent
  // ---------------------------------------------------------------------------

  /** Wraps value with specified delimiter. */
  @Test
  public void wrapStringContentWraps() {
    assertThat(IOUtils.wrapStringContent("hello", '\'')).isEqualTo("'hello'");
  }

  /** Null returns null. */
  @Test
  public void wrapStringContentNull() {
    assertThat(IOUtils.wrapStringContent(null, '\'')).isNull();
  }

  // ---------------------------------------------------------------------------
  // equals(byte[], byte[])
  // ---------------------------------------------------------------------------

  /** Equal arrays return true. */
  @Test
  public void equalsEqualArrays() {
    assertThat(IOUtils.equals(new byte[] {1, 2, 3}, new byte[] {1, 2, 3})).isTrue();
  }

  /** Different content returns false. */
  @Test
  public void equalsDifferentContent() {
    assertThat(IOUtils.equals(new byte[] {1, 2, 3}, new byte[] {1, 2, 4})).isFalse();
  }

  /** Different lengths return false. */
  @Test
  public void equalsDifferentLength() {
    assertThat(IOUtils.equals(new byte[] {1, 2}, new byte[] {1, 2, 3})).isFalse();
  }

  /** Null first array returns false. */
  @Test
  public void equalsNullFirst() {
    assertThat(IOUtils.equals(null, new byte[] {1})).isFalse();
  }

  /** Null second array returns false. */
  @Test
  public void equalsNullSecond() {
    assertThat(IOUtils.equals(new byte[] {1}, null)).isFalse();
  }

  /** Both null returns false (by design — null arrays are not considered equal). */
  @Test
  public void equalsBothNullReturnsFalse() {
    assertThat(IOUtils.equals(null, null)).isFalse();
  }

  /** Two empty arrays are equal. */
  @Test
  public void equalsEmptyArrays() {
    assertThat(IOUtils.equals(new byte[0], new byte[0])).isTrue();
  }

  // ---------------------------------------------------------------------------
  // isLong
  // ---------------------------------------------------------------------------

  /** Valid long string returns true. */
  @Test
  public void isLongValidNumber() {
    assertThat(IOUtils.isLong("12345")).isTrue();
  }

  /** Non-numeric string returns false. */
  @Test
  public void isLongNonNumeric() {
    assertThat(IOUtils.isLong("abc")).isFalse();
  }

  /** Mixed digits and letters returns false. */
  @Test
  public void isLongMixed() {
    assertThat(IOUtils.isLong("123abc")).isFalse();
  }

  /** Single digit returns true. */
  @Test
  public void isLongSingleDigit() {
    assertThat(IOUtils.isLong("0")).isTrue();
  }

  /**
   * Empty string returns true due to a vacuous-truth bug: the loop body never executes when
   * iText.length() == 0, so isLong stays true. Callers would then get NumberFormatException from
   * Long.parseLong(""). This test documents the current (buggy) behavior.
   */
  @Test
  public void isLongEmptyStringReturnsTrueBug() {
    // Pre-existing bug: empty string passes the digit check vacuously.
    assertThat(IOUtils.isLong("")).isTrue();
  }

  // ---------------------------------------------------------------------------
  // getUnixFileName
  // ---------------------------------------------------------------------------

  /** Backslashes are converted to forward slashes. */
  @Test
  public void getUnixFileNameConvertsBackslashes() {
    assertThat(IOUtils.getUnixFileName("C:\\path\\to\\file")).isEqualTo("C:/path/to/file");
  }

  /** Already-unix path is unchanged. */
  @Test
  public void getUnixFileNameAlreadyUnix() {
    assertThat(IOUtils.getUnixFileName("/path/to/file")).isEqualTo("/path/to/file");
  }

  /** Null returns null. */
  @Test
  public void getUnixFileNameNull() {
    assertThat(IOUtils.getUnixFileName(null)).isNull();
  }

  // ---------------------------------------------------------------------------
  // getRelativePathIfAny
  // ---------------------------------------------------------------------------

  /** With null base path, extracts filename after last slash. */
  @Test
  public void getRelativePathNullBaseExtractsFilename() {
    assertThat(IOUtils.getRelativePathIfAny("/path/to/db", null)).isEqualTo("db");
  }

  /** With base path, extracts relative portion after the base. */
  @Test
  public void getRelativePathWithBase() {
    assertThat(IOUtils.getRelativePathIfAny("/path/to/db", "/path/to")).isEqualTo("db");
  }

  /** When base path is not found, returns the full URL. */
  @Test
  public void getRelativePathBaseNotFound() {
    assertThat(IOUtils.getRelativePathIfAny("/path/to/db", "/other")).isEqualTo("/path/to/db");
  }

  /** With null base path and no slash, returns the full URL. */
  @Test
  public void getRelativePathNullBaseNoSlash() {
    assertThat(IOUtils.getRelativePathIfAny("db", null)).isEqualTo("db");
  }

  // ---------------------------------------------------------------------------
  // writeFile + readFileAsString roundtrip
  // ---------------------------------------------------------------------------

  /** Write and read back file content. */
  @Test
  public void writeFileAndReadBack() throws IOException {
    File file = tempFolder.newFile("test.txt");
    IOUtils.writeFile(file, "hello world");
    String content = IOUtils.readFileAsString(file);
    assertThat(content).isEqualTo("hello world");
  }

  // ---------------------------------------------------------------------------
  // copyStream
  // ---------------------------------------------------------------------------

  /** Copies input stream to output stream. */
  @Test
  public void copyStreamCopiesContent() throws IOException {
    byte[] data = "hello stream".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream in = new ByteArrayInputStream(data);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    IOUtils.copyStream(in, out);
    assertThat(out.toByteArray()).isEqualTo(data);
  }

  // ---------------------------------------------------------------------------
  // readFully
  // ---------------------------------------------------------------------------

  /** Reads exact number of bytes from stream. */
  @Test
  public void readFullyReadsExactBytes() throws IOException {
    byte[] data = {1, 2, 3, 4, 5};
    ByteArrayInputStream in = new ByteArrayInputStream(data);
    byte[] result = new byte[5];
    IOUtils.readFully(in, result, 0, 5);
    assertThat(result).isEqualTo(data);
  }

  /** Throws EOFException when stream is shorter than requested length. */
  @Test
  public void readFullyThrowsOnPrematureEof() {
    byte[] data = {1, 2};
    ByteArrayInputStream in = new ByteArrayInputStream(data);
    byte[] result = new byte[5];
    assertThatThrownBy(() -> IOUtils.readFully(in, result, 0, 5))
        .isInstanceOf(EOFException.class);
  }

  // ---------------------------------------------------------------------------
  // readStreamAsString — BOM stripping
  // ---------------------------------------------------------------------------

  /** readStreamAsString strips a leading UTF-8 BOM if present. */
  @Test
  public void readStreamAsStringStripsUtf8Bom() throws IOException {
    byte[] bomPlusContent =
        new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'e', 'l', 'l', 'o'};
    ByteArrayInputStream in = new ByteArrayInputStream(bomPlusContent);
    String result = IOUtils.readStreamAsString(in);
    assertThat(result).isEqualTo("hello");
  }
}
