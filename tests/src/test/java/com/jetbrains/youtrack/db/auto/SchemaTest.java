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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.ClusterDoesNotExistException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrack.db.internal.core.index.Index;
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

  @Override
  protected YouTrackDBConfig createConfig(YouTrackDBConfigBuilderImpl builder) {
    builder.addGlobalConfigurationParameter(GlobalConfiguration.NON_TX_READS_WARNING_MODE,
        "EXCEPTION");
    return builder.build();
  }

  @Test
  public void checkSchema() {
    Schema schema = database.getMetadata().getSchema();

    assert schema != null;
    assert schema.getClass("Profile") != null;
    assert
        schema.getClass("Profile").getProperty(database, "nick").getType() == PropertyType.STRING;
    assert
        schema.getClass("Profile").getProperty(database, "name").getType() == PropertyType.STRING;
    assert schema.getClass("Profile").getProperty(database, "surname").getType()
        == PropertyType.STRING;
    assert
        schema.getClass("Profile").getProperty(database, "registeredOn").getType()
            == PropertyType.DATETIME;
    assert
        schema.getClass("Profile").getProperty(database, "lastAccessOn").getType()
            == PropertyType.DATETIME;

    assert schema.getClass("Whiz") != null;
    assert schema.getClass("whiz").getProperty(database, "account").getType() == PropertyType.LINK;
    assert schema
        .getClass("whiz")
        .getProperty(database, "account")
        .getLinkedClass(database)
        .getName()
        .equalsIgnoreCase("Account");
    assert schema.getClass("WHIZ").getProperty(database, "date").getType() == PropertyType.DATE;
    assert schema.getClass("WHIZ").getProperty(database, "text").getType() == PropertyType.STRING;
    assert schema.getClass("WHIZ").getProperty(database, "text").isMandatory();
    assert schema.getClass("WHIZ").getProperty(database, "text").getMin().equals("1");
    assert schema.getClass("WHIZ").getProperty(database, "text").getMax().equals("140");
    assert schema.getClass("whiz").getProperty(database, "replyTo").getType() == PropertyType.LINK;
    assert schema
        .getClass("Whiz")
        .getProperty(database, "replyTo")
        .getLinkedClass(database)
        .getName()
        .equalsIgnoreCase("Account");
  }

  @Test(dependsOnMethods = "checkSchema")
  public void checkInvalidNamesBefore30() {

    Schema schema = database.getMetadata().getSchema();

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

    Schema schema = database.getMetadata().getSchema();

    try {
      Assert.assertNull(schema.getClass("Animal33"));
    } catch (SchemaException e) {
    }
  }

  @Test(dependsOnMethods = "checkSchemaApi")
  public void checkClusters() {

    for (SchemaClass cls : database.getMetadata().getSchema().getClasses(database)) {
      assert cls.isAbstract() || database.getClusterNameById(cls.getClusterIds()[0]) != null;
    }
  }

  @Test
  public void checkTotalRecords() {

    Assert.assertTrue(database.getStorage().countRecords(database) > 0);
  }

  @Test(expectedExceptions = ValidationException.class)
  public void checkErrorOnUserNoPasswd() {
    database.begin();
    database.getMetadata().getSecurity().createUser("error", null, (String) null);
    database.commit();
  }

  @Test
  public void testMultiThreadSchemaCreation() throws InterruptedException {

    Thread thread =
        new Thread(
            new Runnable() {

              @Override
              public void run() {
                DatabaseRecordThreadLocal.instance().set(database);
                EntityImpl doc = new EntityImpl("NewClass");

                database.begin();
                database.save(doc);
                database.commit();

                database.begin();
                doc = database.bindToSession(doc);
                doc.delete();
                database.commit();

                database.getMetadata().getSchema().dropClass("NewClass");
              }
            });

    thread.start();
    thread.join();
  }

  @Test
  public void createAndDropClassTestApi() {

    final String testClassName = "dropTestClass";
    final int clusterId;
    SchemaClass dropTestClass = database.getMetadata().getSchema().createClass(testClassName);
    clusterId = dropTestClass.getClusterIds()[0];
    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(database.getClusterNameById(clusterId));

    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(database.getClusterNameById(clusterId));
    database.getMetadata().getSchema().dropClass(testClassName);
    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(database.getClusterNameById(clusterId));

    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(database.getClusterNameById(clusterId));
  }

  @Test
  public void createAndDropClassTestCommand() {

    final String testClassName = "dropTestClass";
    final int clusterId;
    SchemaClass dropTestClass = database.getMetadata().getSchema().createClass(testClassName);
    clusterId = dropTestClass.getClusterIds()[0];
    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(database.getClusterNameById(clusterId));

    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNotNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), clusterId);
    Assert.assertNotNull(database.getClusterNameById(clusterId));
    database.command("drop class " + testClassName).close();

    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(database.getClusterNameById(clusterId));

    dropTestClass = database.getMetadata().getSchema().getClass(testClassName);
    Assert.assertNull(dropTestClass);
    Assert.assertEquals(database.getStorage().getClusterIdByName(testClassName), -1);
    Assert.assertNull(database.getClusterNameById(clusterId));
  }

  @Test
  public void customAttributes() {

    // TEST CUSTOM PROPERTY CREATION
    database
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty(database, "nick")
        .setCustom(database, "stereotype", "icon");

    Assert.assertEquals(
        database
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty(database, "nick")
            .getCustom("stereotype"),
        "icon");

    // TEST CUSTOM PROPERTY EXISTS EVEN AFTER REOPEN

    Assert.assertEquals(
        database
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty(database, "nick")
            .getCustom("stereotype"),
        "icon");

    // TEST CUSTOM PROPERTY REMOVAL
    database
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty(database, "nick")
        .setCustom(database, "stereotype", null);
    Assert.assertNull(
        database
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty(database, "nick")
            .getCustom("stereotype"));

    // TEST CUSTOM PROPERTY UPDATE
    database
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty(database, "nick")
        .setCustom(database, "stereotype", "polygon");
    Assert.assertEquals(
        database
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty(database, "nick")
            .getCustom("stereotype"),
        "polygon");

    // TEST CUSTOM PROPERTY UDPATED EVEN AFTER REOPEN

    Assert.assertEquals(
        database
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty(database, "nick")
            .getCustom("stereotype"),
        "polygon");

    // TEST CUSTOM PROPERTY WITH =

    database
        .getMetadata()
        .getSchema()
        .getClass("Profile")
        .getProperty(database, "nick")
        .setCustom(database, "equal", "this = that");

    Assert.assertEquals(
        database
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty(database, "nick")
            .getCustom("equal"),
        "this = that");

    // TEST CUSTOM PROPERTY WITH = AFTER REOPEN

    Assert.assertEquals(
        database
            .getMetadata()
            .getSchema()
            .getClass("Profile")
            .getProperty(database, "nick")
            .getCustom("equal"),
        "this = that");
  }

  @Test
  public void alterAttributes() {

    SchemaClass company = database.getMetadata().getSchema().getClass("Company");
    SchemaClass superClass = company.getSuperClass(database);

    Assert.assertNotNull(superClass);
    boolean found = false;
    for (SchemaClass c : superClass.getSubclasses(database)) {
      if (c.equals(company)) {
        found = true;
        break;
      }
    }
    Assert.assertTrue(found);

    company.setSuperClass(database, null);
    Assert.assertNull(company.getSuperClass(database));
    for (SchemaClass c : superClass.getSubclasses(database)) {
      // this is weird, we want it to be the same,
      // so changes on one class will be reflected all over the app
      // todo @Andriy, do you know why we have this check?
//      Assert.assertNotSame(c, company);
    }

    // todo reload entire metadata?
    // most likely we need to set all classes dirty after this call, but this will be super slow
    // maybe we need to infer somehow that class will be altered and only affect this class
    database
        .command("alter class " + company.getName() + " superclass " + superClass.getName())
        .close();

    company = database.getMetadata().getSchema().getClass("Company");
    superClass = company.getSuperClass(database);

    Assert.assertNotNull(company.getSuperClass(database));
    found = false;
    for (SchemaClass c : superClass.getSubclasses(database)) {
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
      database.command(new CommandSQL("create class Antani cluster 212121")).execute(database);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(e instanceof ClusterDoesNotExistException);
    }
  }

  @Test
  public void invalidClusterWrongClusterName() {
    try {
      database.command(new CommandSQL("create class Antani cluster blaaa")).execute(database);
      Assert.fail();

    } catch (Exception e) {
      Assert.assertTrue(e instanceof CommandSQLParsingException);
    }
  }

  @Test
  public void invalidClusterWrongKeywords() {

    try {
      database.command(new CommandSQL("create class Antani the pen is on the table"))
          .execute(database);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(e instanceof CommandSQLParsingException);
    }
  }

  @Test
  public void testRenameClass() {

    var oClass = (SchemaClassInternal) database.getMetadata().getSchema()
        .createClass("RenameClassTest");

    database.begin();
    EntityImpl document = new EntityImpl("RenameClassTest");
    document.save();

    document = new EntityImpl("RenameClassTest");

    document.setClassName("RenameClassTest");
    document.save();
    database.commit();

    database.begin();
    ResultSet result = database.query("select from RenameClassTest");
    Assert.assertEquals(result.stream().count(), 2);
    database.commit();

    oClass.set(database, SchemaClass.ATTRIBUTES.NAME, "RenameClassTest2");

    database.begin();
    result = database.query("select from RenameClassTest2");
    Assert.assertEquals(result.stream().count(), 2);
    database.commit();
  }

  public void testMinimumClustersAndClusterSelection() {
    database.command(new CommandSQL("alter database minimum_clusters 3")).execute(database);
    try {
      database.command("create class multipleclusters").close();

      Assert.assertTrue(database.existsCluster("multipleclusters"));

      for (int i = 1; i < 3; ++i) {
        Assert.assertTrue(database.existsCluster("multipleclusters_" + i));
      }

      for (int i = 0; i < 6; ++i) {
        database.begin();
        new EntityImpl("multipleclusters").field("num", i).save();
        database.commit();
      }

      // CHECK THERE ARE 2 RECORDS IN EACH CLUSTER (ROUND-ROBIN STRATEGY)
      Assert.assertEquals(
          database.countClusterElements(database.getClusterIdByName("multipleclusters")), 2);
      for (int i = 1; i < 3; ++i) {
        Assert.assertEquals(
            database.countClusterElements(database.getClusterIdByName("multipleclusters_" + i)), 2);
      }

      database.begin();
      // DELETE ALL THE RECORDS
      var deleted =
          database.command("delete from cluster:multipleclusters_2").stream().
              findFirst().orElseThrow().<Long>getProperty("count");
      database.commit();
      Assert.assertEquals(deleted, 2);

      // CHANGE CLASS STRATEGY to BALANCED
      database
          .command("alter class multipleclusters cluster_selection balanced").close();

      for (int i = 0; i < 2; ++i) {
        database.begin();
        new EntityImpl("multipleclusters").field("num", i).save();
        database.commit();
      }

      Assert.assertEquals(
          database.countClusterElements(database.getClusterIdByName("multipleclusters_2")), 2);

    } finally {
      // RESTORE DEFAULT
      database.command("alter database minimum_clusters 0").close();
    }
  }

  public void testExchangeCluster() {
    database.command("CREATE CLASS TestRenameClusterOriginal clusters 2").close();
    swapClusters(database, 1);
    swapClusters(database, 2);
    swapClusters(database, 3);
  }

  public void testRenameWithSameNameIsNop() {
    database.getMetadata().getSchema().getClass("V").setName(database, "V");
  }

  public void testRenameWithExistentName() {
    try {
      database.getMetadata().getSchema().getClass("V").setName(database, "OUser");
      Assert.fail();
    } catch (SchemaException e) {
    } catch (CommandExecutionException e) {
    }
  }

  public void testShortNameAlreadyExists() {
    try {
      database.getMetadata().getSchema().getClass("V").setShortName(database, "OUser");
      Assert.fail();
    } catch (IllegalArgumentException e) {
    } catch (CommandExecutionException e) {
    }
  }

  @Test
  public void testDeletionOfDependentClass() {
    Schema schema = database.getMetadata().getSchema();
    SchemaClass oRestricted = schema.getClass(SecurityShared.RESTRICTED_CLASSNAME);
    SchemaClass classA = schema.createClass("TestDeletionOfDependentClassA", oRestricted);
    SchemaClass classB = schema.createClass("TestDeletionOfDependentClassB", classA);
    schema.dropClass(classB.getName());
  }

  @Test
  public void testCaseSensitivePropNames() {
    String className = "TestCaseSensitivePropNames";
    String propertyName = "propName";
    database.command("create class " + className);
    database.command(
        "create property "
            + className
            + "."
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " STRING");
    database.command(
        "create property "
            + className
            + "."
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " STRING");

    database.command(
        "create index "
            + className
            + "."
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " on "
            + className
            + "("
            + propertyName.toLowerCase(Locale.ENGLISH)
            + ") NOTUNIQUE");
    database.command(
        "create index "
            + className
            + "."
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " on "
            + className
            + "("
            + propertyName.toUpperCase(Locale.ENGLISH)
            + ") NOTUNIQUE");

    database.begin();
    database.command(
        "insert into "
            + className
            + " set "
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " = 'FOO', "
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " = 'foo'");
    database.command(
        "insert into "
            + className
            + " set "
            + propertyName.toUpperCase(Locale.ENGLISH)
            + " = 'BAR', "
            + propertyName.toLowerCase(Locale.ENGLISH)
            + " = 'bar'");
    database.commit();

    database.begin();
    try (ResultSet rs =
        database.command(
            "select from "
                + className
                + " where "
                + propertyName.toLowerCase(Locale.ENGLISH)
                + " = 'foo'")) {
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
    }
    database.commit();

    try (ResultSet rs =
        database.command(
            "select from "
                + className
                + " where "
                + propertyName.toLowerCase(Locale.ENGLISH)
                + " = 'FOO'")) {
      Assert.assertFalse(rs.hasNext());
    }

    database.begin();
    try (ResultSet rs =
        database.command(
            "select from "
                + className
                + " where "
                + propertyName.toUpperCase(Locale.ENGLISH)
                + " = 'FOO'")) {
      Assert.assertTrue(rs.hasNext());
      rs.next();
      Assert.assertFalse(rs.hasNext());
    }
    database.commit();

    database.begin();
    try (ResultSet rs =
        database.command(
            "select from "
                + className
                + " where "
                + propertyName.toUpperCase(Locale.ENGLISH)
                + " = 'foo'")) {
      Assert.assertFalse(rs.hasNext());
    }
    database.commit();

    var schema = (SchemaInternal) database.getSchema();
    var clazz = schema.getClassInternal(className);
    var idx = clazz.getIndexesInternal(database);

    Set<String> indexes = new HashSet<>();
    for (Index id : idx) {
      indexes.add(id.getName());
    }

    Assert.assertTrue(indexes.contains(className + "." + propertyName.toLowerCase(Locale.ENGLISH)));
    Assert.assertTrue(indexes.contains(className + "." + propertyName.toUpperCase(Locale.ENGLISH)));
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
        .execute(database);

    database.begin();
    List<EntityImpl> result =
        databaseDocumentTx.query(
            new SQLSynchQuery<EntityImpl>("select * from TestRenameClusterOriginal"));
    Assert.assertEquals(result.size(), 1);

    EntityImpl document = result.get(0);
    Assert.assertEquals(document.<Object>field("iteration"), i);
    database.commit();
  }
}
