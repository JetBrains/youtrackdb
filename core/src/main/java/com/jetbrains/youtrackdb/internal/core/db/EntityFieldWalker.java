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
package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassSnapshot;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
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
 * EntityPropertiesVisitor#visitField(DatabaseSessionInternal, PropertyTypeInternal, PropertyTypeInternal, Object)}
 * will return new value original value will be updated but returned result will not be visited by
 * {@link EntityPropertiesVisitor} instance.
 *
 * <p>If currently processed value is collection or map of embedded documents or embedded entity
 * itself then method {@link EntityPropertiesVisitor#goDeeper(PropertyTypeInternal, PropertyTypeInternal, Object)}
 * is called, if it returns false then this collection will not be visited by
 * {@link EntityPropertiesVisitor} instance.
 *
 * <p>Fields will be visited till method
 * {@link EntityPropertiesVisitor#goFurther(PropertyTypeInternal, PropertyTypeInternal, Object, Object)} returns
 * true.
 */
public class EntityFieldWalker {

  public EntityImpl walkDocument(
      DatabaseSessionInternal session, EntityImpl entity, EntityPropertiesVisitor fieldWalker) {
    final Set<EntityImpl> walked = Collections.newSetFromMap(new IdentityHashMap<>());

    if (entity.getIdentity().isValidPosition()) {
      var activeTx = session.getActiveTransaction();
      entity = activeTx.load(entity);
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

    SchemaClassSnapshot result;
    result = entity.getImmutableSchemaClass(session);
    final SchemaClass clazz = result;
    for (var fieldName : entity.getPropertyNamesInternal(false, false)) {

      final var concreteType = entity.getPropertyTypeInternal(fieldName);
      var fieldType = concreteType;

      PropertyTypeInternal linkedType = null;
      if (fieldType == null && clazz != null) {
        var property = clazz.getProperty(fieldName);
        if (property != null) {
          fieldType = PropertyTypeInternal.convertFromPublicType(property.getType());
          linkedType = PropertyTypeInternal.convertFromPublicType(property.getLinkedType());
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
          && !(PropertyTypeInternal.LINK == fieldType
          || PropertyTypeInternal.LINKBAG == fieldType
          || PropertyTypeInternal.LINKLIST == fieldType
          || PropertyTypeInternal.LINKSET == fieldType
          || PropertyTypeInternal.LINKMAP == fieldType)) {
        if (fieldWalker.goDeeper(fieldType, linkedType, fieldValue)) {
          if (fieldValue instanceof Map) {
            walkMap(session, (Map) fieldValue, fieldType, fieldWalker, walked);
          } else if (fieldValue instanceof EntityImpl e) {
            if (PropertyTypeInternal.EMBEDDED.equals(fieldType) || e.isEmbedded()) {
              if (e.isUnloaded()) {
                throw new IllegalStateException("Entity is unloaded");
              }
              walkDocument(session, e, fieldWalker);
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
      PropertyTypeInternal fieldType,
      EntityPropertiesVisitor fieldWalker,
      Set<EntityImpl> walked) {
    for (var value : map.values()) {
      if (value instanceof EntityImpl entity) {
        // only embedded documents are walked
        if (PropertyTypeInternal.EMBEDDEDMAP.equals(fieldType) || entity.isEmbedded()) {
          walkDocument(session, entity, fieldWalker, walked);
        }
      }
    }
  }

  private void walkIterable(
      DatabaseSessionInternal session,
      Iterable iterable,
      PropertyTypeInternal fieldType,
      EntityPropertiesVisitor fieldWalker,
      Set<EntityImpl> walked) {
    for (var value : iterable) {
      if (value instanceof EntityImpl entity) {
        // only embedded documents are walked
        if (PropertyTypeInternal.EMBEDDEDLIST.equals(fieldType)
            || PropertyTypeInternal.EMBEDDEDSET.equals(fieldType)
            || entity.isEmbedded()) {
          walkDocument(session, entity, fieldWalker, walked);
        }
      }
    }
  }

  private static boolean updateFieldValueIfChanged(
      EntityImpl entity,
      String fieldName,
      Object fieldValue,
      Object newValue,
      PropertyTypeInternal concreteType) {
    if (fieldValue != newValue) {
      entity.setPropertyInternal(fieldName, newValue, concreteType);
      return true;
    }

    return false;
  }
}
