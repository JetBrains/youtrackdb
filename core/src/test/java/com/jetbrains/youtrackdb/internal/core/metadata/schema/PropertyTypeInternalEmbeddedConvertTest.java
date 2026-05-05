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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import org.junit.Test;

/**
 * Drives the per-arm {@link PropertyTypeInternal#convert(Object, PropertyTypeInternal,
 * com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass,
 * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)} body for the
 * {@link PropertyTypeInternal#EMBEDDED} arm. Five branches are exercised:
 *
 * <ul>
 *   <li><b>null</b> — short-circuits to {@code null}.</li>
 *   <li><b>Entity</b> — pass-through identity (no defensive copy on the convert path; the
 *       sibling {@code copy(value, session)} is what clones).</li>
 *   <li><b>Map</b> — allocates a fresh embedded entity of {@code linkedClass} and populates
 *       it via {@code updateFromMap}.</li>
 *   <li><b>String</b> — JSON-deserialises into a fresh embedded entity of {@code linkedClass}.</li>
 *   <li><b>wrong-type</b> — falls through to the {@code default ->} arm and throws
 *       {@link DatabaseException}.</li>
 * </ul>
 *
 * <p>The Map and String arms both call {@code session.newEmbeddedEntity(linkedClass)} unguarded,
 * so {@code linkedClass} must be non-null. A {@code linkedClass}-aware fixture is created in
 * each test to keep the contracts checkable.
 *
 * <p>This test is the embedded-singleton sibling of the collection convert test
 * ({@code PropertyTypeInternalCollectionConvertTest}, which exercises EMBEDDEDLIST /
 * EMBEDDEDSET / EMBEDDEDMAP) and the link convert test
 * ({@code PropertyTypeInternalLinkConvertTest}, which exercises LINK / LINKLIST / LINKSET /
 * LINKMAP / LINKBAG). Together they pin every reference / container arm of
 * {@code PropertyTypeInternal.convert(value, linkedType, linkedClass, session)}; the
 * primitive / scalar arms are pinned by the numeric and datetime/binary sibling tests.
 *
 * <p>Extends {@link DbTestBase} because the Map / String arms require a live session to
 * allocate the embedded {@link EntityImpl} via {@code session.newEmbeddedEntity(linkedClass)}.
 */
public class PropertyTypeInternalEmbeddedConvertTest extends DbTestBase {

  @Test
  public void embeddedNullReturnsNull() {
    // The null-arm short-circuits before linkedClass / session are dereferenced.
    assertNull(PropertyTypeInternal.EMBEDDED.convert(null, null, null, null));
  }

  /**
   * Entity input is returned as identity (no defensive copy). Pin the contract — the sibling
   * {@code copy(value, session)} method is what clones the underlying record. A regression
   * introducing a fresh embedded entity on the convert path would silently change object
   * identity for downstream consumers (and would force every caller into a copy of the entity
   * graph rather than the lightweight reference reuse the impl currently does).
   */
  @Test
  public void embeddedEntityPassesThroughAsIdentity() {
    var schema = session.getMetadata().getSchema();
    // session.newEmbeddedEntity requires the linked class to be abstract — the runtime
    // rejects non-abstract classes for embedded materialisation.
    var cls = schema.createClass("EmbeddedHolder").setAbstract(true);
    Entity embeddedInput = session.newEmbeddedEntity(cls);
    embeddedInput.setProperty("k", "v");

    var result = (Entity) PropertyTypeInternal.EMBEDDED.convert(embeddedInput, null, cls, session);
    assertSame(embeddedInput, result);
    assertEquals("v", result.getProperty("k"));
  }

  /**
   * Map → fresh embedded {@link Entity}: the arm calls {@code session.newEmbeddedEntity(linkedClass)}
   * then {@code updateFromMap(map)} on the fresh instance. Verify the resulting entity is a
   * different instance from the input map, has the expected properties, and is marked
   * {@code isEmbedded()} (the contract that distinguishes EMBEDDED from a heap-rooted Entity).
   */
  @Test
  public void embeddedMapAllocatesFreshEmbeddedEntityAndUpdates() {
    var schema = session.getMetadata().getSchema();
    // session.newEmbeddedEntity rejects non-abstract classes — see the EmbeddedHolder fixture
    // above for the full rationale.
    var cls = schema.createClass("EmbeddedFromMap").setAbstract(true);
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("count", PropertyType.INTEGER);

    var inputMap = new HashMap<String, Object>();
    inputMap.put("name", "alpha");
    inputMap.put("count", 7);

    var result = (Entity) PropertyTypeInternal.EMBEDDED.convert(inputMap, null, cls, session);
    assertNotNull(result);
    assertEquals("alpha", result.getProperty("name"));
    assertEquals(Integer.valueOf(7), result.getProperty("count"));
    assertTrue("convert must return an embedded entity for the Map arm", result.isEmbedded());
  }

  /**
   * String → fresh embedded {@link Entity} via JSON: the arm calls
   * {@code session.newEmbeddedEntity(linkedClass)} then
   * {@code JSONSerializerJackson.fromString} to populate. Pin a known-good JSON round-trip so a
   * future change in the JSON serializer's contract (e.g., changing how it deserialises into a
   * pre-allocated record) is a deliberate, visible event.
   */
  @Test
  public void embeddedStringJsonDeserialisesIntoFreshEmbeddedEntity() {
    var schema = session.getMetadata().getSchema();
    // Same abstract-required rule as the Map fixture.
    var cls = schema.createClass("EmbeddedFromJson").setAbstract(true);
    cls.createProperty("name", PropertyType.STRING);

    var json = "{\"name\":\"beta\"}";
    var result = (Entity) PropertyTypeInternal.EMBEDDED.convert(json, null, cls, session);
    assertNotNull(result);
    assertEquals("beta", result.getProperty("name"));
    assertTrue("convert must return an embedded entity for the String arm", result.isEmbedded());
  }

  /**
   * Non-Entity, non-Map, non-String input (a sentinel Object) hits the {@code default ->} arm
   * and throws. The throw site uses the active {@code session} so {@code session.getDatabaseName()}
   * is safe — no null-session NPE risk on this arm because no production caller routes a wrong
   * type through {@code EMBEDDED.convert} without a live session anyway.
   */
  @Test
  public void embeddedWrongTypeThrowsDatabaseException() {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("EmbeddedHolderWrong");
    assertThrows(DatabaseException.class,
        () -> PropertyTypeInternal.EMBEDDED.convert(new Object(), null, cls, session));
  }
}
