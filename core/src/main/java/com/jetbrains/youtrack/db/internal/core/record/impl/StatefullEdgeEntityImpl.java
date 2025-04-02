package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class StatefullEdgeEntityImpl extends EntityImpl implements EdgeInternal, StatefulEdge {

  public static final byte RECORD_TYPE = 'e';

  public StatefullEdgeEntityImpl(@Nonnull DatabaseSessionInternal session, String iClassName) {
    super(session, iClassName);
  }

  public StatefullEdgeEntityImpl(DatabaseSessionInternal database, RecordId rid) {
    super(database, rid);
  }

  @Nullable
  @Override
  public Vertex getFrom() {
    var result = getPropertyInternal(DIRECTION_OUT);
    if (!(result instanceof Entity v)) {
      return null;
    }

    if (!v.isVertex()) {
      return null;
    }

    return v.asVertex();
  }

  @Override
  public boolean isLabeled(@Nonnull String[] labels) {
    if (labels.length == 0) {
      return true;
    }
    Set<String> types = new HashSet<>();

    var typeClass = getImmutableSchemaClass(session);

    types.add(typeClass.getName());
    typeClass.getAllSuperClasses().stream().map(SchemaClass::getName)
        .forEach(types::add);

    for (var s : labels) {
      for (var type : types) {
        if (type.equalsIgnoreCase(s)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public Vertex getEntity(@Nonnull Direction dir) {
    return getVertex(dir);
  }


  @Nullable
  @Override
  public Identifiable getFromLink() {
    var db = getSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();

    var result = getLinkPropertyInternal(DIRECTION_OUT);
    if (result == null) {
      return null;
    }

    var rid = result.getIdentity();
    if (schema.getClassByClusterId(rid.getClusterId()).isVertexType()) {
      return rid;
    }

    return null;
  }

  @Nullable
  @Override
  public Vertex getTo() {
    var result = getPropertyInternal(DIRECTION_IN);
    if (!(result instanceof Entity v)) {
      return null;
    }

    if (!v.isVertex()) {
      return null;
    }

    return v.asVertex();
  }

  @Nullable
  @Override
  public Identifiable getToLink() {
    checkForBinding();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var result = getLinkPropertyInternal(DIRECTION_IN);
    if (result == null) {
      return null;
    }

    var rid = result.getIdentity();
    if (schema.getClassByClusterId(rid.getClusterId()).isVertexType()) {
      return rid;
    }

    return null;
  }

  @Override
  public boolean isLightweight() {
    return false;
  }

  @Nonnull
  @Override
  public Entity asEntity() {
    return this;
  }

  @Override
  public String label() {
    var typeClass = getImmutableSchemaClass(session);
    return typeClass.getName();
  }

  @Override
  public @Nonnull List<String> getPropertyNames() {
    return EdgeInternal.filterPropertyNames(super.getPropertyNames());
  }

  @Override
  protected void validatePropertyName(String propertyName, boolean allowMetadata) {
    EdgeInternal.checkPropertyName(propertyName);
    super.validatePropertyName(propertyName, allowMetadata);
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String fieldName) {
    EdgeInternal.checkPropertyName(fieldName);

    return super.getLink(fieldName);
  }

  @Nonnull
  @Override
  public SchemaClass getSchemaClass() {
    return super.getSchemaClass();
  }

  @Nonnull
  @Override
  public String getSchemaClassName() {
    return super.getSchemaClassName();
  }

  public static void deleteLinks(DatabaseSessionInternal db, Edge delegate) {
    var from = delegate.getFrom();
    if (from != null) {
      VertexEntityImpl.removeOutgoingEdge(db, from, delegate);
    }

    var to = delegate.getTo();
    if (to != null) {
      VertexEntityImpl.removeIncomingEdge(db, to, delegate);
    }
  }

  @Override
  public byte getRecordType() {
    return RECORD_TYPE;
  }

  @Nullable
  @Override
  public Vertex fromEntity() {
    return getFrom();
  }

  @Nullable
  @Override
  public Vertex toEntity() {
    return getTo();
  }

  @Override
  public Iterable<Entity> getEntities(Direction direction, String... linkNames) {
    throw new UnsupportedOperationException("Operation not supported for edges");
  }

  @Override
  public Iterable<? extends BidirectionalLink<Entity>> getBidirectionalLinks(Direction direction,
      String... linkNames) {
    throw new UnsupportedOperationException("Operation not supported for edges");
  }
}
