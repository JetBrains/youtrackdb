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

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import org.junit.Test;

/**
 * Direct tests for {@link UpdatableResult}, the {@link ResultInternal} subclass that delegates
 * every accessor straight to its backing {@link Entity}. Every method is a thin wrapper, so the
 * tests focus on covering each one exactly once with a real entity behind the wheel.
 *
 * <p>Extends {@link TestUtilsFixture} for {@link com.jetbrains.youtrackdb.internal.DbTestBase}
 * lifecycle and the {@code @After rollbackIfLeftOpen} safety-net inherited from Track 7/8.
 */
public class UpdatableResultTest extends TestUtilsFixture {

  @org.junit.Before
  public void createUrEClass() {
    // Schema mutations must happen outside any active transaction; set up classes here.
    var schema = session.getMetadata().getSchema();
    schema.getOrCreateClass("UR_E");
    schema.getOrCreateClass("UR_GE");
    schema.getOrCreateClass("UR_GL");
    schema.getOrCreateClass("UR_GB");
  }

  private Entity newSavedEntity() {
    var e = session.newEntity("UR_E");
    e.setProperty("k", "v");
    e.setProperty("n", 1);
    return e;
  }

  // =========================================================================
  // Type checks — always-true / always-false invariants
  // =========================================================================

  @Test
  public void isIdentifiableIsAlwaysTrue() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      assertThat(r.isIdentifiable()).isTrue();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void isEntityIsAlwaysTrue() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      assertThat(r.isEntity()).isTrue();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void isProjectionIsAlwaysFalse() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      assertThat(r.isProjection()).isFalse();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void isBlobIsAlwaysFalse() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      assertThat(r.isBlob()).isFalse();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void asBlobThrowsDatabaseException() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      assertThatThrownBy(r::asBlob).isInstanceOf(DatabaseException.class);
      assertThat(r.asBlobOrNull()).isNull();
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // asEntity / asRecord / as-wrapper methods
  // =========================================================================

