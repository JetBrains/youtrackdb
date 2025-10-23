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

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
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
        session.getMetadata().getSlowMutableSchema().getClass("InheritanceTestAbstractClass");
    var baseClass = session.getMetadata().getSlowMutableSchema()
        .getClass("InheritanceTestBaseClass");
    var testClass = session.getMetadata().getSlowMutableSchema().getClass("InheritanceTestClass");

    Assert.assertTrue(baseClass.getParentClasses().contains(abstractClass));
    Assert.assertTrue(testClass.getParentClasses().contains(baseClass));
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
    var klass = session.getMetadata().getSlowMutableSchema().createClass("Not");

    var klass1 = session.getMetadata().getSlowMutableSchema().createClass("Extends_Not", klass);
    Assert.assertEquals(klass1.getParentClasses().size(), 1, 1);
    Assert.assertEquals(klass1.getParentClasses().getFirst().getName(), "Not");
  }

  @Test
  public void testSchemaGeneration() {
    var testSchemaClass = "JavaTestSchemaGeneration";
    var childClass = "TestSchemaGenerationChild";

    graph.autoExecuteInTx(g ->
        g.createSchemaClass(testSchemaClass,
            __.createSchemaProperty("text", PropertyType.STRING),
            __.createSchemaProperty("enumeration", PropertyType.STRING),
            __.createSchemaProperty("numberSimple", PropertyType.INTEGER),
            __.createSchemaProperty("longSimple", PropertyType.LONG),
            __.createSchemaProperty("doubleSimple", PropertyType.DOUBLE),
            __.createSchemaProperty("floatSimple", PropertyType.FLOAT),
            __.createSchemaProperty("byteSimple", PropertyType.BYTE),
            __.createSchemaProperty("flagSimple", PropertyType.BOOLEAN),
            __.createSchemaProperty("dateField", PropertyType.DATETIME),

            __.createSchemaProperty("stringListMap", PropertyType.EMBEDDEDMAP,
                PropertyType.EMBEDDEDLIST),
            __.createSchemaProperty("enumList", PropertyType.EMBEDDEDLIST,
                PropertyType.STRING),
            __.createSchemaProperty("enumSet", PropertyType.EMBEDDEDSET,
                PropertyType.STRING),
            __.createSchemaProperty("stringSet", PropertyType.EMBEDDEDSET,
                PropertyType.STRING),
            __.createSchemaProperty("stringMap", PropertyType.EMBEDDEDMAP,
                PropertyType.STRING),

            __.createSchemaProperty("list", PropertyType.LINKLIST, childClass),
            __.createSchemaProperty("set", PropertyType.LINKSET, childClass),
            __.createSchemaProperty("children", PropertyType.LINKMAP, childClass),
            __.createSchemaProperty("child", PropertyType.LINK, childClass),

            __.createSchemaProperty("embeddedSet", PropertyType.EMBEDDEDSET, childClass),
            __.createSchemaProperty("embeddedChildren", PropertyType.EMBEDDEDMAP,
                childClass),
            __.createSchemaProperty("embeddedChild", PropertyType.EMBEDDED, childClass),
            __.createSchemaProperty("embeddedList", PropertyType.EMBEDDEDLIST, childClass)
        )
    );

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

  protected static void checkProperty(DatabaseSessionInternal session, String iClass,
      String iPropertyName,
      PropertyType iType) {
    var prop = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(iClass)
        .getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), PropertyTypeInternal.convertFromPublicType(iType));
  }

  protected static void checkProperty(
      DatabaseSessionInternal session, String iClass, String iPropertyName, PropertyType iType,
      String iLinkedClass) {
    var prop = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(iClass)
        .getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), PropertyTypeInternal.convertFromPublicType(iType));
    Assert.assertEquals(prop.getLinkedClass().getName(), iLinkedClass);
  }

  protected static void checkProperty(
      DatabaseSessionInternal session, String iClass, String iPropertyName, PropertyType iType,
      PropertyType iLinkedType) {
    var prop = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(iClass)
        .getProperty(iPropertyName);
    Assert.assertNotNull(prop);
    Assert.assertEquals(prop.getType(), PropertyTypeInternal.convertFromPublicType(iType));
    Assert.assertEquals(prop.getLinkedType(),
        PropertyTypeInternal.convertFromPublicType(iLinkedType));
  }
}
