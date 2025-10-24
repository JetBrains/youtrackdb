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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

@Test
public class SchemaTest extends BaseDBTest {

  @Test
  public void checkSchema() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    assert schema != null;
    assert schema.getClass("Profile") != null;
    assert schema.getClass("Profile").getProperty("nick").getType()
        == PropertyTypeInternal.STRING;
    assert schema.getClass("Profile").getProperty("name").getType()
        == PropertyTypeInternal.STRING;
    assert schema.getClass("Profile").getProperty("surname").getType()
        == PropertyTypeInternal.STRING;
    assert
        schema.getClass("Profile").getProperty("registeredOn").getType()
            == PropertyTypeInternal.DATETIME;
    assert
        schema.getClass("Profile").getProperty("lastAccessOn").getType()
            == PropertyTypeInternal.DATETIME;

    assert schema.getClass("Whiz") != null;
    assert schema.getClass("whiz").getProperty("account").getType()
        == PropertyTypeInternal.LINK;
    assert schema
        .getClass("whiz")
        .getProperty("account")
        .getLinkedClass()
        .getName()
        .equalsIgnoreCase("Account");
    assert
        schema.getClass("WHIZ").getProperty("date").getType() == PropertyTypeInternal.DATE;
    assert schema.getClass("WHIZ").getProperty("text").getType()
        == PropertyTypeInternal.STRING;
    assert schema.getClass("WHIZ").getProperty("text").isMandatory();
    assert schema.getClass("WHIZ").getProperty("text").getMin().equals("1");
    assert schema.getClass("WHIZ").getProperty("text").getMax().equals("140");
    assert schema.getClass("whiz").getProperty("replyTo").getType()
        == PropertyTypeInternal.LINK;
    assert schema
        .getClass("Whiz")
        .getProperty("replyTo")
        .getLinkedClass()
        .getName()
        .equalsIgnoreCase("Account");
  }

  @Test(dependsOnMethods = "checkSchema")
  public void checkInvalidNamesBefore30() {

    Schema schema = session.getMetadata().getSlowMutableSchema();

    schema.createClass("TestInvalidName,");
    Assert.assertNotNull(schema.getClass("TestInvalidName,"));
    schema.createClass("TestInvalidName;");
    Assert.assertNotNull(schema.getClass("TestInvalidName;"));
    schema.createClass("TestInvalid Name");
    Assert.assertNotNull(schema.getClass("TestInvalid Name"));
    schema.createClass("TestInvalid_Name");
    Assert.assertNotNull(schema.getClass("TestInvalid_Name"));
  }

  @Test(dependsOnMethods = "checkSchema")
  public void checkSchemaApi() {

    Schema schema = session.getMetadata().getSlowMutableSchema();

    try {
      Assert.assertNull(schema.getClass("Animal33"));
    } catch (SchemaException e) {
    }
  }

  @Test(dependsOnMethods = "checkSchemaApi")
  public void checkCollections() {

    for (var cls : session.getMetadata().getSlowMutableSchema().getClasses()) {
      assert cls.isAbstract()
          || session.getCollectionNameById(cls.getCollectionIds()[0]) != null;
    }
  }

  @Test
  public void checkTotalRecords() {

    Assert.assertTrue(session.getStorage().countRecords(session) > 0);
  }

  @Test(expectedExceptions = ValidationException.class)
  public void checkErrorOnUserNoPasswd() {
    session.begin();
    session.getMetadata().getSecurity().createUser("error", null, (String) null);
    session.commit();
  }

  @Test
  public void testMultiThreadSchemaCreation() throws InterruptedException {

    var thread =
        new Thread(
            new Runnable() {

              @Override
              public void run() {
                var doc = ((EntityImpl) session.newEntity("NewClass"));

                session.begin();
                session.commit();

                session.begin();
                var activeTx = session.getActiveTransaction();
                doc = activeTx.load(doc);
                doc.delete();
                session.commit();

                session.getMetadata().getSlowMutableSchema().dropClass("NewClass");
              }
            });

    thread.start();
    thread.join();
  }

  @Test
  public void createAndDropClassTestApi() {

    final var testClassName = "dropTestClass";
    final int collectionId;
    var dropTestClass = session.getMetadata().getSlowMutableSchema().createClass(testClassName);
    collectionId = dropTestClass.getCollectionIds()[0];
    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), collectionId);
    Assert.assertNotNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), collectionId);
    Assert.assertNotNull(session.getCollectionNameById(collectionId));
    session.getMetadata().getSlowMutableSchema().dropClass(testClassName);
    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), -1);
    Assert.assertNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), -1);
    Assert.assertNull(session.getCollectionNameById(collectionId));
  }

  @Test
  public void createAndDropClassTestCommand() {

    final var testClassName = "dropTestClass";
    final int collectionId;
    var dropTestClass = session.getMetadata().getSlowMutableSchema().createClass(testClassName);
    collectionId = dropTestClass.getCollectionIds()[0];
    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), collectionId);
    Assert.assertNotNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), collectionId);
    Assert.assertNotNull(session.getCollectionNameById(collectionId));
    session.execute("drop class " + testClassName).close();

    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), -1);
    Assert.assertNull(session.getCollectionNameById(collectionId));

    dropTestClass = session.getMetadata().getSlowMutableSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getCollectionIdByName(testClassName), -1);
    Assert.assertNull(session.getCollectionNameById(collectionId));
  }

  @Test
  public void customAttributes() {

    // TEST CUSTOM PROPERTY CREATION
    session
        .getMetadata()
        .getSlowMutableSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustomProperty("stereotype", "icon");

    Assert.assertEquals(
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustomProperty("stereotype"),
        "icon");

    // TEST CUSTOM PROPERTY EXISTS EVEN AFTER REOPEN

    Assert.assertEquals(
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustomProperty("stereotype"),
        "icon");

    // TEST CUSTOM PROPERTY REMOVAL
    session
        .getMetadata()
        .getSlowMutableSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustomProperty("stereotype", null);
    Assert.assertNull(
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustomProperty("stereotype"));

    // TEST CUSTOM PROPERTY UPDATE
    session
        .getMetadata()
        .getSlowMutableSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustomProperty("stereotype", "polygon");
    Assert.assertEquals(
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustomProperty("stereotype"),
        "polygon");

    // TEST CUSTOM PROPERTY UDPATED EVEN AFTER REOPEN

    Assert.assertEquals(
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustomProperty("stereotype"),
        "polygon");

    // TEST CUSTOM PROPERTY WITH =

    session
        .getMetadata()
        .getSlowMutableSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustomProperty("equal", "this = that");

    Assert.assertEquals(
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustomProperty("equal"),
        "this = that");

    // TEST CUSTOM PROPERTY WITH = AFTER REOPEN

    Assert.assertEquals(
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustomProperty("equal"),
        "this = that");
  }

  @Test
  public void alterAttributes() {
    var company = session.getMetadata().getSlowMutableSchema().getClass("Company");
    var superClasses = company.getParentClasses();
    Assert.assertEquals(superClasses.size(), 1);
    var superClass = superClasses.getFirst();

    Assert.assertNotNull(superClass);
    var found = false;
    for (var c : superClass.getChildClasses()) {
      if (c.equals(company)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);

    company.removeSuperClass(superClass);
    Assert.assertTrue(company.getParentClasses().isEmpty());

    for (var c : superClass.getChildClasses()) {
      Assert.assertNotSame(c, company);
    }

    session
        .execute("alter class " + company.getName() + " superclasses " + superClass.getName())
        .close();

    company = session.getMetadata().getSlowMutableSchema().getClass("Company");
    superClasses = company.getParentClasses();
    Assert.assertEquals(superClasses.size(), 1);
    superClass = superClasses.getFirst();

    found = false;
    for (var c : superClass.getChildClasses()) {
      if (c.equals(company)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);
  }

  @Test
  public void invalidCollectionWrongKeywords() {

    try {
      session.command("create class Antani the pen is on the table");
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(e instanceof CommandSQLParsingException);
    }
  }

  @Test
  public void testRenameClass() {
    graph.autoExecuteInTx(g -> g.createSchemaClass("RenameClassTest"));
    session.begin();
    session.newEntity("RenameClassTest");
    session.newEntity("RenameClassTest");
    session.commit();

    session.begin();
    var result = session.query("select from RenameClassTest");
    Assert.assertEquals(result.stream().count(), 2);
    session.commit();

    graph.autoExecuteInTx(
        g -> g.schemaClass("RenameClassTest").schemaClassName("RenameClassTest2"));

    session.begin();
    result = session.query("select from RenameClassTest2");
    Assert.assertEquals(result.stream().count(), 2);
    session.commit();
  }

  public void testRenameWithSameNameIsNop() {
    session.getMetadata().getSlowMutableSchema().getClass("V").setName("V");
  }

  public void testRenameWithExistentName() {
    try {
      session.getMetadata().getSlowMutableSchema().getClass("V").setName("OUser");
      Assert.fail();
    } catch (SchemaException | CommandExecutionException ignore) {
    }
  }

  @Test
  public void testDeletionOfDependentClass() {
    Schema schema = session.getMetadata().getSlowMutableSchema();
    var oClass = schema.getClass(EntityImpl.DEFAULT_CLASS_NAME);
    var classA = schema.createClass("TestDeletionOfDependentClassA", oClass);
    var classB = schema.createClass("TestDeletionOfDependentClassB", classA);
    schema.dropClass(classB.getName());
  }

  @Test
  public void testCaseSensitivePropNames() {
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

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var clazz = schema.getClass(className);
    var idx = clazz.getIndexes();

    Set<String> indexes = new HashSet<>();
    for (var id : idx) {
      indexes.add(id.getName());
    }

    Assert.assertTrue(
        indexes.contains(className + "." + propertyName.toLowerCase(Locale.ENGLISH)));
    Assert.assertTrue(
        indexes.contains(className + "." + propertyName.toUpperCase(Locale.ENGLISH)));

    graph.autoExecuteInTx(g -> g.schemaClass(className).drop());
  }

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
    Assert.assertEquals(result.size(), 1);

    var document = result.getFirst();
    Assert.assertEquals(document.<Object>getProperty("iteration"), i);
    session.commit();
  }
}
