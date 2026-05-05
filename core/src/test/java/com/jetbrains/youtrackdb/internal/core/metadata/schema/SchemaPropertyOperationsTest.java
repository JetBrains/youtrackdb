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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty.ATTRIBUTES;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * End-to-end behavioural coverage for the live property-mutation surface implemented by
 * {@link SchemaPropertyImpl} (read-side accessors, lock framing) and
 * {@link SchemaPropertyEmbedded} (mutators via {@code session.checkSecurity} → write-lock →
 * {@code …Internal} → release pattern). All assertions drive the public {@link SchemaProperty}
 * interface obtained from {@code session.getMetadata().getSchema()...} so that the dispatch
 * traverses the {@code SchemaClassProxy} / {@code SchemaPropertyProxy} → impl chain (Track 16
 * design note T3 — the proxy super-method-dispatch trap; PSI find-usages on individual proxy
 * methods returns 0 direct production callers because all calls flow through the public
 * interface).
 *
 * <p>This class deliberately complements {@link AlterSchemaPropertyTest}: that class covers
 * renaming, the linkmap-with-linked-type / linked-class rejections, removing a linked class via
 * SQL and direct API, the date-typed {@code max} validation on a list, alter-with-dot-quoted
 * names and the custom-attribute setter. The cases here cover the remaining live surface — the
 * boolean and string mutators, type-cast checks, custom-attribute set/remove/clear/keys lifecycle,
 * the ATTRIBUTES bulk get/set switch, the linked-class / linked-type validation predicates, and
 * the index-creation paths (delegated through {@code SchemaClassImpl.createIndex} via the
 * property-level overloads).
 *
 * <p>Per the R7 working note the schema reference is re-fetched via
 * {@code session.getMetadata().getSchema()} after each mutation rather than cached across a
 * transaction boundary; per the R8 working note any test that asserts on a fresh index reads
 * back through the immutable schema snapshot's {@code indexExists} predicate, which is
 * portable across the disk / memory storage modes.
 */
public class SchemaPropertyOperationsTest extends DbTestBase {

  @Test
  public void booleanFlagsRoundTripThroughSetters() {
    // Pin every boolean flag's setter+getter pair on SchemaPropertyEmbedded —
    // mandatory / readonly / notNull each toggle independently and persist to a re-fetch of the
    // property from the schema (no caching of the proxy across the assertion boundary).
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("BooleanFlags");
    var prop = cls.createProperty("flags", PropertyType.STRING);

    assertFalse("default mandatory must be false", prop.isMandatory());
    assertFalse("default readonly must be false", prop.isReadonly());
    assertFalse("default notNull must be false", prop.isNotNull());

    prop.setMandatory(true);
    prop.setReadonly(true);
    prop.setNotNull(true);

    var refetched = session.getMetadata().getSchema().getClass("BooleanFlags").getProperty("flags");
    assertTrue(refetched.isMandatory());
    assertTrue(refetched.isReadonly());
    assertTrue(refetched.isNotNull());

    refetched.setMandatory(false);
    refetched.setReadonly(false);
    refetched.setNotNull(false);
    assertFalse(refetched.isMandatory());
    assertFalse(refetched.isReadonly());
    assertFalse(refetched.isNotNull());
  }

  @Test
  public void minMaxRegexpDefaultDescriptionRoundTrip() {
    // Pin the four String-valued limit/regex/default/description setters on
    // SchemaPropertyEmbedded — each writes through to the underlying field and is observable via
    // the matching getter. Min/Max are validated through checkCorrectLimitValue so we use a
    // numeric type to exercise the BYTE/SHORT/INTEGER/... arm of that switch.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("Bounds");
    var numericProp = cls.createProperty("count", PropertyType.INTEGER);

    numericProp.setMin("0");
    numericProp.setMax("100");
    numericProp.setDefaultValue("42");
    numericProp.setDescription("a bounded count attribute");
    assertEquals("0", numericProp.getMin());
    assertEquals("100", numericProp.getMax());
    assertEquals("42", numericProp.getDefaultValue());
    assertEquals("a bounded count attribute", numericProp.getDescription());

    var stringProp = cls.createProperty("name", PropertyType.STRING);
    stringProp.setRegexp("[A-Za-z]+");
    stringProp.setMin("3");
    stringProp.setMax("32");
    assertEquals("[A-Za-z]+", stringProp.getRegexp());
    assertEquals("3", stringProp.getMin());
    assertEquals("32", stringProp.getMax());

    // Setting back to null clears each value.
    stringProp.setRegexp(null);
    stringProp.setMin(null);
    stringProp.setMax(null);
    stringProp.setDescription(null);
    assertNull(stringProp.getRegexp());
    assertNull(stringProp.getMin());
    assertNull(stringProp.getMax());
    assertNull(stringProp.getDescription());
  }

