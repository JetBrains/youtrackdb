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

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import java.util.Date;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of SecurityTest. Original test class:
 * com.jetbrains.youtrackdb.auto.SecurityTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SecurityTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    SecurityTest instance = new SecurityTest();
    instance.beforeClass();
  }

  /**
   * Original method: beforeMethod Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:34
   */
  @Override
  @Before
  public void beforeMethod() throws Exception {
    super.beforeMethod();
    session.close();
  }

  /**
   * Original test method: testWrongPassword Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:42
   */
  @Test
  public void test01_WrongPassword() throws IOException {
    try {
      session = createSessionInstance("reader", "swdsds");
    } catch (BaseException e) {
      Assert.assertTrue(
          e instanceof SecurityAccessException
              || e.getCause() != null
              && e.getCause()
              .toString()
              .contains("com.orientechnologies.core.exception.SecurityAccessException"));
    }
  }

  /**
   * Original test method: testSecurityAccessWriter Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:55
   */
  @Test
  public void test02_SecurityAccessWriter() throws IOException {
    session = createSessionInstance("writer", "writer");

    try {
      session.begin();
      session.newInternalInstance();
      session.commit();

      Assert.fail();
    } catch (SecurityAccessException e) {
      Assert.assertTrue(true);
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testSecurityAccessReader Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:71
   */
  @Test
  public void test03_SecurityAccessReader() throws IOException {
    session = createSessionInstance("reader", "reader");

    try {
      session.createClassIfNotExist("Profile");

      session.begin();
      ((EntityImpl) session.newEntity("Profile"))
          .properties(
              "nick",
              "error",
              "password",
              "I don't know",
              "lastAccessOn",
              new Date(),
              "registeredOn",
              new Date());

      session.commit();
    } catch (SecurityAccessException e) {
      Assert.assertTrue(true);
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testEncryptPassword Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:98
   */
  @Test
  public void test04_EncryptPassword() throws IOException {
    session = createSessionInstance("admin", "admin");

    session.begin();
    Long updated =
        session
            .execute("update ouser set password = 'test' where name = 'reader'")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertEquals(1, updated.intValue());

    session.begin();
    var result = session.query("select from ouser where name = 'reader'");
    Assert.assertNotEquals("test", result.next().getProperty("password"));
    session.commit();

    // RESET OLD PASSWORD
    session.begin();
    updated =
        session
            .execute("update ouser set password = 'reader' where name = 'reader'")
            .next()
            .getProperty("count");
    session.commit();
    Assert.assertEquals(1, updated.intValue());

    session.begin();
    result = session.query("select from ouser where name = 'reader'");
    Assert.assertNotEquals("reader", result.next().getProperty("password"));
    session.commit();

    session.close();
  }

  /**
   * Original test method: testParentRole Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:135
   */
  @Test
  public void test05_ParentRole() {
    session = createSessionInstance("admin", "admin");

    session.begin();
    var security = session.getMetadata().getSecurity();
    var writer = security.getRole("writer");

    var writerChild =
        security.createRole("writerChild", writer);
    writerChild.save(session);
    session.commit();

    try {
      session.begin();
      var writerGrandChild =
          security.createRole(
              "writerGrandChild", writerChild);
      writerGrandChild.save(session);
      session.commit();

      try {
        session.begin();
        var child = security.createUser("writerChild", "writerChild",
            writerGrandChild);
        child.save(session);
        session.commit();

        try {
          session.begin();
          Assert.assertTrue(child.hasRole(session, "writer", true));
          Assert.assertFalse(child.hasRole(session, "wrter", true));
          session.commit();

          session.close();
          session = createSessionInstance("writerChild", "writerChild");

          session.begin();
          var user = session.getCurrentUser();
          Assert.assertTrue(user.hasRole(session, "writer", true));
          Assert.assertFalse(user.hasRole(session, "wrter", true));
          session.commit();

          session.close();

          session = createSessionInstance();
          security = session.getMetadata().getSecurity();
        } finally {
          session.begin();
          security.dropUser("writerChild");
          session.commit();
        }
      } finally {
        session.begin();
        security.dropRole("writerGrandChild");
        session.commit();
      }
    } finally {
      session.begin();
      security.dropRole("writerChild");
      session.commit();
    }
  }

  /**
   * Original test method: testQuotedUserName Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:198
   */
  @Test
  public void test06_QuotedUserName() {
    session = createSessionInstance();

    session.begin();
    var security = session.getMetadata().getSecurity();

    var adminRole = security.getRole("admin");
    security.createUser("user'quoted", "foobar", adminRole);
    session.commit();
    session.close();

    session = createSessionInstance();
    session.begin();
    security = session.getMetadata().getSecurity();
    var user = security.getUser("user'quoted");
    Assert.assertNotNull(user);
    security.dropUser(user.getName(session));
    session.commit();
    session.close();

    try {
      session = createSessionInstance("user'quoted", "foobar");
      Assert.fail();
    } catch (Exception e) {
    }
  }

  /**
   * Original test method: testUserNoRole Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:227
   */
  @Test
  public void test07_UserNoRole() {
    session = createSessionInstance();

    var security = session.getMetadata().getSecurity();

    session.begin();
    security.createUser("noRole", "noRole", (String[]) null);
    session.commit();

    session.close();

    try {
      session = createSessionInstance("noRole", "noRole");
      Assert.fail();
    } catch (SecurityAccessException e) {
      session = createSessionInstance();
      session.begin();
      security = session.getMetadata().getSecurity();
      security.dropUser("noRole");
      session.commit();
    }
  }

  /**
   * Original test method: testAdminCanSeeSystemCollections Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:251
   */
  @Test
  public void test08_AdminCanSeeSystemCollections() {
    session = createSessionInstance();

    session.begin();
    var result =
        session.execute("select from ouser").stream().collect(Collectors.toList());
    Assert.assertFalse(result.isEmpty());
    session.commit();

    session.begin();
    Assert.assertTrue(session.browseClass("OUser").hasNext());
    session.commit();

    session.begin();
    Assert.assertTrue(session.browseCollection("OUser").hasNext());
    session.commit();
  }

  /**
   * Original test method: testOnlyAdminCanSeeSystemCollections Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:270 Note: This test was
   *
   * @Ignore in original TestNG version
   */
  @Test
  @Ignore
  public void test09_OnlyAdminCanSeeSystemCollections() {
    session = createSessionInstance("reader", "reader");

    try {
      session.query("select from ouser").close();
    } catch (SecurityException e) {
    }

    try {
      Assert.assertFalse(session.browseClass("OUser").hasNext());
      Assert.fail();
    } catch (SecurityException e) {
    }

    try {
      Assert.assertFalse(session.browseCollection("OUser").hasNext());
      Assert.fail();
    } catch (SecurityException e) {
    }
  }

  /**
   * Original test method: testCannotExtendClassWithNoUpdateProvileges Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:293
   */
  @Test
  public void test10_CannotExtendClassWithNoUpdateProvileges() {
    session = createSessionInstance();
    session.getMetadata().getSchema().createClass("Protected");
    session.close();

    session = createSessionInstance("writer", "writer");

    try {
      session.command("alter class Protected superclasses OUser");
      Assert.fail();
    } catch (SecurityException e) {
    } finally {
      session.close();

      session = createSessionInstance();
      session.getMetadata().getSchema().dropClass("Protected");
    }
  }

  /**
   * Original test method: testSuperUserCanExtendClassWithNoUpdateProvileges Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:313
   */
  @Test
  public void test11_SuperUserCanExtendClassWithNoUpdateProvileges() {
    session = createSessionInstance();
    session.getMetadata().getSchema().createClass("Protected");

    try {
      session.execute("alter class Protected superclasses OUser").close();
    } finally {
      session.getMetadata().getSchema().dropClass("Protected");
    }
  }

  /**
   * Original test method: testEmptyUserName Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:325
   */
  @Test
  public void test12_EmptyUserName() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();
      var userName = "";
      try {
        session.begin();
        var reader = security.getRole("reader");
        security.createUser(userName, "foobar", reader);
        session.commit();
        Assert.fail();
      } catch (ValidationException ve) {
        Assert.assertTrue(true);
      }
      Assert.assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testUserNameWithAllSpaces Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:346
   */
  @Test
  public void test13_UserNameWithAllSpaces() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();

      session.begin();
      var reader = security.getRole("reader");
      session.commit();
      final var userName = "  ";
      try {
        session.begin();
        security.createUser(userName, "foobar", reader);
        session.commit();
        Assert.fail();
      } catch (ValidationException ve) {
        Assert.assertTrue(true);
      }
      Assert.assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testUserNameWithSurroundingSpacesOne Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:370
   */
  @Test
  public void test14_UserNameWithSurroundingSpacesOne() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();

      session.begin();
      var reader = security.getRole("reader");
      session.commit();
      final var userName = " sas";
      try {
        session.begin();
        security.createUser(userName, "foobar", reader);
        session.commit();
        Assert.fail();
      } catch (ValidationException ve) {
        Assert.assertTrue(true);
      }
      Assert.assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testUserNameWithSurroundingSpacesTwo Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:394
   */
  @Test
  public void test15_UserNameWithSurroundingSpacesTwo() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();

      session.begin();
      var reader = security.getRole("reader");
      final var userName = "sas ";
      try {
        security.createUser(userName, "foobar", reader);
        session.commit();
        Assert.fail();
      } catch (ValidationException ve) {
        Assert.assertTrue(true);
      }
      Assert.assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testUserNameWithSurroundingSpacesThree Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:416
   */
  @Test
  public void test16_UserNameWithSurroundingSpacesThree() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();

      session.begin();
      var reader = security.getRole("reader");
      session.commit();
      final var userName = " sas ";
      try {
        session.begin();
        security.createUser(userName, "foobar", reader);
        session.commit();
        Assert.fail();
      } catch (ValidationException ve) {
        Assert.assertTrue(true);
      }
      Assert.assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  /**
   * Original test method: testUserNameWithSpacesInTheMiddle Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SecurityTest.java:440
   */
  @Test
  public void test17_UserNameWithSpacesInTheMiddle() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();

      session.begin();
      var reader = security.getRole("reader");
      session.commit();
      final var userName = "s a s";
      session.begin();
      security.createUser(userName, "foobar", reader);
      session.commit();
      session.begin();
      Assert.assertNotNull(security.getUser(userName));
      security.dropUser(userName);
      session.commit();
      session.begin();
      Assert.assertNull(security.getUser(userName));
      session.commit();
    } finally {
      session.close();
    }
  }
}
