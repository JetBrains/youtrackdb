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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.internal.common.parser.StringParser;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class StringsTest extends BaseDBTest {

  @Parameters(value = "remote")
  public StringsTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void splitArray() {
    var pieces =
        StringSerializerHelper.smartSplit(
            "first, orders : ['this is mine', 'that is your']",
            new char[]{','},
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
    Assert.assertEquals(pieces.size(), 2);
    Assert.assertTrue(pieces.get(1).contains("this is mine"));
  }

  @Test
  public void replaceAll() {
    var test1 = "test string number 1";
    var test2 =
        "test \\string\\ \"number\" \\2\\ \\\\ \"\"\"\" test String number 2 test string number 2";
    Assert.assertEquals(StringParser.replaceAll(test1, "", ""), test1);
    Assert.assertEquals(StringParser.replaceAll(test1, "1", "10"), test1 + "0");
    Assert.assertEquals(
        StringParser.replaceAll(test1, "string", "number"), "test number number 1");
    Assert.assertEquals(StringParser.replaceAll(test1, "string", "test"), "test test number 1");
    Assert.assertEquals(
        StringParser.replaceAll(test1, "test", "string"), "string string number 1");
    Assert.assertEquals(StringParser.replaceAll(test2, "", ""), test2);
    Assert.assertEquals(
        StringParser.replaceAll(test2, "\\", ""),
        "test string \"number\" 2  \"\"\"\" test String number 2 test string number 2");
    Assert.assertEquals(
        StringParser.replaceAll(test2, "\"", "'"),
        "test \\string\\ 'number' \\2\\ \\\\ '''' test String number 2 test string number 2");
    Assert.assertEquals(
        StringParser.replaceAll(test2, "\\\\", "replacement"),
        "test \\string\\ \"number\" \\2\\ replacement \"\"\"\" test String number 2 test string"
            + " number 2");
    var subsequentReplaceTest = StringParser.replaceAll(test2, "\\", "");
    subsequentReplaceTest = StringParser.replaceAll(subsequentReplaceTest, "\"", "");
    subsequentReplaceTest =
        StringParser.replaceAll(
            subsequentReplaceTest, "test string number 2", "text replacement 1");
    Assert.assertEquals(
        subsequentReplaceTest, "text replacement 1   test String number 2 text replacement 1");
  }

  @Test
  public void testNoEmptyFields() {
    var pieces =
        StringSerializerHelper.split(
            "1811000032;03/27/2014;HA297000610K;+3415.4000;+3215.4500;+0.0000;+1117.0000;+916.7500;3583;890;+64.8700;4;4;+198.0932",
            ';');
    Assert.assertEquals(pieces.size(), 14);
  }

  @Test
  public void testEmptyFields() {
    var pieces =
        StringSerializerHelper.split(
            "1811000032;03/27/2014;HA297000960C;+0.0000;+0.0000;+0.0000;+0.0000;+0.0000;0;0;+0.0000;;5;+0.0000",
            ';');
    Assert.assertEquals(pieces.size(), 14);
  }

  @Test
  public void testDocumentSelfReference() {
    session.begin();
    var document = session.newEntity();
    document.setProperty("selfref", document);

    var docTwo = session.newEntity();
    docTwo.setProperty("ref", document);
    document.setProperty("ref", docTwo);

    var value = document.toString();

    Assert.assertEquals(value,
        "O{selfref:<recursion:rid=#-1:-1>,ref:O{ref:<recursion:rid=#-1:-1>}}");
    session.commit();
  }
}
