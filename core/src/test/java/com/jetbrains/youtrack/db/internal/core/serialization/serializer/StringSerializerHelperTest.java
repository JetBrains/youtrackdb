package com.jetbrains.youtrack.db.internal.core.serialization.serializer;

import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper.decode;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper.encode;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper.indexOf;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper.smartSplit;
import static com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper.smartTrim;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import java.util.ArrayList;
import java.util.List;
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
    var values = new String[]{
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
}
