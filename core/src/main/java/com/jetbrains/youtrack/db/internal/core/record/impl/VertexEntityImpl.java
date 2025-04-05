package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class VertexEntityImpl extends EntityImpl implements VertexInternal {

  public VertexEntityImpl() {
    super();
  }

  public VertexEntityImpl(DatabaseSessionInternal database, RID rid) {
    super(database, (RecordId) rid);
  }

  public VertexEntityImpl(DatabaseSessionInternal session, String klass) {
    super(session, klass);
    if (!getImmutableSchemaClass().isVertexType(session)) {
      throw new IllegalArgumentException(getClassName() + " is not a vertex class");
    }
  }

  @Override
  public Set<String> getPropertyNames() {
    checkForBinding();

    return VertexInternal.filterPropertyNames(super.getPropertyNames());
  }

  @Override
  public <RET> RET getProperty(String fieldName) {
    checkForBinding();

    VertexInternal.checkPropertyName(fieldName);

    return getPropertyInternal(fieldName);
  }

  @Nullable
  @Override
  public Identifiable getLinkProperty(String fieldName) {
    checkForBinding();

    VertexInternal.checkPropertyName(fieldName);

    return super.getLinkProperty(fieldName);
  }

  @Override
  public void setProperty(String fieldName, Object propertyValue) {
    checkForBinding();

    VertexInternal.checkPropertyName(fieldName);
    setPropertyInternal(fieldName, propertyValue);
  }

  @Override
  public void setProperty(String name, Object value, PropertyType... types) {
    checkForBinding();

    VertexInternal.checkPropertyName(name);
    super.setProperty(name, value, types);
  }

  @Override
  public <RET> RET removeProperty(String fieldName) {
    checkForBinding();

    VertexInternal.checkPropertyName(fieldName);
    return removePropertyInternal(fieldName);
  }

  @Override
  public Iterable<Vertex> getVertices(Direction direction) {
    checkForBinding();
    return VertexInternal.super.getVertices(direction);
  }

  @Override
  public void delete() {
    checkForBinding();

    super.delete();
  }

  @Override
  public VertexEntityImpl copy() {
    checkForBinding();

    var newEntity = new VertexEntityImpl();
    RecordInternal.unsetDirty(newEntity);
    copyTo(newEntity);
    newEntity.dirty = true;

    return newEntity;
  }

  @Override
  @Nonnull
  public EntityImpl getBaseDocument() {
    return this;
  }
}
