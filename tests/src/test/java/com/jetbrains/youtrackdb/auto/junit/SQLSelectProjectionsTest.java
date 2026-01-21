/*
 * JUnit 4 version of SQLSelectProjectionsTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectProjectionsTest.java
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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLSelectProjectionsTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectProjectionsTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLSelectProjectionsTest extends BaseDBTest {

  private static SQLSelectProjectionsTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLSelectProjectionsTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 36) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectProjectionsTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateGraphData();
    generateProfiles();
  }

  /**
   * Original: testSelectExcludeFunction (line 299) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectProjectionsTest.java
   */
  @Test
  public void test01_SelectExcludeFunction() {
    try {
      session.createClass("A");
      session.createClass("B");

      session.begin();
      var rootElement = session.newInstance("A");
      var childElement = session.newInstance("B");

      rootElement.setProperty("a", "a");
      rootElement.setProperty("b", "b");

      childElement.setProperty("c", "c");
      childElement.setProperty("d", "d");
      childElement.setProperty("e", "e");

      rootElement.setProperty("child", childElement, PropertyType.LINK);

      session.commit();

      session.begin();
      var res =
          executeQuery("select a,b, child.exclude('d') as child from " + rootElement.getIdentity());

      Assert.assertNotNull(res.getFirst().getProperty("a"));
      Assert.assertNotNull(res.getFirst().getProperty("b"));

      final var child = res.getFirst().getResult("child");

      Assert.assertNotNull(child.getProperty("c"));
      Assert.assertNull(child.getProperty("d"));
      Assert.assertNotNull(child.getProperty("e"));
      session.commit();

    } finally {
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }

  /**
   * Original: testSimpleExpandExclude (line 340) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLSelectProjectionsTest.java
   */
  @Test
  public void test02_SimpleExpandExclude() {
    try {
      session.createClass("A");
      session.createClass("B");

      session.begin();
      var rootElement = session.newInstance("A");
      rootElement.setProperty("a", "a");
      rootElement.setProperty("b", "b");

      var childElement = session.newInstance("B");
      childElement.setProperty("c", "c");
      childElement.setProperty("d", "d");
      childElement.setProperty("e", "e");

      rootElement.setProperty("child", childElement, PropertyType.LINK);
      childElement.setProperty("root", session.newLinkList(List.of(rootElement)),
          PropertyType.LINKLIST);

      session.commit();

      session.begin();
      var res =
          executeQuery(
              "select child.exclude('d') as link from (select expand(root) from "
                  + childElement.getIdentity()
                  + " )");
      Assert.assertEquals(res.size(), 1);

      var root = res.getFirst();
      Assert.assertNotNull(root.getProperty("link"));

      Assert.assertNull(root.<Result>getProperty("link").getProperty("d"));
      Assert.assertNotNull(root.<Result>getProperty("link").getProperty("c"));
      Assert.assertNotNull(root.<Result>getProperty("link").getProperty("e"));

    } finally {
      session.commit();
      session.execute("drop class A").close();
      session.execute("drop class B").close();
    }
  }

}
