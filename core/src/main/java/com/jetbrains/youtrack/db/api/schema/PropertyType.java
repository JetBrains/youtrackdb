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
package com.jetbrains.youtrack.db.api.schema;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.type.IdentityWrapper;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Generic representation of a type.<br> allowAssignmentFrom accepts any class, but Array.class
 * means that the type accepts generic Arrays.
 */
public enum PropertyType {
  BOOLEAN("Boolean", 0, Boolean.class, new Class<?>[]{Number.class}),

  INTEGER("Integer", 1, Integer.class, new Class<?>[]{Number.class}),

  SHORT("Short", 2, Short.class, new Class<?>[]{Number.class}),

  LONG(
      "Long",
      3,
      Long.class,
      new Class<?>[]{
          Number.class,
      }),

  FLOAT("Float", 4, Float.class, new Class<?>[]{Number.class}),

  DOUBLE("Double", 5, Double.class, new Class<?>[]{Number.class}),

  DATETIME("Datetime", 6, Date.class, new Class<?>[]{Date.class, Number.class}),

  STRING("String", 7, String.class, new Class<?>[]{Enum.class}),

  BINARY("Binary", 8, byte[].class, new Class<?>[]{byte[].class}),

  EMBEDDED(
      "Embedded",
      9,
      Object.class,
      new Class<?>[]{EntitySerializable.class, SerializableStream.class}),

  EMBEDDEDLIST(
      "EmbeddedList", 10, TrackedList.class,
      new Class<?>[]{List.class, MultiCollectionIterator.class}),

  EMBEDDEDSET("EmbeddedSet", 11, TrackedSet.class, new Class<?>[]{Set.class}),

  EMBEDDEDMAP("EmbeddedMap", 12, TrackedMap.class, new Class<?>[]{Map.class}),

  LINK("Link", 13, Identifiable.class, new Class<?>[]{Identifiable.class, RID.class}),

  LINKLIST("LinkList", 14, LinkList.class, new Class<?>[]{List.class}),

  LINKSET("LinkSet", 15, LinkSet.class, new Class<?>[]{Set.class}),

  LINKMAP("LinkMap", 16, LinkMap.class, new Class<?>[]{Map.class}),

  BYTE("Byte", 17, Byte.class, new Class<?>[]{Number.class}),

  DATE("Date", 19, Date.class, new Class<?>[]{Number.class}),

  DECIMAL("Decimal", 21, BigDecimal.class, new Class<?>[]{BigDecimal.class, Number.class}),

  LINKBAG("LinkBag", 22, RidBag.class, new Class<?>[]{RidBag.class}),

  ANY("Any", 23, null, new Class<?>[]{});

  // Don't change the order, the type discover get broken if you change the order.
  private static final PropertyType[] TYPES =
      new PropertyType[]{
          EMBEDDEDLIST, EMBEDDEDSET, EMBEDDEDMAP, LINK, EMBEDDED, STRING, DATETIME
      };

  private static final PropertyType[] TYPES_BY_ID = new PropertyType[24];
  // Values previosly stored in javaTypes
  private static final Map<Class<?>, PropertyType> TYPES_BY_CLASS = new HashMap<>();

