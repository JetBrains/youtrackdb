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

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class CRUDInheritanceTest extends BaseDBTest {

  @Parameters(value = "remote")
  public CRUDInheritanceTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    createCompanyClass();
  }

  @Test
  public void create() {
    database.command("delete from Company").close();

    generateCompanyData();
  }

  @Test(dependsOnMethods = "create")
  public void testCreate() {
    Assert.assertEquals(database.countClass("Company"), TOT_COMPANY_RECORDS);
  }

  @Test(dependsOnMethods = "testCreate")
  public void queryByBaseType() {
    final List<EntityImpl> result = executeQuery("select from Company where name.length() > 0");

    Assert.assertFalse(result.isEmpty());
    Assert.assertEquals(result.size(), TOT_COMPANY_RECORDS);

    int companyRecords = 0;
    EntityImpl account;
    for (EntityImpl entries : result) {
      account = entries;

      if ("Company".equals(account.getClassName())) {
        companyRecords++;
      }

      Assert.assertNotSame(account.<String>getProperty("name").length(), 0);
    }

    Assert.assertEquals(companyRecords, TOT_COMPANY_RECORDS);
  }

  @Test(dependsOnMethods = "queryByBaseType")
  public void queryPerSuperType() {
    final List<EntityImpl> result = executeQuery("select * from Company where name.length() > 0");

    Assert.assertEquals(result.size(), TOT_COMPANY_RECORDS);

    Entity account;
    for (EntityImpl entries : result) {
      account = entries;
      Assert.assertNotSame(account.<String>getProperty("name").length(), 0);
    }
  }

  @Test(dependsOnMethods = {"queryPerSuperType", "testCreate"})
  public void deleteFirst() {
    // DELETE ALL THE RECORD IN THE CLUSTER
    database.begin();
    var companyClusterIterator = database.browseClass("Company");
    for (Entity obj : companyClusterIterator) {
      if (obj.<Integer>getProperty("id") == 1) {
        database.delete(obj);
        break;
      }
    }
    database.commit();

    Assert.assertEquals(database.countClass("Company"), TOT_COMPANY_RECORDS - 1);
  }

  @Test(dependsOnMethods = "deleteFirst")
  public void testSuperclassInheritanceCreation() {
    database.close();
    database = createSessionInstance();

    createInheritanceTestClass();

    SchemaClass abstractClass =
        database.getMetadata().getSchema().getClass("InheritanceTestAbstractClass");
    SchemaClass baseClass = database.getMetadata().getSchema().getClass("InheritanceTestBaseClass");
    SchemaClass testClass = database.getMetadata().getSchema().getClass("InheritanceTestClass");

    Assert.assertTrue(baseClass.getSuperClasses(database).contains(abstractClass));
    Assert.assertTrue(testClass.getSuperClasses(database).contains(baseClass));
  }

  @Test
  public void testIdFieldInheritanceFirstSubClass() {
    createInheritanceTestClass();

    Entity a = database.newInstance("InheritanceTestBaseClass");
    Entity b = database.newInstance("InheritanceTestClass");

    database.begin();
    database.save(a);
    database.save(b);
    database.commit();

    final List<EntityImpl> result1 = executeQuery("select from InheritanceTestBaseClass");
    Assert.assertEquals(2, result1.size());
  }

  @Test
  public void testKeywordClass() {
    SchemaClass klass = database.getMetadata().getSchema().createClass("Not");

    SchemaClass klass1 = database.getMetadata().getSchema().createClass("Extends_Not", klass);
    Assert.assertEquals(1, klass1.getSuperClasses(database).size(), 1);
    Assert.assertEquals("Not", klass1.getSuperClasses(database).get(0).getName());
  }

  @Test
  public void testSchemaGeneration() {
    var schema = database.getMetadata().getSchema();
    SchemaClass testSchemaClass = schema.createClass("JavaTestSchemaGeneration");
    SchemaClass childClass = schema.createClass("TestSchemaGenerationChild");

    testSchemaClass.createProperty(database, "text", PropertyType.STRING);
    testSchemaClass.createProperty(database, "enumeration", PropertyType.STRING);
    testSchemaClass.createProperty(database, "numberSimple", PropertyType.INTEGER);
    testSchemaClass.createProperty(database, "longSimple", PropertyType.LONG);
    testSchemaClass.createProperty(database, "doubleSimple", PropertyType.DOUBLE);
    testSchemaClass.createProperty(database, "floatSimple", PropertyType.FLOAT);
    testSchemaClass.createProperty(database, "byteSimple", PropertyType.BYTE);
    testSchemaClass.createProperty(database, "flagSimple", PropertyType.BOOLEAN);
    testSchemaClass.createProperty(database, "dateField", PropertyType.DATETIME);

    testSchemaClass.createProperty(database, "stringListMap", PropertyType.EMBEDDEDMAP,
        PropertyType.EMBEDDEDLIST);
    testSchemaClass.createProperty(database, "enumList", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    testSchemaClass.createProperty(database, "enumSet", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    testSchemaClass.createProperty(database, "stringSet", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    testSchemaClass.createProperty(database, "stringMap", PropertyType.EMBEDDEDMAP,
        PropertyType.STRING);

    testSchemaClass.createProperty(database, "list", PropertyType.LINKLIST, childClass);
    testSchemaClass.createProperty(database, "set", PropertyType.LINKSET, childClass);
    testSchemaClass.createProperty(database, "children", PropertyType.LINKMAP, childClass);
    testSchemaClass.createProperty(database, "child", PropertyType.LINK, childClass);

    testSchemaClass.createProperty(database, "embeddedSet", PropertyType.EMBEDDEDSET, childClass);
    testSchemaClass.createProperty(database, "embeddedChildren", PropertyType.EMBEDDEDMAP,
        childClass);
    testSchemaClass.createProperty(database, "embeddedChild", PropertyType.EMBEDDED, childClass);
    testSchemaClass.createProperty(database, "embeddedList", PropertyType.EMBEDDEDLIST, childClass);

    // Test simple types
    checkProperty(testSchemaClass, "text", PropertyType.STRING);
    checkProperty(testSchemaClass, "enumeration", PropertyType.STRING);
    checkProperty(testSchemaClass, "numberSimple", PropertyType.INTEGER);
    checkProperty(testSchemaClass, "longSimple", PropertyType.LONG);
    checkProperty(testSchemaClass, "doubleSimple", PropertyType.DOUBLE);
    checkProperty(testSchemaClass, "floatSimple", PropertyType.FLOAT);
    checkProperty(testSchemaClass, "byteSimple", PropertyType.BYTE);
    checkProperty(testSchemaClass, "flagSimple", PropertyType.BOOLEAN);
    checkProperty(testSchemaClass, "dateField", PropertyType.DATETIME);

    // Test complex types
    checkProperty(testSchemaClass, "stringListMap", PropertyType.EMBEDDEDMAP,
        PropertyType.EMBEDDEDLIST);
    checkProperty(testSchemaClass, "enumList", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    checkProperty(testSchemaClass, "enumSet", PropertyType.EMBEDDEDSET, PropertyType.STRING);
    checkProperty(testSchemaClass, "stringSet", PropertyType.EMBEDDEDSET, PropertyType.STRING);
    checkProperty(testSchemaClass, "stringMap", PropertyType.EMBEDDEDMAP, PropertyType.STRING);

    // Test linked types
    checkProperty(testSchemaClass, "list", PropertyType.LINKLIST, childClass);
    checkProperty(testSchemaClass, "set", PropertyType.LINKSET, childClass);
    checkProperty(testSchemaClass, "children", PropertyType.LINKMAP, childClass);
    checkProperty(testSchemaClass, "child", PropertyType.LINK, childClass);

    // Test embedded types
    checkProperty(testSchemaClass, "embeddedSet", PropertyType.EMBEDDEDSET, childClass);
    checkProperty(testSchemaClass, "embeddedChildren", PropertyType.EMBEDDEDMAP, childClass);
    checkProperty(testSchemaClass, "embeddedChild", PropertyType.EMBEDDED, childClass);
    checkProperty(testSchemaClass, "embeddedList", PropertyType.EMBEDDEDLIST, childClass);
  }

  protected void checkProperty(SchemaClass iClass, String iPropertyName, PropertyType iType) {
    SchemaProperty prop = iClass.getProperty(database, iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), iType);
  }

  protected void checkProperty(
      SchemaClass iClass, String iPropertyName, PropertyType iType, SchemaClass iLinkedClass) {
    SchemaProperty prop = iClass.getProperty(database, iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), iType);
    Assert.assertEquals(prop.getLinkedClass(database), iLinkedClass);
  }

  protected void checkProperty(
      SchemaClass iClass, String iPropertyName, PropertyType iType, PropertyType iLinkedType) {
    SchemaProperty prop = iClass.getProperty(database, iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), iType);
    Assert.assertEquals(prop.getLinkedType(), iLinkedType);
  }
}
