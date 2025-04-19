package com.jetbrains.youtrack.db.api.common.query;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrack.db.api.common.query.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkList;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkMap;
import com.jetbrains.youtrack.db.api.common.query.collection.links.LinkSet;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface BasicResult {

  /**
   * @param name the property name
   * @return the property value.
   */
  @Nullable
  <T> T getProperty(@Nonnull String name);

  @Nullable
  default Boolean getBoolean(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a boolean type, but " + value.getClass().getName());
  }

  @Nullable
  default Byte getByte(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Byte) {
      return (Byte) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a byte type, but " + value.getClass().getName());
  }

  @Nullable
  default Short getShort(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Short) {
      return (Short) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a short type, but " + value.getClass().getName());
  }

  @Nullable
  default Integer getInt(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Integer) {
      return (Integer) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not an integer type, but " + value.getClass().getName());

  }

  @Nullable
  default Long getLong(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof Long) {
      return (Long) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a long type, but " + value.getClass().getName());
  }

  @Nullable
  default Float getFloat(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Float) {
      return (Float) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a float type, but " + value.getClass().getName());
  }

  @Nullable
  default Double getDouble(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Double) {
      return (Double) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a double type, but " + value.getClass().getName());
  }

  @Nullable
  default String getString(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof String) {
      return (String) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a string type, but " + value.getClass().getName());
  }

  @Nullable
  default byte[] getBinary(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof byte[]) {
      return (byte[]) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a binary type, but " + value.getClass().getName());
  }

  @Nullable
  default Date getDate(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Date) {
      return (Date) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a date type, but " + value.getClass().getName());
  }

  @Nullable
  default Date getDateTime(@Nonnull String name) {
    return getDate(name);
  }

  @Nullable
  default BigDecimal getDecimal(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }

    throw new DatabaseException(
        "Property " + name + " is not a decimal type, but " + value.getClass().getName());
  }

  @Nullable
  default <T> EmbeddedList<T> getEmbeddedList(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof EmbeddedList<?> embeddedList) {
      //noinspection unchecked
      return (EmbeddedList<T>) embeddedList;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded list type, but " + value.getClass().getName());
  }

  @Nullable
  default LinkList getLinkList(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof LinkList list) {
      return list;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link list type, but " + value.getClass().getName());
  }

  @Nullable
  default <T> EmbeddedSet<T> getEmbeddedSet(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof EmbeddedSet<?> set) {
      //noinspection unchecked
      return (EmbeddedSet<T>) set;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded set type, but " + value.getClass().getName());
  }

  @Nullable
  default LinkSet getLinkSet(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof LinkSet linkSet) {
      return linkSet;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link set type, but " + value.getClass().getName());
  }

  @Nullable
  default <T> Map<String, T> getEmbeddedMap(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof Map<?, ?> map && !PropertyTypeInternal.checkLinkCollection(map.values())) {
      //noinspection unchecked
      return (Map<String, T>) map;
    }

    throw new DatabaseException(
        "Property " + name + " is not a embedded map type, but " + value.getClass().getName());
  }

  @Nullable
  default LinkMap getLinkMap(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof LinkMap linkMap) {
      return linkMap;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link map type, but " + value.getClass().getName());
  }


  @Nullable
  BasicResult getResult(@Nonnull String name);

  /**
   * This method similar to {@link #getProperty(String)} bun unlike before mentioned method it does
   * not load link automatically.
   *
   * @param name the name of the link property
   * @return the link property value, or null if the property does not exist
   * @throws IllegalArgumentException if requested property is not a link.
   * @see #getProperty(String)
   */
  @Nullable
  RID getLink(@Nonnull String name);

  /**
   * Returns all the names of defined properties
   *
   * @return all the names of defined properties
   */
  @Nonnull
  List<String> getPropertyNames();

  boolean isIdentifiable();

  @Nullable
  RID getIdentity();

  boolean isProjection();

  /**
   * Returns the result as <code>Map</code>. If the result has identity, then the @rid entry is
   * added. If the result is an entity that has a class, then the @class entry is added. If entity
   * is embedded, then the @embedded entry is added.
   */
  @Nonnull
  Map<String, Object> toMap();

  @Nonnull
  String toJSON();

  boolean hasProperty(@Nonnull String varName);

  /**
   * @return Returns session to which given record is bound or <code>null</code> if record is
   * unloaded.
   */
  @Nullable
  BasicDatabaseSession<?, ?> getBoundedToSession();

  /**
   * Detach the result from the session. If result contained a record, it will be converted into
   * record id.
   */
  @Nonnull
  BasicResult detach();
}
