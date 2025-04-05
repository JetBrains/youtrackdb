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
package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class EdgeDelegate implements EdgeInternal {

  protected Vertex vOut;
  protected Vertex vIn;
  protected SchemaImmutableClass lightweightEdgeType;
  protected String lightwightEdgeLabel;

  protected EntityImpl entity;

  public EdgeDelegate(
      Vertex out, Vertex in, SchemaImmutableClass lightweightEdgeType, String edgeLabel) {
    vOut = out;
    vIn = in;
    this.lightweightEdgeType = lightweightEdgeType;
    this.lightwightEdgeLabel = edgeLabel;
  }

  public EdgeDelegate(EntityImpl elem) {
    this.entity = elem;
  }

  @Override
  public Vertex getFrom() {
    if (vOut != null) {
      // LIGHTWEIGHT EDGE
      return vOut;
    }

    final EntityImpl entity;
    try {
      entity = getRecord();
    } catch (RecordNotFoundException rnf) {
      return null;
    }

    Object result = entity.getProperty(DIRECTION_OUT);
    if (!(result instanceof Entity v)) {
      return null;
    }

    if (!v.isVertex()) {
      return null; // TODO optional...?
    }
    return v.toVertex();
  }

  @Override
  public Identifiable getFromIdentifiable() {
    if (vOut != null) {
      // LIGHTWEIGHT EDGE
      return vOut;
    }

    final EntityImpl entity;
    try {
      entity = getRecord();
    } catch (RecordNotFoundException rnf) {
      return null;
    }

    var result = entity.getLinkProperty(DIRECTION_OUT);
    assert result != null;

    var id = result.getIdentity();
    var db = entity.getSession();
    var schema = db.getMetadata().getSchema();

    if (schema.getClassByClusterId(id.getClusterId()).isVertexType(db)) {
      return id;
    }

    return null;
  }

  @Override
  public boolean isEmbedded() {
    return false;
  }

  @Override
  public Vertex getTo() {
    if (vIn != null)
    // LIGHTWEIGHT EDGE
    {
      return vIn;
    }

    final EntityImpl entity;
    try {
      entity = getRecord();
    } catch (RecordNotFoundException rnf) {
      return null;
    }

    Object result = entity.getProperty(DIRECTION_IN);
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
    if (vIn != null) {
      // LIGHTWEIGHT EDGE
      return vIn;
    }

    final EntityImpl entity;

    try {
      entity = getRecord();
    } catch (RecordNotFoundException rnf) {
      return null;
    }

    var result = entity.getLinkProperty(DIRECTION_IN);
    assert result != null;

    var id = result.getIdentity();
    var schema = entity.getSession().getMetadata().getSchema();

    if (schema.getClassByClusterId(id.getClusterId()).isVertexType(entity.getSession())) {
      return id;
    }

    return null;
  }

  @Override
  public boolean isLightweight() {
    return this.entity == null;
  }

  public void delete() {
    if (entity != null) {
      entity.delete();
    } else {
      EdgeEntityImpl.deleteLinks(this);
    }
  }

  @Override
  public void promoteToRegularEdge() {

    var from = getFrom();
    Vertex to = getTo();
    VertexInternal.removeOutgoingEdge(from, this);
    VertexInternal.removeIncomingEdge(to, this);

    var db = entity.getSession();
    this.entity =
        db.newRegularEdge(
                lightweightEdgeType == null ? "E" : lightweightEdgeType.getName(), from, to)
            .getRecord();
    this.lightweightEdgeType = null;
    this.vOut = null;
    this.vIn = null;
  }

  @Override
  @Nullable
  public EntityImpl getBaseDocument() {
    return entity;
  }

  @Override
  public Optional<Vertex> asVertex() {
    return Optional.empty();
  }

  @Nullable
  @Override
  public Vertex toVertex() {
    return null;
  }

  @Override
  public Optional<Edge> asEdge() {
    return Optional.of(this);
  }

  @Nonnull
  @Override
  public Edge toEdge() {
    return this;
  }

  @Override
  public boolean isVertex() {
    return false;
  }

  @Override
  public boolean isEdge() {
    return true;
  }

  @Override
  public Optional<SchemaClass> getSchemaType() {
    if (entity == null) {
      return Optional.ofNullable(lightweightEdgeType);
    }
    return entity.getSchemaType();
  }

  @Nullable
  @Override
  public SchemaClass getSchemaClass() {
    if (entity == null) {
      return lightweightEdgeType;
    }
    return entity.getSchemaClass();
  }

  public boolean isLabeled(String[] labels) {
    if (labels == null) {
      return true;
    }
    if (labels.length == 0) {
      return true;
    }
    Set<String> types = new HashSet<>();

    Optional<SchemaClass> typeClass = getSchemaType();
    if (typeClass.isPresent()) {
      types.add(typeClass.get().getName());
      typeClass.get().getAllSuperClasses(entity.getSession()).stream()
          .map(x -> x.getName())
          .forEach(name -> types.add(name));
    } else {
      if (lightwightEdgeLabel != null) {
        types.add(lightwightEdgeLabel);
      } else {
        types.add("E");
      }
    }
    for (String s : labels) {
      for (String type : types) {
        if (type.equalsIgnoreCase(s)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public RID getIdentity() {
    if (entity == null) {
      return null;
    }
    return entity.getIdentity();
  }

  @Nonnull
  @Override
  public <T extends DBRecord> T getRecord() {

    if (entity == null) {
      return null;
    }
    return (T) entity;
  }

  @Override
  public int compare(Identifiable o1, Identifiable o2) {
    return o1.compareTo(o2);
  }

  @Override
  public int compareTo(Identifiable o) {
    return 0;
  }

  @Override
  public boolean equals(Object obj) {
    if (entity == null) {
      return this == obj;
      // TODO double-check this logic for lightweight edges
    }
    if (!(obj instanceof Identifiable)) {
      return false;
    }
    if (!(obj instanceof Entity)) {
      obj = ((Identifiable) obj).getRecord();
    }

    return entity.equals(((Entity) obj).getRecord());
  }

  @Override
  public int hashCode() {
    if (entity == null) {
      return super.hashCode();
    }

    return entity.hashCode();
  }

  @Override
  public void clear() {
    if (entity != null) {
      entity.clear();
    }
  }

  @Override
  public int getVersion() {
    if (entity != null) {
      return entity.getVersion();
    }
    return 1;
  }

  @Override
  public boolean isDirty() {
    if (entity != null) {
      return entity.isDirty();
    }
    return false;
  }

  @Override
  public void save() {
    if (entity != null) {
      entity.save();
    } else {
      vIn.save();
    }
  }

  @Override
  public void fromJSON(String iJson) {
    if (entity == null) {
      promoteToRegularEdge();
    }
    entity.fromJSON(iJson);
  }

  @Override
  public String toJSON() {
    if (entity != null) {
      return entity.toJSON();
    } else {
      return "{\"out\":\""
          + vOut.getIdentity()
          + "\", \"in\":\""
          + vIn.getIdentity()
          + "\", \"@class\":\""
          + StringSerializerHelper.encode(lightweightEdgeType.getName())
          + "\"}";
    }
  }

  @Override
  public String toJSON(String iFormat) {
    if (entity != null) {
      return entity.toJSON(iFormat);
    } else {
      return "{\"out\":\""
          + vOut.getIdentity()
          + "\", \"in\":\""
          + vIn.getIdentity()
          + "\", \"@class\":\""
          + StringSerializerHelper.encode(lightweightEdgeType.getName())
          + "\"}";
    }
  }

  @Override
  public void fromMap(Map<String, ?> map) {
    if (entity != null) {
      entity.fromMap(map);
    }

    throw new UnsupportedOperationException("fromMap is not supported for lightweight edges");
  }

  @Override
  public Map<String, Object> toMap() {
    return Map.of(DIRECTION_OUT, getToIdentifiable(), DIRECTION_IN, getFromIdentifiable());
  }

  @Override
  public boolean isNotBound(DatabaseSession session) {
    if (entity != null) {
      return entity.isNotBound(session);
    }

    return false;
  }

  @Override
  public String toString() {
    if (entity != null) {
      return entity.toString();
    } else {
      StringBuilder result = new StringBuilder();
      boolean first = true;
      result.append("{");
      if (lightweightEdgeType != null) {
        result.append("class: " + lightweightEdgeType.getName());
        first = false;
      }
      if (vOut != null) {
        if (!first) {
          result.append(", ");
        }
        result.append("out: " + vOut.getIdentity());
        first = false;
      }
      if (vIn != null) {
        if (!first) {
          result.append(", ");
        }
        result.append("in: " + vIn.getIdentity());
        first = false;
      }
      result.append("} (lightweight)");
      return result.toString();
    }
  }

  @Nullable
  @Override
  public DatabaseSession getBoundedToSession() {
    if (entity != null) {
      return entity.getBoundedToSession();
    }

    return null;
  }
}
