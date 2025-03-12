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
package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.db.record.IdentifiableMultiValue;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class allows to walk through all fields of single entity using instance of
 * {@link EntityPropertiesVisitor} class.
 *
 * <p>Only current entity and embedded documents will be walked. Which means that all embedded
 * collections will be visited too and all embedded documents which are contained in this
 * collections also will be visited.
 *
 * <p>Fields values can be updated/converted too. If method {@link
 * EntityPropertiesVisitor#visitField(DatabaseSessionInternal, PropertyType, PropertyType, Object)}
 * will return new value original value will be updated but returned result will not be visited by
 * {@link EntityPropertiesVisitor} instance.
 *
 * <p>If currently processed value is collection or map of embedded documents or embedded entity
 * itself then method {@link EntityPropertiesVisitor#goDeeper(PropertyType, PropertyType, Object)}
 * is called, if it returns false then this collection will not be visited by
 * {@link EntityPropertiesVisitor} instance.
 *
 * <p>Fields will be visited till method
 * {@link EntityPropertiesVisitor#goFurther(PropertyType, PropertyType, Object, Object)} returns
 * true.
 */
public class EntityFieldWalker {

  public EntityImpl walkDocument(
      DatabaseSessionInternal session, EntityImpl entity, EntityPropertiesVisitor fieldWalker) {
    final Set<EntityImpl> walked = Collections.newSetFromMap(new IdentityHashMap<>());

    if (entity.getIdentity().isValid()) {
      entity = session.bindToSession(entity);
    }

    walkDocument(session, entity, fieldWalker, walked);
    walked.clear();
    return entity;
  }

  private void walkDocument(
      DatabaseSessionInternal session,
      EntityImpl entity,
      EntityPropertiesVisitor fieldWalker,
      Set<EntityImpl> walked) {
    if (entity.isUnloaded()) {
      throw new IllegalStateException("Entity is unloaded");
    }

    if (walked.contains(entity)) {
      return;
    }

    walked.add(entity);
    var oldLazyLoad = entity.isLazyLoad();
    entity.setLazyLoad(false);

    final var updateMode = fieldWalker.updateMode();

    SchemaImmutableClass result = null;
    result = entity.getImmutableSchemaClass(session);
    final SchemaClass clazz = result;
    for (var fieldName : entity.fieldNames()) {

      final var concreteType = entity.getPropertyType(fieldName);
      var fieldType = concreteType;

      PropertyType linkedType = null;
      if (fieldType == null && clazz != null) {
        var property = clazz.getProperty(fieldName);
        if (property != null) {
          fieldType = property.getType();
          linkedType = property.getLinkedType();
        }
      }

      var fieldValue = entity.getPropertyInternal(fieldName);
      var newValue = fieldWalker.visitField(session, fieldType, linkedType, fieldValue);

      boolean updated;
      if (updateMode) {
        updated =
            updateFieldValueIfChanged(entity, fieldName, fieldValue, newValue, concreteType);
      } else {
        updated = false;
      }

      // exclude cases when:
      // 1. value was updated.
      // 2. we use link types.
      // 3. entity is not not embedded.
      if (!updated
          && fieldValue != null
          && !(PropertyType.LINK.equals(fieldType)
          || PropertyType.LINKBAG.equals(fieldType)
          || PropertyType.LINKLIST.equals(fieldType)
          || PropertyType.LINKSET.equals(fieldType)
          || (fieldValue instanceof IdentifiableMultiValue))) {
        if (fieldWalker.goDeeper(fieldType, linkedType, fieldValue)) {
          if (fieldValue instanceof Map) {
            walkMap(session, (Map) fieldValue, fieldType, fieldWalker, walked);
          } else if (fieldValue instanceof EntityImpl e) {
            if (PropertyType.EMBEDDED.equals(fieldType) || e.isEmbedded()) {
              var fEntity = (EntityImpl) fieldValue;
              if (fEntity.isUnloaded()) {
                throw new IllegalStateException("Entity is unloaded");
              }
              walkDocument(session, fEntity, fieldWalker);
            }
          } else if (MultiValue.isIterable(fieldValue)) {
            walkIterable(
                session,
                MultiValue.getMultiValueIterable(
                    fieldValue),
                fieldType,
                fieldWalker,
                walked);
          }
        }
      }

      if (!fieldWalker.goFurther(fieldType, linkedType, fieldValue, newValue)) {
        entity.setLazyLoad(oldLazyLoad);
        return;
      }
    }

    entity.setLazyLoad(oldLazyLoad);
  }

  private void walkMap(
      DatabaseSessionInternal session,
      Map map,
      PropertyType fieldType,
      EntityPropertiesVisitor fieldWalker,
      Set<EntityImpl> walked) {
    for (var value : map.values()) {
      if (value instanceof EntityImpl entity) {
        // only embedded documents are walked
        if (PropertyType.EMBEDDEDMAP.equals(fieldType) || entity.isEmbedded()) {
          walkDocument(session, (EntityImpl) value, fieldWalker, walked);
        }
      }
    }
  }

  private void walkIterable(
      DatabaseSessionInternal session,
      Iterable iterable,
      PropertyType fieldType,
      EntityPropertiesVisitor fieldWalker,
      Set<EntityImpl> walked) {
    for (var value : iterable) {
      if (value instanceof EntityImpl entity) {
        // only embedded documents are walked
        if (PropertyType.EMBEDDEDLIST.equals(fieldType)
            || PropertyType.EMBEDDEDSET.equals(fieldType)
            || entity.isEmbedded()) {
          walkDocument(session, (EntityImpl) value, fieldWalker, walked);
        }
      }
    }
  }

  private static boolean updateFieldValueIfChanged(
      EntityImpl entity,
      String fieldName,
      Object fieldValue,
      Object newValue,
      PropertyType concreteType) {
    if (fieldValue != newValue) {
      entity.field(fieldName, newValue, concreteType);
      return true;
    }

    return false;
  }
}
