package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SchemaPropertyIndexDefinitionTest extends DbTestBase {

  private PropertyIndexDefinition propertyIndex;

  @Before
  public void beforeMethod() {
    session.begin();
    propertyIndex = new PropertyIndexDefinition("testClass", "fOne", PropertyTypeInternal.INTEGER);
  }

  @After
  public void afterMethod() {
    session.rollback();
  }

  @Test
  public void testCreateValueSingleParameter() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Collections.singletonList("12"));
    Assert.assertEquals(12, result);
  }

  @Test
  public void testCreateValueTwoParameters() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(),
        Arrays.asList("12", "25"));
    Assert.assertEquals(12, result);
  }

  @Test(expected = NumberFormatException.class)
  public void testCreateValueWrongParameter() {
    propertyIndex.createValue(session.getActiveTransaction(), Collections.singletonList("tt"));
  }

  @Test
  public void testCreateValueSingleParameterArrayParams() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(), "12");
    Assert.assertEquals(12, result);
  }

  @Test
  public void testCreateValueTwoParametersArrayParams() {
    final var result = propertyIndex.createValue(session.getActiveTransaction(), "12", "25");
    Assert.assertEquals(12, result);
  }

  @Test(expected = NumberFormatException.class)
  public void testCreateValueWrongParameterArrayParams() {
    propertyIndex.createValue(session.getActiveTransaction(), "tt");
  }

  @Test
  public void testGetDocumentValueToIndex() {
    session.begin();
    final var document = (EntityImpl) session.newEntity();

    document.setProperty("fOne", "15");
    document.setProperty("fTwo", 10);

    final var result = propertyIndex.getDocumentValueToIndex(session.getActiveTransaction(),
        document);
    Assert.assertEquals(15, result);
    session.rollback();
  }

  @Test
  public void testGetProperties() {
    final var result = propertyIndex.getProperties();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals("fOne", result.getFirst());
  }

  @Test
  public void testGetTypes() {
    final var result = propertyIndex.getTypes();
    Assert.assertEquals(1, result.length);
    Assert.assertEquals(PropertyTypeInternal.INTEGER, result[0]);
  }

  @Test
  public void testEmptyIndexReload() {
    propertyIndex = new PropertyIndexDefinition("tesClass", "fOne", PropertyTypeInternal.INTEGER);

    final var map = propertyIndex.toMap(session);
    final var result = new PropertyIndexDefinition();
    result.fromMap(map);

    Assert.assertEquals(result, propertyIndex);
  }

  @Test
  public void testIndexReload() {
    final var map = propertyIndex.toMap(session);

    final var result = new PropertyIndexDefinition();
    result.fromMap(map);

    Assert.assertEquals(result, propertyIndex);
  }

  @Test
  public void testGetParamCount() {
    Assert.assertEquals(1, propertyIndex.getParamCount());
  }

  @Test
  public void testClassName() {
    Assert.assertEquals("testClass", propertyIndex.getClassName());
  }

  // ---- toCreateIndexDDL -----------------------------------------------------

  /**
   * Verifies that toCreateIndexDDL produces a valid CREATE INDEX DDL string containing
   * the index name, type, class, and property field.
   */
  @Test
  public void testToCreateIndexDDL() {
    var ddl = propertyIndex.toCreateIndexDDL("myIdx", "UNIQUE", null);
    Assert.assertTrue(ddl.contains("myIdx"));
    Assert.assertTrue(ddl.contains("UNIQUE"));
    Assert.assertTrue(ddl.contains("testClass"));
    Assert.assertTrue(ddl.contains("fOne"));
  }

  /**
   * Verifies that toCreateIndexDDL includes the engine name when provided.
   */
  @Test
  public void testToCreateIndexDDLWithEngine() {
    var ddl = propertyIndex.toCreateIndexDDL("myIdx", "UNIQUE", "LUCENE");
    Assert.assertTrue(ddl.contains("LUCENE"));
  }

  // ---- getFieldsToIndex with collate ----------------------------------------

  /**
   * Verifies that getFieldsToIndex returns just the field name when the collate is
   * the default (no collate suffix appended).
   */
  @Test
  public void testGetFieldsToIndexDefaultCollate() {
    var fields = propertyIndex.getFieldsToIndex();
    Assert.assertEquals(1, fields.size());
    Assert.assertEquals("fOne", fields.get(0));
  }

  /**
   * Verifies that getFieldsToIndex appends the collate name when a non-default collate
   * is configured, so the index manager can use the full field specifier.
   */
  @Test
  public void testGetFieldsToIndexWithCaseInsensitiveCollate() {
    propertyIndex.setCollate(SQLEngine.getCollate(CaseInsensitiveCollate.NAME));
    var fields = propertyIndex.getFieldsToIndex();
    Assert.assertEquals(1, fields.size());
    Assert.assertTrue("Field spec should include collate name",
        fields.get(0).contains(CaseInsensitiveCollate.NAME));
  }

  // ---- setCollate(String) ---------------------------------------------------

  /**
   * Verifies that setCollate(String) with null defaults to the DefaultCollate.
   */
  @Test
  public void testSetCollateStringNullDefaultsToDefault() {
    propertyIndex.setCollate((String) null);
    Assert.assertEquals(DefaultCollate.NAME, propertyIndex.getCollate().getName());
  }

  /**
   * Verifies that setCollate(String) with the CI collate name installs the CI collate.
   */
  @Test
  public void testSetCollateStringCaseInsensitive() {
    propertyIndex.setCollate(CaseInsensitiveCollate.NAME);
    Assert.assertEquals(CaseInsensitiveCollate.NAME, propertyIndex.getCollate().getName());
  }

  // ---- isAutomatic ----------------------------------------------------------

  /**
   * Verifies that PropertyIndexDefinition is automatic when bound to a schema class
   * property (it attaches to schema-driven indexing).
   */
  @Test
  public void testIsAutomatic() {
    Assert.assertTrue(propertyIndex.isAutomatic());
  }

  // ---- toString -------------------------------------------------------------

  /**
   * Verifies that toString returns a non-null string that contains the class name,
   * field, and key type.
   */
  @Test
  public void testToStringContainsFields() {
    var str = propertyIndex.toString();
    Assert.assertNotNull(str);
    Assert.assertTrue(str.contains("testClass"));
    Assert.assertTrue(str.contains("fOne"));
    Assert.assertTrue(str.contains("INTEGER"));
  }
}
