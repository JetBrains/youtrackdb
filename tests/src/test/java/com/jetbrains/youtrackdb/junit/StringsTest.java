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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.common.parser.StringParser;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import org.junit.jupiter.api.Test;

public class StringsTest extends BaseDBJUnit5Test {

  @Test
  void splitArray() {
    var pieces =
        StringSerializerHelper.smartSplit(
            "first, orders : ['this is mine', 'that is your']",
            new char[] {','},
            0,
            -1,
            true,
            true,
            false,
            false,
            ' ',
            '\n',
            '\r',
            '\t');
    assertEquals(2, pieces.size());
    assertTrue(pieces.get(1).contains("this is mine"));
  }

  @Test
  void replaceAll() {
    var test1 = "test string number 1";
    var test2 =
        "test \\string\\ \"number\" \\2\\ \\\\ \"\"\"\" test String number 2 test string"
            + " number 2";
    assertEquals(test1, StringParser.replaceAll(test1, "", ""));
    assertEquals(test1 + "0", StringParser.replaceAll(test1, "1", "10"));
    assertEquals(
        "test number number 1", StringParser.replaceAll(test1, "string", "number"));
    assertEquals("test test number 1", StringParser.replaceAll(test1, "string", "test"));
    assertEquals(
        "string string number 1", StringParser.replaceAll(test1, "test", "string"));
    assertEquals(test2, StringParser.replaceAll(test2, "", ""));
    assertEquals(
        "test string \"number\" 2  \"\"\"\" test String number 2 test string number 2",
        StringParser.replaceAll(test2, "\\", ""));
    assertEquals(
        "test \\string\\ 'number' \\2\\ \\\\ '''' test String number 2 test string"
            + " number 2",
        StringParser.replaceAll(test2, "\"", "'"));
    assertEquals(
        "test \\string\\ \"number\" \\2\\ replacement \"\"\"\" test String number 2 test"
            + " string number 2",
        StringParser.replaceAll(test2, "\\\\", "replacement"));
    var subsequentReplaceTest = StringParser.replaceAll(test2, "\\", "");
    subsequentReplaceTest = StringParser.replaceAll(subsequentReplaceTest, "\"", "");
    subsequentReplaceTest =
        StringParser.replaceAll(
            subsequentReplaceTest, "test string number 2", "text replacement 1");
    assertEquals(
        "text replacement 1   test String number 2 text replacement 1",
        subsequentReplaceTest);
  }

  @Test
  void testNoEmptyFields() {
    var pieces =
        StringSerializerHelper.split(
            "1811000032;03/27/2014;HA297000610K;+3415.4000;+3215.4500;+0.0000;+1117.0000;"
                + "+916.7500;3583;890;+64.8700;4;4;+198.0932",
            ';');
    assertEquals(14, pieces.size());
  }

  @Test
  void testEmptyFields() {
    var pieces =
        StringSerializerHelper.split(
            "1811000032;03/27/2014;HA297000960C;+0.0000;+0.0000;+0.0000;+0.0000;+0.0000;"
                + "0;0;+0.0000;;5;+0.0000",
            ';');
    assertEquals(14, pieces.size());
  }

  @Test
  void testDocumentSelfReference() {
    session.begin();
    var document = session.newEntity();
    document.setProperty("selfref", document);

    var docTwo = session.newEntity();
    docTwo.setProperty("ref", document);
    document.setProperty("ref", docTwo);

    var value = document.toString();

    assertEquals(
        "O#7:-2{ref:#7:-3,selfref:#7:-2} v0", value);
    session.commit();
  }
}
