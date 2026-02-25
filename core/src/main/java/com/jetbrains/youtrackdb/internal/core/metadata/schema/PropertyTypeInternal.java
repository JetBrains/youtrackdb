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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.ValidationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrackdb.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Generic representation of a type.<br> allowAssignmentFrom accepts any class, but Array.class
 * means that the type accepts generic Arrays.
 */
public enum PropertyTypeInternal {
  BOOLEAN("Boolean", 0, Boolean.class, new Class<?>[]{Number.class}) {
    @Override
    public Boolean convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Boolean b -> {
          return b;
        }
        case String s -> {
          return Boolean.valueOf(s);
        }
        case Number number -> {
          return number.intValue() != 0;
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.BOOLEAN;
    }
  },

  INTEGER("Integer", 1, Integer.class, new Class<?>[]{Number.class}) {
    @Override
    public Integer convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Integer i -> {
          return i;
        }
        case String s -> {
          if (value.toString().isEmpty()) {
            return null;
          }
          return Integer.valueOf(s);
        }
        case Number number -> {
          return number.intValue();
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.INTEGER;
    }
  },

  SHORT("Short", 2, Short.class, new Class<?>[]{Number.class}) {
    @Override
    public Short convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Short i -> {
          return i;
        }
        case String s -> {
          return Short.valueOf(s);
        }
        case Number number -> {
          return number.shortValue();
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.SHORT;
    }
  },

  LONG(
      "Long",
      3,
      Long.class,
      new Class<?>[]{
          Number.class,
      }) {
    @Override
    public Long convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Long l -> {
          return l;
        }
        case String s -> {
          return Long.valueOf(s);
        }
        case Date date -> {
          return date.getTime();
        }
        case Number number -> {
          return number.longValue();
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.LONG;
    }
  },

  FLOAT("Float", 4, Float.class, new Class<?>[]{Number.class}) {
    @Override
    public Float convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Float v -> {
          return v;
        }
        case String s -> {
          return Float.valueOf(s);
        }
        case Number number -> {
          return number.floatValue();
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.FLOAT;
    }
  },

  DOUBLE("Double", 5, Double.class, new Class<?>[]{Number.class}) {
    @Override
    public Double convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Double v -> {
          return v;
        }
        case String s -> {
          return Double.valueOf(s);
        }
        case Float ignored -> {
          return Double.valueOf(value.toString());
        }
        case Number number -> {
          return number.doubleValue();
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.DOUBLE;
    }
  },

  DATETIME("Datetime", 6, Date.class, new Class<?>[]{Date.class, Number.class}) {
    @Override
    public Date convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Date date -> {
          return date;
        }
        case Number number -> {
          return new Date(number.longValue());
        }
        case String s -> {
          if (IOUtils.isLong(value.toString())) {
            return new Date(Long.parseLong(value.toString()));
          }
          try {
            return DateHelper.getDateTimeFormatInstance(session)
                .parse((String) value);
          } catch (ParseException ignore) {
            try {
              return DateHelper.getDateFormatInstance(session).parse((String) value);
            } catch (ParseException e) {
              throw BaseException.wrapException(
                  new DatabaseException(session,
                      conversionErrorMessage(value, this)), e, session);
            }
          }
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.DATETIME;
    }
  },

