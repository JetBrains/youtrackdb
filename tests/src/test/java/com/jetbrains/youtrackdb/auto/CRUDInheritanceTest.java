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

import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class CRUDInheritanceTest extends BaseDBTest {

  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    createCompanyClass();
  }

  @Test
  public void create() {
    session.begin();
    session.command("delete from Company");
    session.commit();

    generateCompanyData();
  }

  @Test(dependsOnMethods = "create")
  public void testCreate() {
    Assert.assertEquals(session.countClass("Company"), TOT_COMPANY_RECORDS);
  }

  @Test(dependsOnMethods = "testCreate")
  public void queryByBaseType() {
    session.begin();
    var resultSet = executeQuery("select from Company where name.length() > 0");

    Assert.assertFalse(resultSet.isEmpty());
    Assert.assertEquals(resultSet.size(), TOT_COMPANY_RECORDS);

    var companyRecords = 0;
    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntity();

      if ("Company".equals(account.getSchemaClassName())) {
        companyRecords++;
      }

      Assert.assertNotSame(account.<String>getProperty("name").length(), 0);
    }

    Assert.assertEquals(companyRecords, TOT_COMPANY_RECORDS);
    session.commit();
  }

  @Test(dependsOnMethods = "queryByBaseType")
  public void queryPerSuperType() {
    session.begin();
    var resultSet = executeQuery("select * from Company where name.length() > 0");

    Assert.assertEquals(resultSet.size(), TOT_COMPANY_RECORDS);

    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntity();
      Assert.assertNotSame(account.<String>getProperty("name").length(), 0);
    }
    session.commit();
  }

  @Test(dependsOnMethods = {"queryPerSuperType", "testCreate"})
  public void deleteFirst() {
    // DELETE ALL THE RECORD IN THE COLLECTION
    session.begin();
    var companyCollectionIterator = session.browseClass("Company");
    while (companyCollectionIterator.hasNext()) {
      var obj = companyCollectionIterator.next();
      if (obj.<Integer>getProperty("id") == 1) {
        session.delete(obj);
        break;
      }
    }
    session.commit();

    Assert.assertEquals(session.countClass("Company"), TOT_COMPANY_RECORDS - 1);
  }

  @Test(dependsOnMethods = "deleteFirst")
  public void testSuperclassInheritanceCreation() {
    session.close();
    session = createSessionInstance();

    createInheritanceTestClass();

    var abstractClass =
        session.getMetadata().getSchema().getClass("InheritanceTestAbstractClass");
    var baseClass = session.getMetadata().getSchema().getClass("InheritanceTestBaseClass");
    var testClass = session.getMetadata().getSchema().getClass("InheritanceTestClass");

    Assert.assertTrue(baseClass.getSuperClasses().contains(abstractClass));
    Assert.assertTrue(testClass.getSuperClasses().contains(baseClass));
  }

  @Test
  public void testIdFieldInheritanceFirstSubClass() {
    createInheritanceTestClass();

    session.begin();
    var a = session.newInstance("InheritanceTestBaseClass");
    var b = session.newInstance("InheritanceTestClass");
    session.commit();

    var resultSet = executeQuery("select from InheritanceTestBaseClass");
    Assert.assertEquals(resultSet.size(), 2);
  }

  @Test
  public void testKeywordClass() {
    var klass = session.getMetadata().getSchema().createClass("Not");

    var klass1 = session.getMetadata().getSchema().createClass("Extends_Not", klass);
    Assert.assertEquals(klass1.getSuperClasses().size(), 1, 1);
    Assert.assertEquals(klass1.getSuperClasses().getFirst().getName(), "Not");
  }

  @Test
  public void testSchemaGeneration() {
    var schema = session.getMetadata().getSchema();
    var testSchemaClass = schema.createClass("JavaTestSchemaGeneration");
    var childClass = schema.createClass("TestSchemaGenerationChild");

    testSchemaClass.createProperty("text", PropertyType.STRING);
    testSchemaClass.createProperty("enumeration", PropertyType.STRING);
    testSchemaClass.createProperty("numberSimple", PropertyType.INTEGER);
    testSchemaClass.createProperty("longSimple", PropertyType.LONG);
    testSchemaClass.createProperty("doubleSimple", PropertyType.DOUBLE);
    testSchemaClass.createProperty("floatSimple", PropertyType.FLOAT);
    testSchemaClass.createProperty("byteSimple", PropertyType.BYTE);
    testSchemaClass.createProperty("flagSimple", PropertyType.BOOLEAN);
    testSchemaClass.createProperty("dateField", PropertyType.DATETIME);

    testSchemaClass.createProperty("stringListMap", PropertyType.EMBEDDEDMAP,
        PropertyType.EMBEDDEDLIST);
    testSchemaClass.createProperty("enumList", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    testSchemaClass.createProperty("enumSet", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    testSchemaClass.createProperty("stringSet", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    testSchemaClass.createProperty("stringMap", PropertyType.EMBEDDEDMAP,
        PropertyType.STRING);

    testSchemaClass.createProperty("list", PropertyType.LINKLIST, childClass);
    testSchemaClass.createProperty("set", PropertyType.LINKSET, childClass);
    testSchemaClass.createProperty("children", PropertyType.LINKMAP, childClass);
    testSchemaClass.createProperty("child", PropertyType.LINK, childClass);

    testSchemaClass.createProperty("embeddedSet", PropertyType.EMBEDDEDSET, childClass);
    testSchemaClass.createProperty("embeddedChildren", PropertyType.EMBEDDEDMAP,
        childClass);
    testSchemaClass.createProperty("embeddedChild", PropertyType.EMBEDDED, childClass);
    testSchemaClass.createProperty("embeddedList", PropertyType.EMBEDDEDLIST, childClass);

    // Test simple types
    checkProperty(session, testSchemaClass, "text", PropertyType.STRING);
    checkProperty(session, testSchemaClass, "enumeration", PropertyType.STRING);
    checkProperty(session, testSchemaClass, "numberSimple", PropertyType.INTEGER);
    checkProperty(session, testSchemaClass, "longSimple", PropertyType.LONG);
    checkProperty(session, testSchemaClass, "doubleSimple", PropertyType.DOUBLE);
    checkProperty(session, testSchemaClass, "floatSimple", PropertyType.FLOAT);
    checkProperty(session, testSchemaClass, "byteSimple", PropertyType.BYTE);
    checkProperty(session, testSchemaClass, "flagSimple", PropertyType.BOOLEAN);
    checkProperty(session, testSchemaClass, "dateField", PropertyType.DATETIME);

    // Test complex types
    checkProperty(session, testSchemaClass, "stringListMap",
        PropertyType.EMBEDDEDMAP, PropertyType.EMBEDDEDLIST);
    checkProperty(session, testSchemaClass, "enumList", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING);
    checkProperty(session, testSchemaClass, "enumSet", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    checkProperty(session, testSchemaClass, "stringSet", PropertyType.EMBEDDEDSET,
        PropertyType.STRING);
    checkProperty(session, testSchemaClass, "stringMap", PropertyType.EMBEDDEDMAP,
        PropertyType.STRING);

    // Test linked types
    checkProperty(session, testSchemaClass, "list", PropertyType.LINKLIST, childClass);
    checkProperty(session, testSchemaClass, "set", PropertyType.LINKSET, childClass);
    checkProperty(session, testSchemaClass, "children", PropertyType.LINKMAP, childClass);
    checkProperty(session, testSchemaClass, "child", PropertyType.LINK, childClass);

    // Test embedded types
    checkProperty(session, testSchemaClass, "embeddedSet", PropertyType.EMBEDDEDSET, childClass);
    checkProperty(session, testSchemaClass, "embeddedChildren", PropertyType.EMBEDDEDMAP,
        childClass);
    checkProperty(session, testSchemaClass, "embeddedChild", PropertyType.EMBEDDED, childClass);
    checkProperty(session, testSchemaClass, "embeddedList", PropertyType.EMBEDDEDLIST, childClass);
  }

  protected static void checkProperty(DatabaseSessionInternal session, SchemaClass iClass,
      String iPropertyName,
      PropertyType iType) {
    var prop = iClass.getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), iType);
  }

  protected static void checkProperty(
      DatabaseSessionInternal session, SchemaClass iClass, String iPropertyName, PropertyType iType,
      SchemaClass iLinkedClass) {
    var prop = iClass.getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), iType);
    Assert.assertEquals(prop.getLinkedClass(), iLinkedClass);
  }

  protected static void checkProperty(
      DatabaseSessionInternal session, SchemaClass iClass, String iPropertyName, PropertyType iType,
      PropertyType iLinkedType) {
    var prop = iClass.getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), iType);
    Assert.assertEquals(prop.getLinkedType(), iLinkedType);
  }
}
