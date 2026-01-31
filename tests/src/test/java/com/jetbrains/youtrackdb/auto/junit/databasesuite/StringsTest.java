/*
 * JUnit 4 version of StringsTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/StringsTest.java
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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of StringsTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/StringsTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class StringsTest extends BaseDBTest {

  private static StringsTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new StringsTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/StringsTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testNoEmptyFields (line 78) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/StringsTest.java
   */
  @Test
  public void test01_NoEmptyFields() {
    var pieces =
        StringSerializerHelper.split(
            "1811000032;03/27/2014;HA297000610K;+3415.4000;+3215.4500;+0.0000;+1117.0000;+916.7500;3583;890;+64.8700;4;4;+198.0932",
            ';');
    Assert.assertEquals(pieces.size(), 14);
  }

  /**
   * Original: testEmptyFields (line 87) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/StringsTest.java
   */
  @Test
  public void test02_EmptyFields() {
    var pieces =
        StringSerializerHelper.split(
            "1811000032;03/27/2014;HA297000960C;+0.0000;+0.0000;+0.0000;+0.0000;+0.0000;0;0;+0.0000;;5;+0.0000",
            ';');
    Assert.assertEquals(pieces.size(), 14);
  }

  /**
   * Original: testDocumentSelfReference (line 96) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/StringsTest.java
   */
  @Test
  public void test03_DocumentSelfReference() {
    session.begin();
    var document = session.newEntity();
    document.setProperty("selfref", document);

    var docTwo = session.newEntity();
    docTwo.setProperty("ref", document);
    document.setProperty("ref", docTwo);

    var value = document.toString();

    Assert.assertEquals(value,
        "O#7:-2{ref:#7:-3,selfref:#7:-2} v0");
    session.commit();
  }

}
