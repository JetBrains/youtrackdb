/*
 * JUnit 4 version of SQLFunctionsTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLFunctionsTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLFunctionsTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLFunctionsTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLFunctionsTest extends BaseDBTest {

  private static SQLFunctionsTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new SQLFunctionsTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 49) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLFunctionsTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateCompanyData();
    generateProfiles();
    generateGraphData();
  }

  /**
   * Original: testSelectMap (line 216) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLFunctionsTest.java
   */
  @Test
  public void test01_SelectMap() {
    var result =
        session
            .query("select list( 1, 4, 5.00, 'john', map( 'kAA', 'vAA' ) ) as myresult")
            .toList();

    assertEquals(result.size(), 1);

    var document = result.getFirst();
    @SuppressWarnings("rawtypes")
    List myresult = document.getProperty("myresult");
    assertNotNull(myresult);

    assertTrue(myresult.remove(Integer.valueOf(1)));
    assertTrue(myresult.remove(Integer.valueOf(4)));
    assertTrue(myresult.remove(Float.valueOf(5)));
    assertTrue(myresult.remove("john"));

    assertEquals(myresult.size(), 1);

    assertTrue("The object is: " + myresult.getClass(), myresult.getFirst() instanceof Map);
    @SuppressWarnings("rawtypes")
    var map = (Map) myresult.getFirst();

    var value = (String) map.get("kAA");
    assertEquals(value, "vAA");

    assertEquals(map.size(), 1);
  }

  /**
   * Original: testHashMethod (line 465) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLFunctionsTest.java
   */
  @Test
  public void test02_HashMethod() throws Exception {
    var result =
        session
            .query("select name, name.hash() as n256, name.hash('sha-512') as n512 from OUser")
            .toList();

    assertFalse(result.isEmpty());
    for (var d : result) {
      final String name = d.getProperty("name");

      assertEquals(SecurityManager.createHash(name, "SHA-256"), d.getProperty("n256"));
      assertEquals(SecurityManager.createHash(name, "SHA-512"), d.getProperty("n512"));
    }
  }

  /**
   * Original: testFirstFunction (line 481) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLFunctionsTest.java
   */
  @Test
  public void test03_FirstFunction() {
    List<Long> sequence = new ArrayList<>(100);
    for (long i = 0; i < 100; ++i) {
      sequence.add(i);
    }

    session.begin();
    session.newVertex()
        .setProperty("sequence", session.newEmbeddedList(sequence), PropertyType.EMBEDDEDLIST);
    var newSequence = new ArrayList<>(sequence);
    newSequence.removeFirst();
    session.newVertex()
        .setProperty("sequence", session.newEmbeddedList(newSequence), PropertyType.EMBEDDEDLIST);
    session.commit();

    var result =
        session.query(
                "select first(sequence) as first from V where sequence is not null order by first")
            .toList();

    assertEquals(result.size(), 2);
    assertEquals(result.get(0).<Object>getProperty("first"), 0L);
    assertEquals(result.get(1).<Object>getProperty("first"), 1L);
  }

  /**
   * Original: testLastFunction (line 507) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLFunctionsTest.java
   */
  @Test
  public void test04_LastFunction() {
    List<Long> sequence = new ArrayList<>(100);
    for (long i = 0; i < 100; ++i) {
      sequence.add(i);
    }

    session.begin();
    session.newVertex().setProperty("sequence2", session.newEmbeddedList(sequence));

    var newSequence = new ArrayList<>(sequence);
    newSequence.remove(sequence.size() - 1);

    session.newVertex().setProperty("sequence2", session.newEmbeddedList(newSequence));
    session.commit();

    var result =
        session.query(
                "select last(sequence2) as last from V where sequence2 is not null order by last desc")
            .toList();

    assertEquals(result.size(), 2);

    assertEquals(result.get(0).<Object>getProperty("last"), 99L);
    assertEquals(result.get(1).<Object>getProperty("last"), 98L);
  }

}
