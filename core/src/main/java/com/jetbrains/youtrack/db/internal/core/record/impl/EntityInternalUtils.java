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

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyAccess;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryption;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

public class EntityInternalUtils {
  public static void rawField(
      final EntityImpl entity,
      final String iFieldName,
      final Object iFieldValue,
      final PropertyType iFieldType) {
    entity.rawField(iFieldName, iFieldValue, iFieldType);
  }

  public static boolean rawContainsField(final EntityImpl entity, final String iFiledName) {
    return entity.rawContainsField(iFiledName);
  }

  public static ImmutableSchema getImmutableSchema(final EntityImpl entity) {
    if (entity == null) {
      return null;
    }
    return entity.getImmutableSchema();
  }

  public static GlobalProperty getGlobalPropertyById(final EntityImpl entity, final int id) {
    return entity.getGlobalPropertyById(id);
  }

  public static void fillClassNameIfNeeded(final EntityImpl entity, String className) {
    entity.fillClassIfNeed(className);
  }

  public static Set<Entry<String, EntityEntry>> rawEntries(final EntityImpl entity) {
    return entity.getRawEntries();
  }

  public static List<Entry<String, EntityEntry>> filteredEntries(final EntityImpl entity) {
    return entity.getFilteredEntries();
  }

  public static void autoConvertValueToClass(DatabaseSessionInternal database, EntityImpl entity) {
    entity.autoConvertFieldsToClass(database);
  }

  public static Object getRawProperty(EntityImpl entity, String propertyName) {
    if (entity == null) {
      return null;
    }
    return entity.getRawProperty(propertyName);
  }

  public static <RET> RET rawPropertyRead(Entity element, String propertyName) {
    if (element == null) {
      return null;
    }
    return ((EntityImpl) element).rawField(propertyName);
  }

  public static void setPropertyAccess(EntityImpl entity, PropertyAccess propertyAccess) {
    entity.propertyAccess = propertyAccess;
  }

  public static PropertyAccess getPropertyAccess(EntityImpl entity) {
    return entity.propertyAccess;
  }

  public static void setPropertyEncryption(EntityImpl entity,
      PropertyEncryption propertyEncryption) {
    entity.propertyEncryption = propertyEncryption;
  }

  public static PropertyEncryption getPropertyEncryption(EntityImpl entity) {
    return entity.propertyEncryption;
  }

  public static Iterator<String> iteratePropertieNames(EntityImpl entity) {
    return entity.calculatePropertyNames().iterator();
  }
}
