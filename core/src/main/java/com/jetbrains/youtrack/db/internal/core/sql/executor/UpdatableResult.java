package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class UpdatableResult extends ResultInternal {

  protected ResultInternal previousValue = null;

  public UpdatableResult(DatabaseSessionInternal session, Entity entity) {
    super(session, entity);
  }

  @Override
  public void setProperty(@Nonnull String name, Object value) {
    assert checkSession();
    asEntity().setProperty(name, value);
  }

  public void removeProperty(String name) {
    assert checkSession();
    asEntity().removeProperty(name);
  }

  @Override
  public boolean isRecord() {
    assert checkSession();
    return true;
  }

  @Override
  public Entity getEntity(@Nonnull String name) {
    assert checkSession();
    return this.asEntity().getEntity(name);
  }

  @Nullable
  @Override
  public Result getResult(@Nonnull String name) {
    assert checkSession();
    return this.asEntity().getResult(name);
  }

  @Override
  public Vertex getVertex(@Nonnull String name) {
    assert checkSession();
    return this.asEntity().getVertex(name);
  }

  @Override
  public Edge getEdge(@Nonnull String name) {
    assert checkSession();
    return this.asEntity().getEdge(name);
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    assert checkSession();
    return this.asEntity().getLink(name);
  }

  @Override
  public Blob getBlob(String name) {
    assert checkSession();
    return this.asEntity().getBlob(name);
  }

  @Nonnull
  @Override
  public List<String> getPropertyNames() {
    assert checkSession();
    return this.asEntity().getPropertyNames();
  }

  @Override
  public boolean hasProperty(@Nonnull String propName) {
    assert checkSession();
    return this.asEntity().hasProperty(propName);
  }

  @Override
  @Nonnull
  public Result detach() {
    assert checkSession();
    return this.asEntity().detach();
  }

  @Override
  public boolean isEntity() {
    assert checkSession();
    return true;
  }

  @Nonnull
  @Override
  public Entity asEntity() {
    assert checkSession();
    return ((Entity) identifiable);
  }

  @Nullable
  @Override
  public Entity asEntityOrNull() {
    assert checkSession();
    return this.asEntity();
  }

  @Override
  public RID getIdentity() {
    assert checkSession();
    return identifiable.getIdentity();
  }

  @Override
  public boolean isProjection() {
    assert checkSession();
    return false;
  }

  @Nonnull
  @Override
  public DBRecord asRecord() {
    assert checkSession();
    return this.asEntity();
  }

  @Nullable
  @Override
  public DBRecord asRecordOrNull() {
    assert checkSession();
    return this.asEntity();
  }

  @Override
  public boolean isBlob() {
    assert checkSession();
    return false;
  }

  @Nonnull
  @Override
  public Blob asBlob() {
    assert checkSession();
    throw new DatabaseException("Result is not a blob");
  }

  @Nullable
  @Override
  public Blob asBlobOrNull() {
    assert checkSession();
    return null;
  }

  @Override
  public void setIdentifiable(Identifiable identifiable) {
    assert checkSession();
    if (identifiable instanceof Entity) {
      this.identifiable = identifiable;
    } else {
      checkSessionForRecords();
      var transaction = session.getActiveTransaction();
      this.identifiable = transaction.loadEntity(identifiable);
    }
  }

  @Override
  public void setLightweightEdge(Edge edge) {
    assert checkSession();
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    assert checkSession();
    return this.asEntity().toMap();
  }

  @Override
  public boolean isEdge() {
    assert checkSession();
    return this.asEntity().isEdge();
  }

  @Override
  public boolean isStatefulEdge() {
    assert checkSession();
    return this.asEntity().isStatefulEdge();
  }

  @Nonnull
  @Override
  public Edge asEdge() {
    assert checkSession();
    return this.asEntity().asEdge();
  }

  @Nullable
  @Override
  public Edge asEdgeOrNull() {
    assert checkSession();
    return this.asEntity().asEdgeOrNull();
  }

  @Nonnull
  @Override
  public String toJSON() {
    assert checkSession();
    return this.asEntity().toJSON();
  }

  @Override
  public String toString() {
    return identifiable.toString();
  }
}
