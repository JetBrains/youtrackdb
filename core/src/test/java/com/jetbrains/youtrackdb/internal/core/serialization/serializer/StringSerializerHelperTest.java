package com.jetbrains.youtrackdb.internal.core.serialization.serializer;

import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.decode;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.encode;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.indexOf;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.joinIntArray;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.smartSplit;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.smartTrim;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.split;
import static com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper.splitIntArray;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class StringSerializerHelperTest extends DbTestBase {

  @Test
  public void test() {
    final List<String> stringItems = new ArrayList<String>();
    final var text =
        "['f\\'oo', 'don\\'t can\\'t', \"\\\"bar\\\"\", 'b\\\"a\\'z', \"q\\\"u\\'x\"]";
    final var startPos = 0;

    StringSerializerHelper.getCollection(
        text,
        startPos,
        stringItems,
        StringSerializerHelper.LIST_BEGIN,
        StringSerializerHelper.LIST_END,
        StringSerializerHelper.COLLECTION_SEPARATOR);

    assertEquals(IOUtils.getStringContent(stringItems.get(0)), "f'oo");
    assertEquals(IOUtils.getStringContent(stringItems.get(1)), "don't can't");
    assertEquals(IOUtils.getStringContent(stringItems.get(2)), "\"bar\"");
    assertEquals(IOUtils.getStringContent(stringItems.get(3)), "b\"a'z");
    assertEquals(IOUtils.getStringContent(stringItems.get(4)), "q\"u'x");
  }

  @Test
  public void testSmartTrim() {
    var input = "   t  est   ";
    assertEquals(smartTrim(input, true, true), "t  est");
    assertEquals(smartTrim(input, false, true), " t  est");
    assertEquals(smartTrim(input, true, false), "t  est ");
    assertEquals(smartTrim(input, false, false), " t  est ");
  }

  @Test
  public void testEncode() {
    assertEquals(encode("test"), "test");
    assertEquals(encode("\"test\""), "\\\"test\\\"");
    assertEquals(encode("\\test\\"), "\\\\test\\\\");
    assertEquals(encode("test\"test"), "test\\\"test");
    assertEquals(encode("test\\test"), "test\\\\test");
  }

  @Test
  public void testDecode() {
    assertEquals(decode("test"), "test");
    assertEquals(decode("\\\"test\\\""), "\"test\"");
    assertEquals(decode("\\\\test\\\\"), "\\test\\");
    assertEquals(decode("test\\\"test"), "test\"test");
    assertEquals(decode("test\\\\test"), "test\\test");
  }

  @Test
  public void testEncodeAndDecode() {
    var values = new String[] {
        "test",
        "test\"",
        "test\"test",
        "test\\test",
        "test\\\\test",
        "test\\\\\"test",
        "\\\\\\\\",
        "\"\"\"\"",
        "\\\"\\\"\\\""
    };
    for (var value : values) {
      var encoded = encode(value);
      var decoded = decode(encoded);
      assertEquals(decoded, value);
    }
  }

  @Test
  public void testGetMap() {
    var testText = "";
    var map = StringSerializerHelper.getMap(session, testText);
    assertNotNull(map);
    assertTrue(map.isEmpty());

    testText = "{ param1 :value1, param2 :value2}";
    // testText = "{\"param1\":\"value1\",\"param2\":\"value2\"}";
    map = StringSerializerHelper.getMap(session, testText);
    assertNotNull(map);
    assertFalse(map.isEmpty());
    System.out.println(map);
    System.out.println(map.keySet());
    System.out.println(map.values());
    assertEquals(map.get("param1"), "value1");
    assertEquals(map.get("param2"), "value2");
    // Following tests will be nice to support, but currently it's not supported!
    // {param1 :value1, param2 :value2}
    // {param1 : value1, param2 : value2}
    // {param1 : "value1", param2 : "value2"}
    // {"param1" : "value1", "param2" : "value2"}
    // {param1 : "value1\\value1", param2 : "value2\\value2"}
  }

  @Test
  public void testIndexOf() {
    var testString = "This is my test string";
    assertEquals(indexOf(testString, 0, 'T'), 0);
    assertEquals(indexOf(testString, 0, 'h'), 1);
    assertEquals(indexOf(testString, 0, 'i'), 2);
    assertEquals(indexOf(testString, 0, 'h', 'i'), 1);
    assertEquals(indexOf(testString, 2, 'i'), 2);
    assertEquals(indexOf(testString, 3, 'i'), 5);
  }

  @Test
  public void testSmartSplit() {
    var testString = "a, b, c, d";
    var splitted = smartSplit(testString, ',');
    assertEquals(splitted.get(0), "a");
    assertEquals(splitted.get(1), " b");
    assertEquals(splitted.get(2), " c");
    assertEquals(splitted.get(3), " d");

    splitted = smartSplit(testString, ',', ' ');
    assertEquals(splitted.get(0), "a");
    assertEquals(splitted.get(1), "b");
    assertEquals(splitted.get(2), "c");
    assertEquals(splitted.get(3), "d");

    splitted = smartSplit(testString, ',', ' ', 'c');
    assertEquals(splitted.get(0), "a");
    assertEquals(splitted.get(1), "b");
    assertEquals(splitted.get(2), "");
    assertEquals(splitted.get(3), "d");

    testString = "a test, b test, c test, d test";
    splitted = smartSplit(testString, ',', ' ');
    assertEquals(splitted.get(0), "atest");
    assertEquals(splitted.get(1), "btest");
    assertEquals(splitted.get(2), "ctest");
    assertEquals(splitted.get(3), "dtest");
  }

  @Test
  public void testGetLowerIndexOfKeywords() {

    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("from", 0, "from"), 0);
    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select from", 0, "from"), 7);
    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select from foo", 0, "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords("select out[' from '] from foo", 0, "from"),
        21);

    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select from", 7, "from"), 7);
    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select from foo", 7, "from"), 7);

    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select from", 8, "from"), -1);
    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select from foo", 8, "from"), -1);

    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select\tfrom", 0, "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords("select\tfrom\tfoo", 0, "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords(
            "select\tout[' from ']\tfrom\tfoo", 0, "from"),
        21);

    assertEquals(StringSerializerHelper.getLowerIndexOfKeywords("select\nfrom", 0, "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords("select\nfrom\nfoo", 0, "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords(
            "select\nout[' from ']\nfrom\nfoo", 0, "from"),
        21);

    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords("select from", 0, "let", "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords("select from foo", 0, "let", "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords(
            "select out[' from '] from foo", 0, "let", "from"),
        21);

    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords("select from", 0, "let", "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords("select from foo", 0, "let", "from"), 7);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords(
            "select out[' from '] from foo let a = 1", 0, "let", "from"),
        21);
    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords(
            "select out[' from '] from foo let a = 1", 0, "from", "let"),
        21);

    assertEquals(
        StringSerializerHelper.getLowerIndexOfKeywords(
            "select (select from foo) as bar from foo", 0, "let", "from"),
        32);
  }

  /** {@code joinIntArray} produces a {@code COMMA-separated} string in input order. */
  @Test
  public void testJoinIntArrayProducesCommaSeparatedString() {
    assertEquals("1,2,3", joinIntArray(new int[] {1, 2, 3}));
  }

  /** Empty array returns empty string — no leading separator. */
  @Test
  public void testJoinIntArrayEmptyArrayReturnsEmptyString() {
    assertEquals("", joinIntArray(new int[] {}));
  }

  /** Single-element array has no separator at all. */
  @Test
  public void testJoinIntArraySingleElementHasNoSeparator() {
    assertEquals("42", joinIntArray(new int[] {42}));
  }

  /** Negative ints survive the round-trip — pin {@code -} is not the separator. */
  @Test
  public void testJoinIntArrayPreservesNegativeNumbers() {
    assertEquals("-1,2,-3", joinIntArray(new int[] {-1, 2, -3}));
  }

  /** Round-trip: every output of {@code joinIntArray} reverses to the same int[]. */
  @Test
  public void testJoinSplitIntArrayRoundTrip() {
    final var original = new int[] {0, 1, -2, 3, Integer.MAX_VALUE, Integer.MIN_VALUE};
    final var joined = joinIntArray(original);
    assertArrayEquals(original, splitIntArray(joined));
  }

  /** {@code splitIntArray} on a single int returns a 1-element array. */
  @Test
  public void testSplitIntArraySingleElement() {
    assertArrayEquals(new int[] {42}, splitIntArray("42"));
  }

  /** {@code splitIntArray} trims whitespace inside elements (per {@code Integer.parseInt}). */
  @Test
  public void testSplitIntArrayTrimsWhitespacePerElement() {
    assertArrayEquals(new int[] {1, 2, 3}, splitIntArray("1, 2 , 3"));
  }

  /** {@code split(String, char, char...)} basic comma-separated split with no jump chars. */
  @Test
  public void testSplitBasicCommaSeparated() {
    final List<String> result = split("a,b,c", ',');
    assertEquals(List.of("a", "b", "c"), result);
  }

  /**
   * {@code split} drops jump characters only when the element buffer is empty (leading
   * position). Trailing jump characters are trimmed only from the LAST element after the loop
   * ends — middle elements retain their trailing jump chars verbatim, since they are committed
   * to the result list as soon as a separator is hit.
   */
  @Test
  public void testSplitDropsLeadingJumpCharactersOnlyAtBufferStart() {
    final List<String> result = split("  a , b , c  ", ',', ' ');
    // "  a " trims its 2 leading spaces (buffer empty), keeps trailing space → "a "
    // " b "  trims its leading space, keeps trailing → "b "
    // " c  " trims leading space, then post-loop scan-from-end strips both trailing spaces → "c"
    assertEquals(List.of("a ", "b ", "c"), result);
  }

  /**
   * Multi-record-separator overload: any character in the separator string ends an item.
   */
  @Test
  public void testSplitMultiSeparator() {
    final Collection<String> result =
        StringSerializerHelper.split(new ArrayList<>(), "a,b;c|d", 0, -1, ",;|");
    assertEquals(List.of("a", "b", "c", "d"), result);
  }

  /**
   * {@code -1} as end-position means "to end of string" (the post-condition normalization).
   * Pin so a regression that interpreted -1 as an absolute index would AIOBE.
   */
  @Test
  public void testSplitNegativeEndPositionMeansEndOfString() {
    final Collection<String> result =
        StringSerializerHelper.split(new ArrayList<>(), "a,b,c", 0, -1, ",");
    assertEquals(List.of("a", "b", "c"), result);
  }

  /** {@code contains} returns true only when the separator is actually present. */
  @Test
  public void testContainsReturnsTrueWhenSeparatorPresent() {
    assertTrue(StringSerializerHelper.contains("a,b", ','));
  }

  @Test
  public void testContainsReturnsFalseWhenSeparatorAbsent() {
    assertFalse(StringSerializerHelper.contains("ab", ','));
  }

  /** Null input returns {@code false} — explicit guard pin. */
  @Test
  public void testContainsNullInputReturnsFalse() {
    assertFalse(StringSerializerHelper.contains(null, ','));
  }

  /**
   * {@code getCollection} short overload (3-arg) defaults to {@link
   * StringSerializerHelper#LIST_BEGIN} / {@link StringSerializerHelper#LIST_END} delimiters
   * and {@link StringSerializerHelper#COLLECTION_SEPARATOR}.
   */
  @Test
  public void testGetCollectionShortOverloadDefaultsToListDelimiters() {
    final List<String> out = new ArrayList<>();
    final var endPos = StringSerializerHelper.getCollection("[a,b,c]", 0, out);
    assertEquals(List.of("a", "b", "c"), out);
    assertEquals(6, endPos); // index of closing ']'
  }

  /**
   * If the opening delimiter is missing, {@code getCollection} returns -1 and leaves the
   * collection untouched — pin to catch a regression that returned 0 or appended a single
   * empty entry.
   */
  @Test
  public void testGetCollectionReturnsMinusOneWhenOpenDelimiterMissing() {
    final List<String> out = new ArrayList<>();
    final var endPos = StringSerializerHelper.getCollection("a,b,c", 0, out);
    assertEquals(-1, endPos);
    assertTrue(out.isEmpty());
  }

  /**
   * Nested same-style brackets are preserved verbatim inside a single element — i.e., the
   * parser tracks nesting depth and only splits at the top level.
   */
  @Test
  public void testGetCollectionPreservesNestedListSyntaxAsElement() {
    final List<String> out = new ArrayList<>();
    StringSerializerHelper.getCollection("[a,[b,c],d]", 0, out);
    assertEquals(List.of("a", "[b,c]", "d"), out);
  }

  /**
   * Collection ending without a closing bracket (truncated input) returns -1.
   */
  @Test
  public void testGetCollectionUnterminatedInputReturnsMinusOne() {
    final List<String> out = new ArrayList<>();
    final var endPos = StringSerializerHelper.getCollection("[a,b", 0, out);
    assertEquals(-1, endPos);
  }

  /**
   * {@code getCollection} with a {@link Set} target sink works the same as a {@code List} —
   * the parser is collection-agnostic.
   */
  @Test
  public void testGetCollectionAcceptsSetSink() {
    final Set<String> out = new HashSet<>();
    StringSerializerHelper.getCollection(
        "<a,b,a,c>", 0, out, '<', '>', ',');
    // Set deduplication kicks in on the duplicate "a".
    assertEquals(Set.of("a", "b", "c"), out);
  }

  /**
   * {@code smartSplit} via the array-of-separators overload accepts all listed chars as record
   * terminators, splitting at any one of them. Mode flags pinned to the most permissive
   * (string-extended, brace-aware, set/bag-unaware) so the test exercises the dispatch path
   * commonly taken by the SQL parser.
   */
  @Test
  public void testSmartSplitMultiSeparatorAcceptsAllListedChars() {
    final var result =
        StringSerializerHelper.smartSplit(
            "a,b;c",
            new char[] {',', ';'},
            0,
            -1,
            true,
            true,
            false,
            false);
    assertEquals(List.of("a", "b", "c"), result);
  }

  /**
   * {@code smartSplit} respects single-quote strings: a separator inside the quoted region
   * does not split.
   */
  @Test
  public void testSmartSplitDoesNotSplitInsideSingleQuotes() {
    final var result = smartSplit("a,'b,c',d", ',');
    assertEquals(3, result.size());
    assertEquals("a", result.get(0));
    assertEquals("'b,c'", result.get(1));
    assertEquals("d", result.get(2));
  }

  /**
   * {@code smartSplit} respects bracketed regions: a separator inside {@code [...]} does
   * not split.
   */
  @Test
  public void testSmartSplitDoesNotSplitInsideBrackets() {
    final var result = smartSplit("a,[b,c],d", ',');
    assertEquals(3, result.size());
    assertEquals("a", result.get(0));
    assertEquals("[b,c]", result.get(1));
    assertEquals("d", result.get(2));
  }

  /**
   * Empty-input {@code smartSplit} returns an empty list — pin so a regression that returned
   * a singleton empty-string list would be caught.
   */
  @Test
  public void testSmartSplitEmptyInputReturnsEmptyList() {
    assertTrue(smartSplit("", ',').isEmpty());
  }

  /**
   * Sanity check that the default {@code RECORD_SEPARATOR} character used by
   * {@code joinIntArray} is the documented comma — a regression that flipped to e.g. semicolon
   * would silently break legacy persisted strings.
   */
  @Test
  public void testRecordSeparatorIsComma() {
    assertEquals(',', StringSerializerHelper.RECORD_SEPARATOR);
  }

  /**
   * Round-trip the {@code split} → {@code joinIntArray} composition: any int[] joined and then
   * split via the comma path equals the original.
   */
  @Test
  public void testSplitJoinRoundTripPreservesArray() {
    final var arr = new int[] {10, 20, 30};
    assertArrayEquals(arr, splitIntArray(joinIntArray(arr)));
    // Inverse direction
    assertEquals("10,20,30", joinIntArray(new int[] {10, 20, 30}));
  }

  /** The joined string is independent of subsequent mutations to the source array. */
  @Test
  public void testJoinIntArrayUnaffectedByLaterCallerMutation() {
    final var arr = new int[] {1, 2, 3};
    final var joined = joinIntArray(arr);
    arr[0] = 99;
    assertEquals("1,2,3", joined);
  }
}
