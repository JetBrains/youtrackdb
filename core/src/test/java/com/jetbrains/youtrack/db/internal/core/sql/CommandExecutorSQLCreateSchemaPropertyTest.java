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
package com.jetbrains.youtrack.db.internal.core.sql;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
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

    db.command("CREATE class company").close();
    db.command("CREATE property company.name STRING").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), PROP_FULL_NAME);
    assertEquals(nameProperty.getType(), PropertyType.STRING);
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testBasicUnsafeCreateProperty() throws Exception {

    db.command("CREATE class company").close();
    db.command("CREATE property company.name STRING UNSAFE").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), PROP_FULL_NAME);
    assertEquals(nameProperty.getType(), PropertyType.STRING);
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithLinkedClass() throws Exception {

    db.command("CREATE class division").close();
    db.command("CREATE class company").close();
    db.command("CREATE property company.division LINK division").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_DIVISION);

    assertEquals(nameProperty.getName(), PROP_DIVISION);
    assertEquals(nameProperty.getFullName(), PROP_FULL_DIVISION);
    assertEquals(nameProperty.getType(), PropertyType.LINK);
    assertEquals(nameProperty.getLinkedClass(db).getName(), "division");
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithEmbeddedType() throws Exception {

    db.command("CREATE Class company").close();
    db.command("CREATE Property company.officers EMBEDDEDLIST STRING").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(nameProperty.getFullName(), PROP_FULL_OFFICERS);
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryProperty() throws Exception {

    db.command("CREATE class company").close();
    db.command("CREATE property company.name STRING (MANDATORY)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), PROP_FULL_NAME);
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateNotNullProperty() throws Exception {

    db.command("CREATE class company").close();
    db.command("CREATE property company.name STRING (NOTNULL)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), PROP_FULL_NAME);
    assertFalse(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyProperty() throws Exception {

    db.command("CREATE class company").close();
    db.command("CREATE property company.name STRING (READONLY)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), PROP_FULL_NAME);
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyFalseProperty() throws Exception {

    db.command("CREATE class company").close();
    db.command("CREATE property company.name STRING (READONLY false)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), PROP_FULL_NAME);
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryPropertyWithEmbeddedType() throws Exception {

    db.command("CREATE Class company").close();
    db.command("CREATE Property company.officers EMBEDDEDLIST STRING (MANDATORY)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(nameProperty.getFullName(), PROP_FULL_OFFICERS);
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateUnsafePropertyWithEmbeddedType() throws Exception {

    db.command("CREATE Class company").close();
    db.command("CREATE Property company.officers EMBEDDEDLIST STRING UNSAFE").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(nameProperty.getFullName(), PROP_FULL_OFFICERS);
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
  }

  @Test
  public void testComplexCreateProperty() throws Exception {

    db.command("CREATE Class company").close();
    db.command(
            "CREATE Property company.officers EMBEDDEDLIST STRING (MANDATORY, READONLY, NOTNULL)"
                + " UNSAFE")
        .close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(nameProperty.getFullName(), PROP_FULL_OFFICERS);
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
    assertTrue(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testLinkedTypeDefaultAndMinMaxUnsafeProperty() throws Exception {

    db.command("CREATE CLASS company").close();
    db.command("CREATE PROPERTY company.id EMBEDDEDLIST Integer (DEFAULT 5, MIN 1, MAX 10) UNSAFE")
        .close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), PROP_FULL_ID);
    assertEquals(idProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(idProperty.getLinkedType(), PropertyType.INTEGER);
    assertFalse(idProperty.isMandatory());
    assertFalse(idProperty.isNotNull());
    assertFalse(idProperty.isReadonly());
    assertEquals(idProperty.getDefaultValue(), "5");
    assertEquals(idProperty.getMin(), "1");
    assertEquals(idProperty.getMax(), "10");
  }

  @Test
  public void testDefaultAndMinMaxUnsafeProperty() throws Exception {

    db.command("CREATE CLASS company").close();
    db.command("CREATE PROPERTY company.id INTEGER (DEFAULT 5, MIN 1, MAX 10) UNSAFE").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), PROP_FULL_ID);
    assertEquals(idProperty.getType(), PropertyType.INTEGER);
    assertNull(idProperty.getLinkedType());
    assertFalse(idProperty.isMandatory());
    assertFalse(idProperty.isNotNull());
    assertFalse(idProperty.isReadonly());
    assertEquals(idProperty.getDefaultValue(), "5");
    assertEquals(idProperty.getMin(), "1");
    assertEquals(idProperty.getMax(), "10");
  }

  @Test
  public void testExtraSpaces() throws Exception {

    db.command("CREATE CLASS company").close();
    db.command("CREATE PROPERTY company.id INTEGER  ( DEFAULT  5 ,  MANDATORY  )  UNSAFE ").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), PROP_FULL_ID);
    assertEquals(idProperty.getType(), PropertyType.INTEGER);
    assertNull(idProperty.getLinkedType());
    assertTrue(idProperty.isMandatory());
    assertEquals(idProperty.getDefaultValue(), "5");
  }

  @Test
  public void testNonStrict() throws Exception {

    db.getStorage().setProperty(SQLStatement.CUSTOM_STRICT_SQL, "false");

    db.command("CREATE CLASS company").close();
    db.command(
            "CREATE PROPERTY company.id INTEGER (MANDATORY, NOTNULL false, READONLY true, MAX 10,"
                + " MIN 4, DEFAULT 6)  UNSAFE")
        .close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaProperty idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), PROP_FULL_ID);
    assertEquals(idProperty.getType(), PropertyType.INTEGER);
    assertNull(idProperty.getLinkedType());
    assertTrue(idProperty.isMandatory());
    assertFalse(idProperty.isNotNull());
    assertTrue(idProperty.isReadonly());
    assertEquals(idProperty.getMin(), "4");
    assertEquals(idProperty.getMax(), "10");
    assertEquals(idProperty.getDefaultValue(), "6");
  }

  @Test(expected = CommandExecutionException.class)
  public void testInvalidAttributeName() throws Exception {
    db.command("CREATE CLASS company").close();
    db.command("CREATE PROPERTY company.id INTEGER (MANDATORY, INVALID, NOTNULL)  UNSAFE").close();
  }

  @Test(expected = CommandExecutionException.class)
  public void testMissingAttributeValue() throws Exception {

    db.command("CREATE CLASS company").close();
    db.command("CREATE PROPERTY company.id INTEGER (DEFAULT)  UNSAFE").close();
  }

  @Test(expected = CommandSQLParsingException.class)
  public void tooManyAttributeParts() throws Exception {

    db.command("CREATE CLASS company").close();
    db.command("CREATE PROPERTY company.id INTEGER (DEFAULT 5 10)  UNSAFE").close();
  }

  @Test
  public void testMandatoryAsLinkedName() throws Exception {
    db.command("CREATE CLASS company").close();
    db.command("CREATE CLASS Mandatory").close();
    db.command("CREATE PROPERTY company.id EMBEDDEDLIST Mandatory UNSAFE").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("company");
    SchemaClass mandatoryClass = db.getMetadata().getSchema().getClass("Mandatory");
    SchemaProperty idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), PROP_FULL_ID);
    assertEquals(idProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(idProperty.getLinkedClass(db), mandatoryClass);
    assertFalse(idProperty.isMandatory());
  }

  @Test
  public void testIfNotExists() throws Exception {

    db.command("CREATE class testIfNotExists").close();
    db.command("CREATE property testIfNotExists.name if not exists STRING").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testIfNotExists");
    SchemaProperty property = companyClass.getProperty("name");
    assertEquals(property.getName(), PROP_NAME);

    db.command("CREATE property testIfNotExists.name if not exists STRING").close();

    companyClass = db.getMetadata().getSchema().getClass("testIfNotExists");
    property = companyClass.getProperty("name");
    assertEquals(property.getName(), PROP_NAME);
  }
}
