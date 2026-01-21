/*
 * JUnit 4 version of WrongQueryTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/WrongQueryTest.java
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

import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of WrongQueryTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/WrongQueryTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WrongQueryTest extends BaseDBTest {

  private static WrongQueryTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new WrongQueryTest();
    instance.beforeClass();
  }

  /**
   * Original: queryFieldOperatorNotSupported (line 23) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/WrongQueryTest.java
   */
  @Test
  public void test01_QueryFieldOperatorNotSupported() {
    try (var result = session.execute(
        "select * from Account where name.not() like 'G%'")) {

      Assert.fail();
    } catch (CommandSQLParsingException e) {
    }
  }
}
