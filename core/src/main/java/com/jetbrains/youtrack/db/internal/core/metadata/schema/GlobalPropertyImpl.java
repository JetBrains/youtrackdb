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

package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import javax.annotation.Nonnull;

public class GlobalPropertyImpl implements GlobalProperty {

  private String name;
  private PropertyType type;
  private Integer id;

  public GlobalPropertyImpl() {
  }

  public GlobalPropertyImpl(final String name, final PropertyType type, final Integer id) {
    this.name = name;
    this.type = type;
    this.id = id;
  }

  @Nonnull
  public Integer getId() {
    return id;
  }

  @Nonnull
  public String getName() {
    return name;
  }

  @Nonnull
  public PropertyType getType() {
    return type;
  }

  public void fromEntity(final Entity entity) {
    this.name = entity.getString("name");
    this.type = PropertyType.valueOf(entity.getString("type"));
    this.id = entity.getInt("id");
  }

  public Entity toEntity(DatabaseSessionInternal db) {
    final var entity = db.newEmbeddedEntity();
    entity.setString("name", name);
    entity.setString("type", type.name());
    entity.setInt("id", id);
    return entity;
  }
}
