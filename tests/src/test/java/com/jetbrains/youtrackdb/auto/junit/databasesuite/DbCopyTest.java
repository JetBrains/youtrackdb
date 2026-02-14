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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import java.io.IOException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of DbCopyTest. Original test class: com.jetbrains.youtrackdb.auto.DbCopyTest
 * Location: tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCopyTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DbCopyTest extends BaseDBTest implements CommandOutputListener {

  @BeforeClass
  public static void setUpClass() throws Exception {
    DbCopyTest instance = new DbCopyTest();
    instance.beforeClass();
  }

  /**
   * Original test method: checkCopy Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCopyTest.java:25
   */
  @Test
  public void test01_CheckCopy() throws IOException {
    final var className = "DbCopyTest";
    session.getMetadata().getSchema().createClass(className);

    try (final var otherDB = session.copy()) {
      for (var i = 0; i < 5; i++) {
        otherDB.begin();
        var doc = otherDB.newInstance(className);
        doc.setProperty("num", 20 + i);

        otherDB.commit();
        try {
          Thread.sleep(10);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    for (var i = 0; i < 20; i++) {
      session.begin();
      var doc = session.newInstance(className);
      doc.setProperty("num", i);

      session.commit();
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    session.begin();
    var result = session.query("SELECT FROM " + className);
    Assert.assertEquals(25, result.stream().count());
    session.commit();
  }

  /**
   * Original method: onMessage (CommandOutputListener implementation) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/DbCopyTest.java:64 Note: This was marked as
   *
   * @Test(enabled = false) in TestNG, so not a test in JUnit.
   */
  @Override
  public void onMessage(final String iText) {
    // System.out.print(iText);
    // System.out.flush();
  }
}
