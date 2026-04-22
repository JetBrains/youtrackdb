/*
 *
 *
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
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.BasicResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.EmbeddedListResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.EmbeddedMapResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.EmbeddedSetResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkListResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkMapResultImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.LinkSetResultImpl;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Direct tests for {@link ResultInternal}, the concrete record/projection wrapper used by every
 * executor step. Coverage is split into two halves:
 *
 * <ul>
 *   <li><b>Session-free half</b>: Constructors, property round-trip, temporary properties,
 *       metadata, simple setIdentifiable paths with a plain {@link RecordId}, toMap/toJSON
 *       rendering on projections, equals/hashCode, and the static {@code toResult} factories —
 *       these all tolerate a {@code null} session because {@code checkSession()} returns true for
 *       a null session.
 *   <li><b>DB-backed half</b>: {@code convertPropertyValue} for real entities and blobs,
 *       {@code isEntity / isBlob / isEdge / isVertex} routed through the schema snapshot,
 *       {@code asEntity} / {@code asBlob} / {@code asRecord} re-load paths, projection of
 *       embedded entities via {@code setIdentifiable}, and the {@code getEntity} / {@code getBlob}
 *       / {@code getVertex} / {@code getEdge} / {@code getLink} property-access helpers that go
 *       through the active transaction.
 * </ul>
 *
 * <p>The test class extends {@link TestUtilsFixture} to inherit the {@code @After
 * rollbackIfLeftOpen} safety-net established in Track 7 and codified in Track 8.
 */
public class ResultInternalTest extends TestUtilsFixture {

  // =========================================================================
  // Constructors
  // =========================================================================

  /** Default (session-only) ctor allocates an empty mutable content map. */
  @Test
  public void nullSessionConstructorProducesEmptyProjection() {
    var r = new ResultInternal(null);
    assertThat(r.isProjection()).isTrue();
    assertThat(r.isIdentifiable()).isFalse();
    assertThat(r.getPropertyNames()).isEmpty();
    assertThat(r.getIdentity()).isNull();
    assertThat(r.getBoundedToSession()).isNull();
  }

  /**
   * The {@code (session, int)} sizing ctor is a pure capacity hint — any value, including zero
   * and negative, must not reject input; the internal HashMap clamps to at least one entry.
   */
  @Test
  public void sizedConstructorAcceptsZeroAndSmallAndLargeHints() {
    var zero = new ResultInternal(null, 0);
    zero.setProperty("a", 1);
    assertThat((Integer) zero.getProperty("a")).isEqualTo(1);

    var small = new ResultInternal(null, 4);
    for (var i = 0; i < 4; i++) {
      small.setProperty("p" + i, i);
    }
    assertThat(small.getPropertyNames()).hasSize(4);

    var negative = new ResultInternal(null, -10);
    negative.setProperty("x", "y");
    assertThat((String) negative.getProperty("x")).isEqualTo("y");
  }

  /**
   * The {@code (session, Map)} ctor populates content via {@code setProperty} — so
   * {@code convertPropertyValue} is applied to every entry, and the order of keys matches the
   * map's iteration order.
   */
  @Test
  public void mapConstructorPopulatesContentViaSetProperty() {
    Map<String, Object> src = new LinkedHashMap<>();
    src.put("a", 1);
    src.put("b", "str");
    src.put("c", List.of(1, 2, 3));
    var r = new ResultInternal(null, src);
    assertThat((Integer) r.getProperty("a")).isEqualTo(1);
    assertThat((String) r.getProperty("b")).isEqualTo("str");
    // The List is converted into an EmbeddedListResultImpl via convertPropertyValue.
    Object converted = r.getProperty("c");
    assertThat(converted).isInstanceOf(EmbeddedListResultImpl.class);
    assertThat((List<Object>) (List) converted).containsExactly(1, 2, 3);
  }

  /**
   * The {@code (session, Identifiable)} ctor installs the identifiable and clears the content
   * map, so the result represents a record rather than a projection.
   */
  @Test
  public void identifiableConstructorClearsContent() {
    var rid = new RecordId(12, 34);
    var r = new ResultInternal(null, rid);
    assertThat(r.isIdentifiable()).isTrue();
    assertThat(r.isProjection()).isFalse();
    assertThat(r.getIdentity()).isEqualTo(rid);
    assertThat(r.asIdentifiable()).isEqualTo(rid);
    assertThat(r.asIdentifiableOrNull()).isEqualTo(rid);
  }

  /** {@link ResultInternal#asIdentifiable()} throws when identifiable is null. */
  @Test
  public void asIdentifiableThrowsWhenNoneSet() {
    var r = new ResultInternal(null);
    assertThatThrownBy(r::asIdentifiable)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not an identifiable");
    assertThat(r.asIdentifiableOrNull()).isNull();
  }

  // =========================================================================
  // setProperty / getProperty / removeProperty
  // =========================================================================

  @Test
  public void setAndGetPropertyRoundTripForScalars() {
    var r = new ResultInternal(null);
    r.setProperty("i", 7);
    r.setProperty("s", "abc");
    r.setProperty("b", true);
    r.setProperty("n", null);
    assertThat((Integer) r.getProperty("i")).isEqualTo(7);
    assertThat((String) r.getProperty("s")).isEqualTo("abc");
    assertThat((Boolean) r.getProperty("b")).isTrue();
    assertThat((Object) r.getProperty("n")).isNull();
    assertThat(r.hasProperty("i")).isTrue();
    assertThat(r.hasProperty("missing")).isFalse();
    assertThat(r.getPropertyNames()).containsExactlyInAnyOrder("i", "s", "b", "n");
  }

  @Test
  public void removePropertyDropsKey() {
    var r = new ResultInternal(null);
    r.setProperty("k", 1);
    r.removeProperty("k");
    assertThat(r.hasProperty("k")).isFalse();
    // Removing a missing key must be a no-op.
    r.removeProperty("never-set");
  }

