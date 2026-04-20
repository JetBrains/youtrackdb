/*
 *
 *
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

package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.Date;
import java.util.OptionalInt;
import org.junit.Test;

/**
 * Tests for {@link EntityImpl#isPropertyEqualTo(String, Object)} and
 * {@link EntityImpl#comparePropertyTo(String, Object)}. Covers both the deserialized (properties
 * map) path and the serialized (source bytes) path, plus guard conditions.
 *
 * <p>Entities are serialized via {@code toStream/fromStream} to create entities with {@code source}
 * bytes without requiring a full save/reload cycle.
 */
public class EntityImplInPlaceComparisonTest extends DbTestBase {

  /**
   * Creates a new entity with source bytes set but properties NOT deserialized.
   * Uses {@code RecordAbstract.fromStream(byte[])} which sets source + STATUS.LOADED
   * without calling deserialize(). This exercises the serialized (InPlaceComparator) path.
   */
  private EntityImpl serializeWithSourceOnly(EntityImpl entity) {
    var ser = session.getSerializer();
    var bytes = ser.toStream(session, entity);
    var reloaded = (EntityImpl) session.newEntity();
    reloaded.unsetDirty();
    reloaded.fromStream(bytes);
    return reloaded;
  }

  /**
   * Creates a new entity, serializes it, and fully deserializes it into a fresh entity.
   * Properties are populated in memory; source is cleared. This exercises the deserialized
   * (properties map) path.
   */
  private EntityImpl serializeAndReload(EntityImpl entity) {
    var ser = session.getSerializer();
    var bytes = ser.toStream(session, entity);
    var reloaded = (EntityImpl) session.newEntity();
    ser.fromStream(session, bytes, reloaded, null);
    return reloaded;
  }

  // ===========================================================================
  // Serialized path — entity has source bytes, properties not yet deserialized
  // ===========================================================================

  /** isPropertyEqualTo against serialized source bytes — matching and non-matching. */
  @Test
  public void testIsPropertyEqualToFromSource() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");
    entity.setProperty("age", 30, PropertyType.INTEGER);

