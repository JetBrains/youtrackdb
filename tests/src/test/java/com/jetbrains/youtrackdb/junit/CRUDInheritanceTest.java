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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class CRUDInheritanceTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();
    createCompanyClass();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#create
  @Test
  @Order(1)
  void create() {
    session.begin();
    session.command("delete from Company");
    session.commit();

    generateCompanyData();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#testCreate
  @Test
  @Order(2)
  void testCreate() {
    session.begin();
    assertEquals(TOT_COMPANY_RECORDS, session.countClass("Company"));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#queryByBaseType
  @Test
  @Order(3)
  void queryByBaseType() {
    session.begin();
    var resultSet = executeQuery("select from Company where name.length() > 0");

    assertFalse(resultSet.isEmpty());
    assertEquals(TOT_COMPANY_RECORDS, resultSet.size());

    var companyRecords = 0;
    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntity();

      if ("Company".equals(account.getSchemaClassName())) {
        companyRecords++;
      }

      assertNotSame(0, account.<String>getProperty("name").length());
    }

    assertEquals(TOT_COMPANY_RECORDS, companyRecords);
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#queryPerSuperType
  @Test
  @Order(4)
  void queryPerSuperType() {
    session.begin();
    var resultSet = executeQuery("select * from Company where name.length() > 0");

    assertEquals(TOT_COMPANY_RECORDS, resultSet.size());

    Entity account;
    for (var entries : resultSet) {
      account = entries.asEntity();
      assertNotSame(0, account.<String>getProperty("name").length());
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#deleteFirst
  @Test
  @Order(5)
  void deleteFirst() {
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

    session.begin();
    assertEquals(TOT_COMPANY_RECORDS - 1, session.countClass("Company"));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#testSuperclassInheritanceCreation
  @Test
  @Order(6)
  void testSuperclassInheritanceCreation() {
    session.close();
    session = createSessionInstance();

    createInheritanceTestClass();

    var abstractClass =
        session.getMetadata().getSchema().getClass("InheritanceTestAbstractClass");
    var baseClass = session.getMetadata().getSchema().getClass("InheritanceTestBaseClass");
    var testClass = session.getMetadata().getSchema().getClass("InheritanceTestClass");

    assertTrue(baseClass.getSuperClasses().contains(abstractClass));
    assertTrue(testClass.getSuperClasses().contains(baseClass));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#testIdFieldInheritanceFirstSubClass
  @Test
  @Order(7)
  void testIdFieldInheritanceFirstSubClass() {
    createInheritanceTestClass();

    session.begin();
    session.newInstance("InheritanceTestBaseClass");
    session.newInstance("InheritanceTestClass");
    session.commit();

    var resultSet = executeQuery("select from InheritanceTestBaseClass");
    assertEquals(2, resultSet.size());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#testKeywordClass
  @Test
  @Order(8)
  void testKeywordClass() {
    var klass = session.getMetadata().getSchema().createClass("Not");

    var klass1 = session.getMetadata().getSchema().createClass("Extends_Not", klass);
    assertEquals(1, klass1.getSuperClasses().size());
    assertEquals("Not", klass1.getSuperClasses().getFirst().getName());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.CRUDInheritanceTest#testSchemaGeneration
  @Test
  @Order(9)
  void testSchemaGeneration() {
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

  protected static void checkProperty(DatabaseSessionEmbedded session, SchemaClass iClass,
      String iPropertyName,
      PropertyType iType) {
    var prop = iClass.getProperty(iPropertyName);
    assertNotNull(prop);
    assertEquals(iType, prop.getType());
  }

  protected static void checkProperty(
      DatabaseSessionEmbedded session, SchemaClass iClass, String iPropertyName, PropertyType iType,
      SchemaClass iLinkedClass) {
    var prop = iClass.getProperty(iPropertyName);
    assertNotNull(prop);
    assertEquals(iType, prop.getType());
    assertEquals(iLinkedClass, prop.getLinkedClass());
  }

  protected static void checkProperty(
      DatabaseSessionEmbedded session, SchemaClass iClass, String iPropertyName, PropertyType iType,
      PropertyType iLinkedType) {
    var prop = iClass.getProperty(iPropertyName);
    assertNotNull(prop);
    assertEquals(iType, prop.getType());
    assertEquals(iLinkedType, prop.getLinkedType());
  }
}
