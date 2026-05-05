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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.Test;

/**
 * Behavioural shape pin for {@link GlobalPropertyImpl}, the persistent registry record that
 * {@link SchemaShared} maintains for every (name, type) tuple ever observed in the schema. The
 * baseline measured GlobalPropertyImpl at 100% line coverage already (see
 * {@code track-16-baseline.md}) — this test class anchors the structural shape so that any
 * future deletion of a getter / round-trip method is caught by a failing test rather than by
 * silent drift.
 *
 * <p>The two constructors and three getters are exercised both via direct construction (the
 * constructor that takes (name, type, id)) and via the {@code fromEntity} / {@code toEntity}
 * round-trip through the embedded entity API. The default no-arg constructor exists because
 * {@link GlobalPropertyImpl} is materialised by reflection from the metadata record on disk —
 * that path is exercised through the {@code fromEntity} test.
 *
 * <p>Tests use {@link DbTestBase} only because {@code toEntity(DatabaseSessionEmbedded)} requires
 * a session to allocate the embedded entity; the no-session paths (default ctor, parameterised
 * ctor, getName / getId / getType / getTypeInternal) are exercised inline without DB access.
 */
public class GlobalPropertyImplShapeTest extends DbTestBase {

  @Test
  public void parameterizedConstructorPopulatesEveryField() {
    // The (name, type, id) constructor is the canonical creation path used by SchemaShared when
    // findOrCreateGlobalProperty is called for the first time.
    var prop = new GlobalPropertyImpl("count", PropertyTypeInternal.INTEGER, 7);
    assertEquals("count", prop.getName());
    assertEquals(Integer.valueOf(7), prop.getId());
    assertEquals(PropertyType.INTEGER, prop.getType());
    assertEquals(PropertyTypeInternal.INTEGER, prop.getTypeInternal());
  }

  @Test
  public void defaultConstructorReturnsAllNullFields() {
    // The no-arg constructor is reachable via reflection from the metadata record loader. Pin
    // its observable post-condition: every getter returns null until fromEntity populates it.
    var prop = new GlobalPropertyImpl();
    assertNull("default ctor leaves name null", prop.getName());
    assertNull("default ctor leaves id null", prop.getId());
    assertNull("default ctor leaves type null", prop.getTypeInternal());
  }

  @Test
  public void roundTripThroughEntityPreservesNameTypeAndId() {
    // toEntity emits the persisted shape; fromEntity reconstitutes the GlobalPropertyImpl from
    // it. Verify the three persisted fields survive the round trip on a representative type.
    var original = new GlobalPropertyImpl("score", PropertyTypeInternal.DOUBLE, 17);

    // toEntity emits an entity with three persisted fields: name (string), type (string —
    // PropertyTypeInternal.name()), id (int).
    var entity = original.toEntity(session);
    assertNotNull(entity);
    assertEquals("score", entity.getString("name"));
    assertEquals("DOUBLE", entity.getString("type"));
    assertEquals(Integer.valueOf(17), entity.getInt("id"));

    // fromEntity rebuilds a fresh GlobalPropertyImpl with the same observable state.
    var reconstituted = new GlobalPropertyImpl();
    reconstituted.fromEntity(entity);
    assertEquals(original.getName(), reconstituted.getName());
    assertEquals(original.getId(), reconstituted.getId());
    assertEquals(original.getType(), reconstituted.getType());
    assertEquals(original.getTypeInternal(), reconstituted.getTypeInternal());
  }

  @Test
  public void roundTripPreservesEachInternalTypeArm() {
    // Each PropertyTypeInternal variant must round-trip through name() / valueOf without loss —
    // the persisted form is the type's enum name string and is the reverse-lookup target inside
    // fromEntity. Drive a sample of representative arms covering numeric, string, embedded,
    // link, and binary families to pin the round-trip contract.
    PropertyTypeInternal[] sample = {
        PropertyTypeInternal.BOOLEAN,
        PropertyTypeInternal.INTEGER,
        PropertyTypeInternal.LONG,
        PropertyTypeInternal.STRING,
        PropertyTypeInternal.EMBEDDED,
        PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.LINK,
        PropertyTypeInternal.LINKLIST,
        PropertyTypeInternal.LINKMAP,
        PropertyTypeInternal.BINARY,
        PropertyTypeInternal.DATETIME,
    };
    int id = 100;
    for (var t : sample) {
      var original = new GlobalPropertyImpl("p_" + t.name(), t, id++);
      var rebuilt = new GlobalPropertyImpl();
      rebuilt.fromEntity(original.toEntity(session));
      assertEquals("name lost on " + t, original.getName(), rebuilt.getName());
      assertEquals("id lost on " + t, original.getId(), rebuilt.getId());
      assertEquals("typeInternal lost on " + t,
          original.getTypeInternal(), rebuilt.getTypeInternal());
      assertEquals("public type lost on " + t,
          original.getType(), rebuilt.getType());
    }
  }

  @Test
  public void getTypeMapsThroughPublicEnum() {
    // getType returns the public PropertyType view; getTypeInternal returns the implementation
    // enum. The two must agree under PropertyTypeInternal.convertFromPublicType / inverse.
    var prop = new GlobalPropertyImpl("p", PropertyTypeInternal.LONG, 1);
    assertEquals(PropertyType.LONG, prop.getType());
    assertEquals(PropertyTypeInternal.LONG, prop.getTypeInternal());
    assertEquals(PropertyTypeInternal.LONG,
        PropertyTypeInternal.convertFromPublicType(prop.getType()));
  }
}