  @Test
  public void minMaxRejectInvalidNumericString() {
    // checkCorrectLimitValue routes through PropertyTypeInternal.convert(...) for numeric
    // types — a non-numeric string for an INTEGER property must propagate the convert exception
    // rather than silently storing the bad value.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("BadBounds");
    var prop = cls.createProperty("n", PropertyType.INTEGER);
    try {
      prop.setMin("not-a-number");
      fail("setMin should reject non-numeric string for INTEGER property");
    } catch (RuntimeException expected) {
      // accepted: any RuntimeException — convert(...) wraps the underlying NumberFormatException
    }
    // The bad value must NOT have been stored when the validator threw before the write lock.
    assertNull(prop.getMin());
  }

  @Test
  public void collateLifecycle() {
    // Set a non-default collate, observe getCollate() reflect it, switch back to default via the
    // null-coercion path inside SchemaPropertyEmbedded.setCollate (collate==null becomes
    // DefaultCollate.NAME). Both arms of the conditional in setCollateInternal are exercised.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("Collated");
    var prop = cls.createProperty("text", PropertyType.STRING);

    assertEquals(DefaultCollate.NAME, prop.getCollate().getName());
    prop.setCollate("ci");
    assertEquals("ci", prop.getCollate().getName());
    prop.setCollate((String) null);
    assertEquals(DefaultCollate.NAME, prop.getCollate().getName());
  }

  @Test
  public void customAttributesSetGetRemoveClear() {
    // Walk the full custom-attribute lifecycle — initial state is null/empty, set populates the
    // map, getCustomKeys returns the set view, setCustom(name, null) and removeCustom both
    // delete the entry, clearCustom drops the whole map.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("Customised");
    var prop = cls.createProperty("p", PropertyType.STRING);

    // Pre-mutation: getCustom returns null on missing key, getCustomKeys returns an empty set.
    assertNull(prop.getCustom("absent"));
    assertTrue("custom keys empty before any setCustom", prop.getCustomKeys().isEmpty());

    prop.setCustom("k1", "v1");
    prop.setCustom("k2", "v2");
    assertEquals("v1", prop.getCustom("k1"));
    assertEquals("v2", prop.getCustom("k2"));
    assertTrue(prop.getCustomKeys().contains("k1"));
    assertTrue(prop.getCustomKeys().contains("k2"));

    // setCustom(name, null) removes the entry — the SchemaPropertyEmbedded.setCustomInternal
    // null-or-"null" branch.
    prop.setCustom("k1", null);
    assertNull(prop.getCustom("k1"));
    assertFalse(prop.getCustomKeys().contains("k1"));

    // removeCustom delegates to setCustom(name, null).
    prop.removeCustom("k2");
    assertNull(prop.getCustom("k2"));
    assertTrue(prop.getCustomKeys().isEmpty());

    // clearCustom on a populated map nulls the whole field.
    prop.setCustom("k3", "v3");
    assertEquals(1, prop.getCustomKeys().size());
    prop.clearCustom();
    assertTrue("clearCustom drops every key", prop.getCustomKeys().isEmpty());
    assertNull(prop.getCustom("k3"));
  }

