/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import org.junit.Test;

/**
 *
 */
public class CommandExecutorSQLCreateSchemaPropertyTest extends BaseMemoryInternalDatabase {

  private static final String PROP_NAME = "name";
  private static final String PROP_FULL_NAME = "company.name";
  private static final String PROP_DIVISION = "division";
  private static final String PROP_FULL_DIVISION = "company.division";
  private static final String PROP_OFFICERS = "officers";
  private static final String PROP_FULL_OFFICERS = "company.officers";
  private static final String PROP_ID = "id";
  private static final String PROP_FULL_ID = "company.id";

  @Test
  public void testBasicCreateProperty() throws Exception {

    session.execute("CREATE class company").close();
    session.execute("CREATE property company.name STRING").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals(PROP_FULL_NAME, nameProperty.getFullName());
    assertEquals(PropertyType.STRING, nameProperty.getType());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testBasicUnsafeCreateProperty() throws Exception {

    session.execute("CREATE class company").close();
    session.execute("CREATE property company.name STRING UNSAFE").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals(PROP_FULL_NAME, nameProperty.getFullName());
    assertEquals(PropertyType.STRING, nameProperty.getType());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithLinkedClass() throws Exception {
    session.execute("CREATE class division").close();
    session.execute("CREATE class company").close();
    session.execute("CREATE property company.division LINK division").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_DIVISION);

    assertEquals(PROP_DIVISION, nameProperty.getName());
    assertEquals(PROP_FULL_DIVISION, nameProperty.getFullName());
    assertEquals(PropertyType.LINK, nameProperty.getType());
    assertEquals("division", nameProperty.getLinkedClass().getName());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithEmbeddedType() throws Exception {

    session.execute("CREATE Class company").close();
    session.execute("CREATE Property company.officers EMBEDDEDLIST STRING").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals(PROP_FULL_OFFICERS, nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryProperty() throws Exception {
    session.execute("CREATE class company").close();
    session.execute("CREATE property company.name STRING (MANDATORY)").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals(PROP_FULL_NAME, nameProperty.getFullName());
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateNotNullProperty() {
    session.execute("CREATE class company").close();
    session.execute("CREATE property company.name STRING (NOTNULL)").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals(PROP_FULL_NAME, nameProperty.getFullName());
    assertFalse(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyProperty() {
    session.execute("CREATE class company").close();
    session.execute("CREATE property company.name STRING (READONLY)").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals(PROP_FULL_NAME, nameProperty.getFullName());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyFalseProperty() {
    session.execute("CREATE class company").close();
    session.execute("CREATE property company.name STRING (READONLY false)").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals(PROP_FULL_NAME, nameProperty.getFullName());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryPropertyWithEmbeddedType() {

    session.execute("CREATE Class company").close();
    session.execute("CREATE Property company.officers EMBEDDEDLIST STRING (MANDATORY)").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals(PROP_FULL_OFFICERS, nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateUnsafePropertyWithEmbeddedType() {

    session.execute("CREATE Class company").close();
    session.execute("CREATE Property company.officers EMBEDDEDLIST STRING UNSAFE").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals(PROP_FULL_OFFICERS, nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
  }

  @Test
  public void testComplexCreateProperty() throws Exception {

    session.execute("CREATE Class company").close();
    session.execute(
            "CREATE Property company.officers EMBEDDEDLIST STRING (MANDATORY, READONLY, NOTNULL)"
                + " UNSAFE")
        .close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals(PROP_FULL_OFFICERS, nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
    assertTrue(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testLinkedTypeDefaultAndMinMaxUnsafeProperty() {
    session.execute("CREATE CLASS company").close();
    session.execute(
            "CREATE PROPERTY company.id EMBEDDEDLIST Integer (DEFAULT 5, MIN 1, MAX 10) UNSAFE")
        .close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals(PROP_FULL_ID, idProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, idProperty.getType());
    assertEquals(PropertyType.INTEGER, idProperty.getLinkedType());
    assertFalse(idProperty.isMandatory());
    assertFalse(idProperty.isNotNull());
    assertFalse(idProperty.isReadonly());
    assertEquals("5", idProperty.getDefaultValue());
    assertEquals("1", idProperty.getMin());
    assertEquals("10", idProperty.getMax());
  }

  @Test
  public void testDefaultAndMinMaxUnsafeProperty() {
    session.execute("CREATE CLASS company").close();
    session.execute("CREATE PROPERTY company.id INTEGER (DEFAULT 5, MIN 1, MAX 10) UNSAFE").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals(PROP_FULL_ID, idProperty.getFullName());
    assertEquals(PropertyType.INTEGER, idProperty.getType());
    assertNull(idProperty.getLinkedType());
    assertFalse(idProperty.isMandatory());
    assertFalse(idProperty.isNotNull());
    assertFalse(idProperty.isReadonly());
    assertEquals("5", idProperty.getDefaultValue());
    assertEquals("1", idProperty.getMin());
    assertEquals("10", idProperty.getMax());
  }

  @Test
  public void testExtraSpaces() throws Exception {
    session.execute("CREATE CLASS company").close();
    session.execute("CREATE PROPERTY company.id INTEGER  ( DEFAULT  5 ,  MANDATORY  )  UNSAFE ")
        .close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals(PROP_FULL_ID, idProperty.getFullName());
    assertEquals(PropertyType.INTEGER, idProperty.getType());
    assertNull(idProperty.getLinkedType());
    assertTrue(idProperty.isMandatory());
    assertEquals("5", idProperty.getDefaultValue());
  }

  @Test
  public void testNonStrict() throws Exception {

    session.getStorage().setProperty(SQLStatement.CUSTOM_STRICT_SQL, "false");

    session.execute("CREATE CLASS company").close();
    session.execute(
            "CREATE PROPERTY company.id INTEGER (MANDATORY, NOTNULL false, READONLY true, MAX 10,"
                + " MIN 4, DEFAULT 6)  UNSAFE")
        .close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals(PROP_FULL_ID, idProperty.getFullName());
    assertEquals(PropertyType.INTEGER, idProperty.getType());
    assertNull(idProperty.getLinkedType());
    assertTrue(idProperty.isMandatory());
    assertFalse(idProperty.isNotNull());
    assertTrue(idProperty.isReadonly());
    assertEquals("4", idProperty.getMin());
    assertEquals("10", idProperty.getMax());
    assertEquals("6", idProperty.getDefaultValue());
  }

  @Test(expected = CommandExecutionException.class)
  public void testInvalidAttributeName() throws Exception {
    session.execute("CREATE CLASS company").close();
    session.execute("CREATE PROPERTY company.id INTEGER (MANDATORY, INVALID, NOTNULL)  UNSAFE")
        .close();
  }

  @Test(expected = CommandExecutionException.class)
  public void testMissingAttributeValue() throws Exception {

    session.execute("CREATE CLASS company").close();
    session.execute("CREATE PROPERTY company.id INTEGER (DEFAULT)  UNSAFE").close();
  }

  @Test(expected = CommandSQLParsingException.class)
  public void tooManyAttributeParts() throws Exception {

    session.execute("CREATE CLASS company").close();
    session.execute("CREATE PROPERTY company.id INTEGER (DEFAULT 5 10)  UNSAFE").close();
  }

  @Test
  public void testMandatoryAsLinkedName() throws Exception {
    session.execute("CREATE CLASS company").close();
    session.execute("CREATE CLASS Mandatory").close();
    session.execute("CREATE PROPERTY company.id EMBEDDEDLIST Mandatory UNSAFE").close();

    var companyClass = session.getMetadata().getSchema().getClass("company");
    var mandatoryClass = session.getMetadata().getSchema().getClass("Mandatory");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals(PROP_FULL_ID, idProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, idProperty.getType());
    assertEquals(idProperty.getLinkedClass(), mandatoryClass);
    assertFalse(idProperty.isMandatory());
  }

  @Test
  public void testIfNotExists() {
    session.execute("CREATE class testIfNotExists").close();
    session.execute("CREATE property testIfNotExists.name if not exists STRING").close();

    var companyClass = session.getMetadata().getSchema().getClass("testIfNotExists");
    var property = companyClass.getProperty("name");
    assertEquals(PROP_NAME, property.getName());

    session.execute("CREATE property testIfNotExists.name if not exists STRING").close();

    companyClass = session.getMetadata().getSchema().getClass("testIfNotExists");
    property = companyClass.getProperty("name");
    assertEquals(PROP_NAME, property.getName());
  }
}