  STRING("String", 7, String.class, new Class<?>[]{Enum.class}) {
    @Override
    public Object convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      return switch (value) {
        case null -> null;
        case String s -> s;
        case Collection<?> collection when collection.size() == 1 && collection.iterator()
            .next() instanceof String -> collection.iterator().next();
        default -> value.toString();
      };
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.STRING;
    }
  },

  BINARY("Binary", 8, byte[].class, new Class<?>[]{byte[].class}) {
    @Override
    public byte[] convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      if (value == null) {
        return null;
      }
      if (value instanceof byte[] ar) {
        return ar;
      }

      return StringSerializerHelper.getBinaryContent(value);
    }

    @Override
    public byte[] copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);
      return value == null ? null : ((byte[]) value).clone();
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.BINARY;
    }
  },

  EMBEDDED(
      "Embedded",
      9,
      Entity.class,
      new Class<?>[]{Entity.class, EntitySerializable.class, SerializableStream.class}) {
    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.EMBEDDED;
    }

    @Override
    public Entity convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Entity entity -> {
          return entity;
        }
        case Map<?, ?> map -> {
          var entityImpl = session.newEmbeddedEntity(linkedClass);
          //noinspection unchecked
          entityImpl.updateFromMap((Map<String, ?>) map);
          return entityImpl;
        }
        case String s -> {
          var entityImpl = session.newEmbeddedEntity(linkedClass);
          JSONSerializerJackson.INSTANCE.fromString(session, s, (RecordAbstract) entityImpl);
          return entityImpl;
        }
        default -> {
          throw new DatabaseException(session, conversionErrorMessage(value, this));
        }
      }


    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      var entity = (EntityImpl) value;

      var embeddedEntity = session.newEmbeddedEntity(entity.getSchemaClass());
      for (var propertyName : entity.getPropertyNames()) {
        var property = entity.getProperty(propertyName);
        var type = convertFromPublicType(entity.getPropertyType(propertyName));

        if (type != null) {
          var copy = type.copy(property, session);
          embeddedEntity.setProperty(propertyName, copy, type.getPublicPropertyType());
        } else {
          throw new DatabaseException(session, "Can not determine type of property : "
              + propertyName + " in entity : " + entity);
        }
      }

      return embeddedEntity;
    }
  },

  EMBEDDEDLIST(
      "EmbeddedList", 10, EntityEmbeddedListImpl.class,
      new Class<?>[]{List.class, MultiCollectionIterator.class}) {
    @Override
    public List<Object> convert(Object value, PropertyTypeInternal linkedType,
        SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Object[] array -> {
          var embeddedList = session.newEmbeddedList(array.length);
          for (var item : array) {
            var converted = PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
                linkedClass,
                session, item,
                this);
            embeddedList.add(converted);
          }
          return embeddedList;
        }
        case EntityEmbeddedListImpl<?> trackedList -> {
          //noinspection unchecked
          return (List<Object>) trackedList;
        }
        case Collection<?> collection -> {
          var embeddedList = session.newEmbeddedList(collection.size());
          for (var item : collection) {
            var converted = PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
                linkedClass,
                session, item,
                this);
            embeddedList.add(converted);
          }

          return embeddedList;
        }
        case Iterable<?> iterable -> {
          var embeddedList = session.newEmbeddedList();
          for (var item : iterable) {
            var converted = PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
                linkedClass,
                session, item,
                this);
            embeddedList.add(converted);
          }
          return embeddedList;
        }
        case Iterator<?> iterator -> {
          var embeddedList = session.newEmbeddedList();
          while (iterator.hasNext()) {
            var item = iterator.next();
            var converted = PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
                linkedClass,
                session, item,
                this);
            embeddedList.add(converted);
          }
          return embeddedList;
        }
        default -> {
          if (value.getClass().isArray()) {
            var size = Array.getLength(value);
            var embeddedList = session.newEmbeddedList(size);

            for (var i = 0; i < size; i++) {
              var item = Array.get(value, i);
              var converted = PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
                  linkedClass,
                  session, item,
                  this);
              embeddedList.add(converted);
            }

            return embeddedList;
          }

          var embeddedList = session.newEmbeddedList();
          var converted = PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
              linkedClass,
              session, value,
              this);
          embeddedList.add(converted);
          return embeddedList;
        }
      }
    }

    @Override
    public boolean isTypeInstance(Object value) {
      if (value instanceof EntityLinkListImpl) {
        return false;
      }

      return super.isTypeInstance(value);
    }

    @Override
    public boolean isConvertibleFrom(Object value) {
      if (value instanceof EntityLinkListImpl) {
        return false;
      }
      if (value.getClass().isArray()) {
        var componentType = value.getClass().getComponentType();
        var type = PropertyTypeInternal.getTypeByClass(componentType);
        return type != null;
      }

      return super.isConvertibleFrom(value);
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      var trackedList = (EntityEmbeddedListImpl<?>) value;
      var copy = session.newEmbeddedList(trackedList.size());
      for (var item : trackedList) {
        if (item == null) {
          copy.add(null);
          continue;
        }

        var type = PropertyTypeInternal.getTypeByValue(item);
        if (type == null) {
          throw new DatabaseException(session, "Can not determine type of property : "
              + item + " in list : " + value);
        }

        var converted = type.copy(item, session);
        copy.add(converted);
      }
      return copy;
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.EMBEDDEDLIST;
    }
  },

  EMBEDDEDSET("EmbeddedSet", 11, EntityEmbeddedSetImpl.class, new Class<?>[]{Set.class}) {
    @Override
    public Set<Object> convert(Object value, PropertyTypeInternal linkedType,
        SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case EntityEmbeddedSetImpl<?> trackedSet -> {
          //noinspection unchecked
          return (Set<Object>) trackedSet;
        }
        case Collection<?> collection -> {
          var embeddedSet = session.newEmbeddedSet(collection.size());
          for (var item : collection) {
            var converted = convertEmbeddedCollectionItem(linkedType, linkedClass, session, item,
                this);
            embeddedSet.add(converted);
          }
          return embeddedSet;
        }
        case Iterable<?> iterable -> {
          var embeddedSet = session.newEmbeddedSet();
          for (var item : iterable) {
            var converted = convertEmbeddedCollectionItem(linkedType, linkedClass, session, item,
                this);
            embeddedSet.add(converted);
          }
          return embeddedSet;
        }
        case Iterator<?> iterator -> {
          var embeddedSet = session.newEmbeddedSet();
          while (iterator.hasNext()) {
            var item = iterator.next();
            var converted = convertEmbeddedCollectionItem(linkedType, linkedClass, session, item,
                this);
            embeddedSet.add(converted);
          }
          return embeddedSet;
        }
        default -> {
          var embeddedSet = session.newEmbeddedSet();
          var converted = convertEmbeddedCollectionItem(linkedType, linkedClass, session, value,
              this);
          embeddedSet.add(converted);
          return embeddedSet;
        }
      }
    }

    @Override
    public boolean isTypeInstance(Object value) {
      if (value instanceof EntityLinkSetImpl) {
        return false;
      }
      return super.isTypeInstance(value);
    }

    @Override
    public boolean isConvertibleFrom(Object value) {
      if (value instanceof EntityLinkSetImpl) {
        return false;
      }
      return super.isConvertibleFrom(value);
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);
      if (value == null) {
        return null;
      }

      var trackedSet = (EntityEmbeddedSetImpl<?>) value;
      var copy = session.newEmbeddedSet(trackedSet.size());

      for (var item : trackedSet) {
        if (item != null) {
          var type = PropertyTypeInternal.getTypeByValue(item);

          if (type == null) {
            throw new DatabaseException(session, "Can not determine type of property : "
                + item + " in set : " + value);
          }

          var converted = type.copy(item, session);
          copy.add(converted);
        } else {
          copy.add(null);
        }
      }

      return copy;
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.EMBEDDEDSET;
    }
  },

  EMBEDDEDMAP("EmbeddedMap", 12, EntityEmbeddedMapImpl.class,
      new Class<?>[]{Map.class, MultiCollectionIterator.class}) {
    @Override
    public Map<String, Object> convert(Object value, PropertyTypeInternal linkedType,
        SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case EntityEmbeddedMapImpl<?> trackedMap -> {
          //noinspection unchecked
          return (Map<String, Object>) trackedMap;
        }
        case Map<?, ?> map -> {
          var embeddedMap = session.newEmbeddedMap(map.size());
          for (var entry : map.entrySet()) {
            embeddedMap.put(entry.getKey().toString(),
                PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType, linkedClass, session,
                    entry.getValue(),
                    this));
          }
          return embeddedMap;
        }
        case Result result -> {
          var embeddedMap = session.newEmbeddedMap();
          if (result.isProjection()) {
            for (var property : result.getPropertyNames()) {
              embeddedMap.put(property,
                  PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
                      linkedClass, session, result.getProperty(property), this));
            }
          } else {
            embeddedMap.put("value", PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
                linkedClass, session,
                value, this));
          }
          return embeddedMap;
        }
        case Iterable<?> iterable -> {
          var embeddedMap = session.newEmbeddedMap();

          for (var element : iterable) {
            if ((element instanceof Map<?, ?> map) &&
                !(element instanceof EntityLinkMapIml) &&
                (map.isEmpty() || map.keySet().iterator().next() instanceof String)
            ) {
              //noinspection unchecked
              embeddedMap.putAll((Map<String, ?>) map);
            } else if (element instanceof Map.Entry<?, ?> entry &&
                entry.getKey() instanceof String &&
                !(entry.getValue() instanceof Identifiable)
            ) {
              //noinspection unchecked
              embeddedMap.put(((Entry<String, ?>) entry).getKey(), entry.getValue());
            } else {
              throw new DatabaseException(session.getDatabaseName(),
                  conversionErrorMessage(value, this));
            }
          }

          return embeddedMap;
        }
        default -> {
          var embeddedMap = session.newEmbeddedMap();
          embeddedMap.put("value", PropertyTypeInternal.convertEmbeddedCollectionItem(linkedType,
              linkedClass, session,
              value, this));
          return embeddedMap;
        }
      }
    }

    @Override
    public boolean isTypeInstance(Object value) {
      if (value instanceof EntityLinkMapIml) {
        return false;
      }
      return super.isTypeInstance(value);
    }

    @Override
    public boolean isConvertibleFrom(Object value) {
      if (value instanceof EntityLinkMapIml) {
        return false;
      }

      if (value instanceof Iterable<?> iterable) {
        var iterator = iterable.iterator();
        if (iterator.hasNext()) {
          var firstValue = iterator.next();
          if (firstValue instanceof Map<?, ?> map && !(firstValue instanceof EntityLinkMapIml) &&
              (map.isEmpty() || map.keySet().iterator().next() instanceof String)) {
            return true;
          }
        } else {
          return true;
        }
      }

      return super.isConvertibleFrom(value);
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      var trackedMap = (EntityEmbeddedMapImpl<?>) value;
      var copy = session.newEmbeddedMap(trackedMap.size());
      for (var entry : trackedMap.entrySet()) {
        var entryValue = entry.getValue();
        if (entryValue == null) {
          copy.put(entry.getKey(), null);
          continue;
        }

        var type = PropertyTypeInternal.getTypeByValue(entry.getValue());

        if (type == null) {
          throw new DatabaseException(session, "Can not determine type of property : "
              + entry.getValue() + " in map : " + value);
        }

        var converted = type.copy(entry.getValue(), session);
        copy.put(entry.getKey(), converted);
      }

      return copy;
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.EMBEDDEDMAP;
    }
  },

  LINK("Link", 13, Identifiable.class, new Class<?>[]{Identifiable.class, RID.class}) {
    @Override
    public Identifiable convert(Object value, PropertyTypeInternal linkedType,
        SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case Identifiable identifiable -> {
          return identifiable;
        }
        case Result result -> {
          if (result.isIdentifiable()) {
            return result.asIdentifiable();
          }
          if (result.isProjection()) {
            return convert(result.toMap(), linkedType, linkedClass, session);
          }
        }
        case Collection<?> collection -> {
          if (collection.isEmpty()) {
            return null;
          }
          if (collection.size() == 1) {
            var iterator = collection.iterator();
            var first = iterator.next();
            return convert(first, linkedType, linkedClass, session);
          }
        }
        case Map<?, ?> map when linkedClass != null -> {
          var entity = session.newEntity(linkedClass);
          //noinspection unchecked
          entity.updateFromMap((Map<String, Object>) value);
          return entity;
        }
        case String s -> {
          try {
            return RecordIdInternal.fromString(s, false);
          } catch (Exception e) {
            throw new ValidationException(session,
                conversionErrorMessage(value, this));
          }
        }
        default -> {
          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              conversionErrorMessage(value, this));
        }
      }
      throw new DatabaseException(session, conversionErrorMessage(value, this));
    }

    @Override
    public boolean isTypeInstance(Object value) {
      if (value instanceof Entity entity && entity.isEmbedded()) {
        return false;
      }
      if (value instanceof RecordIdInternal rid && !rid.isValidPosition()) {
        return false;
      }

      return super.isTypeInstance(value);
    }

    @Override
    public boolean isConvertibleFrom(Object value) {
      if (value instanceof Entity entity && entity.isEmbedded()) {
        return false;
      }

      if (value instanceof RecordIdInternal rid && !rid.isValidPosition()) {
        return false;
      }

      return super.isConvertibleFrom(value);
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      var identifiable = (Identifiable) value;
      var rid = (RecordIdInternal) identifiable.getIdentity();
      return rid.copy();
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.LINK;
    }
  },

  LINKLIST("LinkList", 14, EntityLinkListImpl.class, new Class<?>[]{List.class}) {
    @Override
    public Object convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case EntityLinkListImpl linkList -> {
          return linkList;
        }
        case Collection<?> collection -> {
          var linkList = session.newLinkList(collection.size());
          for (var item : collection) {
            linkList.add((Identifiable) LINK.convert(item, null, linkedClass, session));
          }
          return linkList;
        }
        case Iterable<?> iterable -> {
          var linkList = session.newLinkList();
          for (var item : iterable) {
            linkList.add((Identifiable) LINK.convert(item, null, linkedClass, session));
          }
          return linkList;
        }
        case Iterator<?> iterator -> {
          var linkList = session.newLinkList();
          while (iterator.hasNext()) {
            var item = iterator.next();
            linkList.add((Identifiable) LINK.convert(item, null, linkedClass, session));
          }
          return linkList;
        }
        default -> {
          var linkList = session.newLinkList();
          linkList.add((Identifiable) value);
          return linkList;
        }
      }
    }

    @Override
    public boolean isTypeInstance(Object value) {
      var result = super.isTypeInstance(value);

      if (result) {
        return PropertyTypeInternal.checkLinkCollection((Collection<?>) value);
      }
      return false;
    }

    @Override
    public boolean isConvertibleFrom(Object object) {
      var result = super.isConvertibleFrom(object);

      if (result) {
        return PropertyTypeInternal.canBeLinkCollection((Collection<?>) object);
      }

      return false;
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      var linkList = (EntityLinkListImpl) value;
      var copy = session.newLinkList(linkList.size());
      copy.addAll(linkList);

      return copy;
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.LINKLIST;
    }
  },

  LINKSET("LinkSet", 15, EntityLinkSetImpl.class, new Class<?>[]{Set.class}) {
    @Override
    public Set<Identifiable> convert(Object value, PropertyTypeInternal linkedType,
        SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case EntityLinkSetImpl linkSet -> {
          return linkSet;
        }
        case Collection<?> collection -> {
          var linkSet = session.newLinkSet();
          for (var item : collection) {
            linkSet.add((Identifiable) LINK.convert(item, null, linkedClass, session));
          }
          return linkSet;
        }
        case Iterable<?> iterable -> {
          var linkSet = session.newLinkSet();
          for (var item : iterable) {
            linkSet.add((Identifiable) LINK.convert(item, null, linkedClass, session));
          }

          return linkSet;
        }
        case Iterator<?> iterator -> {
          var linkSet = session.newLinkSet();
          while (iterator.hasNext()) {
            var item = iterator.next();
            linkSet.add((Identifiable) LINK.convert(item, null, linkedClass, session));
          }
          return linkSet;
        }
        default -> {
          var linkSet = session.newLinkSet();
          linkSet.add((Identifiable) LINK.convert(value, null, linkedClass, session));
          return linkSet;

        }
      }
    }

    @Override
    public boolean isTypeInstance(Object value) {
      var result = super.isTypeInstance(value);
      if (result) {
        return PropertyTypeInternal.checkLinkCollection((Collection<?>) value);
      }
      return false;
    }

    @Override
    public boolean isConvertibleFrom(Object object) {
      var result = super.isConvertibleFrom(object);
      if (result) {
        return PropertyTypeInternal.canBeLinkCollection((Collection<?>) object);
      }
      return false;
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);
      if (value == null) {
        return null;
      }

      var linkSet = (EntityLinkSetImpl) value;
      var copy = session.newLinkSet();
      copy.addAll(linkSet);

      return copy;
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.LINKSET;
    }
  },

  LINKMAP("LinkMap", 16, EntityLinkMapIml.class, new Class<?>[]{Map.class}) {
    @Override
    public Map<String, Identifiable> convert(Object value, PropertyTypeInternal linkedType,
        SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      switch (value) {
        case null -> {
          return null;
        }
        case EntityLinkMapIml linkMap -> {
          return linkMap;
        }
        case Map<?, ?> map -> {
          var linkMap = session.newLinkMap(map.size());
          for (var entry : map.entrySet()) {
            linkMap.put(entry.getKey().toString(),
                (Identifiable) LINK.convert(entry.getValue(), null, linkedClass, session));
          }
          return linkMap;
        }
        case Result result -> {
          if (result.isProjection()) {
            var linkMap = session.newLinkMap();
            for (var property : result.getPropertyNames()) {
              linkMap.put(property, result.getLink(property));
            }
            return linkMap;
          }
        }
        default -> {
          var linkMap = session.newLinkMap();
          linkMap.put("value", (Identifiable) LINK.convert(value, null, linkedClass, session));
          return linkMap;
        }
      }
      throw new DatabaseException(session != null ? session.getDatabaseName() : null,
          conversionErrorMessage(value, this));
    }

    @Override
    public boolean isTypeInstance(Object value) {
      var result = super.isTypeInstance(value);
      if (result) {
        return PropertyTypeInternal.checkLinkCollection(((Map<?, ?>) value).values());
      }

      return false;
    }


    @Override
    public boolean isConvertibleFrom(Object object) {
      var result = super.isConvertibleFrom(object);
      if (result) {
        return PropertyTypeInternal.canBeLinkCollection(((Map<?, ?>) object).values());
      }

      return false;
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);
      if (value == null) {
        return null;
      }

      var linkMap = (EntityLinkMapIml) value;
      var copy = session.newLinkMap(linkMap.size());
      copy.putAll(linkMap);

      return copy;
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.LINKMAP;
    }
  },

  BYTE("Byte", 17, Byte.class, new Class<?>[]{Number.class}) {
    @Override
    public Object convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      return switch (value) {
        case null -> null;
        case Byte byteValue -> byteValue;
        case String string -> Byte.valueOf(string);
        case Number number -> number.byteValue();
        default -> throw new DatabaseException(session != null ? session.getDatabaseName() : null,
            conversionErrorMessage(value, this));
      };

    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.BYTE;
    }
  },

  DATE("Date", 19, Date.class, new Class<?>[]{Number.class}) {
    @Override
    public Object convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      if (value == null) {
        return null;
      } else if (value instanceof Date date) {
        return date;
      }
      return DATETIME.convert(value, linkedType, linkedClass, session);
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      return new Date(((Date) value).getTime());
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.DATE;
    }
  },

  DECIMAL("Decimal", 21, BigDecimal.class, new Class<?>[]{BigDecimal.class, Number.class}) {
    @Override
    public BigDecimal convert(Object value, PropertyTypeInternal linkedType,
        SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      return switch (value) {
        case null -> null;
        case BigDecimal bigDecimal -> bigDecimal;
        case String s -> new BigDecimal(s);
        case Number number -> new BigDecimal(value.toString());
        default -> throw new DatabaseException(session != null ? session.getDatabaseName() : null,
            conversionErrorMessage(value, this));
      };
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      return new BigDecimal(((BigDecimal) value).toPlainString());
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.DECIMAL;
    }
  },

  LINKBAG("LinkBag", 22, LinkBag.class, new Class<?>[]{LinkBag.class}) {
    @Override
    public LinkBag convert(Object value, PropertyTypeInternal linkedType, SchemaClass linkedClass,
        DatabaseSessionEmbedded session) {
      if (value == null) {
        return null;
      } else if (value instanceof LinkBag linkBag) {
        return linkBag;
      }

      var linkBag = new LinkBag(session);
      switch (value) {
        case Iterable<?> iterable -> {
          for (var item : iterable) {
            linkBag.add(
                ((Identifiable) LINK.convert(item, null, linkedClass, session)).getIdentity());
          }

          return linkBag;
        }
        case Iterator<?> iterator -> {
          while (iterator.hasNext()) {
            linkBag.add(((Identifiable) LINK.convert(iterator.next(), null, linkedClass, session))
                .getIdentity());
          }

          return linkBag;
        }
        case Identifiable identifiable -> {
          linkBag.add(identifiable.getIdentity());
          return linkBag;
        }
        default -> {
        }
      }

      throw new DatabaseException(session, conversionErrorMessage(value, this));
    }

    @Override
    public Object copy(Object value, DatabaseSessionEmbedded session) {
      value = convert(value, session);

      if (value == null) {
        return null;
      }

      var ridBag = (LinkBag) value;
      var copy = new LinkBag(session);
      for (var item : ridBag) {
        copy.add(item);
      }

      return copy;
    }

    @Override
    public PropertyType getPublicPropertyType() {
      return PropertyType.LINKBAG;
    }
  };

  // Don't change the order, the type discover get broken if you change the order.
  private static final PropertyTypeInternal[] TYPES =
      new PropertyTypeInternal[]{
          EMBEDDEDLIST, EMBEDDEDSET, EMBEDDEDMAP, LINK, EMBEDDED, STRING, DATETIME
      };

  private static final PropertyTypeInternal[] TYPES_BY_ID = new PropertyTypeInternal[24];
  // Values previosly stored in javaTypes
  private static final Map<Class<?>, PropertyTypeInternal> TYPES_BY_CLASS = new HashMap<>();

  static {
    for (var oType : values()) {
      TYPES_BY_ID[oType.id] = oType;
    }
    // This is made by hand because not all types should be add.
    TYPES_BY_CLASS.put(Boolean.class, BOOLEAN);
    TYPES_BY_CLASS.put(Boolean.TYPE, BOOLEAN);
    TYPES_BY_CLASS.put(Integer.TYPE, INTEGER);
    TYPES_BY_CLASS.put(Integer.class, INTEGER);
    TYPES_BY_CLASS.put(Short.class, SHORT);
    TYPES_BY_CLASS.put(Short.TYPE, SHORT);
    TYPES_BY_CLASS.put(Long.class, LONG);
    TYPES_BY_CLASS.put(Long.TYPE, LONG);
    TYPES_BY_CLASS.put(Float.TYPE, FLOAT);
    TYPES_BY_CLASS.put(Float.class, FLOAT);
    TYPES_BY_CLASS.put(Double.TYPE, DOUBLE);
    TYPES_BY_CLASS.put(Double.class, DOUBLE);
    TYPES_BY_CLASS.put(Date.class, DATETIME);
    TYPES_BY_CLASS.put(String.class, STRING);
    TYPES_BY_CLASS.put(Enum.class, STRING);
    TYPES_BY_CLASS.put(byte[].class, BINARY);
    TYPES_BY_CLASS.put(Byte.class, BYTE);
    TYPES_BY_CLASS.put(Byte.TYPE, BYTE);
    TYPES_BY_CLASS.put(Character.class, STRING);
    TYPES_BY_CLASS.put(Character.TYPE, STRING);
    TYPES_BY_CLASS.put(RecordIdInternal.class, LINK);
    TYPES_BY_CLASS.put(BigDecimal.class, DECIMAL);
    TYPES_BY_CLASS.put(BigInteger.class, DECIMAL);
    TYPES_BY_CLASS.put(LinkBag.class, LINKBAG);
    TYPES_BY_CLASS.put(EntityEmbeddedSetImpl.class, EMBEDDEDSET);
    TYPES_BY_CLASS.put(EntityLinkSetImpl.class, LINKSET);
    TYPES_BY_CLASS.put(EntityEmbeddedListImpl.class, EMBEDDEDLIST);
    TYPES_BY_CLASS.put(EntityLinkListImpl.class, LINKLIST);
    TYPES_BY_CLASS.put(EntityEmbeddedMapImpl.class, EMBEDDEDMAP);
    TYPES_BY_CLASS.put(EntityLinkMapIml.class, LINKMAP);
    BYTE.castable.add(BOOLEAN);
    SHORT.castable.addAll(Arrays.asList(BOOLEAN, BYTE));
    INTEGER.castable.addAll(Arrays.asList(BOOLEAN, BYTE, SHORT));
    LONG.castable.addAll(Arrays.asList(BOOLEAN, BYTE, SHORT, INTEGER));
    FLOAT.castable.addAll(Arrays.asList(BOOLEAN, BYTE, SHORT, INTEGER));
    DOUBLE.castable.addAll(Arrays.asList(BOOLEAN, BYTE, SHORT, INTEGER, LONG, FLOAT));
    DECIMAL.castable.addAll(Arrays.asList(BOOLEAN, BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE));
    LINKLIST.castable.add(LINKSET);
    EMBEDDEDLIST.castable.add(EMBEDDEDSET);
  }

  private final String name;
  final int id;
  private final Class<?> javaDefaultType;
  @SuppressWarnings("ImmutableEnumChecker")
  private final Class<?>[] allowAssignmentFrom;
  @SuppressWarnings("ImmutableEnumChecker")
  private final Set<PropertyTypeInternal> castable;

  PropertyTypeInternal(
      final String iName,
      final int iId,
      final Class<?> iJavaDefaultType,
      final Class<?>[] iAllowAssignmentBy) {
    name = iName;
    id = iId;
    javaDefaultType = iJavaDefaultType;
    allowAssignmentFrom = iAllowAssignmentBy;
    castable = new HashSet<>();
    castable.add(this);
  }

  /**
   * Return the type by ID.
   *
   * @param iId The id to search
   * @return The type if any, otherwise null
   */
  @Nullable
  public static PropertyTypeInternal getById(final byte iId) {
    if (iId >= 0 && iId < TYPES_BY_ID.length) {
      return TYPES_BY_ID[iId];
    }
    LogManager.instance()
        .warn(PropertyTypeInternal.class, "Invalid type index: " + iId, (Object[]) null);
    return null;
  }

  /**
   * Get the identifier of the type. use this instead of {@link Enum#ordinal()} for guarantee a
   * cross code version identifier.
   *
   * @return the identifier of the type.
   */
  public final int getId() {
    return id;
  }

  @Nullable
  public abstract Object convert(Object value, PropertyTypeInternal linkedType,
      SchemaClass linkedClass,
      DatabaseSessionEmbedded session);

  public boolean isTypeInstance(Object value) {
    var clazz = value.getClass();
    return clazz == javaDefaultType;
  }

  public boolean isConvertibleFrom(Object object) {
    if (isTypeInstance(object)) {
      return true;
    }

    var clazz = object.getClass();
    if (javaDefaultType.isAssignableFrom(clazz)) {
      return true;
    }

    for (var type : allowAssignmentFrom) {
      if (type.isAssignableFrom(clazz)) {
        return true;
      }
    }

    return false;
  }

  public Object convert(Object value, DatabaseSessionEmbedded session) {
    if (value == null || isTypeInstance(value)) {
      return value;
    }

    if (isConvertibleFrom(value)) {
      return convert(value, null, null, session);
    }

    throw new DatabaseException(session, "Cannot convert " + value + " to " + this + " type.");
  }

  @Nullable
  public Object copy(Object value, DatabaseSessionEmbedded session) {
    return convert(value, session);
  }


  /**
   * Return the correspondent type by checking the "assignability" of the class received as
   * parameter.
   *
   * @param iClass Class to check
   * @return PropertyType instance if found, otherwise null
   */
  @Nullable
  public static PropertyTypeInternal getTypeByClass(final Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    var type = TYPES_BY_CLASS.get(iClass);
    if (type != null) {
      return type;
    }
    type = getTypeByClassInherit(iClass);

    return type;
  }

  @Nullable
  private static PropertyTypeInternal getTypeByClassInherit(final Class<?> iClass) {
    if (iClass.isArray()) {
      return EMBEDDEDLIST;
    }
    var priority = 0;
    boolean comparedAtLeastOnce;
    do {
      comparedAtLeastOnce = false;
      for (final var type : TYPES) {
        if (type.allowAssignmentFrom.length > priority) {
          if (type.allowAssignmentFrom[priority].isAssignableFrom(iClass)) {
            return type;
          }
          comparedAtLeastOnce = true;
        }
      }

      priority++;
    } while (comparedAtLeastOnce);
    return null;
  }

  @Nullable
  public static PropertyTypeInternal getTypeByValue(Object value) {
    if (value == null) {
      return null;
    }

    if (value instanceof MultiCollectionIterator<?> it) {
      // link collections not supported here atm
      return it.isInMapMode() ? EMBEDDEDMAP : EMBEDDEDLIST;
    }

    var clazz = value.getClass();
    var type = TYPES_BY_CLASS.get(clazz);
    if (type != null) {
      return type;
    }

    var byType = getTypeByClassInherit(clazz);
    if (EMBEDDEDSET == byType) {
      if (checkLinkCollection(((Collection<?>) value))) {
        return LINKSET;
      }
    } else if (EMBEDDEDLIST == byType && !clazz.isArray()) {
      if (checkLinkCollection(((Collection<?>) value))) {
        return LINKLIST;
      }
    } else if (EMBEDDEDMAP == byType) {
      if (checkLinkCollection(((Map<?, ?>) value).values())) {
        return LINKMAP;
      }
    } else if (LINK == byType && value instanceof Entity entity && entity.isEmbedded()) {
      return EMBEDDED;
    }

    if (byType == null) {
      if (value instanceof Result result) {
        if (result.isIdentifiable()) {
          if (result.isEntity()) {
            var identifable = result.asIdentifiable();
            if (identifable instanceof Entity entity && entity.isEmbedded()) {
              return EMBEDDED;
            }
          }

          return LINK;
        }
        if (result.isProjection()) {
          return EMBEDDEDMAP;
        } else {
          return null;
        }
      }
    }
    return byType;
  }

  public static boolean checkLinkCollection(Collection<?> toCheck) {
    if (toCheck == null) {
      return false;
    }

    var first = toCheck.stream().filter(Objects::nonNull).findAny();
    return first.map(PropertyTypeInternal::isCollectionLink).orElse(false);
  }

  public static boolean canBeLinkCollection(Collection<?> toCheck) {
    if (toCheck == null) {
      return true;
    }

    var first = toCheck.stream().filter(Objects::nonNull).findAny();
    return first.map(PropertyTypeInternal::isCollectionLink).orElse(true);
  }

  private static boolean isCollectionLink(Object value) {
    if (value instanceof Identifiable identifiable) {
      if (identifiable instanceof Entity entity) {
        return !entity.isEmbedded();
      }
      return true;
    }

    if (value instanceof Result result) {
      if (result.isIdentifiable()) {
        var identifiable = result.asIdentifiable();
        if (!(identifiable instanceof Entity entity)) {
          return true;
        }
        return !entity.isEmbedded();
      }
    }

    return false;
  }

  public static boolean isSimpleValueType(@Nullable Object value) {
    if (value == null) {
      return true;
    }

    var cls = value.getClass();
    if (cls.isPrimitive()) {
      return true;
    }

    return Integer.class.isAssignableFrom(cls) ||
        Long.class.isAssignableFrom(cls) ||
        Short.class.isAssignableFrom(cls) ||
        Byte.class.isAssignableFrom(cls) ||
        Double.class.isAssignableFrom(cls) ||
        Float.class.isAssignableFrom(cls) ||
        Boolean.class.isAssignableFrom(cls) ||
        BigInteger.class.isAssignableFrom(cls) ||
        BigDecimal.class.isAssignableFrom(cls) ||
        Date.class.isAssignableFrom(cls) ||
        String.class.isAssignableFrom(cls);
  }

  public static boolean isSingleValueType(@Nullable Object value) {
    if (isSimpleValueType(value)) {
      return true;
    }

    var cls = value.getClass();
    return cls.isArray() && cls.getComponentType().equals(byte.class);
  }


  /**
   * Convert types based on the iTargetClass parameter.
   *
   * @param value       Value to convert
   * @param targetClass Expected class
   * @return The converted value or the original if no conversion was applied
   */
  @Nullable
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <T> T convert(@Nullable DatabaseSessionEmbedded session, final Object value,
      Class<? extends T> targetClass) {
    if (value == null) {
      return null;
    }

    if (targetClass == null) {
      return (T) value;
    }

    if (value.getClass().equals(targetClass))
    // SAME TYPE: DON'T CONVERT IT
    {
      return (T) value;
    }

    if (targetClass.isAssignableFrom(value.getClass()))
    // COMPATIBLE TYPES: DON'T CONVERT IT
    {
      return (T) value;
    }

    try {
      if (byte[].class.isAssignableFrom(targetClass)) {
        return (T) BINARY.convert(value, null, null, session);
      } else if (byte[].class.isAssignableFrom(value.getClass())) {
        return (T) value;
      } else if (targetClass.equals(Byte.TYPE) || targetClass.equals(Byte.class)) {
        return (T) BYTE.convert(value, null, null, session);
      } else if (targetClass.equals(Short.TYPE) || targetClass.equals(Short.class)) {
        return (T) SHORT.convert(value, null, null, session);
      } else if (targetClass.equals(Integer.TYPE) || targetClass.equals(Integer.class)) {
        return (T) INTEGER.convert(value, null, null, session);
      } else if (targetClass.equals(Long.TYPE) || targetClass.equals(Long.class)) {
        return (T) LONG.convert(value, null, null, session);
      } else if (targetClass.equals(Float.TYPE) || targetClass.equals(Float.class)) {
        return (T) FLOAT.convert(value, null, null, session);
      } else if (targetClass.equals(BigDecimal.class)) {
        return (T) DECIMAL.convert(value, null, null, session);
      } else if (targetClass.equals(Double.TYPE) || targetClass.equals(Double.class)) {
        return (T) DOUBLE.convert(value, null, null, session);
      } else if (targetClass.equals(Boolean.TYPE) || targetClass.equals(Boolean.class)) {
        return (T) BOOLEAN.convert(value, null, null, session);
      } else if (EntityLinkSetImpl.class.isAssignableFrom(targetClass)) {
        return (T) LINKSET.convert(value, null, null, session);
      } else if (Set.class.isAssignableFrom(targetClass)) {
        return (T) EMBEDDEDSET.convert(value, null, null, session);
      } else if (EntityLinkListImpl.class.isAssignableFrom(targetClass)) {
        return (T) LINKLIST.convert(value, null, null, session);
      } else if (Collection.class.isAssignableFrom(targetClass)) {
        return (T) EMBEDDEDLIST.convert(value, null, null, session);
      } else if (EntityLinkMapIml.class.isAssignableFrom(targetClass)) {
        return (T) LINKMAP.convert(value, null, null, session);
      } else if (Map.class.isAssignableFrom(targetClass)) {
        return (T) EMBEDDEDMAP.convert(value, null, null, session);
      } else if (targetClass.equals(Date.class)) {
        return (T) DATETIME.convert(value, null, null, session);
      } else if (targetClass.equals(String.class)) {
        return (T) STRING.convert(value, null, null, session);
      } else if (Identifiable.class.isAssignableFrom(targetClass)) {
        return (T) LINK.convert(value, null, null, session);
      } else if (targetClass.equals(LinkBag.class)) {
        return (T) LINKBAG.convert(value, null, null, session);
      }
    } catch (
        IllegalArgumentException e) {
      // PASS THROUGH
      throw BaseException.wrapException(
          new DatabaseException(session != null ? session.getDatabaseName() : null,
              String.format("Error in conversion of value '%s' to type '%s'", value, targetClass)),
          e, session.getDatabaseName());
    } catch (
        Exception e) {
      return switch (value) {
        case Collection collection when collection.size() == 1
            && !Collection.class.isAssignableFrom(targetClass) ->
          // this must be a comparison with the result of a subquery, try to unbox the collection
            convert(session, collection.iterator().next(), targetClass);
        case Result result when result.getPropertyNames().size() == 1
            && !Result.class.isAssignableFrom(targetClass) ->
          // try to unbox Result with a single property, for subqueries
            convert(session,
                result.getProperty(result.getPropertyNames().getFirst()),
                targetClass);
        case Entity entity when ((EntityImpl) value).getPropertyNames().size() == 1
            && !Entity.class.isAssignableFrom(targetClass) ->
          // try to unbox Result with a single property, for subqueries
            convert(session,
                entity
                    .getProperty(
                        ((EntityImpl) value).getPropertyNamesInternal(false,
                                true)
                            .getFirst()),
                targetClass);
        default -> throw BaseException.wrapException(
            new ValidationException(session != null ? session.getDatabaseName() : null,
                String.format("Error in conversion of value '%s' to type '%s'", value,
                    targetClass)),
            e, session.getDatabaseName());
      };
    }

    var type = getTypeByClass(targetClass);
    if (type != null) {
      var typeClass = type.javaDefaultType;
      if (typeClass != targetClass && typeClass.isAssignableFrom(targetClass)) {
        return (T) convert(session, value, typeClass);
      }
    }

    throw new DatabaseException(session != null ? session.getDatabaseName() : null,
        String.format("Error in conversion of value '%s' to type '%s'", value, targetClass));
  }

  private static String conversionErrorMessage(Object value, PropertyTypeInternal type) {
    return String.format("Error in conversion of value '%s' to type '%s'", value,
        type);
  }

  private static Object convertValue(DatabaseSessionEmbedded session, Object item) {
    var type = PropertyTypeInternal.getTypeByValue(item);
    if (type == null) {
      throw new DatabaseException(session.getDatabaseName(),
          String.format("Error in conversion of value '%s'", item));
    }

    return type.convert(item, null, null, session);
  }

  @Nullable
  private static Object convertEmbeddedCollectionItem(PropertyTypeInternal linkedType,
      SchemaClass linkedClass, DatabaseSessionEmbedded session,
      Object item, PropertyTypeInternal rootType) {
    if (item == null) {
      return null;
    }
    if (linkedClass != null) {
      return EMBEDDED.convert(item, null, linkedClass, session);
    }

    var itemType = linkedType;
    if (itemType == null) {
      itemType = PropertyTypeInternal.getTypeByValue(item);

      if (itemType == null) {
        throw new DatabaseException(session.getDatabaseName(),
            conversionErrorMessage(item, rootType));
      }
    }

    return itemType.convert(item, null, null, session);
  }

  public static Number increment(final Number a, final Number b) {
    if (a == null || b == null) {
      throw new IllegalArgumentException("Cannot increment a null value");
    }

    switch (a) {
      case Integer i -> {
        switch (b) {
          case Integer integer -> {
            final var sum = a.intValue() + b.intValue();
            if (sum < 0 && a.intValue() > 0 && b.intValue() > 0)
            // SPECIAL CASE: UPGRADE TO LONG
            {
              return (long) (a.intValue() + b.intValue());
            }
            return sum;
          }
          case Long l -> {
            return a.intValue() + b.longValue();
          }
          case Short aShort -> {
            final var sum = a.intValue() + b.shortValue();
            if (sum < 0 && a.intValue() > 0 && b.shortValue() > 0)
            // SPECIAL CASE: UPGRADE TO LONG
            {
              return (long) (a.intValue() + b.shortValue());
            }
            return sum;
          }
          case Float v -> {
            return a.intValue() + b.floatValue();
          }
          case Double v -> {
            return a.intValue() + b.doubleValue();
          }
          case BigDecimal bigDecimal -> {
            return new BigDecimal(a.intValue()).add(bigDecimal);
          }
          default -> {
          }
        }
      }
      case Long l -> {
        switch (b) {
          case Integer i -> {
            return a.longValue() + b.intValue();
          }
          case Long aLong -> {
            return a.longValue() + b.longValue();
          }
          case Short i -> {
            return a.longValue() + b.shortValue();
          }
          case Float v -> {
            return a.longValue() + b.floatValue();
          }
          case Double v -> {
            return a.longValue() + b.doubleValue();
          }
          case BigDecimal bigDecimal -> {
            return new BigDecimal(a.longValue()).add(bigDecimal);
          }
          default -> {
          }
        }
      }
      case Short i -> {
        switch (b) {
          case Integer integer -> {
            final var sum = a.shortValue() + b.intValue();
            if (sum < 0 && a.shortValue() > 0 && b.intValue() > 0)
            // SPECIAL CASE: UPGRADE TO LONG
            {
              return (long) (a.shortValue() + b.intValue());
            }
            return sum;
          }
          case Long l -> {
            return a.shortValue() + b.longValue();
          }
          case Short aShort -> {
            final var sum = a.shortValue() + b.shortValue();
            if (sum < 0 && a.shortValue() > 0 && b.shortValue() > 0)
            // SPECIAL CASE: UPGRADE TO INTEGER
            {
              return a.intValue() + b.intValue();
            }
            return sum;
          }
          case Float v -> {
            return a.shortValue() + b.floatValue();
          }
          case Double v -> {
            return a.shortValue() + b.doubleValue();
          }
          case BigDecimal bigDecimal -> {
            return new BigDecimal(a.shortValue()).add(bigDecimal);
          }
          default -> {
          }
        }
      }
      case Float v -> {
        switch (b) {
          case Integer i -> {
            return a.floatValue() + b.intValue();
          }
          case Long l -> {
            return a.floatValue() + b.longValue();
          }
          case Short i -> {
            return a.floatValue() + b.shortValue();
          }
          case Float aFloat -> {
            return a.floatValue() + b.floatValue();
          }
          case Double aDouble -> {
            return a.floatValue() + b.doubleValue();
          }
          case BigDecimal bigDecimal -> {
            return BigDecimal.valueOf(a.floatValue()).add(bigDecimal);
          }
          default -> {
          }
        }
      }
      case Double v -> {
        switch (b) {
          case Integer i -> {
            return a.doubleValue() + b.intValue();
          }
          case Long l -> {
            return a.doubleValue() + b.longValue();
          }
          case Short i -> {
            return a.doubleValue() + b.shortValue();
          }
          case Float aFloat -> {
            return a.doubleValue() + b.floatValue();
          }
          case Double aDouble -> {
            return a.doubleValue() + b.doubleValue();
          }
          case BigDecimal bigDecimal -> {
            return BigDecimal.valueOf(a.doubleValue()).add(bigDecimal);
          }
          default -> {
          }
        }
      }
      case BigDecimal bigDecimal -> {
        switch (b) {
          case Integer i -> {
            return ((BigDecimal) a).add(new BigDecimal(b.intValue()));
          }
          case Long l -> {
            return ((BigDecimal) a).add(new BigDecimal(b.longValue()));
          }
          case Short i -> {
            return ((BigDecimal) a).add(new BigDecimal(b.shortValue()));
          }
          case Float v -> {
            return ((BigDecimal) a).add(BigDecimal.valueOf(b.floatValue()));
          }
          case Double v -> {
            return ((BigDecimal) a).add(BigDecimal.valueOf(b.doubleValue()));
          }
          case BigDecimal decimal -> {
            return ((BigDecimal) a).add(decimal);
          }
          default -> {
          }
        }
      }
      default -> {
      }
    }

    throw new IllegalArgumentException(
        "Cannot increment value '"
            + a
            + "' ("
            + a.getClass()
            + ") with '"
            + b
            + "' ("
            + b.getClass()
            + ")");
  }

  public static Number[] castComparableNumber(Number context, Number max) {
    // CHECK FOR CONVERSION
    if (context instanceof Short) {
      // SHORT
      if (max instanceof Integer) {
        context = context.intValue();
      } else if (max instanceof Long) {
        context = context.longValue();
      } else if (max instanceof Float) {
        context = context.floatValue();
      } else if (max instanceof Double) {
        context = context.doubleValue();
      } else if (max instanceof BigDecimal) {
        context = new BigDecimal(context.intValue());
        var maxScale = Math.max(((BigDecimal) context).scale(), ((BigDecimal) max).scale());
        context = ((BigDecimal) context).setScale(maxScale, RoundingMode.DOWN);
        max = ((BigDecimal) max).setScale(maxScale, RoundingMode.DOWN);
      } else if (max instanceof Byte) {
        context = context.byteValue();
      }

    } else if (context instanceof Integer) {
      // INTEGER
      if (max instanceof Long) {
        context = context.longValue();
      } else if (max instanceof Float) {
        context = context.floatValue();
      } else if (max instanceof Double) {
        context = context.doubleValue();
      } else if (max instanceof BigDecimal) {
        context = new BigDecimal(context.intValue());
        var maxScale = Math.max(((BigDecimal) context).scale(), ((BigDecimal) max).scale());
        context = ((BigDecimal) context).setScale(maxScale, RoundingMode.DOWN);
        max = ((BigDecimal) max).setScale(maxScale, RoundingMode.DOWN);
      } else if (max instanceof Short) {
        max = max.intValue();
      } else if (max instanceof Byte) {
        max = max.intValue();
      }

    } else if (context instanceof Long) {
      // LONG
      if (max instanceof Float) {
        context = context.floatValue();
      } else if (max instanceof Double) {
        context = context.doubleValue();
      } else if (max instanceof BigDecimal) {
        context = new BigDecimal(context.longValue());
        var maxScale = Math.max(((BigDecimal) context).scale(), ((BigDecimal) max).scale());
        context = ((BigDecimal) context).setScale(maxScale, RoundingMode.DOWN);
        max = ((BigDecimal) max).setScale(maxScale, RoundingMode.DOWN);
      } else if (max instanceof Integer || max instanceof Byte || max instanceof Short) {
        max = max.longValue();
      }

    } else if (context instanceof Float) {
      // FLOAT
      if (max instanceof Double) {
        context = context.doubleValue();
      } else if (max instanceof BigDecimal) {
        context = BigDecimal.valueOf(context.floatValue());
        var maxScale = Math.max(((BigDecimal) context).scale(), ((BigDecimal) max).scale());
        context = ((BigDecimal) context).setScale(maxScale, RoundingMode.DOWN);
        max = ((BigDecimal) max).setScale(maxScale, RoundingMode.DOWN);
      } else if (max instanceof Byte
          || max instanceof Short
          || max instanceof Integer
          || max instanceof Long) {
        max = max.floatValue();
      }

    } else if (context instanceof Double) {
      // DOUBLE
      if (max instanceof BigDecimal) {
        context = BigDecimal.valueOf(context.doubleValue());
        var maxScale = Math.max(((BigDecimal) context).scale(), ((BigDecimal) max).scale());
        context = ((BigDecimal) context).setScale(maxScale, RoundingMode.DOWN);
        max = ((BigDecimal) max).setScale(maxScale, RoundingMode.DOWN);
      } else if (max instanceof Byte
          || max instanceof Short
          || max instanceof Integer
          || max instanceof Long
          || max instanceof Float) {
        max = max.doubleValue();
      }

    } else if (context instanceof BigDecimal) {
      // DOUBLE
      if (max instanceof Integer) {
        max = new BigDecimal((Integer) max);
      } else if (max instanceof Float) {
        max = BigDecimal.valueOf((Float) max);
      } else if (max instanceof Double) {
        max = BigDecimal.valueOf((Double) max);
      } else if (max instanceof Short) {
        max = new BigDecimal((Short) max);
      } else if (max instanceof Byte) {
        max = new BigDecimal((Byte) max);
      }

      var maxScale = Math.max(((BigDecimal) context).scale(), ((BigDecimal) max).scale());
      context = ((BigDecimal) context).setScale(maxScale, RoundingMode.DOWN);
      max = ((BigDecimal) max).setScale(maxScale, RoundingMode.DOWN);
    } else if (context instanceof Byte) {
      if (max instanceof Short) {
        context = context.shortValue();
      } else if (max instanceof Integer) {
        context = context.intValue();
      } else if (max instanceof Long) {
        context = context.longValue();
      } else if (max instanceof Float) {
        context = context.floatValue();
      } else if (max instanceof Double) {
        context = context.doubleValue();
      } else if (max instanceof BigDecimal) {
        context = new BigDecimal(context.intValue());
        var maxScale = Math.max(((BigDecimal) context).scale(), ((BigDecimal) max).scale());
        context = ((BigDecimal) context).setScale(maxScale, RoundingMode.DOWN);
        max = ((BigDecimal) max).setScale(maxScale, RoundingMode.DOWN);
      }
    }

    return new Number[]{context, max};
  }


  /**
   * Convert the input object to a string.
   *
   * @param iValue Any type supported
   * @return The string if the conversion succeed, otherwise the IllegalArgumentException exception
   */
  @Deprecated
  public static String asString(final Object iValue) {
    return iValue.toString();
  }

  @Nullable
  public static PropertyTypeInternal convertFromPublicType(PropertyType type) {
    return switch (type) {
      case null -> null;
      case BYTE -> BYTE;
      case BOOLEAN -> BOOLEAN;
      case SHORT -> SHORT;
      case INTEGER -> INTEGER;
      case LONG -> LONG;
      case STRING -> STRING;
      case FLOAT -> FLOAT;
      case DOUBLE -> DOUBLE;
      case DECIMAL -> DECIMAL;
      case DATE -> DATE;
      case DATETIME -> DATETIME;
      case BINARY -> BINARY;
      case LINK -> LINK;
      case EMBEDDED -> EMBEDDED;
      case EMBEDDEDLIST -> EMBEDDEDLIST;
      case EMBEDDEDSET -> EMBEDDEDSET;
      case EMBEDDEDMAP -> EMBEDDEDMAP;
      case LINKLIST -> LINKLIST;
      case LINKSET -> LINKSET;
      case LINKMAP -> LINKMAP;
      case LINKBAG -> LINKBAG;
    };
  }

  public boolean isMultiValue() {
    return this == EMBEDDEDLIST
        || this == EMBEDDEDMAP
        || this == EMBEDDEDSET
        || this == LINKLIST
        || this == LINKMAP
        || this == LINKSET
        || this == LINKBAG;
  }

  public boolean isList() {
    return this == EMBEDDEDLIST || this == LINKLIST;
  }

  public abstract PropertyType getPublicPropertyType();

  public boolean isLink() {
    return this == LINK
        || this == LINKSET
        || this == LINKLIST
        || this == LINKMAP
        || this == LINKBAG;
  }

  public boolean isEmbedded() {
    return this == EMBEDDED || this == EMBEDDEDLIST || this == EMBEDDEDMAP || this == EMBEDDEDSET;
  }

  public Class<?> getDefaultJavaType() {
    return javaDefaultType;
  }

  public Set<PropertyTypeInternal> getCastable() {
    return castable;
  }

  public String getName() {
    return name;
  }

}
