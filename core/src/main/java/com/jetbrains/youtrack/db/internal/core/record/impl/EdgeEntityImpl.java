package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.Set;
import javax.annotation.Nullable;

public class EdgeEntityImpl extends EntityImpl implements EdgeInternal {

  public EdgeEntityImpl(DatabaseSessionInternal session, String cl) {
    super(session, cl);
  }

  public EdgeEntityImpl() {
    super();
  }

  public EdgeEntityImpl(DatabaseSessionInternal session) {
    super(session);
  }

  public EdgeEntityImpl(DatabaseSessionInternal database, RecordId rid) {
    super(database, rid);
  }

  @Override
  public Vertex getFrom() {
    Object result = getPropertyInternal(DIRECTION_OUT);
    if (!(result instanceof Entity v)) {
      return null;
    }

    if (!v.isVertex()) {
      return null;
    }

    return v.toVertex();
  }

  @Override
  @Nullable
  public Identifiable getFromIdentifiable() {
    var db = getSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();

    var result = getLinkPropertyInternal(DIRECTION_OUT);
    if (result == null) {
      return null;
    }

    var rid = result.getIdentity();
    if (schema.getClassByClusterId(rid.getClusterId()).isVertexType(getSession())) {
      return rid;
    }

    return null;
  }

  @Override
  public Vertex getTo() {
    Object result = getPropertyInternal(DIRECTION_IN);
    if (!(result instanceof Entity v)) {
      return null;
    }

    if (!v.isVertex()) {
      return null;
    }

    return v.toVertex();
  }

  @Override
  public Identifiable getToIdentifiable() {
    var db = getSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();

    var result = getLinkPropertyInternal(DIRECTION_IN);
    if (result == null) {
      return null;
    }

    var rid = result.getIdentity();
    if (schema.getClassByClusterId(rid.getClusterId()).isVertexType(getSession())) {
      return rid;
    }

    return null;
  }

  @Override
  public boolean isLightweight() {
    // LIGHTWEIGHT EDGES MANAGED BY EdgeDelegate, IN FUTURE MAY BE WE NEED TO HANDLE THEM WITH THIS
    return false;
  }

  public void delete() {
    checkForBinding();

    super.delete();
  }

  @Override
  @Nullable
  public EntityImpl getBaseDocument() {
    return this;
  }

  @Override
  public EdgeEntityImpl copy() {
    checkForBinding();

    return (EdgeEntityImpl) super.copyTo(new EdgeEntityImpl());
  }

  @Override
  public Set<String> getPropertyNames() {
    checkForBinding();

    return EdgeInternal.filterPropertyNames(super.getPropertyNames());
  }

  @Override
  public <RET> RET getProperty(String fieldName) {
    checkForBinding();

    EdgeInternal.checkPropertyName(fieldName);

    return getPropertyInternal(fieldName);
  }

  @Nullable
  @Override
  public Identifiable getLinkProperty(String fieldName) {
    checkForBinding();

    EdgeInternal.checkPropertyName(fieldName);

    return super.getLinkProperty(fieldName);
  }

  @Override
  public void setProperty(String fieldName, Object propertyValue) {
    checkForBinding();

    EdgeInternal.checkPropertyName(fieldName);

    setPropertyInternal(fieldName, propertyValue);
  }

  @Override
  public void setProperty(String name, Object value, PropertyType... types) {
    checkForBinding();
    EdgeInternal.checkPropertyName(name);

    super.setProperty(name, value, types);
  }

  @Override
  public <RET> RET removeProperty(String fieldName) {
    checkForBinding();
    EdgeInternal.checkPropertyName(fieldName);

    return removePropertyInternal(fieldName);
  }

  @Override
  public void promoteToRegularEdge() {
    checkForBinding();
  }

  public static void deleteLinks(Edge delegate) {
    Vertex from = delegate.getFrom();
    if (from != null) {
      VertexInternal.removeOutgoingEdge(from, delegate);
    }
    Vertex to = delegate.getTo();
    if (to != null) {
      VertexInternal.removeIncomingEdge(to, delegate);
    }
  }
}
