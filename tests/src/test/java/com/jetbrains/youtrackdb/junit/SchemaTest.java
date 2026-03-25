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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class SchemaTest extends BaseDBJUnit5Test {

  @Test
  @Order(1)
  void checkSchema() {
    Schema schema = session.getMetadata().getSchema();

    assert schema != null;
    assert schema.getClass("Profile") != null;
    assert schema.getClass("Profile").getProperty("nick").getType()
        == PropertyType.STRING;
    assert schema.getClass("Profile").getProperty("name").getType()
        == PropertyType.STRING;
    assert schema.getClass("Profile").getProperty("surname").getType()
        == PropertyType.STRING;
    assert schema.getClass("Profile").getProperty("registeredOn").getType()
        == PropertyType.DATETIME;
    assert schema.getClass("Profile").getProperty("lastAccessOn").getType()
        == PropertyType.DATETIME;

    assert schema.getClass("Whiz") != null;
    assert schema.getClass("Whiz").getProperty("account").getType()
        == PropertyType.LINK;
    assert schema
        .getClass("Whiz")
        .getProperty("account")
        .getLinkedClass()
        .getName()
        .equals("Account");
    assert schema.getClass("Whiz").getProperty("date").getType() == PropertyType.DATE;
    assert schema.getClass("Whiz").getProperty("text").getType()
        == PropertyType.STRING;
    assert schema.getClass("Whiz").getProperty("text").isMandatory();
    assert schema.getClass("Whiz").getProperty("text").getMin().equals("1");
    assert schema.getClass("Whiz").getProperty("text").getMax().equals("140");
    assert schema.getClass("Whiz").getProperty("replyTo").getType()
        == PropertyType.LINK;
    assert schema
        .getClass("Whiz")
        .getProperty("replyTo")
        .getLinkedClass()
        .getName()
        .equals("Account");
  }

  @Test
  @Order(2)
  void checkInvalidNamesBefore30() {
    Schema schema = session.getMetadata().getSchema();

    schema.createClass("TestInvalidName,");
    assertNotNull(schema.getClass("TestInvalidName,"));
    schema.createClass("TestInvalidName;");
    assertNotNull(schema.getClass("TestInvalidName;"));
    schema.createClass("TestInvalid Name");
    assertNotNull(schema.getClass("TestInvalid Name"));
    schema.createClass("TestInvalid_Name");
    assertNotNull(schema.getClass("TestInvalid_Name"));
  }

  @Test
  @Order(3)
  void checkSchemaApi() {
    Schema schema = session.getMetadata().getSchema();

    try {
      assertNull(schema.getClass("Animal33"));
    } catch (SchemaException e) {
    }
  }

  @Test
  @Order(4)
  void checkCollections() {
    for (var cls : session.getMetadata().getSchema().getClasses()) {
      assert cls.isAbstract()
          || session.getCollectionNameById(cls.getCollectionIds()[0]) != null;
    }
  }

  @Test
  @Order(5)
  void checkTotalRecords() {
    session.begin();
    assertTrue(session.getStorage().countRecords(session) > 0);
    session.rollback();
  }

  @Test
  @Order(6)
  void checkErrorOnUserNoPasswd() {
    assertThrows(ValidationException.class, () -> {
      session.begin();
      session.getMetadata().getSecurity().createUser("error", null, (String) null);
      session.commit();
    });
  }

  @Test
  @Order(7)
  void testMultiThreadSchemaCreation() throws InterruptedException {
    // Create the class on the main thread so it's available in the schema.
    session.getMetadata().getSchema().createClass("NewClass");

    // Background thread must use its own session — sessions are thread-local.
    var error = new AtomicReference<Throwable>();
    var thread =
        new Thread(
            () -> {
              try (var db = acquireSession()) {
                db.begin();
                var doc = ((EntityImpl) db.newEntity("NewClass"));
                db.commit();

                db.begin();
                var activeTx = db.getActiveTransaction();
                doc = activeTx.load(doc);
                doc.delete();
                db.commit();
              } catch (Throwable t) {
                error.set(t);
              }
            });

    thread.start();
    // Use a timeout to prevent infinite CI hangs if the background thread deadlocks.
    thread.join(30_000);
    if (thread.isAlive()) {
      thread.interrupt();
      fail("Background thread did not complete within 30 seconds — possible deadlock");
    }

    // Check for background thread error before cleanup, so cleanup exceptions
    // don't mask the original failure.
    if (error.get() != null) {
      fail("Background thread failed", error.get());
    }

    // Clean up the class on the main thread after the background thread finishes.
    session.getMetadata().getSchema().dropClass("NewClass");
  }

  @Test
  @Order(8)
  void createAndDropClassTestApi() {
    final var testClassName = "dropTestClass";
    final int collectionId;
    var dropTestClass = session.getMetadata().getSchema().createClass(testClassName);
    collectionId = dropTestClass.getCollectionIds()[0];
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNotNull(dropTestClass);
    assertEquals(collectionId,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNotNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNotNull(dropTestClass);
    assertEquals(collectionId,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNotNull(session.getCollectionNameById(collectionId));
    session.getMetadata().getSchema().dropClass(testClassName);
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNull(dropTestClass);
    assertEquals(-1,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNull(dropTestClass);
    assertEquals(-1,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNull(session.getCollectionNameById(collectionId));
  }

  @Test
  @Order(9)
  void createAndDropClassTestCommand() {
    final var testClassName = "dropTestClass";
    final int collectionId;
    var dropTestClass = session.getMetadata().getSchema().createClass(testClassName);
    collectionId = dropTestClass.getCollectionIds()[0];
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNotNull(dropTestClass);
    assertEquals(collectionId,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNotNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNotNull(dropTestClass);
    assertEquals(collectionId,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNotNull(session.getCollectionNameById(collectionId));
    session.execute("drop class " + testClassName).close();

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNull(dropTestClass);
    assertEquals(-1,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    assertNull(dropTestClass);
    assertEquals(-1,
        session.getStorage().getCollectionIdByName(testClassName));
    assertNull(session.getCollectionNameById(collectionId));
  }

  @Test
  @Order(10)
  void customAttributes() {
    // TEST CUSTOM PROPERTY CREATION
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustom("stereotype", "icon");

    assertEquals(
        "icon",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY EXISTS EVEN AFTER REOPEN

    assertEquals(
        "icon",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY REMOVAL
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustom("stereotype", null);
    assertNull(
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY UPDATE
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustom("stereotype", "polygon");
    assertEquals(
        "polygon",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY UDPATED EVEN AFTER REOPEN

    assertEquals(
        "polygon",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY WITH =

    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustom("equal", "this = that");

    assertEquals(
        "this = that",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("equal"));

    // TEST CUSTOM PROPERTY WITH = AFTER REOPEN

    assertEquals(
        "this = that",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("equal"));
  }

  @Test
  @Order(11)
  void alterAttributes() {
    var company = session.getMetadata().getSchema().getClass("Company");
    var superClasses = company.getSuperClasses();
    assertEquals(1, superClasses.size());
    var superClass = superClasses.getFirst();

    assertNotNull(superClass);
    var found = false;
    for (var c : superClass.getSubclasses()) {
      if (c.equals(company)) {
        found = true;
        break;
      }
    }
    assertTrue(found);

    company.removeSuperClass(superClass);
    assertTrue(company.getSuperClasses().isEmpty());

    for (var c : superClass.getSubclasses()) {
      assertNotSame(company, c);
    }

    session
        .execute(
            "alter class " + company.getName() + " superclasses " + superClass.getName())
        .close();

    company = session.getMetadata().getSchema().getClass("Company");
    superClasses = company.getSuperClasses();
    assertEquals(1, superClasses.size());
    superClass = superClasses.getFirst();

    found = false;
    for (var c : superClass.getSubclasses()) {
      if (c.equals(company)) {
        found = true;
        break;
      }
    }
    assertTrue(found);
  }

  @Test
  @Order(12)
  void invalidCollectionWrongKeywords() {
    try {
      session.command("create class Antani the pen is on the table");
      fail();
    } catch (Exception e) {
      assertTrue(e instanceof CommandSQLParsingException);
    }
  }

  @Test
  @Order(13)
  void testRenameClass() {
    var oClass = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass("RenameClassTest");

    session.begin();
    session.newEntity("RenameClassTest");

    session.newEntity("RenameClassTest");

    session.commit();

    session.begin();
    var result = session.query("select from RenameClassTest");
    assertEquals(2, result.stream().count());
    session.commit();

    oClass.set(SchemaClass.ATTRIBUTES.NAME, "RenameClassTest2");

    session.begin();
    result = session.query("select from RenameClassTest2");
    assertEquals(2, result.stream().count());
    session.commit();
  }

  @Test
  @Order(14)
  void testRenameWithSameNameIsNop() {
    session.getMetadata().getSchema().getClass("V").setName("V");
  }

  @Test
  @Order(15)
  void testRenameWithExistentName() {
    try {
      session.getMetadata().getSchema().getClass("V").setName("OUser");
      fail();
    } catch (SchemaException
        | com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException ignore) {
    }
  }

  @Test
  @Order(16)
  void testDeletionOfDependentClass() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.getClass(EntityImpl.DEFAULT_CLASS_NAME);
    var classA = schema.createClass("TestDeletionOfDependentClassA", oClass);
    var classB = schema.createClass("TestDeletionOfDependentClassB", classA);
    schema.dropClass(classB.getName());
  }

  @Test
  @Order(17)
  void testCaseSensitivePropNames() {
    var className = "TestCaseSensitivePropNames";
    var propertyName = "propName";
    session.execute("create class " + className);
    session.execute(
        "create property "
            + className
            + "."
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " STRING");
    session.execute(
        "create property "
            + className
            + "."
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " STRING");

    session.execute(
        "create index "
            + className
            + "."
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " on "
            + className
            + "("
            + propertyName.toLowerCase(Locale.ENGLISH)
            + ") NOTUNIQUE");
    session.execute(
        "create index "
            + className
            + "."
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " on "
            + className
            + "("
            + propertyName.toUpperCase(Locale.ENGLISH)
            + ") NOTUNIQUE");

    session.begin();
    session.execute(
        "insert into "
            + className
            + " set "
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " = 'FOO', "
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " = 'foo'");
    session.execute(
        "insert into "
            + className
            + " set "
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " = 'BAR', "
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " = 'bar'");
    session.commit();

    session.begin();
    try (var rs =
        session.execute(
            "select from "
                + className
                + " where "
                + propertyName.toLowerCase(Locale.ENGLISH)
                + " = 'foo'")) {
      assertTrue(rs.hasNext());
      rs.next();
      assertFalse(rs.hasNext());
    }
    session.commit();

    session.begin();
    try (var rs =
        session.query(
            "select from "
                + className
                + " where "
                + propertyName.toLowerCase(Locale.ENGLISH)
                + " = 'FOO'")) {
      assertFalse(rs.hasNext());
    }
    session.commit();

    session.begin();
    try (var rs =
        session.execute(
            "select from "
                + className
                + " where "
                + propertyName.toUpperCase(Locale.ENGLISH)
                + " = 'FOO'")) {
      assertTrue(rs.hasNext());
      rs.next();
      assertFalse(rs.hasNext());
    }
    session.commit();

    session.begin();
    try (var rs =
        session.execute(
            "select from "
                + className
                + " where "
                + propertyName.toUpperCase(Locale.ENGLISH)
                + " = 'foo'")) {
      assertFalse(rs.hasNext());
    }
    session.commit();

    var schema = (SchemaInternal) session.getSchema();
    var clazz = schema.getClassInternal(className);
    var idx = clazz.getIndexesInternal();

    Set<String> indexes = new HashSet<>();
    for (var id : idx) {
      indexes.add(id.getName());
    }

    assertTrue(
        indexes.contains(className + "." + propertyName.toLowerCase(Locale.ENGLISH)));
    assertTrue(
        indexes.contains(className + "." + propertyName.toUpperCase(Locale.ENGLISH)));

    schema.dropClass(className);
  }

  private void swapCollections(DatabaseSessionEmbedded databaseDocumentTx, int i) {
    databaseDocumentTx
        .execute(
            "CREATE CLASS TestRenameCollectionNew extends TestRenameCollectionOriginal collections 2")
        .close();
    databaseDocumentTx.begin();
    databaseDocumentTx
        .execute("INSERT INTO TestRenameCollectionNew (iteration) VALUES(" + i + ")")
        .close();
    databaseDocumentTx.commit();

    databaseDocumentTx
        .execute(
            "ALTER CLASS TestRenameCollectionOriginal remove_collection TestRenameCollectionOriginal")
        .close();
    databaseDocumentTx
        .execute(
            "ALTER CLASS TestRenameCollectionNew remove_collection TestRenameCollectionNew")
        .close();
    databaseDocumentTx.execute("DROP CLASS TestRenameCollectionNew").close();
    databaseDocumentTx
        .execute(
            "ALTER CLASS TestRenameCollectionOriginal add_collection TestRenameCollectionNew")
        .close();
    databaseDocumentTx.execute("DROP COLLECTION TestRenameCollectionOriginal").close();
    databaseDocumentTx
        .command(
            "ALTER COLLECTION TestRenameCollectionNew name TestRenameCollectionOriginal");

    session.begin();
    var result =
        databaseDocumentTx.query(
            "select * from TestRenameCollectionOriginal").toList();
    assertEquals(1, result.size());

    var document = result.getFirst();
    assertEquals(i, document.<Object>getProperty("iteration"));
    session.commit();
  }
}
