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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import java.util.Date;
import java.util.stream.Collectors;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Ignore;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SecurityTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SecurityTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeMethod
  @Override
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    session.close();
  }

  public void testWrongPassword() throws IOException {
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

  public void testSecurityAccessWriter() throws IOException {
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

  @Test
  public void testSecurityAccessReader() throws IOException {
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

  @Test
  public void testEncryptPassword() throws IOException {
    session = createSessionInstance("admin", "admin");

    session.begin();
    Long updated =
        session
            .execute("update ouser set password = 'test' where name = 'reader'")
            .next()
            .getProperty("count");
    session.commit();

    Assert.assertEquals(updated.intValue(), 1);

    session.begin();
    var result = session.query("select from ouser where name = 'reader'");
    Assert.assertNotEquals(result.next().getProperty("password"), "test");
    session.commit();

    // RESET OLD PASSWORD
    session.begin();
    updated =
        session
            .execute("update ouser set password = 'reader' where name = 'reader'")
            .next()
            .getProperty("count");
    session.commit();
    Assert.assertEquals(updated.intValue(), 1);

    session.begin();
    result = session.query("select from ouser where name = 'reader'");
    Assert.assertNotEquals(result.next().getProperty("password"), "reader");
    session.commit();

    session.close();
  }

  public void testParentRole() {
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
          if (!session.isRemote()) {
            session = createSessionInstance("writerChild", "writerChild");

            session.begin();
            var user = session.getCurrentUser();
            Assert.assertTrue(user.hasRole(session, "writer", true));
            Assert.assertFalse(user.hasRole(session, "wrter", true));
            session.commit();

            session.close();
          }
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

  @Test
  public void testQuotedUserName() {
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

  @Test
  public void testUserNoRole() {
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

  @Test
  public void testAdminCanSeeSystemClusters() {
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
    Assert.assertTrue(session.browseCluster("OUser").hasNext());
    session.commit();
  }

  @Test
  @Ignore
  public void testOnlyAdminCanSeeSystemClusters() {
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
      Assert.assertFalse(session.browseCluster("OUser").hasNext());
      Assert.fail();
    } catch (SecurityException e) {
    }
  }

  @Test
  public void testCannotExtendClassWithNoUpdateProvileges() {
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

  @Test
  public void testSuperUserCanExtendClassWithNoUpdateProvileges() {
    session = createSessionInstance();
    session.getMetadata().getSchema().createClass("Protected");

    try {
      session.execute("alter class Protected superclass OUser").close();
    } finally {
      session.getMetadata().getSchema().dropClass("Protected");
    }
  }

  @Test
  public void testEmptyUserName() {
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

  @Test
  public void testUserNameWithAllSpaces() {
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

  @Test
  public void testUserNameWithSurroundingSpacesOne() {
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

  @Test
  public void testUserNameWithSurroundingSpacesTwo() {
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

  @Test
  public void testUserNameWithSurroundingSpacesThree() {
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

  @Test
  public void testUserNameWithSpacesInTheMiddle() {
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
