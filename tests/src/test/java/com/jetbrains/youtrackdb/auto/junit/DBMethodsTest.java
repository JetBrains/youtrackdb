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
package com.jetbrains.youtrackdb.auto.junit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of DBMethodsTest. Original test class:
 * com.jetbrains.youtrackdb.auto.DBMethodsTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBMethodsTest.java
 *
 * @since 9/15/14
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DBMethodsTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    DBMethodsTest instance = new DBMethodsTest();
    instance.beforeClass();
  }

  /**
   * Original test method: testAddCollection Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DBMethodsTest.java:11
   */
  @Test
  public void test01_AddCollection() {
    session.addCollection("addCollectionTest");

    Assert.assertTrue(session.existsCollection("addCollectionTest"));
    Assert.assertTrue(session.existsCollection("addcOllectiontESt"));
  }
}
