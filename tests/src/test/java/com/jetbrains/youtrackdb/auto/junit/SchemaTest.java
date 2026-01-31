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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
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
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Tests for database schema operations including class creation, property management, and indexes.
 *
 * <p><b>Suite Dependency:</b> This test is part of {@link DatabaseTestSuite} and is one of the
 * first tests to run in the Schema group. It verifies and depends on the basic schema (Profile,
 * Whiz, Company classes) created by {@link BaseDBTest#createBasicTestSchema()}.</p>
 *
 * <p><b>Important:</b> This test creates and modifies schema classes that subsequent tests may
 * depend on. The test order within the suite is critical.</p>
 *
 * <p>Original test class: {@code com.jetbrains.youtrackdb.auto.SchemaTest}</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SchemaTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    SchemaTest instance = new SchemaTest();
    instance.beforeClass();
  }

  /**
   * Original test method: checkSchema Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:38
   */
  @Test
  public void test01_CheckSchema() {
    Schema schema = session.getMetadata().getSchema();

    Assert.assertNotNull(schema);
    Assert.assertNotNull(schema.getClass("Profile"));
    Assert.assertEquals(PropertyType.STRING,
        schema.getClass("Profile").getProperty("nick").getType());
    Assert.assertEquals(PropertyType.STRING,
        schema.getClass("Profile").getProperty("name").getType());
    Assert.assertEquals(PropertyType.STRING,
        schema.getClass("Profile").getProperty("surname").getType());
    Assert.assertEquals(PropertyType.DATETIME,
        schema.getClass("Profile").getProperty("registeredOn").getType());
    Assert.assertEquals(PropertyType.DATETIME,
        schema.getClass("Profile").getProperty("lastAccessOn").getType());

    Assert.assertNotNull(schema.getClass("Whiz"));
    Assert.assertEquals(PropertyType.LINK,
        schema.getClass("whiz").getProperty("account").getType());
    Assert.assertTrue(schema
        .getClass("whiz")
        .getProperty("account")
        .getLinkedClass()
        .getName()
        .equalsIgnoreCase("Account"));
    Assert.assertEquals(PropertyType.DATE,
        schema.getClass("WHIZ").getProperty("date").getType());
    Assert.assertEquals(PropertyType.STRING,
        schema.getClass("WHIZ").getProperty("text").getType());
    Assert.assertTrue(schema.getClass("WHIZ").getProperty("text").isMandatory());
    Assert.assertEquals("1", schema.getClass("WHIZ").getProperty("text").getMin());
    Assert.assertEquals("140", schema.getClass("WHIZ").getProperty("text").getMax());
    Assert.assertEquals(PropertyType.LINK,
        schema.getClass("whiz").getProperty("replyTo").getType());
    Assert.assertTrue(schema
        .getClass("Whiz")
        .getProperty("replyTo")
        .getLinkedClass()
        .getName()
        .equalsIgnoreCase("Account"));
  }

  /**
   * Original test method: checkInvalidNamesBefore30 Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:83 Depends on: checkSchema
   */
  @Test
  public void test02_CheckInvalidNamesBefore30() {
    Schema schema = session.getMetadata().getSchema();

    schema.createClass("TestInvalidName,");
    Assert.assertNotNull(schema.getClass("TestInvalidName,"));
    schema.createClass("TestInvalidName;");
    Assert.assertNotNull(schema.getClass("TestInvalidName;"));
    schema.createClass("TestInvalid Name");
    Assert.assertNotNull(schema.getClass("TestInvalid Name"));
    schema.createClass("TestInvalid_Name");
    Assert.assertNotNull(schema.getClass("TestInvalid_Name"));
  }

  /**
   * Original test method: checkSchemaApi Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:98 Depends on: checkSchema
   */
  @Test
  public void test03_CheckSchemaApi() {
    Schema schema = session.getMetadata().getSchema();

    try {
      Assert.assertNull(schema.getClass("Animal33"));
    } catch (SchemaException e) {
      // Expected
    }
  }

  /**
   * Original test method: checkCollections Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:109 Depends on:
   * checkSchemaApi
   */
  @Test
  public void test04_CheckCollections() {
    for (var cls : session.getMetadata().getSchema().getClasses()) {
      Assert.assertTrue(cls.isAbstract()
          || session.getCollectionNameById(cls.getCollectionIds()[0]) != null);
    }
  }

  /**
   * Original test method: checkTotalRecords Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:118
   */
  @Test
  public void test05_CheckTotalRecords() {
    Assert.assertTrue(session.getStorage().countRecords(session) > 0);
  }

  /**
   * Original test method: checkErrorOnUserNoPasswd Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:124 Note: Original used
   * expectedExceptions = ValidationException.class
   */
  @Test(expected = ValidationException.class)
  public void test06_CheckErrorOnUserNoPasswd() {
    session.begin();
    session.getMetadata().getSecurity().createUser("error", null, (String) null);
    session.commit();
  }

  /**
   * Original test method: testMultiThreadSchemaCreation Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:131
   */
  @Test
  public void test07_MultiThreadSchemaCreation() throws InterruptedException {
    var thread =
        new Thread(
            new Runnable() {

              @Override
              public void run() {
                session.activateOnCurrentThread();

                var doc = ((EntityImpl) session.newEntity("NewClass"));

                session.begin();
                session.commit();

                session.begin();
                var activeTx = session.getActiveTransaction();
                doc = activeTx.load(doc);
                doc.delete();
                session.commit();

                session.getMetadata().getSchema().dropClass("NewClass");
              }
            });

    thread.start();
    thread.join();
  }

  /**
   * Original test method: createAndDropClassTestApi Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:159
   */
  @Test
  public void test08_CreateAndDropClassTestApi() {
    final var testClassName = "dropTestClass";
    final int collectionId;
    var dropTestClass = session.getMetadata().getSchema().createClass(testClassName);
    collectionId = dropTestClass.getCollectionIds()[0];
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(collectionId, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNotNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(collectionId, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNotNull(session.getCollectionNameById(collectionId));
    session.getMetadata().getSchema().dropClass(testClassName);
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(-1, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(-1, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNull(session.getCollectionNameById(collectionId));
  }

  /**
   * Original test method: createAndDropClassTestCommand Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:187
   */
  @Test
  public void test09_CreateAndDropClassTestCommand() {
    final var testClassName = "dropTestClass";
    final int collectionId;
    var dropTestClass = session.getMetadata().getSchema().createClass(testClassName);
    collectionId = dropTestClass.getCollectionIds()[0];
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(collectionId, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNotNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(collectionId, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNotNull(session.getCollectionNameById(collectionId));
    session.execute("drop class " + testClassName).close();

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(-1, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(-1, session.getStorage().getCollectionIdByName(testClassName));
    Assert.assertNull(session.getCollectionNameById(collectionId));
  }

  /**
   * Original test method: customAttributes Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:216
   */
  @Test
  public void test10_CustomAttributes() {
    // TEST CUSTOM PROPERTY CREATION
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustom("stereotype", "icon");

    Assert.assertEquals(
        "icon",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY EXISTS EVEN AFTER REOPEN
    Assert.assertEquals(
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
    Assert.assertNull(
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
    Assert.assertEquals(
        "polygon",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY UPDATED EVEN AFTER REOPEN
    Assert.assertEquals(
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

    Assert.assertEquals(
        "this = that",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("equal"));

    // TEST CUSTOM PROPERTY WITH = AFTER REOPEN
    Assert.assertEquals(
        "this = that",
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("equal"));
  }

  /**
   * Original test method: alterAttributes Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:319
   */
  @Test
  public void test11_AlterAttributes() {
    var company = session.getMetadata().getSchema().getClass("Company");
    var superClasses = company.getSuperClasses();
    Assert.assertEquals(1, superClasses.size());
    var superClass = superClasses.getFirst();

    Assert.assertNotNull(superClass);
    var found = false;
    for (var c : superClass.getSubclasses()) {
      if (c.equals(company)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);

    company.removeSuperClass(superClass);
    Assert.assertTrue(company.getSuperClasses().isEmpty());

    for (var c : superClass.getSubclasses()) {
      Assert.assertNotSame(c, company);
    }

    session
        .execute("alter class " + company.getName() + " superclasses " + superClass.getName())
        .close();

    company = session.getMetadata().getSchema().getClass("Company");
    superClasses = company.getSuperClasses();
    Assert.assertEquals(1, superClasses.size());
    superClass = superClasses.getFirst();

    found = false;
    for (var c : superClass.getSubclasses()) {
      if (c.equals(company)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);
  }

  /**
   * Original test method: invalidCollectionWrongKeywords Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:362
   */
  @Test
  public void test12_InvalidCollectionWrongKeywords() {
    try {
      session.command("create class Antani the pen is on the table");
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(e instanceof CommandSQLParsingException);
    }
  }

  /**
   * Original test method: testRenameClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:373
   */
  @Test
  public void test13_RenameClass() {
    var oClass = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass("RenameClassTest");

    session.begin();
    var document = ((EntityImpl) session.newEntity("RenameClassTest"));

    document = ((EntityImpl) session.newEntity("RenameClassTest"));

    session.commit();

    session.begin();
    var result = session.query("select from RenameClassTest");
    Assert.assertEquals(2, result.stream().count());
    session.commit();

    oClass.set(SchemaClass.ATTRIBUTES.NAME, "RenameClassTest2");

    session.begin();
    result = session.query("select from RenameClassTest2");
    Assert.assertEquals(2, result.stream().count());
    session.commit();
  }

  /**
   * Original test method: testRenameWithSameNameIsNop Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:399 Note: This method had no
   *
   * @Test annotation but was included via class-level @Test
   */
  @Test
  public void test14_RenameWithSameNameIsNop() {
    session.getMetadata().getSchema().getClass("V").setName("V");
  }

  /**
   * Original test method: testRenameWithExistentName Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:403 Note: This method had no
   *
   * @Test annotation but was included via class-level @Test
   */
  @Test
  public void test15_RenameWithExistentName() {
    try {
      session.getMetadata().getSchema().getClass("V").setName("OUser");
      Assert.fail();
    } catch (SchemaException | CommandExecutionException ignore) {
    }
  }

  /**
   * Original test method: testDeletionOfDependentClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:411
   */
  @Test
  public void test16_DeletionOfDependentClass() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.getClass(EntityImpl.DEFAULT_CLASS_NAME);
    var classA = schema.createClass("TestDeletionOfDependentClassA", oClass);
    var classB = schema.createClass("TestDeletionOfDependentClassB", classA);
    schema.dropClass(classB.getName());
  }

  /**
   * Original test method: testCaseSensitivePropNames Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:420
   */
  @Test
  public void test17_CaseSensitivePropNames() {
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
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
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
      Assert.assertFalse(rs.hasNext());
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
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
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
      Assert.assertFalse(rs.hasNext());
    }
    session.commit();

    var schema = (SchemaInternal) session.getSchema();
    var clazz = schema.getClassInternal(className);
    var idx = clazz.getIndexesInternal();

    Set<String> indexes = new HashSet<>();
    for (var id : idx) {
      indexes.add(id.getName());
    }

    Assert.assertTrue(
        indexes.contains(className + "." + propertyName.toLowerCase(Locale.ENGLISH)));
    Assert.assertTrue(
        indexes.contains(className + "." + propertyName.toUpperCase(Locale.ENGLISH)));

    schema.dropClass(className);
  }

  /**
   * Original helper method: swapCollections Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/SchemaTest.java:547 Note: This is a helper
   * method, not a test
   */
  private void swapCollections(DatabaseSessionInternal databaseDocumentTx, int i) {
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
        .execute("ALTER CLASS TestRenameCollectionNew remove_collection TestRenameCollectionNew")
        .close();
    databaseDocumentTx.execute("DROP CLASS TestRenameCollectionNew").close();
    databaseDocumentTx
        .execute("ALTER CLASS TestRenameCollectionOriginal add_collection TestRenameCollectionNew")
        .close();
    databaseDocumentTx.execute("DROP COLLECTION TestRenameCollectionOriginal").close();
    databaseDocumentTx
        .command(
            "ALTER COLLECTION TestRenameCollectionNew name TestRenameCollectionOriginal");

    session.begin();
    var result =
        databaseDocumentTx.query(
            "select * from TestRenameCollectionOriginal").toList();
    Assert.assertEquals(1, result.size());

    var document = result.getFirst();
    Assert.assertEquals(i, document.<Object>getProperty("iteration"));
    session.commit();
  }
}
