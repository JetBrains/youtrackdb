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
package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.StatefulEdge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.HashSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EdgeImpl extends LightweightRelationImpl<Vertex> implements EdgeInternal {
  @Nonnull
  private final SchemaImmutableClass lightweightEdgeType;

  public EdgeImpl(@Nonnull DatabaseSessionInternal session,
      @Nullable Vertex out, @Nullable Vertex in,
      @Nonnull SchemaImmutableClass lightweightEdgeType) {
    super(session, out, in, lightweightEdgeType.getName());
    this.lightweightEdgeType = lightweightEdgeType;
  }


  @Nullable
  @Override
  public Identifiable getFromLink() {
    return this.getFrom();
  }

  @Nullable
  @Override
  public Identifiable getToLink() {
    return this.getTo();
  }

  @Override
  public boolean isLightweight() {
    return true;
  }

  @Override
  public void delete() {
    StatefullEdgeEntityImpl.deleteLinks(session, this);
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

  @Override
  public boolean isLabeled(@Nonnull String[] labels) {
    if (super.isLabeled(labels)) {
      return true;
    }

    var types = new HashSet<String>();

    var typeClass = getSchemaClass();
    typeClass.getAllSuperClasses().stream()
        .map(SchemaClass::getName)
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
  public StatefulEdge asStatefulEdge() {
    throw new DatabaseException("Current edge instance is not a stateful edge");
  }

  @Nullable
  @Override
  public StatefulEdge asStatefulEdgeOrNull() {
    return null;
  }
}