  @Test
  public void getAttributesSwitchExercisesEveryArm() {
    // The bulk-get path SchemaPropertyImpl.get(ATTRIBUTES) is a 13-arm switch — drive every arm
    // by populating the matching field first.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("Attrs");
    var linked = schema.createClass("AttrsLinked");
    var prop = cls.createProperty("p", PropertyType.LINKLIST, linked);
    prop.setMin("0");
    prop.setMax("10");
    prop.setMandatory(true);
    prop.setReadonly(true);
    prop.setDefaultValue("d");
    prop.setNotNull(true);
    prop.setRegexp("rx");
    prop.setDescription("desc");
    prop.setCollate("ci");

    assertNotNull(prop.get(ATTRIBUTES.LINKEDCLASS));
    // LINKLIST does not support a linked type — leave LINKEDTYPE null and assert that.
    assertNull(prop.get(ATTRIBUTES.LINKEDTYPE));
    assertEquals("0", prop.get(ATTRIBUTES.MIN));
    assertEquals(true, prop.get(ATTRIBUTES.MANDATORY));
    assertEquals(true, prop.get(ATTRIBUTES.READONLY));
    assertEquals("10", prop.get(ATTRIBUTES.MAX));
    assertEquals("d", prop.get(ATTRIBUTES.DEFAULT));
    assertEquals("p", prop.get(ATTRIBUTES.NAME));
    assertEquals(true, prop.get(ATTRIBUTES.NOTNULL));
    assertEquals("rx", prop.get(ATTRIBUTES.REGEXP));
    assertEquals(PropertyType.LINKLIST, prop.get(ATTRIBUTES.TYPE));
    assertNotNull(prop.get(ATTRIBUTES.COLLATE));
    assertEquals("desc", prop.get(ATTRIBUTES.DESCRIPTION));
  }

  @Test
  public void getAttributesLinkedTypeExposedOnEmbeddedList() {
    // EMBEDDEDLIST supports a linked type — drive the LINKEDTYPE arm of the get(ATTRIBUTES)
    // switch on a property where setLinkedType(...) is allowed. Note: the live-property
    // get(LINKEDTYPE) returns the internal PropertyTypeInternal enum (it delegates to
    // getLinkedType() on SchemaPropertyImpl, which exposes the internal type), so the assertion
    // is against PropertyTypeInternal.STRING, not the public PropertyType.STRING.
    Schema schema = session.getMetadata().getSchema();
    var prop = schema.createClass("Attrs2").createProperty("p", PropertyType.EMBEDDEDLIST);
    prop.setLinkedType(PropertyType.STRING);
    assertEquals(PropertyTypeInternal.STRING, prop.get(ATTRIBUTES.LINKEDTYPE));
  }

  @Test
  public void getAttributesNullArgumentThrows() {
    // A null ATTRIBUTES argument hits the IllegalArgumentException at the top of get(...).
    Schema schema = session.getMetadata().getSchema();
    var prop = schema.createClass("AttrNull").createProperty("p", PropertyType.STRING);
    assertThrows(IllegalArgumentException.class, () -> prop.get(null));
  }

  @Test
  public void setAttributesSwitchRoundTripsEveryArm() {
    // The bulk-set path SchemaPropertyImpl.set(ATTRIBUTES, Object) routes string values through
    // the per-attribute setter — verify each arm reaches its corresponding *Internal mutator and
    // the value is observable via the getter.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("AttrsBulkSet");
    var linked = schema.createClass("AttrsBulkSetLinked");
    var prop = cls.createProperty("p", PropertyType.EMBEDDEDLIST);

    prop.set(ATTRIBUTES.LINKEDCLASS, "AttrsBulkSetLinked");
    prop.set(ATTRIBUTES.LINKEDTYPE, "STRING");
    prop.set(ATTRIBUTES.MIN, "1");
    prop.set(ATTRIBUTES.MAX, "9");
    prop.set(ATTRIBUTES.MANDATORY, "true");
    prop.set(ATTRIBUTES.READONLY, "true");
    prop.set(ATTRIBUTES.NOTNULL, "true");
    prop.set(ATTRIBUTES.REGEXP, "rx2");
    prop.set(ATTRIBUTES.DEFAULT, "d2");
    prop.set(ATTRIBUTES.DESCRIPTION, "desc2");
    prop.set(ATTRIBUTES.COLLATE, "ci");
    prop.set(ATTRIBUTES.NAME, "renamedP");

    assertEquals(linked.getName(), prop.getLinkedClass().getName());
    assertEquals(PropertyType.STRING, prop.getLinkedType());
    assertEquals("1", prop.getMin());
    assertEquals("9", prop.getMax());
    assertTrue(prop.isMandatory());
    assertTrue(prop.isReadonly());
    assertTrue(prop.isNotNull());
    assertEquals("rx2", prop.getRegexp());
    assertEquals("d2", prop.getDefaultValue());
    assertEquals("desc2", prop.getDescription());
    assertEquals("ci", prop.getCollate().getName());
    assertEquals("renamedP", prop.getName());
  }

