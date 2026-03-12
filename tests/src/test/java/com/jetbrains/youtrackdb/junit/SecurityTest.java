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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import java.util.Date;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class SecurityTest extends BaseDBJUnit5Test {

  @BeforeEach
  @Override
  void beforeEach() throws Exception {
    super.beforeEach();

    session.close();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testWrongPassword
  @Test
  void testWrongPassword() throws IOException {
    try {
      session = createSessionInstance("reader", "swdsds");
    } catch (BaseException e) {
      assertTrue(
          e instanceof SecurityAccessException
              || e.getCause() != null
                  && e.getCause()
                      .toString()
                      .contains("com.orientechnologies.core.exception.SecurityAccessException"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testSecurityAccessWriter
  @Test
  void testSecurityAccessWriter() throws IOException {
    session = createSessionInstance("writer", "writer");

    try {
      session.begin();
      session.newInternalInstance();
      session.commit();

      fail();
    } catch (SecurityAccessException e) {
      assertTrue(true);
    } finally {
      session.close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testSecurityAccessReader
  @Test
  void testSecurityAccessReader() throws IOException {
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
      assertTrue(true);
    } finally {
      session.close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testEncryptPassword
  @Test
  void testEncryptPassword() throws IOException {
    session = createSessionInstance("admin", "admin");

    session.begin();
    Long updated =
        session
            .execute("update ouser set password = 'test' where name = 'reader'")
            .next()
            .getProperty("count");
    session.commit();

    assertEquals(1, updated.intValue());

    session.begin();
    var result = session.query("select from ouser where name = 'reader'");
    assertNotEquals("test", result.next().getProperty("password"));
    session.commit();

    // RESET OLD PASSWORD
    session.begin();
    updated =
        session
            .execute("update ouser set password = 'reader' where name = 'reader'")
            .next()
            .getProperty("count");
    session.commit();
    assertEquals(1, updated.intValue());

    session.begin();
    result = session.query("select from ouser where name = 'reader'");
    assertNotEquals("reader", result.next().getProperty("password"));
    session.commit();

    session.close();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testParentRole
  @Test
  void testParentRole() {
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
          assertTrue(child.hasRole(session, "writer", true));
          assertFalse(child.hasRole(session, "wrter", true));
          session.commit();

          session.close();
          session = createSessionInstance("writerChild", "writerChild");

          session.begin();
          var user = session.getCurrentUser();
          assertTrue(user.hasRole(session, "writer", true));
          assertFalse(user.hasRole(session, "wrter", true));
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

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testQuotedUserName
  @Test
  void testQuotedUserName() {
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
    assertNotNull(user);
    security.dropUser(user.getName(session));
    session.commit();
    session.close();

    try {
      session = createSessionInstance("user'quoted", "foobar");
      fail();
    } catch (Exception e) {

    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testUserNoRole
  @Test
  void testUserNoRole() {
    session = createSessionInstance();

    var security = session.getMetadata().getSecurity();

    session.begin();
    security.createUser("noRole", "noRole", (String[]) null);
    session.commit();

    session.close();

    try {
      session = createSessionInstance("noRole", "noRole");
      fail();
    } catch (SecurityAccessException e) {
      session = createSessionInstance();
      session.begin();
      security = session.getMetadata().getSecurity();
      security.dropUser("noRole");
      session.commit();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testAdminCanSeeSystemCollections
  @Test
  void testAdminCanSeeSystemCollections() {
    session = createSessionInstance();

    session.begin();
    var result =
        session.execute("select from ouser").stream().collect(Collectors.toList());
    assertFalse(result.isEmpty());
    session.commit();

    session.begin();
    assertTrue(session.browseClass("OUser").hasNext());
    session.commit();

    session.begin();
    assertTrue(session.browseCollection("OUser").hasNext());
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testOnlyAdminCanSeeSystemCollections
  @Test
  @Disabled
  void testOnlyAdminCanSeeSystemCollections() {
    session = createSessionInstance("reader", "reader");

    try {
      session.query("select from ouser").close();
    } catch (SecurityException e) {
    }

    try {
      assertFalse(session.browseClass("OUser").hasNext());
      fail();
    } catch (SecurityException e) {
    }

    try {
      assertFalse(session.browseCollection("OUser").hasNext());
      fail();
    } catch (SecurityException e) {
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testCannotExtendClassWithNoUpdateProvileges
  @Test
  void testCannotExtendClassWithNoUpdateProvileges() {
    session = createSessionInstance();
    session.getMetadata().getSchema().createClass("Protected");
    session.close();

    session = createSessionInstance("writer", "writer");

    try {
      session.command("alter class Protected superclasses OUser");
      fail();
    } catch (SecurityException e) {
    } finally {
      session.close();

      session = createSessionInstance();
      session.getMetadata().getSchema().dropClass("Protected");
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testSuperUserCanExtendClassWithNoUpdateProvileges
  @Test
  void testSuperUserCanExtendClassWithNoUpdateProvileges() {
    session = createSessionInstance();
    session.getMetadata().getSchema().createClass("Protected");

    try {
      session.execute("alter class Protected superclasses OUser").close();
    } finally {
      session.getMetadata().getSchema().dropClass("Protected");
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testEmptyUserName
  @Test
  void testEmptyUserName() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();
      var userName = "";
      try {
        session.begin();
        var reader = security.getRole("reader");
        security.createUser(userName, "foobar", reader);
        session.commit();
        fail();
      } catch (ValidationException ve) {
        assertTrue(true);
      }
      assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testUserNameWithAllSpaces
  @Test
  void testUserNameWithAllSpaces() {
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
        fail();
      } catch (ValidationException ve) {
        assertTrue(true);
      }
      assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testUserNameWithSurroundingSpacesOne
  @Test
  void testUserNameWithSurroundingSpacesOne() {
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
        fail();
      } catch (ValidationException ve) {
        assertTrue(true);
      }
      assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testUserNameWithSurroundingSpacesTwo
  @Test
  void testUserNameWithSurroundingSpacesTwo() {
    session = createSessionInstance();
    try {
      var security = session.getMetadata().getSecurity();

      session.begin();
      var reader = security.getRole("reader");
      final var userName = "sas ";
      try {
        security.createUser(userName, "foobar", reader);
        session.commit();
        fail();
      } catch (ValidationException ve) {
        assertTrue(true);
      }
      assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testUserNameWithSurroundingSpacesThree
  @Test
  void testUserNameWithSurroundingSpacesThree() {
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
        fail();
      } catch (ValidationException ve) {
        assertTrue(true);
      }
      assertNull(security.getUser(userName));
    } finally {
      session.close();
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SecurityTest#testUserNameWithSpacesInTheMiddle
  @Test
  void testUserNameWithSpacesInTheMiddle() {
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
      assertNotNull(security.getUser(userName));
      security.dropUser(userName);
      session.commit();
      session.begin();
      assertNull(security.getUser(userName));
      session.commit();
    } finally {
      session.close();
    }
  }
}