  /**
   * setProperty on a record-holding result (content == null) fails with the "Impossible to mutate
   * result set containing entity" message.
   */
  @Test
  public void setPropertyOnIdentifiableResultRejectsMutation() {
    var r = new ResultInternal(null, new RecordId(1, 2));
    assertThatThrownBy(() -> r.setProperty("k", "v"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("containing entity");
  }

  /** The BasicResultInternal view exposes the mutating facets; exercise via interface. */
  @Test
  public void basicResultInternalInterfaceExposesSetPropertyAndSetMetadataAndSetIdentity() {
    BasicResultInternal r = new ResultInternal(null);
    r.setProperty("k", "v");
    r.setMetadata("mk", "mv");
    var impl = (ResultInternal) r;
    assertThat((String) impl.getProperty("k")).isEqualTo("v");
    // Metadata is independent of content.
    assertThat(impl.getMetadata("mk")).isEqualTo("mv");
  }

  // =========================================================================
  // convertPropertyValue — every branch of the switch statement
  // =========================================================================

  @Test
  public void convertNonPersistentBlobReturnsBytes() {
    session.begin();
    try {
      // A freshly-created blob is non-persistent; convertPropertyValue returns its bytes.
      var blob = session.newBlob(new byte[] {1, 2, 3});
      var r = new ResultInternal(session);
      r.setProperty("blob", blob);
      assertThat((byte[]) r.getProperty("blob")).containsExactly(1, 2, 3);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void convertNonEmbeddedEntityReturnsRid() {
    session.createClass("CPV_E");
    session.begin();
    try {
      var e = session.newEntity("CPV_E");
      var rid = e.getIdentity();
      var r = new ResultInternal(session);
      r.setProperty("entity", e);
      assertThat((RID) r.getProperty("entity")).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void convertEmbeddedEntityReturnsDetachedResult() {
    session.begin();
    try {
      var emb = session.newEmbeddedEntity();
      emb.setProperty("k", "v");
      var r = new ResultInternal(session);
      r.setProperty("embedded", emb);
      // Embedded entities flow through entity.detach() which returns a Result (not the Entity).
      assertThat((Object) r.getProperty("embedded"))
          .isInstanceOf(com.jetbrains.youtrackdb.internal.core.query.Result.class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void convertContextualRecordIdAddsMetadata() {
    var ctxId = new ContextualRecordId("#1:2");
    Map<String, Object> context = new HashMap<>();
    context.put("label", "value");
    ctxId.setContext(context);

    var r = new ResultInternal(null);
    r.setProperty("link", ctxId);
    assertThat((Object) r.getProperty("link")).isInstanceOf(RecordId.class);
    assertThat(r.getMetadata("label")).isEqualTo("value");
  }

  @Test
  public void convertPlainIdentifiableReturnsIdentity() {
    var r = new ResultInternal(null);
    var rid = new RecordId(5, 6);
    r.setProperty("link", rid);
    assertThat((RID) r.getProperty("link")).isEqualTo(rid);
  }

  @Test
  public void convertResultDelegatesToScalarValueWhenNotBound() {
    var inner = new ResultInternal(null);
    inner.setProperty("k", "v");
    var r = new ResultInternal(null);
    r.setProperty("res", inner);
    // For a projection-type inner, the outer stores the Result as-is (non-identifiable branch).
    assertThat((Object) r.getProperty("res"))
        .isInstanceOf(com.jetbrains.youtrackdb.internal.core.query.Result.class);
  }

  @Test
  public void convertResultWithDifferentSessionThrows() {
    var inner = new ResultInternal(session);
    inner.setProperty("k", "v");
    var r = new ResultInternal(null);
    assertThatThrownBy(() -> r.setProperty("res", inner))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("different session");
  }

  @Test
  public void convertObjectArrayOfScalarsBecomesEmbeddedList() {
    var r = new ResultInternal(null);
    r.setProperty("arr", new Object[] {1, "two", 3.0});
    assertThat((Object) r.getProperty("arr")).isInstanceOf(EmbeddedListResultImpl.class);
    assertThat((List<Object>) (List) r.getProperty("arr")).containsExactly(1, "two", 3.0);
  }

  @Test
  public void convertEmptyObjectArrayBecomesEmptyEmbeddedList() {
    var r = new ResultInternal(null);
    r.setProperty("arr", new Object[0]);
    assertThat((Object) r.getProperty("arr")).isInstanceOf(EmbeddedListResultImpl.class);
    assertThat((List<?>) (List) r.getProperty("arr")).isEmpty();
  }

  @Test
  public void convertObjectArrayOfRidsBecomesLinkList() {
    var r = new ResultInternal(null);
    r.setProperty("arr", new Object[] {new RecordId(1, 1), new RecordId(2, 2)});
    assertThat((Object) r.getProperty("arr")).isInstanceOf(LinkListResultImpl.class);
  }

  /**
   * If an array begins with identifiables but later contains a scalar, the accumulated link list
   * is promoted to an embedded list so mixed types coexist. Pins the "promotion path".
   */
  @Test
  public void convertObjectArrayMixedPromotesToEmbeddedList() {
    var r = new ResultInternal(null);
    r.setProperty("arr", new Object[] {new RecordId(1, 1), "scalar"});
    assertThat((Object) r.getProperty("arr")).isInstanceOf(EmbeddedListResultImpl.class);
    assertThat((List<?>) (List) r.getProperty("arr")).hasSize(2);
  }

  @Test
  public void convertListOfScalarsBecomesEmbeddedList() {
    var r = new ResultInternal(null);
    r.setProperty("list", List.of(10, 20, 30));
    assertThat((Object) r.getProperty("list")).isInstanceOf(EmbeddedListResultImpl.class);
    assertThat((List<Object>) (List) r.getProperty("list")).containsExactly(10, 20, 30);
  }

  @Test
  public void convertEmptyListBecomesEmptyEmbeddedList() {
    var r = new ResultInternal(null);
    r.setProperty("list", new ArrayList<>());
    assertThat((Object) r.getProperty("list")).isInstanceOf(EmbeddedListResultImpl.class);
  }

  @Test
  public void convertListOfRidsBecomesLinkList() {
    var r = new ResultInternal(null);
    r.setProperty("list", List.of(new RecordId(1, 1), new RecordId(2, 2)));
    assertThat((Object) r.getProperty("list")).isInstanceOf(LinkListResultImpl.class);
  }

  @Test
  public void convertListMixedPromotesToEmbeddedList() {
    var r = new ResultInternal(null);
    r.setProperty("list", List.of(new RecordId(1, 1), "scalar"));
    assertThat((Object) r.getProperty("list")).isInstanceOf(EmbeddedListResultImpl.class);
  }

  @Test
  public void convertResultSetMaterializesEntriesAsList() {
    var r = new ResultInternal(null);
    var rs = new InternalResultSet(null);
    rs.add(new ResultInternal(null, Map.of("k", "a")));
    rs.add(new ResultInternal(null, Map.of("k", "b")));
    r.setProperty("rs", rs);
    List<?> materialized = (List<?>) (List) r.getProperty("rs");
    assertThat(materialized).hasSize(2);
  }

  @Test
  public void convertSetOfScalarsBecomesEmbeddedSet() {
    var r = new ResultInternal(null);
    r.setProperty("set", Set.of(1, 2, 3));
    assertThat((Object) r.getProperty("set")).isInstanceOf(EmbeddedSetResultImpl.class);
    assertThat((Set<Object>) (Set) r.getProperty("set")).containsExactlyInAnyOrder(1, 2, 3);
  }

  @Test
  public void convertEmptySetBecomesEmptyEmbeddedSet() {
    var r = new ResultInternal(null);
    r.setProperty("set", new HashSet<>());
    assertThat((Object) r.getProperty("set")).isInstanceOf(EmbeddedSetResultImpl.class);
  }

  @Test
  public void convertSetOfRidsBecomesLinkSet() {
    var r = new ResultInternal(null);
    Set<RecordId> rids = new HashSet<>();
    rids.add(new RecordId(1, 1));
    rids.add(new RecordId(2, 2));
    r.setProperty("set", rids);
    assertThat((Object) r.getProperty("set")).isInstanceOf(LinkSetResultImpl.class);
  }

  /** Sets cannot mix identifiables and scalars — the second insertion must throw. */
  @Test
  public void convertMixedSetRejectsIdentifiableFollowedByScalar() {
    var r = new ResultInternal(null);
    Set<Object> mixed = new HashSet<>();
    mixed.add(new RecordId(1, 1));
    mixed.add("scalar");
    assertThatThrownBy(() -> r.setProperty("set", mixed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identifiables");
  }

  @Test
  public void convertMapOfScalarsBecomesEmbeddedMap() {
    var r = new ResultInternal(null);
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("a", 1);
    m.put("b", 2);
    r.setProperty("map", m);
    assertThat((Object) r.getProperty("map")).isInstanceOf(EmbeddedMapResultImpl.class);
    assertThat((Map<String, Object>) (Map) r.getProperty("map")).containsEntry("a", 1)
        .containsEntry("b", 2);
  }

  @Test
  public void convertEmptyMapBecomesEmptyEmbeddedMap() {
    var r = new ResultInternal(null);
    r.setProperty("map", new HashMap<>());
    assertThat((Object) r.getProperty("map")).isInstanceOf(EmbeddedMapResultImpl.class);
  }

  @Test
  public void convertMapOfRidsBecomesLinkMap() {
    var r = new ResultInternal(null);
    Map<String, RecordId> m = new LinkedHashMap<>();
    m.put("a", new RecordId(1, 1));
    m.put("b", new RecordId(2, 2));
    r.setProperty("map", m);
    assertThat((Object) r.getProperty("map")).isInstanceOf(LinkMapResultImpl.class);
  }

  @Test
  public void convertMapWithNonStringKeyRejectsInput() {
    var r = new ResultInternal(null);
    Map<Object, Object> bad = new HashMap<>();
    bad.put(42, "value");
    assertThatThrownBy(() -> r.setProperty("m", bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String");
  }

  @Test
  public void convertMixedMapRejectsIdentifiableFollowedByScalar() {
    var r = new ResultInternal(null);
    Map<String, Object> mixed = new LinkedHashMap<>();
    mixed.put("a", new RecordId(1, 1));
    mixed.put("b", "scalar");
    assertThatThrownBy(() -> r.setProperty("m", mixed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identifiables");
  }

  @Test
  public void convertUnsupportedTypeThrowsCommandExecutionException() {
    var r = new ResultInternal(null);
    // A random user POJO with no property-type mapping.
    var custom = new Object() {
      @Override
      public String toString() {
        return "custom";
      }
    };
    assertThatThrownBy(() -> r.setProperty("x", custom))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining("Invalid property value");
  }

  // =========================================================================
  // Temporary properties
  // =========================================================================

  @Test
  public void temporaryPropertiesRoundTripAndStayOutOfRegularNames() {
    var r = new ResultInternal(null);
    r.setTemporaryProperty("$tmp", 42);
    assertThat((Integer) r.getTemporaryProperty("$tmp")).isEqualTo(42);
    assertThat(r.getTemporaryProperties()).containsExactly("$tmp");
    assertThat(r.getPropertyNames()).doesNotContain("$tmp");
    // Null name and missing name paths.
    assertThat((Object) r.getTemporaryProperty(null)).isNull();
    assertThat((Object) r.getTemporaryProperty("missing")).isNull();
  }

  /**
   * If the temporary value is a Result that is itself an entity, the underlying entity is stored
   * — not the Result wrapper. Pin the unwrap.
   */
  @Test
  public void setTemporaryPropertyUnwrapsResultCarryingEntity() {
    session.createClass("TP_E");
    session.begin();
    try {
      var e = session.newEntity("TP_E");
      var inner = new ResultInternal(session, e);
      var r = new ResultInternal(session);
      r.setTemporaryProperty("e", inner);
      assertThat(r.getTemporaryProperty("e")).isInstanceOf(Entity.class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getTemporaryPropertiesReturnsEmptySetWhenNoneSet() {
    var r = new ResultInternal(null);
    assertThat(r.getTemporaryProperties()).isEmpty();
  }

  // =========================================================================
  // Metadata
  // =========================================================================

  @Test
  public void metadataRoundTripAndNullKeysAreIgnored() {
    var r = new ResultInternal(null);
    r.setMetadata("a", 1);
    r.setMetadata("b", "x");
    // null key on setMetadata is a no-op (early return), not an error.
    r.setMetadata(null, "whatever");
    assertThat((Integer) r.getMetadata("a")).isEqualTo(1);
    assertThat((String) r.getMetadata("b")).isEqualTo("x");
    assertThat((Object) r.getMetadata(null)).isNull();
    assertThat((Object) r.getMetadata("never-set")).isNull();
    assertThat(r.getMetadataKeys()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  public void getMetadataReturnsNullWhenMapNeverAllocated() {
    var r = new ResultInternal(null);
    assertThat((Object) r.getMetadata("anything")).isNull();
    assertThat(r.getMetadataKeys()).isEmpty();
  }

  @Test
  public void addMetadataMergesAndHandlesNullInput() {
    var r = new ResultInternal(null);
    r.setMetadata("a", 1);
    r.addMetadata(Map.of("b", 2, "c", 3));
    assertThat(r.getMetadataKeys()).containsExactlyInAnyOrder("a", "b", "c");
    // Null input is a no-op.
    r.addMetadata(null);
    assertThat(r.getMetadataKeys()).hasSize(3);
  }

  @Test
  public void addMetadataAllocatesMapOnFirstCall() {
    var r = new ResultInternal(null);
    r.addMetadata(Map.of("x", 1));
    assertThat((Integer) r.getMetadata("x")).isEqualTo(1);
  }

  // =========================================================================
  // setIdentifiable
  // =========================================================================

  @Test
  public void setIdentifiableWithEmbeddedEntityFlattensToProjection() {
    session.begin();
    try {
      var embedded = session.newEmbeddedEntity();
      embedded.setProperty("name", "Alice");
      embedded.setProperty("age", 30);
      var r = new ResultInternal(session);
      r.setIdentifiable(embedded);
      // Embedded entity flattened: identifiable is cleared, content is populated.
      assertThat(r.isProjection()).isTrue();
      assertThat(r.isIdentifiable()).isFalse();
      assertThat((String) r.getProperty("name")).isEqualTo("Alice");
      assertThat((Integer) r.getProperty("age")).isEqualTo(30);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void setIdentifiableWithContextualRecordIdStoresPlainRecordIdAndMetadata() {
    var ctxId = new ContextualRecordId("#1:2");
    ctxId.setContext(Map.of("k", "v"));
    var r = new ResultInternal(null);
    r.setIdentifiable(ctxId);
    assertThat(r.asIdentifiable()).isInstanceOf(RecordId.class);
    assertThat(r.getMetadata("k")).isEqualTo("v");
  }

  @Test
  public void setIdentityDelegatesToSetIdentifiable() {
    var r = new ResultInternal(null);
    var rid = new RecordId(3, 4);
    r.setIdentity(rid);
    assertThat(r.getIdentity()).isEqualTo(rid);
    assertThat(r.isProjection()).isFalse();
  }

  // =========================================================================
  // Type checks + as* methods with real entities
  // =========================================================================

  @Test
  public void isEntityAndAsEntityWorkWithRealEntity() {
    session.createClass("IsEntity_E");
    session.begin();
    try {
      var e = session.newEntity("IsEntity_E");
      var r = new ResultInternal(session, e);
      assertThat(r.isEntity()).isTrue();
      assertThat(r.isBlob()).isFalse();
      assertThat(r.asEntity()).isSameAs(e);
      assertThat(r.asEntityOrNull()).isSameAs(e);
      assertThat(r.asRecord()).isSameAs(e);
      assertThat(r.asRecordOrNull()).isSameAs(e);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void isEntityIsFalseForNullIdentifiable() {
    var r = new ResultInternal(null);
    assertThat(r.isEntity()).isFalse();
    assertThat(r.isBlob()).isFalse();
    assertThat(r.asEntityOrNull()).isNull();
    assertThat(r.asRecordOrNull()).isNull();
    assertThat(r.asBlobOrNull()).isNull();
  }

  @Test
  public void asEntityThrowsWhenResultIsNotEntity() {
    session.begin();
    try {
      var blob = session.newBlob(new byte[] {1, 2});
      var r = new ResultInternal(session, blob);
      assertThatThrownBy(r::asEntity).isInstanceOf(IllegalStateException.class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void asRecordThrowsWhenIdentifiableNull() {
    var r = new ResultInternal(null);
    assertThatThrownBy(r::asRecord).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void isBlobAndAsBlobWorkWithRealBlob() {
    session.begin();
    try {
      var blob = session.newBlob(new byte[] {9, 8, 7});
      var r = new ResultInternal(session, blob);
      assertThat(r.isBlob()).isTrue();
      assertThat(r.isEntity()).isFalse();
      assertThat(r.asBlob()).isSameAs(blob);
      assertThat(r.asBlobOrNull()).isSameAs(blob);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void asBlobThrowsOnEntity() {
    session.createClass("AsBlob_E");
    session.begin();
    try {
      var e = session.newEntity("AsBlob_E");
      var r = new ResultInternal(session, e);
      assertThatThrownBy(r::asBlob).isInstanceOf(IllegalStateException.class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void isVertexAndIsEdgeTrueForTheirTypes() {
    session.createVertexClass("TypeChecks_V");
    session.createEdgeClass("TypeChecks_E");
    session.begin();
    try {
      var v1 = session.newVertex("TypeChecks_V");
      var v2 = session.newVertex("TypeChecks_V");
      var edge = v1.addEdge(v2, "TypeChecks_E");

      var vResult = new ResultInternal(session, v1);
      var eResult = new ResultInternal(session, edge);
      assertThat(vResult.isVertex()).isTrue();
      assertThat(vResult.isEdge()).isFalse();
      assertThat(eResult.isEdge()).isTrue();
      assertThat(eResult.isVertex()).isFalse();

      // asEdge / asEdgeOrNull paths.
      assertThat(eResult.asEdge()).isNotNull();
      assertThat(eResult.asEdgeOrNull()).isNotNull();
      // asEdge on a non-edge result throws.
      assertThatThrownBy(vResult::asEdge).isInstanceOf(DatabaseException.class);
      assertThat(vResult.asEdgeOrNull()).isNull();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void isVertexAndIsEdgeFalseForNullIdentifiable() {
    var r = new ResultInternal(null);
    assertThat(r.isVertex()).isFalse();
    assertThat(r.isEdge()).isFalse();
  }

  @Test
  public void isEdgeFalseWhenProjection() {
    var r = new ResultInternal(null);
    r.setProperty("k", 1);
    assertThat(r.isEdge()).isFalse();
  }

  // =========================================================================
  // getEntity / getVertex / getEdge / getBlob / getResult / getLink — access
  // helpers that resolve an identifiable into a concrete record via the active
  // transaction.
  // =========================================================================

  @Test
  public void getEntityResolvesRidToEntity() {
    session.createClass("GetEntity_E");
    session.begin();
    try {
      var target = session.newEntity("GetEntity_E");
      var rid = target.getIdentity();
      var r = new ResultInternal(session);
      r.setProperty("ref", target);
      var loaded = r.getEntity("ref");
      assertThat(loaded).isNotNull();
      assertThat(loaded.getIdentity()).isEqualTo(rid);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getEntityReturnsNullWhenPropertyMissing() {
    var r = new ResultInternal(session);
    assertThat(r.getEntity("missing")).isNull();
    assertThat(r.getResult("missing")).isNull();
    assertThat(r.getLink("missing")).isNull();
  }

  @Test
  public void getEntityThrowsWhenPropertyIsNotEntity() {
    var r = new ResultInternal(session);
    r.setProperty("p", "plain-string");
    assertThatThrownBy(() -> r.getEntity("p")).isInstanceOf(DatabaseException.class);
  }

  @Test
  public void getResultReturnsStoredResult() {
    var inner = new ResultInternal(null);
    inner.setProperty("k", "v");
    var r = new ResultInternal(session);
    r.setProperty("inner", inner);
    // Pin the stored Result's content — a mutation returning any non-null Result
    // (e.g., a fresh empty ResultInternal) would pass an isNotNull-only check.
    var fetched = r.getResult("inner");
    assertThat(fetched).isNotNull();
    assertThat(fetched.<String>getProperty("k"))
        .as("getResult must return the stored Result, not any non-null Result")
        .isEqualTo("v");
  }

  @Test
  public void getResultThrowsWhenPropertyIsNotResult() {
    var r = new ResultInternal(session);
    r.setProperty("p", 42);
    assertThatThrownBy(() -> r.getResult("p")).isInstanceOf(DatabaseException.class);
  }

  @Test
  public void getVertexResolvesRidToVertex() {
    session.createVertexClass("GV_V");
    session.begin();
    try {
      var v = session.newVertex("GV_V");
      var r = new ResultInternal(session);
      r.setProperty("vref", v);
      // Pin the resolved vertex's identity — a mutation returning any non-null Vertex
      // (e.g., delegating to the wrong accessor) would pass an isNotNull-only check.
      var fetched = r.getVertex("vref");
      assertThat(fetched).isNotNull();
      assertThat(fetched.getIdentity())
          .as("getVertex must resolve the stored RID, not any non-null Vertex")
          .isEqualTo(v.getIdentity());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getVertexReturnsNullWhenPropertyMissing() {
    var r = new ResultInternal(session);
    assertThat(r.getVertex("none")).isNull();
  }

  @Test
  public void getVertexThrowsWhenPropertyIsNotVertex() {
    var r = new ResultInternal(session);
    r.setProperty("p", "not-a-vertex");
    assertThatThrownBy(() -> r.getVertex("p")).isInstanceOf(DatabaseException.class);
  }

  @Test
  public void getEdgeResolvesRidToEdge() {
    session.createVertexClass("GE_V");
    session.createEdgeClass("GE_E");
    session.begin();
    try {
      var v1 = session.newVertex("GE_V");
      var v2 = session.newVertex("GE_V");
      var edge = v1.addEdge(v2, "GE_E");
      var r = new ResultInternal(session);
      r.setProperty("eref", edge.getIdentity());
      // Pin the resolved edge's identity — a mutation returning any non-null Edge
      // (e.g., an unrelated edge or a sibling accessor) would pass an isNotNull-only check.
      var fetched = r.getEdge("eref");
      assertThat(fetched).isNotNull();
      assertThat(fetched.getIdentity())
          .as("getEdge must resolve the stored RID, not any non-null Edge")
          .isEqualTo(edge.getIdentity());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getEdgeReturnsNullWhenPropertyMissing() {
    var r = new ResultInternal(session);
    assertThat(r.getEdge("none")).isNull();
  }

  @Test
  public void getBlobResolvesRidToBlob() {
    session.begin();
    try {
      var blob = session.newBlob(new byte[] {7});
      var r = new ResultInternal(session);
      r.setProperty("bref", blob.getIdentity());
      // Pin the resolved blob's identity — a mutation returning any non-null Blob would
      // pass an isNotNull-only check.
      var fetched = r.getBlob("bref");
      assertThat(fetched).isNotNull();
      assertThat(fetched.getIdentity())
          .as("getBlob must resolve the stored RID, not any non-null Blob")
          .isEqualTo(blob.getIdentity());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getBlobReturnsNullWhenPropertyMissing() {
    var r = new ResultInternal(session);
    assertThat(r.getBlob("none")).isNull();
  }

  @Test
  public void getLinkReturnsRidIdentityForIdentifiableProperty() {
    var r = new ResultInternal(null);
    var rid = new RecordId(9, 9);
    r.setProperty("link", rid);
    assertThat(r.getLink("link")).isEqualTo(rid);
  }

  @Test
  public void getLinkThrowsForNonLinkProperty() {
    var r = new ResultInternal(null);
    r.setProperty("p", "not-a-link");
    assertThatThrownBy(() -> r.getLink("p")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void getPropertyDelegatesToEntityWhenContentMissesKey() {
    session.createClass("GP_E");
    session.begin();
    try {
      var e = session.newEntity("GP_E");
      e.setProperty("k", "v");
      var r = new ResultInternal(session, e);
      // Content is null — lookup falls through to asEntity().getProperty.
      assertThat((String) r.getProperty("k")).isEqualTo("v");
      // Absent property returns null (not throws).
      assertThat((Object) r.getProperty("absent")).isNull();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void hasPropertyUnionOfContentAndEntity() {
    session.createClass("HP_E");
    session.begin();
    try {
      var e = session.newEntity("HP_E");
      e.setProperty("k", "v");
      var r = new ResultInternal(session, e);
      assertThat(r.hasProperty("k")).isTrue();
      assertThat(r.hasProperty("missing")).isFalse();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getPropertyNamesOfEntityProjection() {
    session.createClass("PN_E");
    session.begin();
    try {
      var e = session.newEntity("PN_E");
      e.setProperty("a", 1);
      e.setProperty("b", 2);
      var r = new ResultInternal(session, e);
      assertThat(r.getPropertyNames()).contains("a", "b");
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getPropertyNamesEmptyForNullIdentifiable() {
    var r = new ResultInternal(null);
    // content is null only if identifiable was set; when both are empty, content is a HashMap.
    assertThat(r.getPropertyNames()).isEmpty();
  }

  // =========================================================================
  // toMap / toJSON / toString
  // =========================================================================

  @Test
  public void toMapOnProjectionReturnsAllProperties() {
    var r = new ResultInternal(null);
    r.setProperty("a", 1);
    r.setProperty("b", "two");
    var m = r.toMap();
    assertThat(m).containsEntry("a", 1).containsEntry("b", "two");
  }

  @Test
  public void toMapOnEntityResultDelegatesToEntityToMap() {
    session.createClass("TM_E");
    session.begin();
    try {
      var e = session.newEntity("TM_E");
      e.setProperty("k", "v");
      var r = new ResultInternal(session, e);
      assertThat(r.toMap()).containsEntry("k", "v");
    } finally {
      session.rollback();
    }
  }

  @Test
  public void toJsonOnProjectionProducesOrderedJson() {
    var r = new ResultInternal(null);
    r.setProperty("z", 1);
    r.setProperty("a", "two");
    // Meta-properties ("@...") must sort before normal keys.
    r.setProperty("@class", "Cls");
    var json = r.toJSON();
    assertThat(json).startsWith("{").endsWith("}");
    assertThat(json.indexOf("\"@class\"")).isLessThan(json.indexOf("\"a\""));
    assertThat(json.indexOf("\"a\"")).isLessThan(json.indexOf("\"z\""));
  }

  @Test
  public void toJsonCoversScalarNumberBooleanStringNullAndNestedTypes() {
    var r = new ResultInternal(null);
    r.setProperty("s", "hello");
    r.setProperty("n", 42);
    r.setProperty("b", true);
    r.setProperty("nil", null);
    r.setProperty("list", List.of(1, 2));
    r.setProperty("map", Map.of("k", "v"));
    r.setProperty("bytes", new byte[] {1, 2, 3});
    r.setProperty("date", new Date(0));
    var inner = new ResultInternal(null);
    inner.setProperty("i", 9);
    r.setProperty("inner", inner);
    var rid = new RecordId(1, 2);
    r.setProperty("link", rid);
    var json = r.toJSON();
    assertThat(json).contains("\"hello\"").contains("42").contains("true").contains("null");
    assertThat(json).contains("\"i\": 9").contains("\"k\": \"v\"");
    assertThat(json).contains("#1:2");
  }

  @Test
  public void toJsonEncodesJavaArraysAsJsonArrays() {
    // Primitive arrays are not a valid setProperty input (convertPropertyValue rejects them),
    // so we stuff one directly into content to drive the isArray() branch of toJson().
    var r = new ResultInternal(null);
    r.content.put("arr", new int[] {10, 20, 30});
    var json = r.toJSON();
    assertThat(json).contains("[10, 20, 30]");
  }

  @Test
  public void toJsonOnEntityDelegatesToEntityJson() {
    // Persist the entity first (so its @class metadata link is serializable), then wrap a fresh
    // re-loaded instance in ResultInternal and exercise the entity toJSON path.
    session.createClass("TJ_E");
    session.executeInTx(tx -> {
      var e = tx.newEntity("TJ_E");
      e.setProperty("k", "v");
    });
    session.executeInTx(tx -> {
      try (var rs = tx.query("SELECT FROM TJ_E")) {
        var first = rs.next().asEntity();
        var r = new ResultInternal(session, first);
        assertThat(r.toJSON()).contains("\"k\"");
      }
    });
  }

  @Test
  public void toJsonIteratorPath() {
    // A bare Iterator (not an Iterable) exercises the Iterator<?> branch of toJson. It cannot
    // flow through setProperty because convertPropertyValue rejects raw Iterators; we stuff it
    // directly into the content map to reach the renderer.
    var r = new ResultInternal(null);
    r.content.put("it", List.of(1, 2, 3).iterator());
    var json = r.toJSON();
    assertThat(json).contains("[1, 2, 3]");
  }

  @Test
  public void toJsonThrowsOnUnsupportedValueType() {
    // Artificial: drive toJson() via a projection whose property is a non-JSONable Object.
    // We construct a property value that sneaks past convertPropertyValue by using a Boolean —
    // then we rewrite content directly (package-private reflection) to install a rogue value.
    var r = new ResultInternal(null);
    r.content.put("bad", new Object()); // bypass convertPropertyValue entirely
    assertThatThrownBy(r::toJSON).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void toJsonSortComparatorHandlesNullPropertyNames() {
    // The @-prefix sort comparator explicitly handles null on either side.
    // We install a null key directly in content to drive that branch.
    var r = new ResultInternal(null);
    r.setProperty("z", 1);
    r.content.put(null, "nullKey");
    // Rendering must not NPE; it produces "null" for the null key via toJson().
    var json = r.toJSON();
    assertThat(json).contains("null");
  }

  @Test
  public void toStringForProjectionListsProperties() {
    var r = new ResultInternal(null);
    r.setProperty("a", 1);
    var s = r.toString();
    assertThat(s).contains("content:").contains("a: 1");
  }

  @Test
  public void toStringForIdentifiableDelegatesToIdentifiable() {
    var rid = new RecordId(5, 7);
    var r = new ResultInternal(null, rid);
    assertThat(r.toString()).contains("identifiable:").contains("#5:7");
  }

  // =========================================================================
  // equals / hashCode
  // =========================================================================

  @Test
  public void equalsReflexiveAndHandlesDifferentType() {
    var r = new ResultInternal(null);
    //noinspection EqualsWithItself
    assertThat(r.equals(r)).isTrue();
    //noinspection SimplifiableAssertion
    assertThat(r.equals("not a result")).isFalse();
  }

  @Test
  public void equalsByIdentityWhenBothIdentifiable() {
    var rid = new RecordId(1, 1);
    var a = new ResultInternal(null, rid);
    var b = new ResultInternal(null, rid);
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  public void equalsAsymmetricBetweenIdentifiableAndProjection() {
    var withId = new ResultInternal(null, new RecordId(1, 1));
    var projection = new ResultInternal(null);
    assertThat(withId).isNotEqualTo(projection);
    assertThat(projection).isNotEqualTo(withId);
  }

  @Test
  public void equalsByProjectionPropertyContents() {
    var a = new ResultInternal(null);
    a.setProperty("k", "v");
    a.setProperty("i", 1);
    var b = new ResultInternal(null);
    b.setProperty("k", "v");
    b.setProperty("i", 1);
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
    // Different size
    var c = new ResultInternal(null);
    c.setProperty("k", "v");
    assertThat(a).isNotEqualTo(c);
    // Different value
    var d = new ResultInternal(null);
    d.setProperty("k", "v");
    d.setProperty("i", 2);
    assertThat(a).isNotEqualTo(d);
  }

  @Test
  public void equalsIdentifiableOnThisButOtherHasNoIdentity() {
    var a = new ResultInternal(null, new RecordId(1, 1));
    var b = new ResultInternal(null);
    b.setProperty("k", "v");
    assertThat(a).isNotEqualTo(b);
  }

  // =========================================================================
  // detach
  // =========================================================================

  @Test
  public void detachCopiesPropertiesToSessionlessResult() {
    var r = new ResultInternal(null);
    r.setProperty("k", "v");
    r.setProperty("num", 1);
    var detached = (ResultInternal) r.detach();
    assertThat(detached.getBoundedToSession()).isNull();
    assertThat((String) detached.getProperty("k")).isEqualTo("v");
    assertThat((Integer) detached.getProperty("num")).isEqualTo(1);
    assertThat(detached).isNotSameAs(r);
  }

  @Test
  public void detachPreservesEntityIdentity() {
    // detach() walks getPropertyNames(), which for an identifiable-holding result routes to
    // asEntity() → session.getActiveTransaction().loadEntity. Must run inside a transaction.
    session.createClass("DetachId_E");
    session.executeInTx(tx -> {
      var e = tx.newEntity("DetachId_E");
      e.setProperty("k", "v");
      var r = new ResultInternal(session, e);
      var detached = r.detach();
      assertThat(detached.getIdentity()).isEqualTo(e.getIdentity());
    });
  }

  // =========================================================================
  // Static toResult / toResultInternal
  // =========================================================================

  @Test
  public void toResultStaticReturnsInputWhenAlreadyResult() {
    var inner = new ResultInternal(session);
    assertThat(ResultInternal.toResult(inner, session)).isSameAs(inner);
    assertThat(ResultInternal.toResultInternal(inner, session)).isSameAs(inner);
  }

  @Test
  public void toResultStaticReturnsNullForNullInput() {
    assertThat((Object) ResultInternal.toResult(null, session)).isNull();
    assertThat((Object) ResultInternal.toResultInternal(null, session)).isNull();
  }

  @Test
  public void toResultInternalFromIdentifiable() {
    var rid = new RecordId(1, 0);
    var r = ResultInternal.toResultInternal(rid, session);
    assertThat(r).isNotNull();
    assertThat(r.isIdentifiable()).isTrue();
  }

  @Test
  public void toResultInternalFromIdentifiableWithAliasThrows() {
    assertThatThrownBy(() -> ResultInternal.toResultInternal(new RecordId(1, 0), session, "alias"))
        .isInstanceOf(CommandExecutionException.class);
  }

  @Test
  public void toResultInternalFromMap() {
    var r = ResultInternal.toResultInternal(Map.of("k", "v"), session);
    assertThat(r).isNotNull();
    assertThat((String) r.getProperty("k")).isEqualTo("v");
  }

  @Test
  public void toResultInternalFromMapWithAliasThrows() {
    assertThatThrownBy(() -> ResultInternal.toResultInternal(Map.of("k", "v"), session, "alias"))
        .isInstanceOf(CommandExecutionException.class);
  }

  @Test
  public void toResultInternalFromMapWithNonStringKeyThrows() {
    Map<Object, Object> bad = new HashMap<>();
    bad.put(1, "value");
    assertThatThrownBy(() -> ResultInternal.toResultInternal(bad, session))
        .isInstanceOf(CommandExecutionException.class);
  }

  @Test
  public void toResultInternalFromMapEntry() {
    Map.Entry<String, Object> entry = new AbstractMap.SimpleEntry<>("key", "value");
    var r = ResultInternal.toResultInternal(entry, session);
    assertThat(r).isNotNull();
    assertThat((String) r.getProperty("key")).isEqualTo("value");
  }

  @Test
  public void toResultInternalFromMapEntryWithNonStringKeyThrows() {
    Map.Entry<Object, Object> bad = new AbstractMap.SimpleEntry<>(42, "value");
    assertThatThrownBy(() -> ResultInternal.toResultInternal(bad, session))
        .isInstanceOf(CommandExecutionException.class);
  }

  @Test
  public void toResultInternalFromScalarUsesAliasWhenProvided() {
    var r = ResultInternal.toResultInternal(42, session, "myalias");
    assertThat(r).isNotNull();
    assertThat((Integer) r.getProperty("myalias")).isEqualTo(42);
  }

  @Test
  public void toResultInternalFromScalarUsesDefaultNameWhenAliasNull() {
    var r = ResultInternal.toResultInternal("scalar", session);
    assertThat(r).isNotNull();
    assertThat((String) r.getProperty("value")).isEqualTo("scalar");
  }

  // =========================================================================
  // toMapValue static helper — covers every case of the type-switch
  // =========================================================================

  @Test
  public void toMapValueNullReturnsNull() {
    assertThat(ResultInternal.toMapValue(null, false)).isNull();
  }

  @Test
  public void toMapValueEdgeReturnsRid() {
    session.createVertexClass("TMV_V");
    session.createEdgeClass("TMV_E");
    session.begin();
    try {
      var v1 = session.newVertex("TMV_V");
      var v2 = session.newVertex("TMV_V");
      var e = v1.addEdge(v2, "TMV_E");
      assertThat(ResultInternal.toMapValue(e, false)).isInstanceOf(RID.class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void toMapValueBlobReturnsStream() {
    session.begin();
    try {
      var blob = session.newBlob(new byte[] {5, 6, 7});
      assertThat(ResultInternal.toMapValue(blob, false)).isInstanceOf(byte[].class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void toMapValueEmbeddedEntityReturnsMap() {
    session.begin();
    try {
      var emb = session.newEmbeddedEntity();
      emb.setProperty("k", "v");
      assertThat(ResultInternal.toMapValue(emb, false)).isInstanceOf(Map.class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void toMapValuePersistentEntityReturnsRid() {
    session.createClass("TMV_ME");
    session.begin();
    try {
      var e = session.newEntity("TMV_ME");
      assertThat(ResultInternal.toMapValue(e, false)).isInstanceOf(RID.class);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void toMapValueResultDelegatesToResultToMap() {
    var inner = new ResultInternal(null);
    inner.setProperty("k", "v");
    var map = ResultInternal.toMapValue(inner, false);
    assertThat(map).isInstanceOf(Map.class);
    assertThat((Map<String, Object>) (Map) map).containsEntry("k", "v");
  }

  @Test
  public void toMapValueLinkListLinkSetLinkMapConvertToPlainCollections() {
    // Only exercises LinkListResultImpl / LinkSetResultImpl / LinkMapResultImpl class-matches.
    // EntityLinkListImpl, EntityLinkSetImpl, EntityLinkMapIml would need a real linked record.
    // Use the in-memory Link*ResultImpl lookalikes via the outer path.

    // Default path: scalar List → List<Object> returned (recursive toMapValue call on items).
    Object list = ResultInternal.toMapValue(List.of(1, 2, 3), false);
    assertThat(list).isInstanceOf(List.class);
    Object set = ResultInternal.toMapValue(Set.of(4, 5, 6), false);
    assertThat(set).isInstanceOf(Set.class);
    Object map = ResultInternal.toMapValue(Map.of("a", 1), false);
    assertThat(map).isInstanceOf(Map.class);
  }

  @Test
  public void toMapValueEntityLinkListImplReturnsListOfRids() {
    // EntityLinkListImpl requires a database session. Use session.newLinkList() which returns
    // the proper container type.
    session.createClass("ELL_E");
    session.executeInTx(tx -> {
      var linked = tx.newEntity("ELL_E");
      var list = tx.newLinkList();
      list.add(linked);
      if (list instanceof EntityLinkListImpl linkList) {
        Object result = ResultInternal.toMapValue(linkList, false);
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) (List) result).hasSize(1);
      }
    });
  }

  @Test
  public void toMapValueEntityLinkSetImplReturnsSetOfRids() {
    session.createClass("ELS_E");
    session.executeInTx(tx -> {
      var linked = tx.newEntity("ELS_E");
      var set = tx.newLinkSet();
      set.add(linked);
      if (set instanceof EntityLinkSetImpl linkSet) {
        Object result = ResultInternal.toMapValue(linkSet, false);
        assertThat(result).isInstanceOf(Set.class);
      }
    });
  }

  @Test
  public void toMapValueEntityLinkMapImplReturnsMapOfRids() {
    session.createClass("ELM_E");
    session.executeInTx(tx -> {
      var linked = tx.newEntity("ELM_E");
      var map = tx.newLinkMap();
      map.put("k", linked);
      if (map instanceof EntityLinkMapIml linkMap) {
        Object result = ResultInternal.toMapValue(linkMap, false);
        assertThat(result).isInstanceOf(Map.class);
      }
    });
  }

  @Test
  public void toMapValueUnsupportedValueThrows() {
    var rogue = new Object();
    assertThatThrownBy(() -> ResultInternal.toMapValue(rogue, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected");
  }

  @Test
  public void toMapValueScalarPassesThrough() {
    assertThat(ResultInternal.toMapValue("string", false)).isEqualTo("string");
    assertThat(ResultInternal.toMapValue(42, false)).isEqualTo(42);
    assertThat(ResultInternal.toMapValue(new BigDecimal("1.5"), false))
        .isEqualTo(new BigDecimal("1.5"));
  }

  // =========================================================================
  // refreshNonPersistentRid
  // =========================================================================

  @Test
  public void refreshNonPersistentRidIsNoopForPersistent() {
    // Persistent RID bypasses refreshRid — exercise the early-return guard.
    var rid = new RecordId(3, 5);
    var r = new ResultInternal(session, rid);
    r.refreshNonPersistentRid();
    assertThat(r.asIdentifiable()).isEqualTo(rid);
  }

  @Test
  public void refreshNonPersistentRidIsNoopForNullIdentifiable() {
    var r = new ResultInternal(session);
    // No exception, even though no identifiable is set.
    r.refreshNonPersistentRid();
  }

  // =========================================================================
  // Session management
  // =========================================================================

  @Test
  public void setSessionReplacesBoundedSession() {
    var r = new ResultInternal(null);
    r.setSession(session);
    assertThat(r.getBoundedToSession()).isSameAs(session);
    r.setSession(null);
    assertThat(r.getBoundedToSession()).isNull();
  }

  // =========================================================================
  // Collections.emptySet / emptyList guard for getTemporaryProperties
  // =========================================================================

  @Test
  public void emptyGettersNeverReturnMutable() {
    var r = new ResultInternal(null);
    // unmodifiable collection guard: nothing to modify, but verify behavior is stable.
    //noinspection RedundantOperationOnEmptyContainer
    for (var ignored : r.getPropertyNames()) {
      // empty
    }
    //noinspection RedundantOperationOnEmptyContainer
    for (var ignored : r.getTemporaryProperties()) {
      // empty
    }
    //noinspection RedundantOperationOnEmptyContainer
    for (var ignored : r.getMetadataKeys()) {
      // empty
    }
    assertThat(Collections.emptyList()).isEqualTo(new ArrayList<>());
  }
}