  @Test
  public void setAttributesLinkedTypeNullClearsSetting() {
    // ATTRIBUTES.LINKEDTYPE with a null string value goes through the null-arm of the inner
    // switch (calls setLinkedType(session, null)) — pin the clear semantics.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("LinkedTypeNull");
    var prop = cls.createProperty("p", PropertyType.EMBEDDEDLIST);

    prop.setLinkedType(PropertyType.STRING);
    assertEquals(PropertyType.STRING, prop.getLinkedType());

    prop.set(ATTRIBUTES.LINKEDTYPE, null);
    assertNull(prop.getLinkedType());
  }

  @Test
  public void setAttributesCustomKeyValueAndClear() {
    // ATTRIBUTES.CUSTOM has its own internal parser — "name=value" sets, "clear" removes all.
    // Bad syntax must throw IllegalArgumentException.
    Schema schema = session.getMetadata().getSchema();
    var prop =
        schema.createClass("CustomViaSet").createProperty("p", PropertyType.STRING);

    prop.set(ATTRIBUTES.CUSTOM, "k1=v1");
    assertEquals("v1", prop.getCustom("k1"));

    // Quoted custom value strips the outer quotes.
    prop.set(ATTRIBUTES.CUSTOM, "k2=\"v2\"");
    assertEquals("v2", prop.getCustom("k2"));

    // Empty value after the '=' removes the entry.
    prop.set(ATTRIBUTES.CUSTOM, "k1=");
    assertNull(prop.getCustom("k1"));

    // The literal "clear" wipes every entry.
    prop.set(ATTRIBUTES.CUSTOM, "clear");
    assertTrue(prop.getCustomKeys().isEmpty());

    // Bad syntax (no '=' and not "clear") throws.
    assertThrows(IllegalArgumentException.class, () -> prop.set(ATTRIBUTES.CUSTOM, "no-equals"));
  }

  @Test
  public void setAttributesNullArgumentThrows() {
    Schema schema = session.getMetadata().getSchema();
    var prop = schema.createClass("AttrNullSet").createProperty("p", PropertyType.STRING);
    assertThrows(IllegalArgumentException.class, () -> prop.set(null, "x"));
  }

  @Test
  public void getOwnerClassAndIdAreStable() {
    // getOwnerClass / getId reach through the proxy directly to the underlying impl — verify the
    // values are non-null and self-consistent across the proxy boundary.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("Owners");
    var prop = cls.createProperty("p", PropertyType.STRING);
    assertNotNull("owner class must be visible from the property proxy", prop.getOwnerClass());
    assertEquals("Owners", prop.getOwnerClass().getName());
    assertNotNull("global property id must be assigned", prop.getId());
  }