  static {
    for (var oType : values()) {
      TYPES_BY_ID[oType.id] = oType;
    }
    // This is made by hand because not all types should be add.
    TYPES_BY_CLASS.put(Boolean.class, BOOLEAN);
    TYPES_BY_CLASS.put(Boolean.TYPE, BOOLEAN);
    TYPES_BY_CLASS.put(Integer.TYPE, INTEGER);
    TYPES_BY_CLASS.put(Integer.class, INTEGER);
    TYPES_BY_CLASS.put(BigInteger.class, INTEGER);
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
    TYPES_BY_CLASS.put(RecordId.class, LINK);
    TYPES_BY_CLASS.put(BigDecimal.class, DECIMAL);
    TYPES_BY_CLASS.put(RidBag.class, LINKBAG);
    TYPES_BY_CLASS.put(TrackedSet.class, EMBEDDEDSET);
    TYPES_BY_CLASS.put(LinkSet.class, LINKSET);
    TYPES_BY_CLASS.put(TrackedList.class, EMBEDDEDLIST);
    TYPES_BY_CLASS.put(LinkList.class, LINKLIST);
    TYPES_BY_CLASS.put(TrackedMap.class, EMBEDDEDMAP);
    TYPES_BY_CLASS.put(LinkMap.class, LINKMAP);
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
  private final Class<?>[] allowAssignmentFrom;
  private final Set<PropertyType> castable;

  PropertyType(
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
  public static PropertyType getById(final byte iId) {
    if (iId >= 0 && iId < TYPES_BY_ID.length) {
      return TYPES_BY_ID[iId];
    }
    LogManager.instance().warn(PropertyType.class, "Invalid type index: " + iId, (Object[]) null);
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

  /**
   * Return the correspondent type by checking the "assignability" of the class received as
   * parameter.
   *
   * @param iClass Class to check
   * @return PropertyType instance if found, otherwise null
   */
  public static PropertyType getTypeByClass(final Class<?> iClass) {
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

  private static PropertyType getTypeByClassInherit(final Class<?> iClass) {
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

  public static PropertyType getTypeByValue(Object value) {
    if (value == null) {
      return null;
    }

    var clazz = value.getClass();
    var type = TYPES_BY_CLASS.get(clazz);
    if (type != null) {
      return type;
    }

    var byType = getTypeByClassInherit(clazz);
    if (LINK == byType) {
      if (value instanceof EntityImpl && ((EntityImpl) value).isEmbedded()) {
        return EMBEDDED;
      }
    } else if (EMBEDDEDSET == byType) {
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
    }
    return byType;
  }

  public static boolean checkLinkCollection(Collection<?> toCheck) {
    if (toCheck == null) {
      return false;
    }

    var first = toCheck.stream().filter(Objects::nonNull).findAny();
    return first.map(
        o -> o instanceof Identifiable identifiable && (!(identifiable instanceof Entity entity)
            || !entity.isEmbedded())).orElse(false);
  }

  public static boolean canBeLinkCollection(Collection<?> toCheck) {
    if (toCheck == null) {
      return true;
    }

    var first = toCheck.stream().filter(Objects::nonNull).findAny();
    return first.map(
        o -> o instanceof Identifiable identifiable && (!(identifiable instanceof Entity entity)
            || !entity.isEmbedded())).orElse(true);
  }

  public static boolean isSimpleValueType(@Nullable Object value) {
    if (value == null) {
      return true;
    }

    var cls = value.getClass();
    if (cls.isPrimitive()) {
      return true;
    }

    return Integer.class.isAssignableFrom(cls) || Long.class.isAssignableFrom(cls) || Short.class
        .isAssignableFrom(cls) || Byte.class.isAssignableFrom(cls) || Double.class
        .isAssignableFrom(cls) || Float.class.isAssignableFrom(cls) || Boolean.class
        .isAssignableFrom(cls) || BigDecimal.class.isAssignableFrom(cls) ||
        Date.class.isAssignableFrom(cls) || String.class.isAssignableFrom(cls);
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
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <T> T convert(@Nullable DatabaseSessionInternal session, final Object value,
      final Class<? extends T> targetClass) {
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
        return (T) StringSerializerHelper.getBinaryContent(value);
      } else if (byte[].class.isAssignableFrom(value.getClass())) {
        return (T) value;
      } else if (targetClass.isEnum()) {
        if (value instanceof Number) {
          return (T) ((Class<Enum>) targetClass).getEnumConstants()[((Number) value).intValue()];
        }
        return (T) Enum.valueOf((Class<Enum>) targetClass, value.toString());
      } else if (targetClass.equals(Byte.TYPE) || targetClass.equals(Byte.class)) {
        if (value instanceof Byte) {
          return (T) value;
        } else if (value instanceof String) {
          return (T) Byte.valueOf((String) value);
        } else {
          assert value instanceof Number;
          return (T) (Byte) ((Number) value).byteValue();
        }

      } else if (targetClass.equals(Short.TYPE) || targetClass.equals(Short.class)) {
        switch (value) {
          case Short i -> {
            return (T) value;
          }
          case String s -> {
            return (T) Short.valueOf(s);
          }
          case Number number -> {
            return (T) (Short) number.shortValue();
          }
          default -> {
          }
        }

      } else if (targetClass.equals(Integer.TYPE) || targetClass.equals(Integer.class)) {
        switch (value) {
          case Integer i -> {
            return (T) value;
          }
          case String s -> {
            if (value.toString().isEmpty()) {
              return null;
            }
            return (T) Integer.valueOf(s);
          }
          case Number number -> {
            return (T) (Integer) number.intValue();
          }
          default -> {
          }
        }

      } else if (targetClass.equals(Long.TYPE) || targetClass.equals(Long.class)) {
        switch (value) {
          case Long l -> {
            return (T) value;
          }
          case String s -> {
            return (T) Long.valueOf(s);
          }
          case Date date -> {
            return (T) (Long) date.getTime();
          }
          case Number number -> {
            return (T) (Long) number.longValue();
          }
          default -> {
          }
        }

      } else if (targetClass.equals(Float.TYPE) || targetClass.equals(Float.class)) {
        switch (value) {
          case Float v -> {
            return (T) value;
          }
          case String s -> {
            return (T) Float.valueOf(s);
          }
          case Number number -> {
            return (T) (Float) number.floatValue();
          }
          default -> {
          }
        }

      } else if (targetClass.equals(BigDecimal.class)) {
        if (value instanceof String) {
          return (T) new BigDecimal((String) value);
        } else if (value instanceof Number) {
          return (T) new BigDecimal(value.toString());
        }

      } else if (targetClass.equals(Double.TYPE) || targetClass.equals(Double.class)) {
        switch (value) {
          case Double v -> {
            return (T) value;
          }
          case String s -> {
            return (T) Double.valueOf(s);
          }
          case Float v -> {
            // THIS IS NECESSARY DUE TO A BUG/STRANGE BEHAVIOR OF JAVA BY LOSSING PRECISION

            return (T) Double.valueOf(value.toString());
            // THIS IS NECESSARY DUE TO A BUG/STRANGE BEHAVIOR OF JAVA BY LOSSING PRECISION
          }
          case Number number -> {
            return (T) (Double) number.doubleValue();
          }
          default -> {
          }
        }

      } else if (targetClass.equals(Boolean.TYPE) || targetClass.equals(Boolean.class)) {
        switch (value) {
          case Boolean b -> {
            return (T) value;
          }
          case String s -> {
            if (s.equalsIgnoreCase("true")) {
              return (T) Boolean.TRUE;
            } else if (((String) value).equalsIgnoreCase("false")) {
              return (T) Boolean.FALSE;
            }

            throw new DatabaseException(session != null ? session.getDatabaseName() : null,
                String.format("Error in conversion of value '%s' to type '%s'", value,
                    targetClass));
          }
          case Number number -> {
            return (T) (Boolean) (number.intValue() != 0);
          }
          default -> {
          }
        }

      } else if (LinkSet.class.isAssignableFrom(targetClass)) {
        switch (value) {
          case Collection<?> collection -> {
            var linkSet = session.newLinkSet(collection.size());
            linkSet.addAll((Collection<? extends Identifiable>) collection);
            return (T) linkSet;
          }
          case Iterable<?> iterable -> {
            var linkSet = session.newLinkSet();
            for (var item : iterable) {
              switch (item) {
                case Identifiable identifiable -> linkSet.add(identifiable);
                case String s -> linkSet.add(new RecordId(s));
                case Result res when res.isRecord() -> linkSet.add(res.getIdentity());
                case null, default -> throw new DatabaseException(session.getDatabaseName(),
                    String.format(
                        "Error in conversion of value '%s' to type '%s'", value, targetClass));
              }
            }
            return (T) linkSet;
          }
          case Iterator<?> iterator -> {
            var linkSet = session.newLinkSet();
            while (iterator.hasNext()) {
              var item = iterator.next();
              switch (item) {
                case Identifiable identifiable -> linkSet.add(identifiable);
                case String s -> linkSet.add(new RecordId(s));
                case Result res when res.isRecord() -> linkSet.add(res.getIdentity());
                case null, default -> throw new DatabaseException(session.getDatabaseName(),
                    String.format(
                        "Error in conversion of value '%s' to type '%s'", value, targetClass));
              }
            }
            return (T) linkSet;
          }
          default -> {
            var linkSet = session.newLinkSet();
            linkSet.add((Identifiable) value);
            return (T) linkSet;

          }
        }
      } else if (TrackedSet.class.isAssignableFrom(targetClass)) {
        switch (value) {
          case Collection<?> collection -> {
            var embeddedSet = session.newEmbeddedSet(collection.size());
            embeddedSet.addAll(collection);
            return (T) embeddedSet;
          }
          case Iterable<?> iterable -> {
            var embeddedSet = session.newEmbeddedSet();

            for (var item : iterable) {
              embeddedSet.add(item);
            }

            return (T) embeddedSet;
          }
          case Iterator<?> iterator -> {
            var embeddedSet = session.newEmbeddedSet();
            while (iterator.hasNext()) {
              embeddedSet.add(iterator.next());
            }
            return (T) embeddedSet;
          }
          default -> {
            var embeddedSet = session.newEmbeddedSet();
            embeddedSet.add(value);
            return (T) embeddedSet;
          }
        }
      } else if (Set.class.isAssignableFrom(targetClass)) {
        // The caller specifically wants a Set.  If the value is a collection
        // we will add all of the items in the collection to a set.  Otherwise
        // we will create a singleton set with only the value in it.
        if (value instanceof Collection<?>) {
          return (T) new HashSet<Object>((Collection<?>) value);
        } else {
          return (T) Collections.singleton(value);
        }
      } else if (LinkList.class.isAssignableFrom(targetClass)) {
        switch (value) {
          case Collection<?> collection -> {
            var linkList = session.newLinkList(collection.size());
            linkList.addAll((Collection<? extends Identifiable>) collection);
            return (T) linkList;
          }
          case Iterable<?> iterable -> {
            var linkList = session.newLinkList();
            for (var item : iterable) {
              switch (item) {
                case Identifiable identifiable -> linkList.add(identifiable);
                case String s -> linkList.add(new RecordId(s));
                case Result res when res.isRecord() -> linkList.add(res.getIdentity());
                case null, default -> throw new DatabaseException(session.getDatabaseName(),
                    String.format(
                        "Error in conversion of value '%s' to type '%s'", value, targetClass));
              }
            }
            return (T) linkList;
          }
          case Iterator<?> iterator -> {
            var linkList = session.newLinkList();
            while (iterator.hasNext()) {
              var item = iterator.next();
              switch (item) {
                case Identifiable identifiable -> linkList.add(identifiable);
                case String s -> linkList.add(new RecordId(s));
                case Result res when res.isRecord() -> linkList.add(res.getIdentity());
                case null, default -> throw new DatabaseException(session.getDatabaseName(),
                    String.format(
                        "Error in conversion of value '%s' to type '%s'", value, targetClass));
              }
            }
            return (T) linkList;
          }
          default -> {
            var linkList = session.newLinkList();
            linkList.add((Identifiable) value);
            return (T) linkList;
          }
        }
      } else if (TrackedList.class.isAssignableFrom(targetClass)) {
        switch (value) {
          case Collection<?> collection -> {
            var embeddedList = session.newEmbeddedList(collection.size());
            embeddedList.addAll(collection);
            return (T) embeddedList;
          }
          case Iterable<?> iterable -> {
            var embeddedList = session.newEmbeddedList();
            for (var item : iterable) {
              embeddedList.add(item);
            }
            return (T) embeddedList;
          }
          case Iterator<?> iterator -> {
            var embeddedList = session.newEmbeddedList();
            while (iterator.hasNext()) {
              embeddedList.add(iterator.next());
            }
            return (T) embeddedList;
          }
          default -> {
            var embeddedList = session.newEmbeddedList();
            embeddedList.add(value);
            return (T) embeddedList;
          }
        }
      } else if (List.class.isAssignableFrom(targetClass)) {
        // The caller specifically wants a List.  If the value is a collection
        // we will add all of the items in the collection to a List.  Otherwise
        // we will create a singleton List with only the value in it.
        if (value instanceof Collection<?>) {
          return (T) new ArrayList<>((Collection<Object>) value);
        } else {
          return (T) Collections.singletonList(value);
        }

      } else if (Collection.class.equals(targetClass)) {
        // The caller specifically wants a Collection of any type.
        // we will return a list if the value is a collection or
        // a singleton set if the value is not a collection.
        if (value instanceof Collection<?>) {
          return (T) new ArrayList<Object>((Collection<?>) value);
        } else {
          return (T) Collections.singleton(value);
        }

      } else if (LinkMap.class.isAssignableFrom(targetClass)) {
        if (value instanceof Map<?, ?> map) {
          var linkMap = session.newLinkMap(map.size());
          for (var entry : map.entrySet()) {
            linkMap.put(entry.getKey().toString(), (Identifiable) entry.getValue());
          }
          return (T) linkMap;
        } else {
          var linkMap = session.newLinkMap();
          linkMap.put("value", (Identifiable) value);
          return (T) linkMap;
        }
      } else if (TrackedMap.class.isAssignableFrom(targetClass)) {
        if (value instanceof Map<?, ?> map) {
          var embeddedMap = session.newEmbeddedMap(map.size());
          embeddedMap.putAll((Map) map);
          return (T) embeddedMap;
        } else {
          var embeddedMap = session.newEmbeddedMap();
          embeddedMap.put("value", value);
          return (T) embeddedMap;
        }
      } else if (Map.class.isAssignableFrom(targetClass)) {
        // The caller specifically wants a Map.  If the value is a map
        // we will add all of the items in the map to a Map.  Otherwise
        // we will create a singleton map with only the value in it.
        if (value instanceof Map<?, ?>) {
          return (T) new HashMap<Object, Object>((Map<?, ?>) value);
        } else {
          return (T) Collections.singletonMap("value", value);
        }
      } else if (targetClass.equals(Date.class)) {
        if (value instanceof Number) {
          return (T) new Date(((Number) value).longValue());
        }
        if (value instanceof String) {
          if (IOUtils.isLong(value.toString())) {
            return (T) new Date(Long.parseLong(value.toString()));
          }
          try {
            return (T) DateHelper.getDateTimeFormatInstance(session)
                .parse((String) value);
          } catch (ParseException ignore) {
            return (T) DateHelper.getDateFormatInstance(session)
                .parse((String) value);
          }
        }
      } else if (targetClass.equals(String.class)) {
        if (value instanceof Collection
            && ((Collection) value).size() == 1
            && ((Collection) value).iterator().next() instanceof String) {
          return (T) ((Collection) value).iterator().next();
        }
        return (T) value.toString();
      } else if (Identifiable.class.isAssignableFrom(targetClass)) {
        if (MultiValue.isMultiValue(value)) {
          List<Identifiable> result = new ArrayList<>();
          for (var o : MultiValue.getMultiValueIterable(value)) {
            if (o instanceof Identifiable) {
              result.add((Identifiable) o);
            } else if (o instanceof String) {
              try {
                result.add(new RecordId(value.toString()));
              } catch (Exception e) {
                throw BaseException.wrapException(
                    new DatabaseException(session != null ? session.getDatabaseName() : null,
                        String.format(
                            "Error in conversion of value '%s' to type '%s'", value, targetClass)),
                    e, session.getDatabaseName());
              }
            } else if (o instanceof Result res && res.isEntity()) {
              result.add(res.getIdentity());
            }
          }

          if (result.isEmpty()) {
            return null;
          }
          if (result.size() == 1) {
            return (T) result.getFirst();
          }

          throw new DatabaseException(session != null ? session.getDatabaseName() : null,
              String.format(
                  "Error in conversion of value '%s' to type '%s'", value, targetClass));
        } else if (value instanceof String) {
          try {
            return (T) new RecordId((String) value);
          } catch (Exception e) {
            throw new ClassCastException(
                String.format(
                    "Error in conversion of value '%s' to type '%s'", value, targetClass));
          }
        } else if (value instanceof IdentityWrapper entityWrapper) {
          return (T) entityWrapper.getIdentity();
        }
      }

      if (targetClass.equals(RidBag.class) && value instanceof Iterable<?> iterable) {
        var ridBag = new RidBag(session);

        for (var item : iterable) {
          if (item instanceof Identifiable identifiable) {
            ridBag.add(identifiable.getIdentity());
          } else if (item instanceof String) {
            ridBag.add(new RecordId((String) item));
          }
        }

        return (T) ridBag;
      }

    } catch (IllegalArgumentException e) {
      // PASS THROUGH
      throw BaseException.wrapException(
          new DatabaseException(session != null ? session.getDatabaseName() : null,
              String.format("Error in conversion of value '%s' to type '%s'", value, targetClass)),
          e, session.getDatabaseName());
    } catch (Exception e) {
      return switch (value) {
        case Collection collection when collection.size() == 1
            && !Collection.class.isAssignableFrom(targetClass) ->
          // this must be a comparison with the result of a subquery, try to unbox the collection
            convert(session, collection.iterator().next(), targetClass);
        case Result result when result.getPropertyNames().size() == 1
            && !Result.class.isAssignableFrom(targetClass) ->
          // try to unbox Result with a single property, for subqueries
            convert(session,
                result.getProperty(result.getPropertyNames().iterator().next()),
                targetClass);
        case Entity entity when ((EntityInternal) value).getPropertyNamesInternal().size() == 1
            && !Entity.class.isAssignableFrom(targetClass) ->
          // try to unbox Result with a single property, for subqueries
            convert(session,
                entity
                    .getProperty(
                        ((EntityInternal) value).getPropertyNamesInternal().iterator().next()),
                targetClass);
        default -> throw BaseException.wrapException(
            new ValidationException(session != null ? session.getDatabaseName() : null,
                String.format("Error in conversion of value '%s' to type '%s'", value,
                    targetClass)),
            e, session.getDatabaseName());
      };
    }

    throw new DatabaseException(session != null ? session.getDatabaseName() : null,
        String.format("Error in conversion of value '%s' to type '%s'", value, targetClass));
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
        var maxScale = Math.max(((BigDecimal) context).scale(), (((BigDecimal) max).scale()));
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
        var maxScale = Math.max(((BigDecimal) context).scale(), (((BigDecimal) max).scale()));
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
        var maxScale = Math.max(((BigDecimal) context).scale(), (((BigDecimal) max).scale()));
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
        var maxScale = Math.max(((BigDecimal) context).scale(), (((BigDecimal) max).scale()));
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
        var maxScale = Math.max(((BigDecimal) context).scale(), (((BigDecimal) max).scale()));
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

      var maxScale = Math.max(((BigDecimal) context).scale(), (((BigDecimal) max).scale()));
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
        var maxScale = Math.max(((BigDecimal) context).scale(), (((BigDecimal) max).scale()));
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

  public Set<PropertyType> getCastable() {
    return castable;
  }

  public String getName() {
    return name;
  }

}
