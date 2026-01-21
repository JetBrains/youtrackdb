/*
 * JUnit 4 version of SQLCommandsTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCommandsTest.java
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

import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLCommandsTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCommandsTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLCommandsTest extends BaseDBTest {

  private static SQLCommandsTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLCommandsTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 0) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCommandsTest.java
   */
  @Override
  public void beforeClass() throws Exception {

  }

  /**
   * Original: testSQLScript (line 89) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCommandsTest.java
   */
  @Test
  public void test01_SQLScript() {
    var cmd = "";
    cmd += "select from ouser limit 1;begin;";
    cmd += "let a = create vertex set script = true;";
    cmd += "let b = select from v limit 1;";
    cmd += "create edge from $a to $b;";
    cmd += "commit;";
    cmd += "return $a;";

    final var tx = session.begin();
    var result = session.computeScript("sql", cmd).findFirst(Result::asEntity);

    Assert.assertTrue(tx.load(result) instanceof EntityImpl);
    EntityImpl identifiable = tx.load(result);
    var activeTx = session.getActiveTransaction();
    EntityImpl entity = activeTx.load(identifiable);
    Assert.assertTrue(
        entity.getProperty("script"));
    session.commit();
  }

}
