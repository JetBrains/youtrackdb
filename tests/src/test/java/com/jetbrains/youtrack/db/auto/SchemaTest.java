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

import com.jetbrains.youtrack.db.api.exception.ClusterDoesNotExistException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityShared;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.CommandSQL;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SchemaTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SchemaTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void checkSchema() {
    Schema schema = session.getMetadata().getSchema();

    assert schema != null;
    assert schema.getClass("Profile") != null;
    assert schema.getClass("Profile").getProperty("nick").getType()
        == PropertyType.STRING;
    assert schema.getClass("Profile").getProperty("name").getType()
        == PropertyType.STRING;
    assert schema.getClass("Profile").getProperty("surname").getType()
        == PropertyType.STRING;
    assert
        schema.getClass("Profile").getProperty("registeredOn").getType()
            == PropertyType.DATETIME;
    assert
        schema.getClass("Profile").getProperty("lastAccessOn").getType()
            == PropertyType.DATETIME;

    assert schema.getClass("Whiz") != null;
    assert schema.getClass("whiz").getProperty("account").getType()
        == PropertyType.LINK;
    assert schema
        .getClass("whiz")
        .getProperty("account")
        .getLinkedClass()
        .getName()
        .equalsIgnoreCase("Account");
    assert
        schema.getClass("WHIZ").getProperty("date").getType() == PropertyType.DATE;
    assert schema.getClass("WHIZ").getProperty("text").getType()
        == PropertyType.STRING;
    assert schema.getClass("WHIZ").getProperty("text").isMandatory();
    assert schema.getClass("WHIZ").getProperty("text").getMin().equals("1");
    assert schema.getClass("WHIZ").getProperty("text").getMax().equals("140");
    assert schema.getClass("whiz").getProperty("replyTo").getType()
        == PropertyType.LINK;
    assert schema
        .getClass("Whiz")
        .getProperty("replyTo")
        .getLinkedClass()
        .getName()
        .equalsIgnoreCase("Account");
  }

  @Test(dependsOnMethods = "checkSchema")
  public void checkInvalidNamesBefore30() {

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

  @Test(dependsOnMethods = "checkSchema")
  public void checkSchemaApi() {

    Schema schema = session.getMetadata().getSchema();

    try {
      Assert.assertNull(schema.getClass("Animal33"));
    } catch (SchemaException e) {
    }
  }

  @Test(dependsOnMethods = "checkSchemaApi")
  public void checkClusters() {

    for (var cls : session.getMetadata().getSchema().getClasses()) {
      assert cls.isAbstract()
          || session.getClusterNameById(cls.getClusterIds()[0]) != null;
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
                doc = session.bindToSession(doc);
                doc.delete();
                session.commit();

                session.getMetadata().getSchema().dropClass("NewClass");
              }
            });

    thread.start();
    thread.join();
  }

  @Test
  public void createAndDropClassTestApi() {

    final var testClassName = "dropTestClass";
    final int clusterId;
    var dropTestClass = session.getMetadata().getSchema().createClass(testClassName);
    clusterId = dropTestClass.getClusterIds()[0];
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(session.getClusterNameById(clusterId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(session.getClusterNameById(clusterId));
    session.getMetadata().getSchema().dropClass(testClassName);
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(session.getClusterNameById(clusterId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(session.getClusterNameById(clusterId));
  }

  @Test
  public void createAndDropClassTestCommand() {

    final var testClassName = "dropTestClass";
    final int clusterId;
    var dropTestClass = session.getMetadata().getSchema().createClass(testClassName);
    clusterId = dropTestClass.getClusterIds()[0];
    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(session.getClusterNameById(clusterId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(session.getClusterNameById(clusterId));
    session.command("drop class " + testClassName).close();

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(session.getClusterNameById(clusterId));

    dropTestClass = session.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(session.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(session.getClusterNameById(clusterId));
  }

  @Test
  public void customAttributes() {

    // TEST CUSTOM PROPERTY CREATION
    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustom("stereotype", "icon");

    Assert.assertEquals(
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"),
        "icon");

    // TEST CUSTOM PROPERTY EXISTS EVEN AFTER REOPEN

    Assert.assertEquals(
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"),
        "icon");

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
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"),
        "polygon");

    // TEST CUSTOM PROPERTY UDPATED EVEN AFTER REOPEN

    Assert.assertEquals(
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("stereotype"),
        "polygon");

    // TEST CUSTOM PROPERTY WITH =

    session
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty("nick")
        .setCustom("equal", "this = that");

    Assert.assertEquals(
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("equal"),
        "this = that");

    // TEST CUSTOM PROPERTY WITH = AFTER REOPEN

    Assert.assertEquals(
        session
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty("nick")
            .getCustom("equal"),
        "this = that");
  }

  @Test
  public void alterAttributes() {
    var company = session.getMetadata().getSchema().getClass("Company");
    var superClasses = company.getSuperClasses();
    Assert.assertEquals(superClasses.size(), 1);
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
        .command("alter class " + company.getName() + " superclasses " + superClass.getName())
        .close();

    company = session.getMetadata().getSchema().getClass("Company");
    superClasses = company.getSuperClasses();
    Assert.assertEquals(superClasses.size(), 1);
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

  @Test
  public void invalidClusterWrongClusterId() {

    try {
      session.command(new CommandSQL("create class Antani cluster 212121")).execute(session);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(e instanceof ClusterDoesNotExistException);
    }
  }

  @Test
  public void invalidClusterWrongClusterName() {
    try {
      session.command(new CommandSQL("create class Antani cluster blaaa")).execute(session);
      Assert.fail();

    } catch (Exception e) {
      Assert.assertTrue(e instanceof CommandSQLParsingException);
    }
  }

  @Test
  public void invalidClusterWrongKeywords() {

    try {
      session.command(new CommandSQL("create class Antani the pen is on the table"))
          .execute(session);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(e instanceof CommandSQLParsingException);
    }
  }

  @Test
  public void testRenameClass() {

    var oClass = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass("RenameClassTest");

    session.begin();
    var document = ((EntityImpl) session.newEntity("RenameClassTest"));

    document = ((EntityImpl) session.newEntity("RenameClassTest"));

    session.commit();

    session.begin();
    var result = session.query("select from RenameClassTest");
    Assert.assertEquals(result.stream().count(), 2);
    session.commit();

    oClass.set(SchemaClass.ATTRIBUTES.NAME, "RenameClassTest2");

    session.begin();
    result = session.query("select from RenameClassTest2");
    Assert.assertEquals(result.stream().count(), 2);
    session.commit();
  }

  public void testMinimumClustersAndClusterSelection() {
    session.command(new CommandSQL("alter database minimum_clusters 3")).execute(session);
    try {
      session.command("create class multipleclusters").close();

      Assert.assertTrue(session.existsCluster("multipleclusters"));

      for (var i = 1; i < 3; ++i) {
        Assert.assertTrue(session.existsCluster("multipleclusters_" + i));
      }

      for (var i = 0; i < 6; ++i) {
        session.begin();
        EntityImpl entity = ((EntityImpl) session.newEntity("multipleclusters"));
        entity.setProperty("num", i);

        session.commit();
      }

      // CHECK THERE ARE 2 RECORDS IN EACH CLUSTER (ROUND-ROBIN STRATEGY)
      Assert.assertEquals(
          session.countClusterElements(session.getClusterIdByName("multipleclusters")), 2);
      for (var i = 1; i < 3; ++i) {
        Assert.assertEquals(
            session.countClusterElements(session.getClusterIdByName("multipleclusters_" + i)), 2);
      }

      session.begin();
      // DELETE ALL THE RECORDS
      var deleted =
          session.command("delete from cluster:multipleclusters_2").stream().
              findFirst().orElseThrow().<Long>getProperty("count");
      session.commit();
      Assert.assertEquals(deleted, 2);

      // CHANGE CLASS STRATEGY to BALANCED
      session
          .command("alter class multipleclusters cluster_selection balanced").close();

      for (var i = 0; i < 2; ++i) {
        session.begin();
        EntityImpl entity = ((EntityImpl) session.newEntity("multipleclusters"));
        entity.setProperty("num", i);

        session.commit();
      }

      Assert.assertEquals(
          session.countClusterElements(session.getClusterIdByName("multipleclusters_2")), 2);

    } finally {
      // RESTORE DEFAULT
      session.command("alter database minimum_clusters 0").close();
    }
  }

  public void testExchangeCluster() {
    session.command("CREATE CLASS TestRenameClusterOriginal clusters 2").close();
    swapClusters(session, 1);
    swapClusters(session, 2);
    swapClusters(session, 3);
  }

  public void testRenameWithSameNameIsNop() {
    session.getMetadata().getSchema().getClass("V").setName("V");
  }

  public void testRenameWithExistentName() {
    try {
      session.getMetadata().getSchema().getClass("V").setName("OUser");
      Assert.fail();
    } catch (SchemaException | CommandExecutionException ignore) {
    }
  }

  @Test
  public void testDeletionOfDependentClass() {
    Schema schema = session.getMetadata().getSchema();
    var oRestricted = schema.getClass(SecurityShared.RESTRICTED_CLASSNAME);
    var classA = schema.createClass("TestDeletionOfDependentClassA", oRestricted);
    var classB = schema.createClass("TestDeletionOfDependentClassB", classA);
    schema.dropClass(classB.getName());
  }

  @Test
  public void testCaseSensitivePropNames() {
    var className = "TestCaseSensitivePropNames";
    var propertyName = "propName";
    session.command("create class " + className);
    session.command(
        "create property "
            + className
            + "."
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " STRING");
    session.command(
        "create property "
            + className
            + "."
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " STRING");

    session.command(
        "create index "
            + className
            + "."
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " on "
            + className
            + "("
            + propertyName.toLowerCase(Locale.ENGLISH)
            + ") NOTUNIQUE");
    session.command(
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
    session.command(
        "insert into "
            + className
            + " set "
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " = 'FOO', "
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " = 'foo'");
    session.command(
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
        session.command(
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

    try (var rs =
        session.command(
            "select from "
                + className
                + " where "
                + propertyName.toLowerCase(Locale.ENGLISH)
                + " = 'FOO'")) {
      Assert.assertFalse(rs.hasNext());
    }

    session.begin();
    try (var rs =
        session.command(
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
        session.command(
            "select from "
                + className
                + " where "
                + propertyName.toUpperCase(Locale.ENGLISH)
                + " = 'foo'")) {
      Assert.assertFalse(rs.hasNext());
    }
    session.commit();

    var schema = (SchemaInternal) session.getSchema();
    if (!remoteDB) {
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
    }

    schema.dropClass(className);
  }

  private void swapClusters(DatabaseSessionInternal databaseDocumentTx, int i) {
    databaseDocumentTx
        .command("CREATE CLASS TestRenameClusterNew extends TestRenameClusterOriginal clusters 2")
        .close();
    databaseDocumentTx.begin();
    databaseDocumentTx
        .command("INSERT INTO TestRenameClusterNew (iteration) VALUES(" + i + ")")
        .close();
    databaseDocumentTx.commit();

    databaseDocumentTx
        .command("ALTER CLASS TestRenameClusterOriginal remove_cluster TestRenameClusterOriginal")
        .close();
    databaseDocumentTx
        .command("ALTER CLASS TestRenameClusterNew remove_cluster TestRenameClusterNew")
        .close();
    databaseDocumentTx.command("DROP CLASS TestRenameClusterNew").close();
    databaseDocumentTx
        .command("ALTER CLASS TestRenameClusterOriginal add_cluster TestRenameClusterNew")
        .close();
    databaseDocumentTx.command("DROP CLUSTER TestRenameClusterOriginal").close();
    databaseDocumentTx
        .command(
            new CommandSQL("ALTER CLUSTER TestRenameClusterNew name TestRenameClusterOriginal"))
        .execute(session);

    session.begin();
    List<EntityImpl> result =
        databaseDocumentTx.query(
            new SQLSynchQuery<EntityImpl>("select * from TestRenameClusterOriginal"));
    Assert.assertEquals(result.size(), 1);

    var document = result.getFirst();
    Assert.assertEquals(document.<Object>getProperty("iteration"), i);
    session.commit();
  }
}