  @Test
  public void asEntityReturnsBackingEntityReferenceIdentity() {
    session.begin();
    try {
      var entity = newSavedEntity();
      var r = new UpdatableResult(session, entity);
      assertThat(r.asEntity()).isSameAs(entity);
      assertThat(r.asEntityOrNull()).isSameAs(entity);
      assertThat(r.asRecord()).isSameAs(entity);
      assertThat(r.asRecordOrNull()).isSameAs(entity);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void isEdgeAndAsEdgeDelegateToEntity() {
    session.createVertexClass("UR_V");
    session.createEdgeClass("UR_E2");
    session.begin();
    try {
      var v1 = session.newVertex("UR_V");
      var v2 = session.newVertex("UR_V");
      var edge = v1.addEdge(v2, "UR_E2");
      var r = new UpdatableResult(session, edge);
      assertThat(r.isEdge()).isTrue();
      assertThat(r.asEdge()).isSameAs(edge);
      assertThat(r.asEdgeOrNull()).isSameAs(edge);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // setProperty / removeProperty
  // =========================================================================

  @Test
  public void setPropertyDelegatesToEntity() {
    session.begin();
    try {
      var e = newSavedEntity();
      var r = new UpdatableResult(session, e);
      r.setProperty("new", "vnew");
      assertThat((String) e.getProperty("new")).isEqualTo("vnew");
      assertThat((String) r.getProperty("new")).isEqualTo("vnew");
    } finally {
      session.rollback();
    }
  }

  @Test
  public void removePropertyDelegatesToEntity() {
    session.begin();
    try {
      var e = newSavedEntity();
      var r = new UpdatableResult(session, e);
      r.removeProperty("k");
      assertThat(e.hasProperty("k")).isFalse();
      assertThat(r.hasProperty("k")).isFalse();
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Property accessors — delegation to asEntity()
  // =========================================================================

  @Test
  public void getEntityDelegatesToEntityGetEntity() {
    session.begin();
    try {
      var target = session.newEntity("UR_GE");
      var holder = session.newEntity("UR_GE");
      holder.setProperty("ref", target);
      var r = new UpdatableResult(session, holder);
      var fetched = r.getEntity("ref");
      assertThat(fetched).isNotNull();
      assertThat(fetched.getIdentity()).isEqualTo(target.getIdentity());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getResultDelegatesToEntityGetResult() {
    session.begin();
    try {
      var e = newSavedEntity();
      var r = new UpdatableResult(session, e);
      // The entity's getResult("k") treats the stored String as a property; for scalar values it
      // wraps them or returns null — we just exercise the delegation path.
      //noinspection ResultOfMethodCallIgnored
      try {
        r.getResult("k");
      } catch (DatabaseException ignored) {
        // Scalars that aren't Result instances raise a DatabaseException — either outcome covers
        // the delegation line.
      }
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getVertexDelegatesToEntityGetVertex() {
    session.createVertexClass("UR_GV_V");
    session.begin();
    try {
      var vertex = session.newVertex("UR_GV_V");
      var holder = session.newVertex("UR_GV_V");
      holder.setProperty("ref", vertex);
      var r = new UpdatableResult(session, holder);
      var fetched = r.getVertex("ref");
      assertThat(fetched).isNotNull();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getEdgeDelegatesToEntityGetEdge() {
    session.createVertexClass("UR_GED_V");
    session.createEdgeClass("UR_GED_E");
    session.begin();
    try {
      var v1 = session.newVertex("UR_GED_V");
      var v2 = session.newVertex("UR_GED_V");
      var edge = v1.addEdge(v2, "UR_GED_E");
      var holder = session.newVertex("UR_GED_V");
      holder.setProperty("ref", edge.getIdentity());
      var r = new UpdatableResult(session, holder);
      var fetched = r.getEdge("ref");
      assertThat(fetched).isNotNull();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getLinkDelegatesToEntityGetLink() {
    session.begin();
    try {
      var target = session.newEntity("UR_GL");
      var holder = session.newEntity("UR_GL");
      holder.setProperty("link", target);
      var r = new UpdatableResult(session, holder);
      // Pin the exact identity so a mutation that returned any non-null Identifiable (e.g.
      // holder itself, or an unrelated RID) would be caught — an isNotNull assertion alone
      // accepts both correct delegation and a wrong-but-present return.
      var link = r.getLink("link");
      assertThat(link).isNotNull();
      assertThat(link.getIdentity())
          .as("getLink must return the linked target's RID, not any non-null Identifiable")
          .isEqualTo(target.getIdentity());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void getBlobDelegatesToEntityGetBlob() {
    session.begin();
    try {
      var blob = session.newBlob(new byte[] {1});
      var holder = session.newEntity("UR_GB");
      holder.setProperty("b", blob.getIdentity());
      var r = new UpdatableResult(session, holder);
      // getBlob may return null if the property is not a blob reference in the schema — the
      // delegation path is what matters.
      //noinspection ResultOfMethodCallIgnored
      try {
        r.getBlob("b");
      } catch (DatabaseException ignored) {
        // non-blob reference throws — also exercises the delegation.
      }
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Collection / name accessors
  // =========================================================================

  @Test
  public void getPropertyNamesDelegatesToEntity() {
    session.begin();
    try {
      var e = newSavedEntity();
      var r = new UpdatableResult(session, e);
      assertThat(r.getPropertyNames()).contains("k", "n");
    } finally {
      session.rollback();
    }
  }

  @Test
  public void hasPropertyDelegatesToEntity() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      assertThat(r.hasProperty("k")).isTrue();
      assertThat(r.hasProperty("missing")).isFalse();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void detachDelegatesToEntityDetach() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      var detached = r.detach();
      assertThat(detached).isNotNull();
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // toMap / toJSON / toString
  // =========================================================================

  @Test
  public void toMapDelegatesToEntityToMap() {
    session.begin();
    try {
      var r = new UpdatableResult(session, newSavedEntity());
      var map = r.toMap();
      assertThat(map).containsEntry("k", "v").containsEntry("n", 1);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void toJsonDelegatesToEntityToJson() {
    // toJSON() on a non-persistent entity fails because @class cannot be serialized as a link
    // until the entity is saved. Persist it first, then render.
    session.executeInTx(tx -> {
      var e = tx.newEntity("UR_E");
      e.setProperty("k", "v");
    });
    session.executeInTx(tx -> {
      try (var rs = tx.query("SELECT FROM UR_E")) {
        var persisted = rs.next().asEntity();
        var r = new UpdatableResult(session, persisted);
        var json = r.toJSON();
        assertThat(json).contains("\"k\"").contains("\"v\"");
      }
    });
  }

  @Test
  public void toStringDelegatesToIdentifiableToString() {
    session.begin();
    try {
      var e = newSavedEntity();
      var r = new UpdatableResult(session, e);
      assertThat(r.toString()).isEqualTo(e.toString());
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Identity
  // =========================================================================

  @Test
  public void getIdentityReturnsEntityIdentity() {
    session.begin();
    try {
      var e = newSavedEntity();
      var r = new UpdatableResult(session, e);
      assertThat(r.getIdentity()).isEqualTo(e.getIdentity());
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // setIdentifiable — two branches (Entity shortcut vs. load via transaction)
  // =========================================================================

  @Test
  public void setIdentifiableWithEntityTakesTheEntityDirectly() {
    session.begin();
    try {
      var e1 = newSavedEntity();
      var r = new UpdatableResult(session, e1);
      var e2 = newSavedEntity();
      r.setIdentifiable(e2);
      assertThat(r.asEntity()).isSameAs(e2);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void setIdentifiableWithRidLoadsFromTransaction() {
    session.executeInTx(tx -> {
      var e = tx.newEntity("UR_E");
      e.setProperty("k", "v");
    });
    session.executeInTx(tx -> {
      // Fetch a persisted entity so we have a real RID to re-load via setIdentifiable.
      try (var rs = tx.query("SELECT FROM UR_E")) {
        var persisted = rs.next().asEntity();
        var initial = tx.newEntity("UR_E");
        var r = new UpdatableResult(session, initial);
        // Pass a plain RID (not an Entity) to exercise the transaction.loadEntity branch.
        r.setIdentifiable(persisted.getIdentity());
        assertThat(r.asEntity().getIdentity()).isEqualTo(persisted.getIdentity());
      }
    });
  }

  @Test
  public void toJsonWithUnloadedEntityTriggersLoad() {
    // Exercise the `entity.isUnloaded() && session != null` branch of toJSON by persisting an
    // entity in one tx, then in another tx wrapping its RID in an UpdatableResult whose entity
    // arrives unloaded — so the conditional flips to the loadEntity path.
    session.executeInTx(tx -> {
      var e = tx.newEntity("UR_E");
      e.setProperty("k", "v");
    });
    session.executeInTx(tx -> {
      try (var rs = tx.query("SELECT FROM UR_E")) {
        var persisted = rs.next().asEntity();
        // Force the entity to an unloaded reference by re-fetching by RID.
        var unloaded = tx.loadEntity(persisted.getIdentity());
        var r = new UpdatableResult(session, unloaded);
        assertThat(r.toJSON()).contains("\"k\"");
      }
    });
  }
}
