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
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of CRUDInheritanceTest. Original test class:
 * com.jetbrains.youtrackdb.auto.CRUDInheritanceTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CRUDInheritanceTest extends BaseDBTest {

  /**
   * Original method: beforeClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:29
   */
  @BeforeClass
  public static void setUpClass() throws Exception {
    CRUDInheritanceTest instance = new CRUDInheritanceTest();
    instance.beforeClass();
    instance.createCompanyClass();
  }

  /**
   * Original test method: create Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:37
   */
  @Test
  public void test01_Create() {
    session.begin();
    session.command("delete from Company");
    session.commit();

    generateCompanyData();
  }

  /**
   * Original test method: testCreate Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:46 Depends on:
   * create
   */
  @Test
  public void test02_TestCreate() {
    Assert.assertEquals(TOT_COMPANY_RECORDS, session.countClass("Company"));
  }

  /**
   * Original test method: queryByBaseType Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:51 Depends on:
   * testCreate
   */
  @Test
  public void test03_QueryByBaseType() {
    session.begin();
    var resultSet = executeQuery("select from Company where name.length() > 0");

    Assert.assertFalse(resultSet.isEmpty());
    Assert.assertEquals(TOT_COMPANY_RECORDS, resultSet.size());

    var companyRecords = 0;
    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntity();

      if ("Company".equals(account.getSchemaClassName())) {
        companyRecords++;
      }

      Assert.assertNotSame(0, account.<String>getProperty("name").length());
    }

    Assert.assertEquals(TOT_COMPANY_RECORDS, companyRecords);
    session.commit();
  }

  /**
   * Original test method: queryPerSuperType Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:75 Depends on:
   * queryByBaseType
   */
  @Test
  public void test04_QueryPerSuperType() {
    session.begin();
    var resultSet = executeQuery("select * from Company where name.length() > 0");

    Assert.assertEquals(TOT_COMPANY_RECORDS, resultSet.size());

    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntity();
      Assert.assertNotSame(0, account.<String>getProperty("name").length());
    }
    session.commit();
  }

  /**
   * Original test method: deleteFirst Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:90 Depends on:
   * queryPerSuperType, testCreate
   */
  @Test
  public void test05_DeleteFirst() {
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

    Assert.assertEquals(TOT_COMPANY_RECORDS - 1, session.countClass("Company"));
  }

  /**
   * Original test method: testSuperclassInheritanceCreation Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:107 Depends on:
   * deleteFirst
   */
  @Test
  public void test06_TestSuperclassInheritanceCreation() {
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

  /**
   * Original test method: testIdFieldInheritanceFirstSubClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:123
   */
  @Test
  public void test07_TestIdFieldInheritanceFirstSubClass() {
    createInheritanceTestClass();

    session.begin();
    var a = session.newInstance("InheritanceTestBaseClass");
    var b = session.newInstance("InheritanceTestClass");
    session.commit();

    var resultSet = executeQuery("select from InheritanceTestBaseClass");
    Assert.assertEquals(2, resultSet.size());
  }

  /**
   * Original test method: testKeywordClass Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:136
   */
  @Test
  public void test08_TestKeywordClass() {
    var klass = session.getMetadata().getSchema().createClass("Not");

    var klass1 = session.getMetadata().getSchema().createClass("Extends_Not", klass);
    Assert.assertEquals(1, klass1.getSuperClasses().size());
    Assert.assertEquals("Not", klass1.getSuperClasses().getFirst().getName());
  }

  /**
   * Original test method: testSchemaGeneration Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:145
   */
  @Test
  public void test09_TestSchemaGeneration() {
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

  /**
   * Original helper method: checkProperty Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:220
   */
  protected static void checkProperty(DatabaseSessionInternal session, SchemaClass iClass,
      String iPropertyName,
      PropertyType iType) {
    var prop = iClass.getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(iType, prop.getType());
  }

  /**
   * Original helper method: checkProperty (with linked class) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:228
   */
  protected static void checkProperty(
      DatabaseSessionInternal session, SchemaClass iClass, String iPropertyName, PropertyType iType,
      SchemaClass iLinkedClass) {
    var prop = iClass.getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(iType, prop.getType());
    Assert.assertEquals(iLinkedClass, prop.getLinkedClass());
  }

  /**
   * Original helper method: checkProperty (with linked type) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CRUDInheritanceTest.java:237
   */
  protected static void checkProperty(
      DatabaseSessionInternal session, SchemaClass iClass, String iPropertyName, PropertyType iType,
      PropertyType iLinkedType) {
    var prop = iClass.getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(iType, prop.getType());
    Assert.assertEquals(iLinkedType, prop.getLinkedType());
  }
}
