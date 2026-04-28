/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the live static helper
 * {@link RecordSerializerCSVAbstract#embeddedMapFromStream(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 * EntityImpl, PropertyTypeInternal, String, String)} — the only non-pinned-dead method on
 * {@code RecordSerializerCSVAbstract} after YTDB-86 removed the only concrete subclass.
 *
 * <p>The helper still has live callers in {@link com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper#fieldTypeFromStream}
 * and {@code RecordSerializerStringAbstract.fieldTypeFromStream}; the dead-code instance API of
 * the same class is pinned by {@code RecordSerializerCsvAbstractDeadCodeTest}.
 *
 * <p>Map shape choice: the helper picks {@link EntityLinkMapIml} when {@code iLinkedType} is
 * {@code LINK} or {@code EMBEDDED}, and {@link EntityEmbeddedMapImpl} otherwise; an
 * {@code iLinkedType==null} input may be auto-promoted to a link map when the inferred per-entry
 * type matches and the source-document property type is not {@code EMBEDDEDMAP}. Tests below pin
 * each branch, including the empty / null sentinel paths.
 */
public class RecordSerializerCsvAbstractEmbeddedMapTest extends TestUtilsFixture {

  /**
   * Open a transaction up-front. The map helper instantiates {@code EntityImpl} owners which
   * cannot be modified outside a transaction; the {@link TestUtilsFixture#rollbackIfLeftOpen}
   * {@code @After} method cleans up after each test.
   */
  @Before
  public void beginTx() {
    session.begin();
  }

  /**
   * Empty input string is the null sentinel — pin so a regression that returned an empty map
   * (which would change downstream null checks in {@code DefaultSecuritySystem} and friends) is
   * caught.
   */
  @Test
  public void emptyInputReturnsNullMap() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(session, entity, null, "", null);
    assertNull(result);
  }

  /**
   * Empty map literal {@code "{}"} returns a non-null empty map. With null linkedType and
   * non-EMBEDDED source-document type, the result is an {@link EntityEmbeddedMapImpl}. Pin both
   * the size and the concrete type so a regression that auto-flipped to a link map is caught.
   */
  @Test
  public void emptyMapLiteralReturnsEmptyEmbeddedMap() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(session, entity, null, "{}", null);
    assertEquals(0, result.size());
    assertTrue(EntityEmbeddedMapImpl.class.isInstance(result));
  }

  /** Empty map with explicit LINK linkedType yields {@link EntityLinkMapIml}. */
  @Test
  public void emptyMapWithLinkLinkedTypeReturnsEmptyLinkMap() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, PropertyTypeInternal.LINK, "{}", null);
    assertEquals(0, result.size());
    assertTrue("LINK linkedType maps to EntityLinkMapIml",
        EntityLinkMapIml.class.isInstance(result));
  }

  /**
   * Single-entry string map: the value's quoted form is decoded and stored under the unquoted
   * key. Pin the contained value semantics + the resulting map type.
   */
  @Test
  public void singleStringEntryParsesIntoEmbeddedMap() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k1\":\"v1\"}", null);
    assertEquals(1, result.size());
    assertEquals("v1", result.get("k1"));
    assertTrue(EntityEmbeddedMapImpl.class.isInstance(result));
  }

  /**
   * Multiple-entry string map preserves keys + values verbatim. Pin so the {@code RECORD_SEPARATOR}
   * (",") and {@code ENTRY_SEPARATOR} (":") splits operate at the right level.
   */
  @Test
  public void multipleStringEntriesPreserveOrderAndContent() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k1\":\"v1\",\"k2\":\"v2\"}", null);
    assertEquals(2, result.size());
    assertEquals("v1", result.get("k1"));
    assertEquals("v2", result.get("k2"));
  }

  /**
   * Integer values: with no linkedType and an integer literal, the helper auto-detects the value
   * type via {@code RecordSerializerStringAbstract.getType}. Pin that the value lands as
   * {@link Integer}, not {@link String} — a regression that lost the auto-detection would store
   * the raw string.
   */
  @Test
  public void integerValueIsAutoTypedToInteger() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\":42}", null);
    assertEquals(42, result.get("k"));
    assertTrue(result.get("k") instanceof Integer);
  }

  /** Boolean values auto-type to {@link Boolean}. */
  @Test
  public void booleanValueIsAutoTypedToBoolean() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\":true}", null);
    assertEquals(true, result.get("k"));
  }

  /**
   * Float values with the {@code 'f'} suffix auto-type to {@link Float}. Pin so the type-suffix
   * dispatch round-trips through getType+convertValue exactly.
   */
  @Test
  public void floatValueWithSuffixIsAutoTypedToFloat() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\":1.5f}", null);
    assertTrue(result.get("k") instanceof Float);
    assertEquals(1.5f, (Float) result.get("k"), 1e-6f);
  }

  /**
   * Long values with the {@code 'l'} suffix auto-type to {@link Long}.
   */
  @Test
  public void longValueWithSuffixIsAutoTypedToLong() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\":123l}", null);
    assertTrue(result.get("k") instanceof Long);
    assertEquals(123L, result.get("k"));
  }

  /**
   * RID-typed value with explicit {@code linkedType=LINK}: the helper uses {@link EntityLinkMapIml}
   * and stores the parsed RID under the (unquoted) key. Pin both the map class and the resolved
   * link identity.
   */
  @Test
  public void linkedTypeLinkStoresParsedRidInLinkMap() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, PropertyTypeInternal.LINK, "{\"k\":#5:1}", null);
    assertTrue(EntityLinkMapIml.class.isInstance(result));
    assertEquals(1, result.size());
    var stored = result.get("k");
    assertTrue(stored instanceof Identifiable);
    assertEquals(new RecordId(5, 1), ((Identifiable) stored).getIdentity());
  }

  /**
   * EMBEDDED linkedType uses an {@link EntityLinkMapIml} as initial container too — pin so a
   * regression that switched on PropertyTypeInternal.EMBEDDED → EntityEmbeddedMapImpl is caught.
   * Body is empty so no value-type promotion happens.
   */
  @Test
  public void linkedTypeEmbeddedUsesLinkMapForEmptyBody() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, PropertyTypeInternal.EMBEDDED, "{}", null);
    assertTrue(EntityLinkMapIml.class.isInstance(result));
    assertEquals(0, result.size());
  }

  /**
   * Auto-detection: when null linkedType is supplied AND every value parses to a {@link RID},
   * the helper promotes the initial embedded map to a {@link EntityLinkMapIml}. Pin the
   * promotion path so a regression that always returned EntityEmbeddedMapImpl is caught.
   */
  @Test
  public void autoDetectsLinkValuesAndPromotesToLinkMap() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\":#5:2}", null);
    // Auto-promotion: started with EntityEmbeddedMapImpl, switched to EntityLinkMapIml because
    // the inferred per-entry type was LINK and isConvertToLinkedMap returned true.
    assertTrue("auto-detected LINK promotes to EntityLinkMapIml",
        EntityLinkMapIml.class.isInstance(result));
    assertEquals(1, result.size());
  }

  /**
   * Smart-split tolerance: a string value containing the entry-separator inside quotes does NOT
   * split prematurely. Pin so a regression that used a naive split would mis-parse imported
   * names containing colons.
   */
  @Test
  public void smartSplitIgnoresColonInsideQuotedValue() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\":\"a:b\"}", null);
    assertEquals(1, result.size());
    assertEquals("a:b", result.get("k"));
  }

  /**
   * Smart-split tolerance: a string value containing the record-separator inside quotes also
   * stays whole. Pin so a regression that mis-handled commas would split the value.
   */
  @Test
  public void smartSplitIgnoresCommaInsideQuotedValue() {
    var entity = (EntityImpl) session.newEntity();
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\":\"a,b\"}", null);
    assertEquals(1, result.size());
    assertEquals("a,b", result.get("k"));
  }

  /**
   * Single-entry input where the value parsed to {@code null} (an entry with no value separator)
   * stores a {@code null} under the key. Pin so callers that special-case null entries (auth
   * import) keep working.
   */
  @Test
  public void singleTokenEntryWithoutValueSeparatorStoresNullValue() {
    var entity = (EntityImpl) session.newEntity();
    // Input "{\"k\"}" — single token, no `:` so the entry list has size 1. The else-branch sets
    // mapValueObject = null.
    var result =
        RecordSerializerCSVAbstract.embeddedMapFromStream(
            session, entity, null, "{\"k\"}", null);
    assertEquals(1, result.size());
    assertNull(result.get("k"));
    assertTrue(result.containsKey("k"));
  }

  // ====================================================== integration with fieldTypeFromStream

  /**
   * The downstream {@link RecordSerializerStringAbstract#fieldTypeFromStream} path with
   * {@code EMBEDDEDMAP} type delegates to {@code embeddedMapFromStream}. Pin the wiring so a
   * regression that broke the dispatch ({@code switch} fall-through, mis-cast) is caught at the
   * delegate call site.
   */
  @Test
  public void fieldTypeFromStreamEmbeddedMapDelegatesHere() {
    var entity = (EntityImpl) session.newEntity();
    @SuppressWarnings("unchecked")
    var result =
        (java.util.Map<String, Object>) RecordSerializerStringAbstract.fieldTypeFromStream(
            session, entity, PropertyTypeInternal.EMBEDDEDMAP, "{\"k\":\"v\"}");
    assertEquals(1, result.size());
    assertEquals("v", result.get("k"));
  }
}
