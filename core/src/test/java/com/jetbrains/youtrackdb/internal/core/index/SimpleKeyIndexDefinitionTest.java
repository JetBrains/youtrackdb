package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SimpleKeyIndexDefinitionTest extends DbTestBase {

  private SimpleKeyIndexDefinition simpleKeyIndexDefinition;

  @Before
  public void beforeMethod() {
    session.begin();
    simpleKeyIndexDefinition = new SimpleKeyIndexDefinition(PropertyTypeInternal.INTEGER,
        PropertyTypeInternal.STRING);
  }

  @After
  public void afterMethod() {
    session.rollback();
  }

  @Test
  public void testGetProperties() {
    Assert.assertTrue(simpleKeyIndexDefinition.getProperties().isEmpty());
  }

  @Test
  public void testGetClassName() {
    Assert.assertNull(simpleKeyIndexDefinition.getClassName());
  }

  @Test
  public void testCreateValueSimpleKey() {
    final var keyIndexDefinition =
        new SimpleKeyIndexDefinition(PropertyTypeInternal.INTEGER);
    final var result = keyIndexDefinition.createValue(session.getActiveTransaction(), "2");
    Assert.assertEquals(2, result);
  }

  @Test
  public void testCreateValueCompositeKeyListParam() {
    final var result = simpleKeyIndexDefinition.createValue(session.getActiveTransaction(),
        Arrays.asList("2", "3"));

    final var compositeKey = new CompositeKey(Arrays.asList(2, "3"));
    Assert.assertEquals(result, compositeKey);
  }

  @Test
  public void testCreateValueCompositeKeyNullListParam() {
    final var result =
        simpleKeyIndexDefinition.createValue(session.getActiveTransaction(),
            Collections.singletonList(null));

    Assert.assertNull(result);
  }

  @Test
  public void testNullParamListItem() {
    final var result = simpleKeyIndexDefinition.createValue(session.getActiveTransaction(),
        Arrays.asList("2", null));

    Assert.assertNull(result);
  }

  @Test(expected = NumberFormatException.class)
  public void testWrongParamTypeListItem() {
    simpleKeyIndexDefinition.createValue(session.getActiveTransaction(), Arrays.asList("a", "3"));
  }

  @Test
  public void testCreateValueCompositeKey() {
    final var result = simpleKeyIndexDefinition.createValue(session.getActiveTransaction(), "2",
        "3");

    final var compositeKey = new CompositeKey(Arrays.asList(2, "3"));
    Assert.assertEquals(result, compositeKey);
  }

  @Test
  public void testCreateValueCompositeKeyNullParamList() {
    final var result = simpleKeyIndexDefinition.createValue(session.getActiveTransaction(),
        (List<?>) null);

    Assert.assertNull(result);
  }

  @Test
  public void testCreateValueCompositeKeyNullParam() {
    final var result = simpleKeyIndexDefinition.createValue(session.getActiveTransaction(),
        (Object) null);

    Assert.assertNull(result);
  }

  @Test
  public void testCreateValueCompositeKeyEmptyList() {
    final var result = simpleKeyIndexDefinition.createValue(session.getActiveTransaction(),
        Collections.emptyList());

    Assert.assertNull(result);
  }

  @Test
  public void testNullParamItem() {
    final var result = simpleKeyIndexDefinition.createValue(session.getActiveTransaction(), "2",
        null);

    Assert.assertNull(result);
  }

  @Test(expected = NumberFormatException.class)
  public void testWrongParamType() {
    simpleKeyIndexDefinition.createValue(session.getActiveTransaction(), "a", "3");
  }

  @Test
  public void testParamCount() {
    Assert.assertEquals(2, simpleKeyIndexDefinition.getParamCount());
  }

  @Test
  public void testParamCountOneItem() {
    final var keyIndexDefinition =
        new SimpleKeyIndexDefinition(PropertyTypeInternal.INTEGER);

    Assert.assertEquals(1, keyIndexDefinition.getParamCount());
  }

  @Test
  public void testGetKeyTypes() {
    Assert.assertEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING},
        simpleKeyIndexDefinition.getTypes());
  }

  @Test
  public void testGetKeyTypesOneType() {
    final var keyIndexDefinition =
        new SimpleKeyIndexDefinition(PropertyTypeInternal.BOOLEAN);

    Assert.assertEquals(new PropertyTypeInternal[] {PropertyTypeInternal.BOOLEAN},
        keyIndexDefinition.getTypes());
  }

  @Test
  public void testReload() {
    final var map = simpleKeyIndexDefinition.toMap(session);
    final var loadedKeyIndexDefinition = new SimpleKeyIndexDefinition();

    loadedKeyIndexDefinition.fromMap(map);
    Assert.assertEquals(loadedKeyIndexDefinition, simpleKeyIndexDefinition);
  }

  @Test(expected = IndexException.class)
  public void testGetDocumentValueToIndex() {
    session.begin();
    simpleKeyIndexDefinition.getDocumentValueToIndex(session.getActiveTransaction(),
        (EntityImpl) session.newEntity());
    session.rollback();
  }

  // ---- isAutomatic ----------------------------------------------------------

  /**
   * Verifies that SimpleKeyIndexDefinition is never automatic — it is a manual
   * (programmer-defined) index that does not attach to a schema class property.
   */
  @Test
  public void testIsAutomaticReturnsFalse() {
    Assert.assertFalse(simpleKeyIndexDefinition.isAutomatic());
  }

  // ---- getFieldsToIndex -----------------------------------------------------

  /**
   * Verifies that getFieldsToIndex returns an empty list, because SimpleKeyIndexDefinition
   * is not bound to any schema property field.
   */
  @Test
  public void testGetFieldsToIndexIsEmpty() {
    Assert.assertTrue(simpleKeyIndexDefinition.getFieldsToIndex().isEmpty());
  }

  // ---- toCreateIndexDDL -----------------------------------------------------

  /**
   * Verifies that toCreateIndexDDL produces the correct DDL for a composite key definition
   * with two types. The DDL must list both types separated by a comma.
   */
  @Test
  public void testToCreateIndexDDLTwoTypes() {
    var ddl = simpleKeyIndexDefinition.toCreateIndexDDL("myIndex", "UNIQUE", null);
    Assert.assertTrue(ddl.contains("myIndex"));
    Assert.assertTrue(ddl.contains("UNIQUE"));
    Assert.assertTrue(ddl.contains("INTEGER"));
    Assert.assertTrue(ddl.contains("STRING"));
  }

  /**
   * Verifies that toCreateIndexDDL works for a single-type definition.
   */
  @Test
  public void testToCreateIndexDDLOneType() {
    var singleType = new SimpleKeyIndexDefinition(PropertyTypeInternal.LONG);
    var ddl = singleType.toCreateIndexDDL("idx", "NOTUNIQUE", null);
    Assert.assertTrue(ddl.contains("idx"));
    Assert.assertTrue(ddl.contains("NOTUNIQUE"));
    Assert.assertTrue(ddl.contains("LONG"));
  }

  // ---- withCollates constructor ---------------------------------------------

  /**
   * Verifies the constructor that accepts a collate list produces a CompositeCollate
   * with the supplied collates and that roundtrip serialization preserves it.
   */
  @Test
  public void testConstructorWithCollatesRoundtrip() {
    var collates = Arrays.asList(
        SQLEngine.getCollate(CaseInsensitiveCollate.NAME),
        SQLEngine.getCollate(DefaultCollate.NAME));
    var def = new SimpleKeyIndexDefinition(
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.INTEGER},
        collates);

    Assert.assertTrue(def.getCollate() instanceof CompositeCollate);

    // Roundtrip: serialize to map and deserialize — collates must survive
    var map = def.toMap(session);
    var restored = new SimpleKeyIndexDefinition();
    restored.fromMap(map);
    Assert.assertEquals(def, restored);
  }

  /**
   * Verifies that the constructor with a null collate list falls back to default collates
   * for each key type, producing a valid CompositeCollate.
   */
  @Test
  public void testConstructorWithNullCollatesUsesDefaults() {
    var def = new SimpleKeyIndexDefinition(
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING, PropertyTypeInternal.INTEGER},
        null);
    Assert.assertTrue(def.getCollate() instanceof CompositeCollate);
  }

  // ---- setCollate null guard (AbstractIndexDefinition) ----------------------

  /**
   * Verifies that setCollate(Collate) rejects null with an IllegalArgumentException,
   * covering the null guard in AbstractIndexDefinition.
   */
  @Test(expected = IllegalArgumentException.class)
  public void testSetCollateNullThrows() {
    // Cast disambiguates between setCollate(Collate) and setCollate(String) overloads.
    simpleKeyIndexDefinition.setCollate((Collate) null);
  }

  // ---- setNullValuesIgnored / isNullValuesIgnored ----------------------------

  /**
   * Verifies that setNullValuesIgnored toggles the flag correctly and isNullValuesIgnored
   * reflects the new state.
   */
  @Test
  public void testSetNullValuesIgnoredToggle() {
    // Default is true
    Assert.assertTrue(simpleKeyIndexDefinition.isNullValuesIgnored());
    simpleKeyIndexDefinition.setNullValuesIgnored(false);
    Assert.assertFalse(simpleKeyIndexDefinition.isNullValuesIgnored());
    simpleKeyIndexDefinition.setNullValuesIgnored(true);
    Assert.assertTrue(simpleKeyIndexDefinition.isNullValuesIgnored());
  }

  // ---- toString -------------------------------------------------------------

  /**
   * Verifies that toString returns a non-null string that includes the key type names.
   */
  @Test
  public void testToStringContainsKeyTypes() {
    var str = simpleKeyIndexDefinition.toString();
    Assert.assertNotNull(str);
    Assert.assertTrue(str.contains("INTEGER"));
    Assert.assertTrue(str.contains("STRING"));
  }
}
