package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
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
    session.execute("CREATE class testBasicCreateProperty").close();
    session.execute("CREATE property testBasicCreateProperty.name STRING").close();

    var companyClass = session.getMetadata().getSchema().getClass("testBasicCreateProperty");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testBasicCreateProperty.name", nameProperty.getFullName());
    assertEquals(PropertyType.STRING, nameProperty.getType());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testBasicUnsafeCreateProperty() throws Exception {
    session.execute("CREATE class testBasicUnsafeCreateProperty").close();
    session.execute("CREATE property testBasicUnsafeCreateProperty.name STRING UNSAFE").close();

    var companyClass = session.getMetadata().getSchema()
        .getClass("testBasicUnsafeCreateProperty");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testBasicUnsafeCreateProperty.name", nameProperty.getFullName());
    assertEquals(PropertyType.STRING, nameProperty.getType());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithLinkedClass() throws Exception {
    session.execute("CREATE class testCreatePropertyWithLinkedClass_1").close();
    session.execute("CREATE class testCreatePropertyWithLinkedClass_2").close();
    session.execute(
            "CREATE property testCreatePropertyWithLinkedClass_2.division LINK"
                + " testCreatePropertyWithLinkedClass_1")
        .close();

    var companyClass =
        session.getMetadata().getSchema().getClass("testCreatePropertyWithLinkedClass_2");
    var nameProperty = companyClass.getProperty(PROP_DIVISION);

    assertEquals(PROP_DIVISION, nameProperty.getName());
    assertEquals("testCreatePropertyWithLinkedClass_2.division", nameProperty.getFullName());
    assertEquals(PropertyType.LINK, nameProperty.getType());
    assertEquals("testCreatePropertyWithLinkedClass_1",
        nameProperty.getLinkedClass().getName(
        ));
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreatePropertyWithEmbeddedType() throws Exception {
    session.execute("CREATE Class testCreatePropertyWithEmbeddedType").close();
    session.execute(
            "CREATE Property testCreatePropertyWithEmbeddedType.officers EMBEDDEDLIST STRING")
        .close();

    var companyClass =
        session.getMetadata().getSchema().getClass("testCreatePropertyWithEmbeddedType");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals("testCreatePropertyWithEmbeddedType.officers", nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryProperty() throws Exception {
    session.execute("CREATE class testCreateMandatoryProperty").close();
    session.execute("CREATE property testCreateMandatoryProperty.name STRING (MANDATORY)").close();

    var companyClass = session.getMetadata().getSchema().getClass("testCreateMandatoryProperty");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testCreateMandatoryProperty.name", nameProperty.getFullName());
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateNotNullProperty() throws Exception {
    session.execute("CREATE class testCreateNotNullProperty").close();
    session.execute("CREATE property testCreateNotNullProperty.name STRING (NOTNULL)").close();

    var companyClass = session.getMetadata().getSchema().getClass("testCreateNotNullProperty");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testCreateNotNullProperty.name", nameProperty.getFullName());
    assertFalse(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyProperty() throws Exception {
    session.execute("CREATE class testCreateReadOnlyProperty").close();
    session.execute("CREATE property testCreateReadOnlyProperty.name STRING (READONLY)").close();

    var companyClass = session.getMetadata().getSchema().getClass("testCreateReadOnlyProperty");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testCreateReadOnlyProperty.name", nameProperty.getFullName());
    assertFalse(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testCreateReadOnlyFalseProperty() throws Exception {
    session.execute("CREATE class testCreateReadOnlyFalseProperty").close();
    session.execute("CREATE property testCreateReadOnlyFalseProperty.name STRING (READONLY false)")
        .close();

    var companyClass = session.getMetadata().getSchema()
        .getClass("testCreateReadOnlyFalseProperty");
    var nameProperty = companyClass.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testCreateReadOnlyFalseProperty.name", nameProperty.getFullName());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateMandatoryPropertyWithEmbeddedType() throws Exception {
    session.execute("CREATE Class testCreateMandatoryPropertyWithEmbeddedType").close();
    session.execute(
            "CREATE Property testCreateMandatoryPropertyWithEmbeddedType.officers EMBEDDEDLIST"
                + " STRING (MANDATORY)")
        .close();

    var companyClass =
        session.getMetadata().getSchema().getClass("testCreateMandatoryPropertyWithEmbeddedType");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals(
        "testCreateMandatoryPropertyWithEmbeddedType.officers", nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
    assertTrue(nameProperty.isMandatory());
    assertFalse(nameProperty.isNotNull());
    assertFalse(nameProperty.isReadonly());
  }

  @Test
  public void testCreateUnsafePropertyWithEmbeddedType() throws Exception {
    session.execute("CREATE Class testCreateUnsafePropertyWithEmbeddedType").close();
    session.execute(
            "CREATE Property testCreateUnsafePropertyWithEmbeddedType.officers EMBEDDEDLIST STRING"
                + " UNSAFE")
        .close();

    var companyClass =
        session.getMetadata().getSchema().getClass("testCreateUnsafePropertyWithEmbeddedType");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals("testCreateUnsafePropertyWithEmbeddedType.officers",
        nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
  }

  @Test
  public void testComplexCreateProperty() throws Exception {
    session.execute("CREATE Class testComplexCreateProperty").close();
    session.execute(
            "CREATE Property testComplexCreateProperty.officers EMBEDDEDLIST STRING (MANDATORY,"
                + " READONLY, NOTNULL) UNSAFE")
        .close();

    var companyClass = session.getMetadata().getSchema().getClass("testComplexCreateProperty");
    var nameProperty = companyClass.getProperty(PROP_OFFICERS);

    assertEquals(PROP_OFFICERS, nameProperty.getName());
    assertEquals("testComplexCreateProperty.officers", nameProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, nameProperty.getType());
    assertEquals(PropertyType.STRING, nameProperty.getLinkedType());
    assertTrue(nameProperty.isMandatory());
    assertTrue(nameProperty.isNotNull());
    assertTrue(nameProperty.isReadonly());
  }

  @Test
  public void testLinkedTypeDefaultAndMinMaxUnsafeProperty() throws Exception {
    session.execute("CREATE CLASS testLinkedTypeDefaultAndMinMaxUnsafeProperty").close();
    session.execute(
            "CREATE PROPERTY testLinkedTypeDefaultAndMinMaxUnsafeProperty.id EMBEDDEDLIST Integer"
                + " (DEFAULT 5, MIN 1, MAX 10) UNSAFE")
        .close();

    var companyClass =
        session.getMetadata().getSchema().getClass("testLinkedTypeDefaultAndMinMaxUnsafeProperty");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals("testLinkedTypeDefaultAndMinMaxUnsafeProperty.id",
        idProperty.getFullName());
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
  public void testDefaultAndMinMaxUnsafeProperty() throws Exception {
    session.execute("CREATE CLASS testDefaultAndMinMaxUnsafeProperty").close();
    session.execute(
            "CREATE PROPERTY testDefaultAndMinMaxUnsafeProperty.id INTEGER (DEFAULT 5, MIN 1, MAX"
                + " 10) UNSAFE")
        .close();

    var companyClass =
        session.getMetadata().getSchema().getClass("testDefaultAndMinMaxUnsafeProperty");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals("testDefaultAndMinMaxUnsafeProperty.id", idProperty.getFullName());
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
    session.execute("CREATE CLASS testExtraSpaces").close();
    session.execute(
            "CREATE PROPERTY testExtraSpaces.id INTEGER  ( DEFAULT  5 ,  MANDATORY  )  UNSAFE ")
        .close();

    var companyClass = session.getMetadata().getSchema().getClass("testExtraSpaces");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals("testExtraSpaces.id", idProperty.getFullName());
    assertEquals(PropertyType.INTEGER, idProperty.getType());
    assertNull(idProperty.getLinkedType());
    assertTrue(idProperty.isMandatory());
    assertEquals("5", idProperty.getDefaultValue());
  }

  @Test(expected = CommandExecutionException.class)
  public void testInvalidAttributeName() throws Exception {
    session.execute("CREATE CLASS CommandExecutionException").close();
    session.execute(
            "CREATE PROPERTY CommandExecutionException.id INTEGER (MANDATORY, INVALID, NOTNULL) "
                + " UNSAFE")
        .close();
  }

  @Test(expected = CommandExecutionException.class)
  public void testMissingAttributeValue() throws Exception {
    session.execute("CREATE CLASS testMissingAttributeValue").close();
    session.execute("CREATE PROPERTY testMissingAttributeValue.id INTEGER (DEFAULT)  UNSAFE")
        .close();
  }

  @Test
  public void testMandatoryAsLinkedName() throws Exception {
    session.execute("CREATE CLASS testMandatoryAsLinkedName").close();
    session.execute("CREATE CLASS testMandatoryAsLinkedName_2").close();
    session.execute(
            "CREATE PROPERTY testMandatoryAsLinkedName.id EMBEDDEDLIST testMandatoryAsLinkedName_2"
                + " UNSAFE")
        .close();

    var companyClass = session.getMetadata().getSchema().getClass("testMandatoryAsLinkedName");
    var mandatoryClass = session.getMetadata().getSchema()
        .getClass("testMandatoryAsLinkedName_2");
    var idProperty = companyClass.getProperty(PROP_ID);

    assertEquals(PROP_ID, idProperty.getName());
    assertEquals("testMandatoryAsLinkedName.id", idProperty.getFullName());
    assertEquals(PropertyType.EMBEDDEDLIST, idProperty.getType());
    assertEquals(idProperty.getLinkedClass(), mandatoryClass);
    assertFalse(idProperty.isMandatory());
  }

  @Test
  public void testIfNotExists() throws Exception {
    session.execute("CREATE class testIfNotExists").close();
    session.execute("CREATE property testIfNotExists.name if not exists STRING").close();

    var clazz = session.getMetadata().getSchema().getClass("testIfNotExists");
    var nameProperty = clazz.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testIfNotExists.name", nameProperty.getFullName());
    assertEquals(PropertyType.STRING, nameProperty.getType());

    session.execute("CREATE property testIfNotExists.name if not exists STRING").close();

    clazz = session.getMetadata().getSchema().getClass("testIfNotExists");
    nameProperty = clazz.getProperty(PROP_NAME);

    assertEquals(PROP_NAME, nameProperty.getName());
    assertEquals("testIfNotExists.name", nameProperty.getFullName());
    assertEquals(PropertyType.STRING, nameProperty.getType());
  }
}
