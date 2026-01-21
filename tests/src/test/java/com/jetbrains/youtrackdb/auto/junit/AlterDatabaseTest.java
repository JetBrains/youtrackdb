/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.auto.junit;

import java.io.IOException;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of AlterDatabaseTest. Original test class:
 * com.jetbrains.youtrackdb.auto.AlterDatabaseTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/AlterDatabaseTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AlterDatabaseTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    AlterDatabaseTest instance = new AlterDatabaseTest();
    instance.beforeClass();
  }

  /**
   * Original test method: alterDateFormatOk Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/AlterDatabaseTest.java:28
   */
  @Test
  public void test01_AlterDateFormatOk() throws IOException {
    session.execute("alter database dateformat 'yyyy-MM-dd';").close();
    session.execute("alter database dateformat 'yyyy-MM-dd'").close();
  }
}
