package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link IndexDefinitionFactory}: field name extraction and index definition
 * creation for single-field and multi-field cases including map/list/linkbag variants.
 *
 * <p>Schema operations (createClass / createProperty) must be performed outside a
 * transaction — they are not transactional and will throw if a tx is active.
 */
public class IndexDefinitionFactoryTest extends DbTestBase {

  private Schema schema;

  @Before
  public void setUp() {
    // Do NOT call session.begin() here — schema changes are non-transactional.
    schema = session.getMetadata().getSchema();
  }

  // ---- extractFieldName -----------------------------------------------------

  /**
   * Verifies that extractFieldName returns the field name unchanged when the definition
   * string contains only the property name (no "by key/value" specifier).
   */
  @Test
  public void testExtractFieldNameSimple() {
    assertEquals("myField", IndexDefinitionFactory.extractFieldName("myField"));
  }

  /**
   * Verifies that extractFieldName extracts the property name from a "field by key" specifier,
   * dropping the "by key" suffix.
   */
  @Test
  public void testExtractFieldNameByKey() {
    assertEquals("myMap", IndexDefinitionFactory.extractFieldName("myMap by key"));
  }

  /**
   * Verifies that extractFieldName extracts the property name from a "field by value" specifier,
   * dropping the "by value" suffix.
   */
  @Test
  public void testExtractFieldNameByValue() {
    assertEquals("myMap", IndexDefinitionFactory.extractFieldName("myMap by value"));
  }

  /**
   * Verifies that extractFieldName reassembles a multi-token field name that has no "by"
   * specifier. Java's Pattern.split with whitespace yields the individual tokens which are
   * then rejoined with single spaces.
   */
  @Test
  public void testExtractFieldNameWithInternalSpaces() {
    // "my field name" splits to ["my","field","name"] — no "by", so all tokens are reassembled.
    var result = IndexDefinitionFactory.extractFieldName("my field name");
    assertEquals("my field name", result);
  }

  /**
   * Verifies that extractFieldName with an illegal "by X" specifier (where X is not
   * "key" or "value") falls through to the reassembly path rather than using the
   * 3-token "by" branch (the branch only matches when fieldNameParts[1].equalsIgnoreCase("by")).
   * A four-token definition like "field1 by badValue extra" reassembles as-is.
   */
  @Test
  public void testExtractFieldNameMultiTokenNoBySpecifier() {
    // Four tokens — "by" check requires exactly 3 tokens, so this falls through to reassembly.
    var result = IndexDefinitionFactory.extractFieldName("field1 extra1 extra2 extra3");
    assertEquals("field1 extra1 extra2 extra3", result);
  }

  // ---- createIndexDefinition — single field, plain property -----------------

  /**
   * Verifies that createIndexDefinition produces a PropertyIndexDefinition for a single
   * INTEGER field on a class that has that property defined. Schema ops run outside a tx.
   */
  @Test
  public void testCreateIndexDefinitionSingleIntegerField() {
    var cls = schema.createClass("TestFactorySingleInt");
    cls.createProperty("age", PropertyType.INTEGER);

    var def = IndexDefinitionFactory.createIndexDefinition(
        cls,
        Collections.singletonList("age"),
        Collections.singletonList(PropertyTypeInternal.INTEGER),
        null,
        "UNIQUE");

    assertTrue("Should produce a PropertyIndexDefinition",
        def instanceof PropertyIndexDefinition);
    assertEquals("TestFactorySingleInt", def.getClassName());
    assertEquals(Collections.singletonList("age"), def.getProperties());
  }

  /**
   * Verifies that createIndexDefinition produces a PropertyMapIndexDefinition when
   * the field is specified with "by key" and its type is EMBEDDEDMAP.
   */
  @Test
  public void testCreateIndexDefinitionMapByKey() {
    var cls = schema.createClass("TestFactoryMapKey");
    cls.createProperty("tags", PropertyType.EMBEDDEDMAP, PropertyType.STRING);

    var def = IndexDefinitionFactory.createIndexDefinition(
        cls,
        Collections.singletonList("tags by key"),
        Collections.singletonList(PropertyTypeInternal.EMBEDDEDMAP),
        null,
        "NOTUNIQUE");

    assertTrue("Should produce a PropertyMapIndexDefinition",
        def instanceof PropertyMapIndexDefinition);
  }

