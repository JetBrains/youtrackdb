package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Test;

/**
 *
 */
public class CreateSchemaPropertyStatementExecutionTest extends DbTestBase {

  private static final String PROP_NAME = "name";

  private static final String PROP_DIVISION = "division";

  private static final String PROP_OFFICERS = "officers";

  private static final String PROP_ID = "id";

  @Test
  public void testBasicCreateProperty() throws Exception {
    db.command("CREATE class testBasicCreateProperty").close();
    db.command("CREATE property testBasicCreateProperty.name STRING").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testBasicCreateProperty");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testBasicCreateProperty.name");
    assertEquals(nameProperty.getType(), PropertyType.STRING);
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testBasicUnsafeCreateProperty() throws Exception {
    db.command("CREATE class testBasicUnsafeCreateProperty").close();
    db.command("CREATE property testBasicUnsafeCreateProperty.name STRING UNSAFE").close();

    SchemaClass companyClass = db.getMetadata().getSchema()
        .getClass("testBasicUnsafeCreateProperty");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testBasicUnsafeCreateProperty.name");
    assertEquals(nameProperty.getType(), PropertyType.STRING);
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithLinkedClass() throws Exception {
    db.command("CREATE class testCreatePropertyWithLinkedClass_1").close();
    db.command("CREATE class testCreatePropertyWithLinkedClass_2").close();
    db.command(
            "CREATE property testCreatePropertyWithLinkedClass_2.division LINK"
                + " testCreatePropertyWithLinkedClass_1")
        .close();

    SchemaClass companyClass =
        db.getMetadata().getSchema().getClass("testCreatePropertyWithLinkedClass_2");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_DIVISION);

    assertEquals(nameProperty.getName(), PROP_DIVISION);
    assertEquals(nameProperty.getFullName(), "testCreatePropertyWithLinkedClass_2.division");
    assertEquals(nameProperty.getType(), PropertyType.LINK);
    assertEquals(nameProperty.getLinkedClass(db).getName(), "testCreatePropertyWithLinkedClass_1");
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithEmbeddedType() throws Exception {
    db.command("CREATE Class testCreatePropertyWithEmbeddedType").close();
    db.command("CREATE Property testCreatePropertyWithEmbeddedType.officers EMBEDDEDLIST STRING")
        .close();

    SchemaClass companyClass =
        db.getMetadata().getSchema().getClass("testCreatePropertyWithEmbeddedType");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(nameProperty.getFullName(), "testCreatePropertyWithEmbeddedType.officers");
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryProperty() throws Exception {
    db.command("CREATE class testCreateMandatoryProperty").close();
    db.command("CREATE property testCreateMandatoryProperty.name STRING (MANDATORY)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testCreateMandatoryProperty");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testCreateMandatoryProperty.name");
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateNotNullProperty() throws Exception {
    db.command("CREATE class testCreateNotNullProperty").close();
    db.command("CREATE property testCreateNotNullProperty.name STRING (NOTNULL)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testCreateNotNullProperty");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testCreateNotNullProperty.name");
    assertFalse(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyProperty() throws Exception {
    db.command("CREATE class testCreateReadOnlyProperty").close();
    db.command("CREATE property testCreateReadOnlyProperty.name STRING (READONLY)").close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testCreateReadOnlyProperty");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testCreateReadOnlyProperty.name");
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyFalseProperty() throws Exception {
    db.command("CREATE class testCreateReadOnlyFalseProperty").close();
    db.command("CREATE property testCreateReadOnlyFalseProperty.name STRING (READONLY false)")
        .close();

    SchemaClass companyClass = db.getMetadata().getSchema()
        .getClass("testCreateReadOnlyFalseProperty");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testCreateReadOnlyFalseProperty.name");
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryPropertyWithEmbeddedType() throws Exception {
    db.command("CREATE Class testCreateMandatoryPropertyWithEmbeddedType").close();
    db.command(
            "CREATE Property testCreateMandatoryPropertyWithEmbeddedType.officers EMBEDDEDLIST"
                + " STRING (MANDATORY)")
        .close();

    SchemaClass companyClass =
        db.getMetadata().getSchema().getClass("testCreateMandatoryPropertyWithEmbeddedType");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(
        nameProperty.getFullName(), "testCreateMandatoryPropertyWithEmbeddedType.officers");
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateUnsafePropertyWithEmbeddedType() throws Exception {
    db.command("CREATE Class testCreateUnsafePropertyWithEmbeddedType").close();
    db.command(
            "CREATE Property testCreateUnsafePropertyWithEmbeddedType.officers EMBEDDEDLIST STRING"
                + " UNSAFE")
        .close();

    SchemaClass companyClass =
        db.getMetadata().getSchema().getClass("testCreateUnsafePropertyWithEmbeddedType");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(nameProperty.getFullName(), "testCreateUnsafePropertyWithEmbeddedType.officers");
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
  }

  @Test
  public void testComplexCreateProperty() throws Exception {
    db.command("CREATE Class testComplexCreateProperty").close();
    db.command(
            "CREATE Property testComplexCreateProperty.officers EMBEDDEDLIST STRING (MANDATORY,"
                + " READONLY, NOTNULL) UNSAFE")
        .close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testComplexCreateProperty");
    SchemaProperty nameProperty = companyClass.getProperty(db, PROP_OFFICERS);

    assertEquals(nameProperty.getName(), PROP_OFFICERS);
    assertEquals(nameProperty.getFullName(), "testComplexCreateProperty.officers");
    assertEquals(nameProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(nameProperty.getLinkedType(), PropertyType.STRING);
    assertTrue(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testLinkedTypeDefaultAndMinMaxUnsafeProperty() throws Exception {
    db.command("CREATE CLASS testLinkedTypeDefaultAndMinMaxUnsafeProperty").close();
    db.command(
            "CREATE PROPERTY testLinkedTypeDefaultAndMinMaxUnsafeProperty.id EMBEDDEDLIST Integer"
                + " (DEFAULT 5, MIN 1, MAX 10) UNSAFE")
        .close();

    SchemaClass companyClass =
        db.getMetadata().getSchema().getClass("testLinkedTypeDefaultAndMinMaxUnsafeProperty");
    SchemaProperty idProperty = companyClass.getProperty(db, PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), "testLinkedTypeDefaultAndMinMaxUnsafeProperty.id");
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
    db.command("CREATE CLASS testDefaultAndMinMaxUnsafeProperty").close();
    db.command(
            "CREATE PROPERTY testDefaultAndMinMaxUnsafeProperty.id INTEGER (DEFAULT 5, MIN 1, MAX"
                + " 10) UNSAFE")
        .close();

    SchemaClass companyClass =
        db.getMetadata().getSchema().getClass("testDefaultAndMinMaxUnsafeProperty");
    SchemaProperty idProperty = companyClass.getProperty(db, PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), "testDefaultAndMinMaxUnsafeProperty.id");
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
    db.command("CREATE CLASS testExtraSpaces").close();
    db.command("CREATE PROPERTY testExtraSpaces.id INTEGER  ( DEFAULT  5 ,  MANDATORY  )  UNSAFE ")
        .close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testExtraSpaces");
    SchemaProperty idProperty = companyClass.getProperty(db, PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), "testExtraSpaces.id");
    assertEquals(idProperty.getType(), PropertyType.INTEGER);
    assertNull(idProperty.getLinkedType());
    assertTrue(idProperty.isMandatory());
    assertEquals(idProperty.getDefaultValue(), "5");
  }

  @Test(expected = CommandExecutionException.class)
  public void testInvalidAttributeName() throws Exception {
    db.command("CREATE CLASS CommandExecutionException").close();
    db.command(
            "CREATE PROPERTY CommandExecutionException.id INTEGER (MANDATORY, INVALID, NOTNULL) "
                + " UNSAFE")
        .close();
  }

  @Test(expected = CommandExecutionException.class)
  public void testMissingAttributeValue() throws Exception {
    db.command("CREATE CLASS testMissingAttributeValue").close();
    db.command("CREATE PROPERTY testMissingAttributeValue.id INTEGER (DEFAULT)  UNSAFE").close();
  }

  @Test
  public void testMandatoryAsLinkedName() throws Exception {
    db.command("CREATE CLASS testMandatoryAsLinkedName").close();
    db.command("CREATE CLASS testMandatoryAsLinkedName_2").close();
    db.command(
            "CREATE PROPERTY testMandatoryAsLinkedName.id EMBEDDEDLIST testMandatoryAsLinkedName_2"
                + " UNSAFE")
        .close();

    SchemaClass companyClass = db.getMetadata().getSchema().getClass("testMandatoryAsLinkedName");
    SchemaClass mandatoryClass = db.getMetadata().getSchema()
        .getClass("testMandatoryAsLinkedName_2");
    SchemaProperty idProperty = companyClass.getProperty(db, PROP_ID);

    assertEquals(idProperty.getName(), PROP_ID);
    assertEquals(idProperty.getFullName(), "testMandatoryAsLinkedName.id");
    assertEquals(idProperty.getType(), PropertyType.EMBEDDEDLIST);
    assertEquals(idProperty.getLinkedClass(db), mandatoryClass);
    assertFalse(idProperty.isMandatory());
  }

  @Test
  public void testIfNotExists() throws Exception {
    db.command("CREATE class testIfNotExists").close();
    db.command("CREATE property testIfNotExists.name if not exists STRING").close();

    SchemaClass clazz = db.getMetadata().getSchema().getClass("testIfNotExists");
    SchemaProperty nameProperty = clazz.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testIfNotExists.name");
    assertEquals(nameProperty.getType(), PropertyType.STRING);

    db.command("CREATE property testIfNotExists.name if not exists STRING").close();

    clazz = db.getMetadata().getSchema().getClass("testIfNotExists");
    nameProperty = clazz.getProperty(db, PROP_NAME);

    assertEquals(nameProperty.getName(), PROP_NAME);
    assertEquals(nameProperty.getFullName(), "testIfNotExists.name");
    assertEquals(nameProperty.getType(), PropertyType.STRING);
  }
}