  @Test
  public void setTypeCompatibleCastSucceedsAndIncompatibleRejects() {
    // setTypeInternal walks getCastable() — INTEGER → LONG is castable, INTEGER → LINK is not
    // (per PropertyTypeInternal's castable matrix). The compatible cast is observable via the
    // type getter; the incompatible cast throws IllegalArgumentException and leaves the type
    // unchanged.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("TypeCast");
    var prop = cls.createProperty("n", PropertyType.INTEGER);

    prop.setType(PropertyType.LONG);
    assertEquals(PropertyType.LONG, prop.getType());

    try {
      prop.setType(PropertyType.LINK);
      fail("incompatible setType must throw IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // accepted
    }
    assertEquals("type must remain LONG after rejected cast", PropertyType.LONG, prop.getType());

    // Setting the same type back is a no-op (the early return arm of setTypeInternal).
    prop.setType(PropertyType.LONG);
    assertEquals(PropertyType.LONG, prop.getType());
  }

  @Test
  public void linkedClassNullClearsAndCheckSupportLinkedClassRejectsBadType() {
    // setLinkedClass(null) clears the existing linked class. The static
    // checkSupportLinkedClass(...) precondition rejects non-link-or-embedded types — try setting
    // a linked class on a STRING-typed property to hit the SchemaException arm.
    Schema schema = session.getMetadata().getSchema();
    var owner = schema.createClass("LinkedClassClear");
    var linked = schema.createClass("LinkedClassClearTarget");
    var prop = owner.createProperty("link", PropertyType.LINK, linked);
    assertNotNull(prop.getLinkedClass());

    prop.setLinkedClass(null);
    assertNull(prop.getLinkedClass());

    // STRING does not support a linked class — the static check throws before the write lock.
    var stringProp = owner.createProperty("text", PropertyType.STRING);
    try {
      stringProp.setLinkedClass(linked);
      fail("setLinkedClass on STRING property must throw SchemaException");
    } catch (SchemaException expected) {
      // accepted
    }
  }

  @Test
  public void checkLinkTypeSupportRejectsBadType() {
    // setLinkedType is only valid on EMBEDDEDLIST / EMBEDDEDSET / EMBEDDEDMAP per
    // checkLinkTypeSupport(...). Setting it on a LINKLIST property hits the SchemaException arm.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("LinkTypeBad");
    var prop = cls.createProperty("p", PropertyType.LINKLIST);
    try {
      prop.setLinkedType(PropertyType.STRING);
      fail("setLinkedType on LINKLIST property must throw SchemaException");
    } catch (SchemaException expected) {
      // accepted
    }
  }

  @Test
  public void createIndexUniqueRoundTrip() {
    // The property-level createIndex(INDEX_TYPE) returns the full-name index id; the index is
    // observable via the immutable schema snapshot's indexExists predicate (R8 working note —
    // the snapshot is the portable assertion path across the disk / memory storage modes).
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("Idx");
    var prop = cls.createProperty("name", PropertyType.STRING);

    var indexName = prop.createIndex(INDEX_TYPE.UNIQUE);
    assertEquals("Idx.name", indexName);
    assertTrue("created index must be visible in the immutable schema snapshot",
        session.getMetadata().getImmutableSchemaSnapshot().indexExists("Idx.name"));
  }

  @Test
  public void createIndexWithMetadataRoundTrip() {
    // The property-level createIndex(String, Map) overload writes the metadata through to the
    // class-level helper and the index is reachable by name.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("IdxMeta");
    var prop = cls.createProperty("v", PropertyType.STRING);

    Map<String, Object> meta = new HashMap<>();
    meta.put("ignoreNullValues", Boolean.TRUE);
    var indexName = prop.createIndex("NOTUNIQUE", meta);
    assertEquals("IdxMeta.v", indexName);
    assertTrue(session.getMetadata().getImmutableSchemaSnapshot().indexExists("IdxMeta.v"));
  }

  @Test
  public void createIndexEnumWithMetadataRoundTrip() {
    // Pin the second metadata-bearing overload — createIndex(INDEX_TYPE, Map) — which delegates
    // to createIndex(String, Map) via INDEX_TYPE.name().
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("IdxMetaEnum");
    var prop = cls.createProperty("v", PropertyType.STRING);

    Map<String, Object> meta = new HashMap<>();
    meta.put("ignoreNullValues", Boolean.TRUE);
    var indexName = prop.createIndex(INDEX_TYPE.NOTUNIQUE, meta);
    assertEquals("IdxMetaEnum.v", indexName);
    assertTrue(session.getMetadata().getImmutableSchemaSnapshot().indexExists("IdxMetaEnum.v"));
  }
}
