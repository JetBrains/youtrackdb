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
import java.util.Collection;
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
    checkSession();
    ((Entity) identifiable).setProperty(name, value);
  }

  public void removeProperty(String name) {
    checkSession();
    ((Entity) identifiable).removeProperty(name);
  }

  @Override
  public boolean isRecord() {
    checkSession();
    return true;
  }

  @Override
  public Entity getEntity(@Nonnull String name) {
    checkSession();
    return castToEntity().getEntity(name);
  }

  @Nullable
  @Override
  public Result getResult(@Nonnull String name) {
    checkSession();
    return castToEntity().getResult(name);
  }

  @Override
  public Vertex getVertex(@Nonnull String name) {
    checkSession();
    return castToEntity().getVertex(name);
  }

  @Override
  public Edge getEdge(@Nonnull String name) {
    checkSession();
    return castToEntity().getEdge(name);
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    checkSession();
    return castToEntity().getLink(name);
  }

  @Override
  public Blob getBlob(String name) {
    checkSession();
    return castToEntity().getBlob(name);
  }

  @Nonnull
  @Override
  public Collection<String> getPropertyNames() {
    checkSession();
    return castToEntity().getPropertyNames();
  }

  @Override
  public boolean hasProperty(@Nonnull String propName) {
    checkSession();
    return castToEntity().hasProperty(propName);
  }

  @Override
  @Nonnull
  public Result detach() {
    checkSession();
    return castToEntity().detach();
  }

  @Override
  public boolean isEntity() {
    checkSession();
    return true;
  }

  @Nonnull
  @Override
  public Entity castToEntity() {
    checkSession();
    var entity = ((Entity) identifiable);

    if (session != null) {
      if (entity.isNotBound(session)) {
        entity = session.bindToSession(entity);
        identifiable = entity;
      }

      return entity;
    }

    return ((Entity) identifiable);
  }

  @Nullable
  @Override
  public Entity asEntity() {
    checkSession();
    return castToEntity();
  }

  @Override
  public RID getIdentity() {
    checkSession();
    return identifiable.getIdentity();
  }

  @Override
  public boolean isProjection() {
    checkSession();
    return false;
  }

  @Nonnull
  @Override
  public DBRecord castToRecord() {
    checkSession();
    return castToEntity();
  }

  @Nullable
  @Override
  public DBRecord asRecord() {
    checkSession();
    return castToEntity();
  }

  @Override
  public boolean isBlob() {
    checkSession();
    return false;
  }

  @Nonnull
  @Override
  public Blob castToBlob() {
    checkSession();
    throw new DatabaseException("Result is not a blob");
  }

  @Nullable
  @Override
  public Blob asBlob() {
    checkSession();
    return null;
  }

  @Override
  public void setIdentifiable(Identifiable identifiable) {
    checkSession();
    if (identifiable instanceof Entity) {
      this.identifiable = identifiable;
    } else {
      checkSessionForRecords();
      this.identifiable = identifiable.getEntity(session);
    }
  }

  @Override
  public void setLightweightEdge(Edge edge) {
    checkSession();
    throw new UnsupportedOperationException();
  }

  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    checkSession();
    return castToEntity().toMap();
  }

  @Override
  public boolean isEdge() {
    checkSession();
    return castToEntity().isEdge();
  }

  @Override
  public boolean isStatefulEdge() {
    checkSession();
    return castToEntity().isStatefulEdge();
  }

  @Nonnull
  @Override
  public Edge castToEdge() {
    checkSession();
    return castToEntity().castToEdge();
  }

  @Nullable
  @Override
  public Edge asEdge() {
    checkSession();
    return castToEntity().asEdge();
  }

  @Nonnull
  @Override
  public String toJSON() {
    checkSession();
    return castToEntity().toJSON();
  }

  @Override
  public String toString() {
    return identifiable.toString();
  }
}
