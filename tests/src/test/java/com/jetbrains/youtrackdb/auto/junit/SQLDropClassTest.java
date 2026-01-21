/*
 * JUnit 4 version of SQLDropClassTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropClassTest.java
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
package com.jetbrains.youtrackdb.auto.junit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLDropClassTest extends BaseDBTest {

  private static SQLDropClassTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLDropClassTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropClassTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testSimpleDrop (line 11) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropClassTest.java
   */
  @Test
  public void test01_SimpleDrop() {
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
    session.execute("create class testSimpleDrop").close();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
    session.execute("Drop class testSimpleDrop").close();
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testSimpleDrop"));
  }

  /**
   * Original: testIfExists (line 20) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLDropClassTest.java
   */
  @Test
  public void test02_IfExists() {
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("create class testIfExists if not exists").close();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("drop class testIfExists if exists").close();
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
    session.execute("drop class testIfExists if exists").close();
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testIfExists"));
  }

}