    var loaded = serializeWithSourceOnly(entity);
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("name", "Alice"));
    assertEquals(InPlaceResult.FALSE, loaded.isPropertyEqualTo("name", "Bob"));
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("age", 30));
    assertEquals(InPlaceResult.FALSE, loaded.isPropertyEqualTo("age", 99));
    session.rollback();
  }

  /** comparePropertyTo against serialized source bytes — equal, greater, less. */
  @Test
  public void testComparePropertyToFromSource() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("score", 100L, PropertyType.LONG);

    var loaded = serializeWithSourceOnly(entity);
    assertEquals(OptionalInt.of(0), loaded.comparePropertyTo("score", 100L));
    assertTrue(loaded.comparePropertyTo("score", 50L).getAsInt() > 0);
    assertTrue(loaded.comparePropertyTo("score", 200L).getAsInt() < 0);
    session.rollback();
  }

  // ===========================================================================
  // Deserialized path — properties already in memory
  // ===========================================================================

  /** After accessing a property via getProperty(), the comparison uses the deserialized path. */
  @Test
  public void testIsPropertyEqualToFromPropertiesMap() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");
    entity.setProperty("age", 30, PropertyType.INTEGER);

    var loaded = serializeAndReload(entity);
    // Trigger deserialization by reading a property
    loaded.getProperty("name");

    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("name", "Alice"));
    assertEquals(InPlaceResult.FALSE, loaded.isPropertyEqualTo("name", "Bob"));
    session.rollback();
  }

  /** comparePropertyTo from deserialized properties. */
  @Test
  public void testComparePropertyToFromPropertiesMap() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("score", 100L, PropertyType.LONG);

    var loaded = serializeAndReload(entity);
    // Trigger deserialization
    loaded.getProperty("score");

    assertEquals(OptionalInt.of(0), loaded.comparePropertyTo("score", 100L));
    assertTrue(loaded.comparePropertyTo("score", 50L).getAsInt() > 0);
    session.rollback();
  }

  // ===========================================================================
  // Null handling
  // ===========================================================================

  /** Null comparison value — should fall back. */
  @Test
  public void testNullValueFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");

    var loaded = serializeWithSourceOnly(entity);
    assertEquals(InPlaceResult.FALLBACK, loaded.isPropertyEqualTo("name", null));
    assertTrue(loaded.comparePropertyTo("name", null).isEmpty());
    session.rollback();
  }

  /** Non-existent property — should fall back via source path (deserializeField returns null). */
  @Test
  public void testNonExistentPropertyFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");

    var loaded = serializeWithSourceOnly(entity);
    assertEquals(InPlaceResult.FALLBACK, loaded.isPropertyEqualTo("nonExistent", "value"));
    assertTrue(loaded.comparePropertyTo("nonExistent", "value").isEmpty());
    session.rollback();
  }

  // ===========================================================================
  // Multiple types
  // ===========================================================================

  /** Test various property types through the source path. */
  @Test
  public void testMultiplePropertyTypes() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("str", "hello");
    entity.setProperty("intVal", 42, PropertyType.INTEGER);
    entity.setProperty("longVal", 999L, PropertyType.LONG);
    entity.setProperty("dblVal", 3.14, PropertyType.DOUBLE);
    entity.setProperty("boolVal", true, PropertyType.BOOLEAN);
    entity.setProperty("dateVal", new Date(1700000000000L), PropertyType.DATETIME);

    var loaded = serializeWithSourceOnly(entity);
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("str", "hello"));
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("intVal", 42));
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("longVal", 999L));
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("dblVal", 3.14));
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("boolVal", true));
    assertEquals(InPlaceResult.FALSE, loaded.isPropertyEqualTo("boolVal", false));
    assertEquals(InPlaceResult.TRUE,
        loaded.isPropertyEqualTo("dateVal", new Date(1700000000000L)));
    session.rollback();
  }

  // ===========================================================================
  // Cross-type numeric conversion
  // ===========================================================================

  /** INTEGER property compared with Long value — should work via type conversion. */
  @Test
  public void testCrossTypeNumericComparison() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("intVal", 42, PropertyType.INTEGER);

    var loaded = serializeWithSourceOnly(entity);
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("intVal", 42L));
    assertEquals(InPlaceResult.FALSE, loaded.isPropertyEqualTo("intVal", 99L));
    session.rollback();
  }

  // ===========================================================================
  // Entity without source (new, unsaved)
  // ===========================================================================

  /** New entity with properties but no source — comparison uses deserialized path. */
  @Test
  public void testNewEntityNoSource() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");
    entity.setProperty("age", 30, PropertyType.INTEGER);

    assertEquals(InPlaceResult.TRUE, entity.isPropertyEqualTo("name", "Alice"));
    assertEquals(InPlaceResult.FALSE, entity.isPropertyEqualTo("name", "Bob"));
    assertEquals(InPlaceResult.TRUE, entity.isPropertyEqualTo("age", 30));
    session.rollback();
  }

  /** New entity — comparePropertyTo via deserialized path. */
  @Test
  public void testNewEntityComparePropertyTo() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("score", 100L, PropertyType.LONG);

    assertEquals(OptionalInt.of(0), entity.comparePropertyTo("score", 100L));
    assertTrue(entity.comparePropertyTo("score", 50L).getAsInt() > 0);
    session.rollback();
  }

  // ===========================================================================
  // String ordering
  // ===========================================================================

  // ===========================================================================
  // Status guard: entity must be in LOADED status
  // ===========================================================================

  /**
   * After mutating a loaded entity, the properties map has the updated value, so the
   * deserialized path returns the correct result based on the in-memory data. This verifies
   * that mutations are reflected in comparison results — the comparison uses the properties
   * map (which has the new value), not the stale source bytes.
   */
  @Test
  public void testMutatedEntityUsesPropertiesMap() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");

    var loaded = serializeWithSourceOnly(entity);
    // Verify it works via source path before mutation
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("name", "Alice"));

    // Mutate — properties map is populated with new value
    loaded.setProperty("name", "Bob");

    // Comparison uses properties map (with "Bob"), not stale source (with "Alice")
    assertEquals(InPlaceResult.FALSE, loaded.isPropertyEqualTo("name", "Alice"));
    assertEquals(InPlaceResult.TRUE, loaded.isPropertyEqualTo("name", "Bob"));
    session.rollback();
  }

  // ===========================================================================
  // Null property value in properties map
  // ===========================================================================

  /**
   * When a property exists in the map but has a null value, comparison must return FALLBACK
   * to delegate null handling to the standard SQL NULL semantics.
   */
  @Test
  public void testNullPropertyValueInMapReturnsFallback() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Alice");
    entity.setProperty("nullProp", (String) null);

    // New entity — properties are in the map, no serialization
    assertEquals(InPlaceResult.FALLBACK,
        entity.isPropertyEqualTo("nullProp", "anything"));
    assertTrue(entity.comparePropertyTo("nullProp", "anything").isEmpty());
    session.rollback();
  }

  // ===========================================================================
  // String ordering
  // ===========================================================================

  /** String ordering via comparePropertyTo. */
  @Test
  public void testStringOrdering() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("name", "Bob");

    var loaded = serializeWithSourceOnly(entity);
    assertTrue(loaded.comparePropertyTo("name", "Alice").getAsInt() > 0);
    assertTrue(loaded.comparePropertyTo("name", "Charlie").getAsInt() < 0);
    assertEquals(OptionalInt.of(0), loaded.comparePropertyTo("name", "Bob"));
    session.rollback();
  }

  // ===========================================================================
  // Collation — non-default collation must cause FALLBACK on deserialized path
  // ===========================================================================

  /**
   * Regression test: compareDeserialized must return FALLBACK for CI-collated properties
   * so the caller falls through to the collation-aware standard path. Before the fix,
   * compareDeserialized did a raw String.compareTo, returning FALSE for "Alice" vs "alice".
   */
  @Test
  public void testDeserializedPathReturnsFallbackForCiCollation() {
    // Schema changes must happen outside a transaction
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CiCollateEqTest");
    clazz.createProperty("name", PropertyType.STRING).setCollate("ci");

    // Insert via SQL so the record is properly schema-bound
    session.begin();
    session.execute("INSERT INTO CiCollateEqTest SET name = 'Alice'");
    session.commit();

    // Reload from DB, then force deserialization into properties map
    session.begin();
    var loaded = (EntityImpl) session.query("SELECT FROM CiCollateEqTest").next().asEntity();
    loaded.getProperty("name"); // force deserialized (properties map) path
    // Must return FALLBACK, not TRUE or FALSE, because CI collation
    // cannot be applied by in-place comparison
    assertEquals(InPlaceResult.FALLBACK, loaded.isPropertyEqualTo("name", "alice"));
    assertEquals(InPlaceResult.FALLBACK, loaded.isPropertyEqualTo("name", "ALICE"));
    session.rollback();
  }

  /**
   * Regression test: compareDeserializedOrdering must return empty for CI-collated properties
   * so ordering comparisons fall back to the collation-aware standard path.
   */
  @Test
  public void testDeserializedOrderingPathReturnsEmptyForCiCollation() {
    // Schema changes must happen outside a transaction
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("CiCollateOrdTest");
    clazz.createProperty("name", PropertyType.STRING).setCollate("ci");

    // Insert via SQL so the record is properly schema-bound
    session.begin();
    session.execute("INSERT INTO CiCollateOrdTest SET name = 'Bob'");
    session.commit();

    // Reload from DB, then force deserialization into properties map
    session.begin();
    var loaded = (EntityImpl) session.query("SELECT FROM CiCollateOrdTest").next().asEntity();
    loaded.getProperty("name"); // force deserialized (properties map) path
    // Must return empty so the caller uses the collation-aware comparator
    assertTrue(loaded.comparePropertyTo("name", "bob").isEmpty());
    assertTrue(loaded.comparePropertyTo("name", "BOB").isEmpty());
    session.rollback();
  }

  // ----- hasDeserializedProperty -----

  @Test
  public void testHasDeserializedPropertyFalseBeforeAccess() {
    // After loading a record from storage but before any getProperty call, the
    // properties map is null — callers must see false so they skip the in-place
    // fast path and fall through to getProperty, which performs the one
    // full deserialization that then populates the map for subsequent predicates.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("HasDeserPropFalse");
    clazz.createProperty("a", PropertyType.INTEGER);
    clazz.createProperty("b", PropertyType.STRING);

    session.begin();
    session.execute("INSERT INTO HasDeserPropFalse SET a = 1, b = 'x'");
    session.commit();

    session.begin();
    var loaded = (EntityImpl) session.query("SELECT FROM HasDeserPropFalse").next().asEntity();
    // No getProperty calls yet — properties map is unpopulated.
    assertFalse(loaded.hasDeserializedProperty("a"));
    assertFalse(loaded.hasDeserializedProperty("b"));
    session.rollback();
  }

  @Test
  public void testHasDeserializedPropertyTrueAfterGetProperty() {
    // getProperty populates the properties map for the accessed property — after
    // the call, the probe must return true so subsequent SQLBinaryCondition
    // evaluations on the same entity take the cheap compareDeserialized branch
    // instead of falling through again. Other, not-yet-accessed properties stay
    // uncached (per-property lazy deserialization) and the probe returns false
    // for them until someone reads them.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("HasDeserPropTrue");
    clazz.createProperty("a", PropertyType.INTEGER);
    clazz.createProperty("b", PropertyType.STRING);

    session.begin();
    session.execute("INSERT INTO HasDeserPropTrue SET a = 1, b = 'x'");
    session.commit();

    session.begin();
    var loaded = (EntityImpl) session.query("SELECT FROM HasDeserPropTrue").next().asEntity();
    loaded.getProperty("a");
    assertTrue(loaded.hasDeserializedProperty("a"));
    // b is uncached because we never read it — hasDeserializedProperty is
    // per-property, matching EntityImpl.properties map granularity.
    assertFalse(loaded.hasDeserializedProperty("b"));

    // After reading b explicitly, the probe must flip to true.
    loaded.getProperty("b");
    assertTrue(loaded.hasDeserializedProperty("b"));
    session.rollback();
  }

  @Test
  public void testHasDeserializedPropertyFalseForMissingProperty() {
    // Probing for a property name that does not exist on the schema must return
    // false even after other properties have been deserialized — the caller uses
    // this to decide between fast-path and fallback per-property.
    var schema = session.getMetadata().getSchema();
    var clazz = schema.createClass("HasDeserPropMissing");
    clazz.createProperty("a", PropertyType.INTEGER);

    session.begin();
    session.execute("INSERT INTO HasDeserPropMissing SET a = 1");
    session.commit();

    session.begin();
    var loaded = (EntityImpl) session.query("SELECT FROM HasDeserPropMissing").next().asEntity();
    loaded.getProperty("a");
    assertFalse(loaded.hasDeserializedProperty("nonexistent"));
    session.rollback();
  }
}
