/*
 * JUnit 4 version of SQLCreateClassTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateClassTest.java
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

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLCreateClassTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateClassTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLCreateClassTest extends BaseDBTest {

  private static SQLCreateClassTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLCreateClassTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateClassTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testSimpleCreate (line 23) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateClassTest.java
   */
  @Test
  public void test01_SimpleCreate() {
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testSimpleCreate"));
    session.execute("create class testSimpleCreate").close();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("testSimpleCreate"));
  }

  /**
   * Original: testIfNotExists (line 30) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateClassTest.java
   */
  @Test
  public void test02_IfNotExists() {
    Assert.assertFalse(session.getMetadata().getSchema().existsClass("testIfNotExists"));
    session.execute("create class testIfNotExists if not exists").close();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("testIfNotExists"));
    session.execute("create class testIfNotExists if not exists").close();
    Assert.assertTrue(session.getMetadata().getSchema().existsClass("testIfNotExists"));
    try {
      session.execute("create class testIfNotExists").close();
      Assert.fail();
    } catch (Exception e) {
      // okay
    }
  }

}
