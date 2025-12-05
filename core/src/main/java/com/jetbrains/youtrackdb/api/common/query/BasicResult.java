package com.jetbrains.youtrackdb.api.common.query;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedList;
import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkList;
import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkMap;
import com.jetbrains.youtrackdb.api.common.query.collection.links.LinkSet;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.transaction.Transaction;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/// Interface that represents both the result execution of a single SQL query and
/// [Entity] in the database.
///
/// Instances of this object are not thread-safe and cannot be used concurrently. Results are always
/// attached to the current [Transaction] if you want to
/// use it outside of the transaction call [#detach()] method.
///
///
/// @see Transaction#query(String, Map)
/// @see Transaction#query(String, Object...)
/// @see Transaction#execute(String, Map)
/// @see Transaction#execute(String, Object...)
/// @see Transaction#command(String, Map)
/// @see Transaction#command(String, Object...)
public interface BasicResult {

  /// Returns either value of a single property in [Entity] or
  /// value of a single projection property returned by a query.
  ///
  /// All types expressed in [PropertyType] are supported.
  /// Except for the listed above types, SQL queries can return nested results.
  ///
  /// @see #getResult(String)
  @Nullable
  <T> T getProperty(@Nonnull String name);

  /// Returns `boolean` property value associated with
  /// [PropertyType#BOOLEAN] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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

  /// Returns `byte` property value associated with the
  /// [PropertyType#BYTE] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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

  /// Returns `short` property value associated with the
  /// [PropertyType#SHORT] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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

  /// Returns `int` property value associated with the
  /// [PropertyType#INTEGER] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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

  /// Returns `long` property value associated with the
  /// [PropertyType#LONG] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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

  /// Returns `float` property value associated with the
  /// [PropertyType#FLOAT] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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

  /// Returns `double` property value associated with the
  /// [PropertyType#DOUBLE] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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

  /// Returns `string` property value associated with the
  /// [PropertyType#STRING] type contained in
  /// [Entity] or in a result of a query projection.
  ///
  /// @see #getProperty(String)
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
  default <T> EmbeddedMap<T> getEmbeddedMap(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof EmbeddedMap<?> map) {
      //noinspection unchecked
      return (EmbeddedMap<T>) map;
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
