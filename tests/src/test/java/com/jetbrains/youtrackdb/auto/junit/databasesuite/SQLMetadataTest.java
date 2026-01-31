/*
 * JUnit 4 version of SQLMetadataTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLMetadataTest.java
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
 * JUnit 4 version of SQLMetadataTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLMetadataTest.java SQL test against
 * metadata.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLMetadataTest extends BaseDBTest {

  private static SQLMetadataTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLMetadataTest();
    instance.beforeClass();
  }

  /**
   * Original: querySchemaClasses (line 28) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLMetadataTest.java
   */
  @Test
  public void test01_QuerySchemaClasses() {
    var result =
        session
            .query("select expand(classes) from metadata:schema").toList();

    Assert.assertTrue(result.size() != 0);
  }

  /**
   * Original: querySchemaProperties (line 37) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLMetadataTest.java
   */
  @Test
  public void test02_QuerySchemaProperties() {
    var result =
        session
            .query(
                "select expand(properties) from (select expand(classes) from metadata:schema)"
                    + " where name = 'OUser'").toList();

    Assert.assertTrue(result.size() != 0);
  }

  /**
   * Original: queryIndexes (line 48) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLMetadataTest.java
   */
  @Test
  public void test03_QueryIndexes() {
    var result =
        session
            .query(
                "select from metadata:indexes").toList();

    Assert.assertTrue(result.size() != 0);
  }

  /**
   * Original: queryMetadataNotSupported (line 57) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLMetadataTest.java
   */
  @Test
  public void test04_QueryMetadataNotSupported() {
    try {
      session
          .query("select from metadata:blaaa").toList();
      Assert.fail();
    } catch (UnsupportedOperationException e) {
    }
  }
}