  /**
   * Verifies that createIndexDefinition produces a PropertyListIndexDefinition when
   * the field type is EMBEDDEDLIST with a linked-type STRING property.
   */
  @Test
  public void testCreateIndexDefinitionEmbeddedList() {
    var cls = schema.createClass("TestFactoryList");
    cls.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);

    var def = IndexDefinitionFactory.createIndexDefinition(
        cls,
        Collections.singletonList("tags"),
        Collections.singletonList(PropertyTypeInternal.EMBEDDEDLIST),
        null,
        "NOTUNIQUE");

    assertTrue("Should produce a PropertyListIndexDefinition",
        def instanceof PropertyListIndexDefinition);
  }

  // ---- createIndexDefinition — multi-field composite ------------------------

  /**
   * Verifies that createIndexDefinition produces a CompositeIndexDefinition when two
   * fields are provided, containing a sub-definition per field.
   */
  @Test
  public void testCreateIndexDefinitionMultiField() {
    var cls = schema.createClass("TestFactoryComposite");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("age", PropertyType.INTEGER);

    var def = IndexDefinitionFactory.createIndexDefinition(
        cls,
        Arrays.asList("name", "age"),
        Arrays.asList(PropertyTypeInternal.STRING, PropertyTypeInternal.INTEGER),
        null,
        "UNIQUE");

    assertTrue("Multi-field should produce CompositeIndexDefinition",
        def instanceof CompositeIndexDefinition);
    var props = def.getProperties();
    assertEquals(2, props.size());
    assertTrue(props.contains("name"));
    assertTrue(props.contains("age"));
  }

  // ---- createIndexDefinition — field/type count mismatch --------------------

  /**
   * Verifies that createIndexDefinition throws IllegalArgumentException when the number
   * of fields does not match the number of types, covering the checkTypes guard.
   * The class has no properties defined so the second field "age" is unknown and the
   * count mismatch (2 fields, 1 type) triggers the guard before the property-type check.
   */
  @Test(expected = IllegalArgumentException.class)
  public void testCreateIndexDefinitionFieldTypeMismatchThrows() {
    var cls = schema.createClass("TestFactoryMismatch");
    cls.createProperty("name", PropertyType.STRING);

    // Two fields but only one type — checkTypes must throw IllegalArgumentException.
    IndexDefinitionFactory.createIndexDefinition(
        cls,
        Arrays.asList("name", "age"),
        Collections.singletonList(PropertyTypeInternal.STRING),
        null,
        "UNIQUE");
  }

  // ---- createSingleFieldIndexDefinition — LINKBAG variants ------------------

  /**
   * Verifies that createSingleFieldIndexDefinition produces a
   * PropertyLinkBagSecondaryIndexDefinition when the LINKBAG field is indexed BY VALUE.
   */
  @Test
  public void testCreateSingleFieldIndexDefinitionLinkBagByValue() {
    var def = IndexDefinitionFactory.createSingleFieldIndexDefinition(
        "TestClass", "edges",
        PropertyTypeInternal.LINKBAG,
        null, // linkedType
        null, // collate
        "UNIQUE",
        PropertyMapIndexDefinition.INDEX_BY.VALUE);

    assertNotNull(def);
    assertTrue("LINKBAG by value must produce PropertyLinkBagSecondaryIndexDefinition",
        def instanceof PropertyLinkBagSecondaryIndexDefinition);
  }

  /**
   * Verifies that createSingleFieldIndexDefinition produces a
   * PropertyLinkBagIndexDefinition when the LINKBAG field is indexed BY KEY (primary RID).
   */
  @Test
  public void testCreateSingleFieldIndexDefinitionLinkBagByKey() {
    var def = IndexDefinitionFactory.createSingleFieldIndexDefinition(
        "TestClass", "edges",
        PropertyTypeInternal.LINKBAG,
        null, // linkedType
        null, // collate
        "UNIQUE",
        PropertyMapIndexDefinition.INDEX_BY.KEY);

    assertNotNull(def);
    assertTrue("LINKBAG by key must produce PropertyLinkBagIndexDefinition",
        def instanceof PropertyLinkBagIndexDefinition);
  }
}
