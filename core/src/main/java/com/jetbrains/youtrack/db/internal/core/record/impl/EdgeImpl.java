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

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EdgeImpl implements EdgeInternal {

  @Nullable
  private final Vertex vOut;
  @Nullable
  private final Vertex vIn;

  @Nonnull
  private final SchemaImmutableClass lightweightEdgeType;
  @Nonnull
  private final DatabaseSessionInternal session;

  public EdgeImpl(@Nonnull DatabaseSessionInternal session,
      @Nullable Vertex out, @Nullable Vertex in,
      @Nonnull SchemaImmutableClass lightweightEdgeType) {
    vOut = out;
    vIn = in;

    this.lightweightEdgeType = lightweightEdgeType;
    this.session = session;
  }

  @Nullable
  @Override
  public Vertex getFrom() {
    return vOut;
  }

  @Nullable
  @Override
  public Identifiable getFromLink() {
    return vOut;
  }

  @Nullable
  @Override
  public Vertex getTo() {
    return vIn;

  }

  @Nullable
  @Override
  public Identifiable getToLink() {
    return vIn;
  }

  @Override
  public boolean isLightweight() {
    return true;
  }

  public void delete() {
    EdgeEntityImpl.deleteLinks(session, this);
  }

  @Nonnull
  @Override
  public SchemaClass getSchemaClass() {
    return lightweightEdgeType;
  }

  @Nonnull
  @Override
  public String getSchemaClassName() {
    return lightweightEdgeType.getName();
  }

  public boolean isLabeled(String[] labels) {
    if (labels == null) {
      return true;
    }
    if (labels.length == 0) {
      return true;
    }
    Set<String> types = new HashSet<>();

    var typeClass = getSchemaClass();
    types.add(typeClass.getName());
    typeClass.getAllSuperClasses().stream()
        .map(x -> x.getName())
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


  @Nonnull
  @Override
  public Map<String, Object> toMap() {
    return Map.of(DIRECTION_OUT, getToLink(), DIRECTION_IN, getFromLink());
  }

  @Nonnull
  @Override
  public String toJSON() {
    return "{\"out\":\""
        + vOut.getIdentity()
        + "\", \"in\":\""
        + vIn.getIdentity()
        + "\", \"@class\":\""
        + StringSerializerHelper.encode(lightweightEdgeType.getName())
        + "\"}";
  }

  @Nonnull
  @Override
  public StatefulEdge castToStatefulEdge() {
    throw new DatabaseException("Current edge instance is not a stateful edge");
  }

  @Nullable
  @Override
  public StatefulEdge asStatefulEdge() {
    return null;
  }
}
