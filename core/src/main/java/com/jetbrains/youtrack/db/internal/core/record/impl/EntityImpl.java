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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.EmbeddedEntity;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Relation;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedList;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrack.db.api.record.collection.embedded.EmbeddedSet;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkList;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkMap;
import com.jetbrains.youtrack.db.api.record.collection.links.LinkSet;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.StorageBackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Identity;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyAccess;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryptionNone;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordVersionHelper;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeBasedLinkBag;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IterableUtils;

/**
 * Entity representation to handle values dynamically. Can be used in schema-less, schema-mixed and
 * schema-full modes. Fields can be added at run-time. Instances can be reused across calls by using
 * the reset() before re-use.
 */
@SuppressWarnings({"unchecked"})
public class EntityImpl extends RecordAbstract implements Entity {

  public static char OPPOSITE_LINK_CONTAINER_PREFIX = '#';
  public static final byte RECORD_TYPE = 'd';

  public static final String RESULT_PROPERTY_TYPES = "$propertyTypes";
  private int propertiesCount;

  private Map<String, EntityEntry> properties;

  private boolean lazyLoad = true;
  protected WeakReference<RecordElement> owner = null;

  private ImmutableSchema schema;
  private String className;
  private SchemaImmutableClass immutableClazz;

  @Nullable
  private ArrayList<BTreeBasedLinkBag> linkBagsToDelete;

  private int immutableSchemaVersion = 1;

  public PropertyAccess propertyAccess;
  public PropertyEncryption propertyEncryption;

  private boolean propertyConversionInProgress = false;

  /**
   * Internal constructor used on unmarshalling.
   */
  public EntityImpl(@Nonnull DatabaseSessionInternal session) {
    super(session);
    assert session.assertIfNotActive();
    setup();
  }

  /**
   * Internal constructor used on unmarshalling.
   */
  public EntityImpl(@Nonnull DatabaseSessionInternal database, RecordId rid) {
    super(database);
    assert assertIfAlreadyLoaded(rid);

    setup();

    this.recordId.setCollectionAndPosition(rid.getCollectionId(), rid.getCollectionPosition());
  }

  /**
   * Creates a new instance in memory of the specified class.
   * <b>Can be used only for newly created entities</b>
   *
   * @param session    the session the instance will be attached to
   * @param iClassName Class name
   */
  public EntityImpl(@Nonnull DatabaseSessionInternal session, final String iClassName) {
    super(session);

    status = STATUS.LOADED;
    assert session.assertIfNotActive();
    setup();
    setClassNameWithoutPropertiesPostProcessing(iClassName);

    var cls = getImmutableSchemaClass(session);
    if (cls != null) {
      if (!isEmbedded() && cls.isAbstract()) {
        throw new SchemaException(session,
            "Standalone entities can be only of non-abstract classes. Provided class : "
                + cls.getName(
            ) + " is abstract.");
      }
    }
  }

  @Override
  @Nonnull
  public Vertex asVertex() {
    checkForBinding();
    if (this instanceof Vertex vertex) {
      return vertex;
    }

    throw new IllegalStateException("Entity is not a vertex");
  }

  @Override
  public boolean sourceIsParsedByProperties() {
    return super.sourceIsParsedByProperties() || properties != null && !properties.isEmpty();
  }

  @Nullable
  @Override
  public Vertex asVertexOrNull() {
    checkForBinding();
    if (this instanceof Vertex vertex) {
      return vertex;
    }

    return null;
  }

  @Override
  @Nonnull
  public StatefulEdge asStatefulEdge() {
    checkForBinding();
    if (this instanceof StatefulEdge edge) {
      return edge;
    }

    throw new DatabaseException("Entity is not an edge");
  }

  @Override
  @Nullable
  public StatefulEdge asStatefulEdgeOrNull() {
    checkForBinding();
    if (this instanceof StatefulEdge edge) {
      return edge;
    }

    return null;
  }

  @Override
  public boolean isVertex() {
    checkForBinding();
    if (this instanceof Vertex) {
      return true;
    }

    SchemaClass type = this.getImmutableSchemaClass(session);
    if (type == null) {
      return false;
    }

    return type.isVertexType();
  }

  @Override
  public boolean isStatefulEdge() {
    checkForBinding();
    if (this instanceof Edge) {
      return true;
    }

    SchemaClass type = this.getImmutableSchemaClass(session);
    if (type == null) {
      return false;
    }

    return type.isEdgeType();
  }

  @Override
  public boolean isEdge() {
    return isStatefulEdge();
  }

  @Override
  @Nonnull
  public Edge asEdge() {
    return asStatefulEdgeOrNull();
  }

  @Override
  @Nullable
  public Edge asEdgeOrNull() {
    return asStatefulEdgeOrNull();
  }

  @Override
  public boolean isProjection() {
    return false;
  }

  List<String> calculatePropertyNames(boolean includeSystemProperties, boolean checkAccess) {
    checkForBinding();

    if (status == RecordElement.STATUS.LOADED && source != null) {
      // DESERIALIZE FIELD NAMES ONLY (SUPPORTED ONLY BY BINARY SERIALIZER)
      final var propertyNames = recordSerializer.getFieldNames(session, this, source);
      if (propertyNames != null) {
        var properties = new ArrayList<String>();
        if (checkAccess && (propertyAccess != null && propertyAccess.hasFilters())) {
          for (var propertyName : propertyNames) {
            if ((includeSystemProperties || !isSystemProperty(propertyName))
                && propertyAccess.isReadable(propertyName)) {
              properties.add(propertyName);
            }
          }
        } else {
          for (var propertyName : propertyNames) {
            if ((includeSystemProperties || !isSystemProperty(propertyName))) {
              properties.add(propertyName);
            }
          }
        }
        return properties;
      }
    }

    checkForProperties();

    if (properties == null || properties.isEmpty()) {
      return Collections.emptyList();
    }

    var properties = new ArrayList<String>();
    if (checkAccess && (propertyAccess != null && propertyAccess.hasFilters())) {
      for (var entry : this.properties.entrySet()) {
        var propertyName = entry.getKey();
        if (entry.getValue().exists() && (includeSystemProperties || !isSystemProperty(
            propertyName)) &&
            propertyAccess.isReadable(propertyName)) {
          properties.add(entry.getKey());
        }
      }
    } else {
      for (var entry : this.properties.entrySet()) {
        if (entry.getValue().exists()) {
          var propertyName = entry.getKey();
          if (includeSystemProperties || !isSystemProperty(propertyName)) {
            properties.add(propertyName);
          }
        }
      }
    }

    return properties;
  }


  @Override
  public @Nonnull List<String> getPropertyNames() {
    return getPropertyNamesInternal(false, true);
  }


  public List<String> getPropertyNamesInternal(boolean includeSystemProperties,
      boolean checkAccess) {
    return calculatePropertyNames(includeSystemProperties, checkAccess);
  }

  /**
   * retrieves a property value from the current entity
   *
   * @param propertyName The property name, it can contain any character (it's not evaluated as an
   *                     expression, as in #eval()
   * @return the property value. Null if the property does not exist.
   */
  @Override
  public <RET> RET getProperty(final @Nonnull String propertyName) {
    validatePropertyName(propertyName, true);

    if (!isPropertyAccessible(propertyName)) {
      return null;
    }

    return getPropertyInternal(propertyName);
  }

  @Nullable
  @Override
  public EmbeddedEntity getEmbeddedEntity(@Nonnull String name) {
    var propertyValue = getProperty(name);
    if (propertyValue == null) {
      return null;
    }
    if (propertyValue instanceof EmbeddedEntity entity) {
      return entity;
    }

    throw new DatabaseException("Property " + name + " does not contain an embedded entity");
  }

  @Override
  @Nullable
  public Entity getEntity(@Nonnull String name) {
    var property = getProperty(name);

    return switch (property) {
      case null -> null;
      case Entity entity -> entity;
      case Identifiable identifiable -> {
        var transaction = session.getActiveTransaction();
        yield transaction.loadEntity(identifiable);
      }
      default -> throw new DatabaseException(session.getDatabaseName(),
          "Property "
              + name
              + " is not an entity property, it is a "
              + property.getClass().getName());
    };
  }

  @Override
  @Nullable
  public Result getResult(@Nonnull String name) {
    return getEntity(name);
  }

  @Override
  @Nullable
  public Blob getBlob(@Nonnull String propertyName) {
    var property = getProperty(propertyName);

    return switch (property) {
      case null -> null;
      case Blob blob -> blob;
      case Identifiable identifiable -> {
        var transaction = session.getActiveTransaction();
        yield transaction.loadBlob(identifiable);
      }
      default -> throw new DatabaseException(session.getDatabaseName(),
          "Property "
              + propertyName
              + " is not a blob property, it is a "
              + property.getClass().getName());
    };
  }

  public <RET> RET getPropertyInternal(String name) {
    return getPropertyInternal(name, isLazyLoad());
  }

  @Nullable
  public <RET> RET getPropertyInternal(String name, boolean lazyLoad) {
    if (name == null) {
      return null;
    }

    checkForBinding();
    if (!name.isEmpty() && name.charAt(0) == '@') {
      var value = (RET) EntityHelper.getRecordAttribute(session, this, name);
      if (value != null) {
        return value;
      }
    }

    RET value = null;
    checkForProperties(name);

    var entry = properties.get(name);
    if (entry != null && entry.exists()) {
      value = (RET) entry.value;
    }

    if (value == null) {
      return null;
    }

    if (value instanceof RID rid && lazyLoad) {
      try {
        value = session.load(rid);
      } catch (RecordNotFoundException e) {
        return null;
      }
    }

    return convertToGraphElement(value);
  }

  @Override
  public <RET> RET getPropertyOnLoadValue(@Nonnull String name) {
    validatePropertyName(name, false);

    return getPropertyOnLoadValueInternal(name);
  }

  @Override
  public @Nonnull List<String> getDirtyProperties() {
    return getDirtyPropertiesInternal(false, true);
  }

  @Override
  public @Nonnull List<String> getDirtyPropertiesBetweenCallbacks() {
    return getDirtyPropertiesBetweenCallbacksInternal(false, true);
  }

  @Nullable
  public <RET> RET getPropertyOnLoadValueInternal(@Nonnull String name) {
    checkForBinding();
    checkForProperties();

    var property = properties.get(name);
    if (property != null) {
      var onLoadValue = (RET) property.getOnLoadValue(session);
      if (onLoadValue instanceof LinkBag) {
        throw new IllegalArgumentException(
            "getPropertyOnLoadValue(name) is not designed to work with Edge properties");
      }
      if (onLoadValue instanceof RID orid) {
        if (isLazyLoad()) {
          try {
            return session.load(orid);
          } catch (RecordNotFoundException e) {
            return null;
          }
        } else {
          return onLoadValue;
        }
      }
      if (onLoadValue instanceof DBRecord record) {
        if (isLazyLoad()) {
          return onLoadValue;
        } else {
          return (RET) record.getIdentity();
        }
      }
      return onLoadValue;
    } else {
      return getPropertyInternal(name);
    }
  }


  private static <RET> RET convertToGraphElement(RET value) {
    if (value instanceof Entity entity) {
      if (entity.isVertex()) {
        value = (RET) entity.asVertex();
      } else {
        if (entity.isStatefulEdge()) {
          value = (RET) entity.asEdge();
        }
      }
    }
    return value;
  }

  /**
   * This method similar to {@link Result#getProperty(String)} but unlike before mentioned method it
   * does not load links automatically.
   *
   * @param propertyName the name of the link property
   * @return the link property value, or null if the property does not exist
   * @throws IllegalArgumentException if requested property is not a link.
   * @see Result#getProperty(String)
   */
  @Override
  @Nullable
  public RID getLink(@Nonnull String propertyName) {
    validatePropertyName(propertyName, true);
    if (!isPropertyAccessible(propertyName)) {
      return null;
    }

    return getLinkPropertyInternal(propertyName);
  }

  @Nullable
  public RID getLinkPropertyInternal(String name) {
    var result = getPropertyInternal(name, false);

    return switch (result) {
      case null -> null;
      case RecordAbstract recordAbstract -> recordAbstract.getIdentity();
      case Identifiable identifiable -> identifiable.getIdentity();
      default -> throw new IllegalArgumentException(
          "Property " + name + " is not a link type, but " + result.getClass().getName());
    };
  }

  @Override
  @Nullable
  public <T> EmbeddedList<T> getEmbeddedList(@Nonnull String name) {
    var value = getProperty(name);
    if (value == null) {
      return null;
    }

    if (value instanceof EntityEmbeddedListImpl<?> list) {
      return (EmbeddedList<T>) list;
    }

    throw new DatabaseException(
        "Property " + name + " is not an embedded list type, but " + value.getClass().getName());
  }

  @Override
  @Nullable
  public LinkList getLinkList(@Nonnull String name) {
    var value = getProperty(name);

    if (value == null) {
      return null;
    }

    if (value instanceof EntityLinkListImpl list) {
      return list;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link list type, but " + value.getClass().getName());
  }

  @Override
  @Nullable
  public <T> Map<String, T> getEmbeddedMap(@Nonnull String name) {
    var value = getProperty(name);

    if (value == null) {
      return null;
    }

    if (value instanceof EntityEmbeddedMapImpl<?> map) {
      return (Map<String, T>) map;
    }

    throw new DatabaseException(
        "Property " + name + " is not an embedded map type, but " + value.getClass().getName());
  }

  @Override
  @Nullable
  public LinkMap getLinkMap(@Nonnull String name) {
    var value = getProperty(name);

    if (value == null) {
      return null;
    }

    if (value instanceof EntityLinkMapIml map) {
      return map;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link map type, but " + value.getClass().getName());
  }

  @Override
  @Nullable
  public <T> EmbeddedSet<T> getEmbeddedSet(@Nonnull String name) {
    var value = getProperty(name);

    if (value == null) {
      return null;
    }

    if (value instanceof EntityEmbeddedSetImpl<?> set) {
      return (EmbeddedSet<T>) set;
    }

    throw new DatabaseException(
        "Property " + name + " is not an embedded set type, but " + value.getClass().getName());
  }

  @Override
  @Nullable
  public LinkSet getLinkSet(@Nonnull String name) {
    var value = getProperty(name);

    if (value == null) {
      return null;
    }

    if (value instanceof EntityLinkSetImpl set) {
      return set;
    }

    throw new DatabaseException(
        "Property " + name + " is not a link set type, but " + value.getClass().getName());
  }

  private void validatePropertyUpdate(String propertyName, Object propertyValue) {
    validatePropertyName(propertyName, false);
    validatePropertyValue(propertyName, propertyValue);

    if (!isPropertyAccessible(propertyName)) {
      throw new SecurityException("Property " + propertyName + " is not accessible");
    }
  }


  @Override
  public @Nonnull <T> EmbeddedList<T> getOrCreateEmbeddedList(@Nonnull String name) {
    var value = this.<EmbeddedList<T>>getProperty(name);

    if (value == null) {
      value = new EntityEmbeddedListImpl<>(this);
      return (EmbeddedList<T>) setProperty(name, value, PropertyType.EMBEDDEDLIST);
    }

    return value;
  }

  @Override
  @Nonnull
  public <T> EmbeddedList<T> getOrCreateEmbeddedList(@Nonnull String name,
      @Nonnull PropertyType linkedType) {
    var value = this.<EntityEmbeddedListImpl<T>>getProperty(name);

    if (value == null) {
      value = new EntityEmbeddedListImpl<>(this);
      setProperty(name, value, PropertyType.EMBEDDEDLIST, linkedType);
    } else {
      var linkedTypeProperty = PropertyTypeInternal.getTypeByValue(value);
      if (linkedTypeProperty.getPublicPropertyType() != linkedType) {
        throw new IllegalArgumentException(
            "Property " + name + " is not of type " + linkedType + ", but " + linkedTypeProperty);
      }
    }

    return value;
  }

  @Override
  @Nonnull
  public <T> EmbeddedList<T> newEmbeddedList(@Nonnull String name) {
    var value = new EntityEmbeddedListImpl<T>(this);
    return (EmbeddedList<T>) setProperty(name, value, PropertyType.EMBEDDEDLIST);
  }

  @Override
  @Nonnull
  public <T> EmbeddedList<T> newEmbeddedList(@Nonnull String name,
      @Nonnull PropertyType linkedType) {
    var value = new EntityEmbeddedListImpl<T>(this);
    setProperty(name, value, PropertyType.EMBEDDEDLIST, linkedType);
    return value;
  }

  @Override
  @Nonnull
  public <T> EmbeddedList<T> newEmbeddedList(@Nonnull String name, @Nonnull Collection<T> source) {
    var value = (EmbeddedList<T>) PropertyTypeInternal.EMBEDDEDLIST.copy(source, session);
    return (EmbeddedList<T>) setProperty(name, value, PropertyType.EMBEDDEDLIST);
  }

  @Override
  @Nonnull
  public <T> EmbeddedList<T> newEmbeddedList(@Nonnull String name, @Nonnull Collection<T> source,
      @Nonnull PropertyType linkedType) {
    var value = (EmbeddedList<T>) PropertyTypeInternal.EMBEDDEDLIST.copy(source, session);
    setProperty(name, value, PropertyType.EMBEDDEDLIST, linkedType);
    return value;
  }

  @Override
  @Nonnull
  public <T> EmbeddedList<T> newEmbeddedList(@Nonnull String name, T[] source) {
    var componentType = source.getClass().getComponentType();
    var linkedType = PropertyTypeInternal.getTypeByClass(componentType);

    if (linkedType == null) {
      throw new IllegalArgumentException("Unsupported type: " + componentType);
    }

    var value = (EmbeddedList<T>) PropertyTypeInternal.EMBEDDEDLIST.copy(source, session);
    setProperty(name, value, PropertyType.EMBEDDEDLIST, linkedType.getPublicPropertyType());
    return value;
  }


  @Override
  @Nonnull
  public EmbeddedList<Byte> newEmbeddedList(@Nonnull String name, byte[] source) {
    var value = new EntityEmbeddedListImpl<Byte>(source.length);
    for (var b : source) {
      value.add(b);
    }
    setProperty(name, value, PropertyType.EMBEDDEDLIST, PropertyType.BYTE);
    return value;
  }

  @Override
  @Nonnull
  public EmbeddedList<Short> newEmbeddedList(@Nonnull String name, short[] source) {
    var value = new EntityEmbeddedListImpl<Short>(source.length);
    for (var s : source) {
      value.add(s);
    }

    setProperty(name, value, PropertyType.EMBEDDEDLIST, PropertyType.SHORT);
    return value;
  }

  @Override
  @Nonnull
  public EmbeddedList<Integer> newEmbeddedList(@Nonnull String name, int[] source) {
    var value = new EntityEmbeddedListImpl<Integer>(source.length);
    for (var i : source) {
      value.add(i);
    }

    setProperty(name, value, PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);
    return value;
  }

  @Override
  @Nonnull
  public EmbeddedList<Long> newEmbeddedList(@Nonnull String name, long[] source) {
    var value = new EntityEmbeddedListImpl<Long>(source.length);
    for (var l : source) {
      value.add(l);
    }
    setProperty(name, value, PropertyType.EMBEDDEDLIST, PropertyType.LONG);
    return value;
  }

  @Override
  @Nonnull
  public EmbeddedList<Boolean> newEmbeddedList(@Nonnull String name, boolean[] source) {
    var value = new EntityEmbeddedListImpl<Boolean>(source.length);
    for (var b : source) {
      value.add(b);
    }
    setProperty(name, value, PropertyType.EMBEDDEDLIST, PropertyType.BOOLEAN);
    return value;
  }

  @Override
  @Nonnull
  public EmbeddedList<Float> newEmbeddedList(@Nonnull String name, float[] source) {
    var value = new EntityEmbeddedListImpl<Float>(source.length);
    for (var f : source) {
      value.add(f);
    }
    setProperty(name, value, PropertyType.EMBEDDEDLIST, PropertyType.FLOAT);
    return value;
  }

  @Override
  @Nonnull
  public EmbeddedList<Double> newEmbeddedList(@Nonnull String name, double[] source) {
    var value = new EntityEmbeddedListImpl<Double>(source.length);
    for (var d : source) {
      value.add(d);
    }
    setProperty(name, value, PropertyType.EMBEDDEDLIST, PropertyType.DOUBLE);
    return value;
  }


  @Override
  public @Nonnull <T> EmbeddedSet<T> getOrCreateEmbeddedSet(@Nonnull String name) {
    var value = this.<EmbeddedSet<T>>getProperty(name);
    if (value == null) {
      value = new EntityEmbeddedSetImpl<>(this);
      return (EmbeddedSet<T>) setProperty(name, value, PropertyType.EMBEDDEDSET);
    }

    return value;
  }

  @Override
  @Nonnull
  public <T> EmbeddedSet<T> getOrCreateEmbeddedSet(@Nonnull String name,
      @Nonnull PropertyType linkedType) {
    var value = this.<EmbeddedSet<T>>getProperty(name);
    if (value == null) {
      value = new EntityEmbeddedSetImpl<>(this);
      setProperty(name, value, PropertyType.EMBEDDEDSET, linkedType);
    } else {
      var linkedTypeProperty = PropertyTypeInternal.getTypeByValue(value);
      if (linkedTypeProperty.getPublicPropertyType() != linkedType) {
        throw new IllegalArgumentException(
            "Property " + name + " is not of type " + linkedType + ", but " + linkedTypeProperty);
      }
    }

    return value;
  }

  @Override
  @Nonnull
  public <T> EmbeddedSet<T> newEmbeddedSet(@Nonnull String name) {
    var value = new EntityEmbeddedSetImpl<T>(this);
    return (EmbeddedSet<T>) setProperty(name, value, PropertyType.EMBEDDEDSET);
  }

  @Override
  @Nonnull
  public <T> EmbeddedSet<T> newEmbeddedSet(@Nonnull String name, @Nonnull PropertyType linkedType) {
    var value = new EntityEmbeddedSetImpl<T>(this);
    setProperty(name, value, PropertyType.EMBEDDEDSET, linkedType);
    return value;
  }

  @Override
  @Nonnull
  public <T> EmbeddedSet<T> newEmbeddedSet(@Nonnull String name, @Nonnull Collection<T> source) {
    var value = (EmbeddedSet<T>) PropertyTypeInternal.EMBEDDEDSET.copy(source, session);
    return (EmbeddedSet<T>) setProperty(name, value, PropertyType.EMBEDDEDSET);
  }

  @Override
  @Nonnull
  public <T> EmbeddedSet<T> newEmbeddedSet(@Nonnull String name, Collection<T> source,
      @Nonnull PropertyType linkedType) {
    var value = (EmbeddedSet<T>) PropertyTypeInternal.EMBEDDEDSET.copy(source, session);
    setProperty(name, value, PropertyType.EMBEDDEDSET, linkedType);
    return value;
  }


  @Override
  public @Nonnull <T> Map<String, T> getOrCreateEmbeddedMap(@Nonnull String name) {
    var value = this.<Map<String, T>>getProperty(name);
    if (value == null) {
      value = new EntityEmbeddedMapImpl<>(this);
      return (Map<String, T>) setProperty(name, value, PropertyType.EMBEDDEDMAP);
    }

    return value;
  }

  @Override
  @Nonnull
  public <T> Map<String, T> getOrCreateEmbeddedMap(@Nonnull String name,
      @Nonnull PropertyType linkedType) {
    var value = this.<Map<String, T>>getProperty(name);
    if (value == null) {
      value = new EntityEmbeddedMapImpl<>(this);
      setProperty(name, value, PropertyType.EMBEDDEDMAP, linkedType);
    } else {
      var linkedTypeProperty = PropertyTypeInternal.getTypeByValue(value);
      if (linkedTypeProperty.getPublicPropertyType() != linkedType) {
        throw new IllegalArgumentException(
            "Property " + name + " is not of type " + linkedType + ", but " + linkedTypeProperty);
      }
    }

    return value;
  }

  @Override
  @Nonnull
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name) {
    var value = new EntityEmbeddedMapImpl<T>(this);
    return (Map<String, T>) setProperty(name, value, PropertyType.EMBEDDEDMAP);
  }


  @Override
  public @Nonnull <T> Map<String, T> newEmbeddedMap(@Nonnull String name,
      @Nonnull PropertyType linkedType) {
    var value = new EntityEmbeddedMapImpl<T>(this);
    setProperty(name, value, PropertyType.EMBEDDEDMAP, linkedType);
    return value;
  }

  @Override
  @Nonnull
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name, Map<String, T> source) {
    var value = (EmbeddedMap<T>) PropertyTypeInternal.EMBEDDEDMAP.copy(source, session);
    return (EmbeddedMap<T>) setProperty(name, value, PropertyType.EMBEDDEDMAP);
  }

  @Override
  @Nonnull
  public <T> Map<String, T> newEmbeddedMap(@Nonnull String name, Map<String, T> source,
      @Nonnull PropertyType linkedType) {
    var value = (EmbeddedMap<T>) PropertyTypeInternal.EMBEDDEDMAP.copy(source, session);
    setProperty(name, value, PropertyType.EMBEDDEDMAP, linkedType);
    return value;
  }


  @Override
  public @Nonnull LinkList getOrCreateLinkList(@Nonnull String name) {
    var value = this.<LinkList>getProperty(name);
    if (value == null) {
      value = new EntityLinkListImpl(this);
      return (LinkList) setProperty(name, value, PropertyType.LINKLIST);
    }

    return value;
  }

  @Override
  @Nonnull
  public LinkList newLinkList(@Nonnull String name) {
    var value = new EntityLinkListImpl(this);
    return (LinkList) setProperty(name, value, PropertyType.LINKLIST);
  }

  @Override
  @Nonnull
  public LinkList newLinkList(@Nonnull String name, Collection<? extends Identifiable> source) {
    var value = new EntityLinkListImpl(this);
    value.addAll(source);
    return (LinkList) setProperty(name, value, PropertyType.LINKLIST);
  }

  @Override
  @Nonnull
  public LinkSet getOrCreateLinkSet(@Nonnull String name) {
    var value = this.<EntityLinkSetImpl>getProperty(name);
    if (value == null) {
      value = new EntityLinkSetImpl(this);
      return (LinkSet) setProperty(name, value, PropertyType.LINKSET);
    }

    return value;
  }

  @Override
  @Nonnull
  public LinkSet newLinkSet(@Nonnull String name) {
    var value = new EntityLinkSetImpl(this);
    return (LinkSet) setProperty(name, value, PropertyType.LINKSET);
  }

  @Override
  @Nonnull
  public LinkSet newLinkSet(@Nonnull String name, Collection<? extends Identifiable> source) {
    var value = new EntityLinkSetImpl(this);
    value.addAll(source);
    return (LinkSet) setProperty(name, value, PropertyType.LINKSET);
  }

  @Override
  @Nonnull
  public LinkMap getOrCreateLinkMap(@Nonnull String name) {
    var value = this.<LinkMap>getProperty(name);
    if (value == null) {
      value = new EntityLinkMapIml(this);
      return (LinkMap) setProperty(name, value, PropertyType.LINKMAP);
    }

    return value;
  }

  @Override
  @Nonnull
  public LinkMap newLinkMap(@Nonnull String name) {
    var value = new EntityLinkMapIml(this);
    return (LinkMap) setProperty(name, value, PropertyType.LINKMAP);
  }

  @Override
  @Nonnull
  public LinkMap newLinkMap(@Nonnull String name,
      Map<String, ? extends Identifiable> source) {
    var value = new EntityLinkMapIml(this);
    value.putAll(source);
    return (LinkMap) setProperty(name, value, PropertyType.LINKMAP);
  }

  protected void validatePropertyName(String propertyName, boolean allowMetadata) {
    final var c = SchemaShared.checkPropertyNameIfValid(propertyName);
    if (allowMetadata && propertyName.charAt(0) == '@') {
      return;
    }

    if (c != null) {
      throw new IllegalArgumentException(
          "Invalid property name '" + propertyName);
    }

    var firstChar = propertyName.charAt(0);
    if (firstChar != '_' && !Character.isLetter(firstChar)) {
      throw new DatabaseException(
          "Property name has to start with a letter or underscore, provided " + propertyName);
    }
  }

  /**
   * Moves property values from one entity to another. Only properties with different values are
   * marked as dirty in result of such change. This rule is applied for all properties except of
   * <code>RidBag</code>. Only embedded <code>RidBag</code>s are compared but tree based are
   * always assigned to avoid performance overhead.
   *
   * @param from    Entity from which properties are moved.
   * @param exclude Field names to exclude from move.
   */
  public void movePropertiesFromOtherEntity(@Nonnull EntityImpl from, String... exclude) {
    checkForProperties();
    from.checkForProperties();

    if (from.properties.isEmpty()) {
      return;
    }

    var fromFields = new HashMap<>(from.properties);
    var sameCollection = from.recordId.getCollectionId() == recordId.getCollectionId();
    var excludeSet = new HashSet<String>();

    if (exclude.length > 0) {
      Collections.addAll(excludeSet, exclude);
    }

    for (var mapEntry : fromFields.entrySet()) {
      if (mapEntry.getValue().exists()) {
        var propertyName = mapEntry.getKey();
        if (excludeSet.contains(propertyName)) {
          continue;
        }

        var fromEntry = mapEntry.getValue();
        var currentEntry = properties.get(mapEntry.getKey());
        var currentValue = currentEntry != null ? currentEntry.value : null;
        var fromValue = fromEntry.value;

        var fromType = fromEntry.type;

        from.removePropertyInternal(mapEntry.getKey());

        if (fromValue != null && currentValue == null) {
          setPropertyInternal(propertyName,
              copyRidBagIfNecessary(session, fromValue, sameCollection), fromType);
        } else if (fromValue == null && currentValue != null) {
          setPropertyInternal(propertyName, null, currentEntry.type);
        } else if (fromValue.getClass() != currentValue.getClass()) {
          setPropertyInternal(propertyName,
              copyRidBagIfNecessary(session, fromValue, sameCollection),
              fromType);
        } else {
          if (!(currentValue instanceof LinkBag linkBag)) {
            if (!Objects.equals(fromType, currentEntry.type)) {
              setPropertyInternal(propertyName, fromValue, fromType);
            }
          } else {
            if (linkBag.isEmbedded() || ((LinkBag) fromValue).isEmbedded()) {
              if (!Objects.equals(fromType, currentEntry.type)) {
                setPropertyInternal(propertyName,
                    copyRidBagIfNecessary(session,
                        copyRidBagIfNecessary(session, fromValue, sameCollection), sameCollection),
                    fromType);
              }
            } else {
              setPropertyInternal(propertyName,
                  copyRidBagIfNecessary(session, fromValue, sameCollection), fromType);
            }
          }
        }
      }
    }
  }

  /**
   * All tree based ridbags are partitioned by collections, so if we move entity to another
   * collection we need to copy ridbags to avoid inconsistency.
   */
  private static Object copyRidBagIfNecessary(DatabaseSessionInternal seession, Object value,
      boolean sameCollection) {
    if (sameCollection) {
      return value;
    }

    if (!(value instanceof LinkBag linkBag)) {
      return value;
    }

    if (linkBag.isEmbedded()) {
      return linkBag;
    }

    var ridBagCopy = new LinkBag(seession);
    for (var rid : linkBag) {
      ridBagCopy.add(rid);
    }

    return ridBagCopy;
  }

  /**
   * Sets a property value
   *
   * @param propertyName  The property name
   * @param propertyValue The property value
   */
  @Override
  public void setProperty(final @Nonnull String propertyName, @Nullable Object propertyValue) {
    setPropertyInternal(propertyName, propertyValue, null, null, true);
  }

  /**
   * Sets a property value
   *
   * @param propertyName  The property name
   * @param propertyValue The property value
   * @param type          Forced type (not auto-determined)
   */
  @Override
  public Object setProperty(@Nonnull String propertyName, Object propertyValue,
      @Nonnull PropertyType type) {
    return setPropertyInternal(
        propertyName, propertyValue,
        PropertyTypeInternal.convertFromPublicType(type), null,
        true);
  }


  @Override
  public void setProperty(@Nonnull String propertyName, @Nullable Object propertyValue,
      @Nonnull PropertyType propertyType, @Nonnull PropertyType linkedType) {

    setPropertyInternal(
        propertyName, propertyValue,
        PropertyTypeInternal.convertFromPublicType(propertyType),
        PropertyTypeInternal.convertFromPublicType(linkedType),
        true);
  }

  public void compareAndSetPropertyInternal(String name, Object value, PropertyTypeInternal type) {
    checkForBinding();

    var oldValue = getPropertyInternal(name);
    if (!Objects.equals(oldValue, value)) {
      setPropertyInternal(name, value, type);
    }
  }

  public void setPropertyInternal(String name, Object value) {
    setPropertyInternal(name, value, null);
  }


  public void setPropertyInternal(
      String name, Object value,
      @Nullable PropertyTypeInternal type
  ) {
    setPropertyInternal(name, value, type, null, false);
  }

  public Object setPropertyInternal(
      String name, Object value,
      @Nullable PropertyTypeInternal type,
      @Nullable PropertyTypeInternal linkedType,
      boolean validate
  ) {

    if (name == null) {
      throw new IllegalArgumentException("Field is null");
    }

    if (name.isEmpty()) {
      throw new IllegalArgumentException("Field name is empty");
    }

    if (validate) {
      validatePropertyUpdate(name, value);
    }

    checkForBinding();

    if (type == null && value instanceof EntityImpl entity && entity.isEmbedded()) {
      type = PropertyTypeInternal.EMBEDDED;
    }

    if (value instanceof RecordAbstract recordAbstract) {
      recordAbstract.checkForBinding();

      if (recordAbstract.getSession() != session) {
        throw new DatabaseException(getSession().getDatabaseName(),
            "Entity instance is bound to another session instance");
      }
    }

    final var begin = name.charAt(0);
    if (begin == '@') {
      switch (name.toLowerCase(Locale.ROOT)) {
        case EntityHelper.ATTRIBUTE_RID -> {
          if (status == STATUS.UNMARSHALLING) {
            recordId.fromString(value.toString());
          } else {
            throw new DatabaseException(getSession().getDatabaseName(),
                "Attribute " + EntityHelper.ATTRIBUTE_RID + " is read-only");
          }
        }
        case EntityHelper.ATTRIBUTE_VERSION -> {
          if (status == STATUS.UNMARSHALLING) {
            setVersion(Integer.parseInt(value.toString()));
          }
          throw new DatabaseException(getSession().getDatabaseName(),
              "Attribute " + EntityHelper.ATTRIBUTE_VERSION + " is read-only");
        }
        default -> {
          throw new DatabaseException(session.getDatabaseName(),
              "Attribute " + name + " can not be set");
        }
      }
    }

    checkForProperties();

    var entry = properties.get(name);
    final boolean knownProperty;
    final Object oldValue;
    final PropertyTypeInternal oldType;

    if (entry == null) {
      entry = new EntityEntry();

      propertiesCount++;
      properties.put(name, entry);

      entry.markCreated();

      knownProperty = false;
      oldValue = null;
      oldType = null;
    } else {
      knownProperty = entry.exists();
      oldValue = entry.value;
      oldType = entry.type;
    }

    if (value instanceof Enum) {
      value = value.toString();
    }

    var propertyType = derivePropertyType(name, type, value);
    value = convertField(session, this, name, propertyType, linkedType, value);

    if (knownProperty) {
      try {
        if (propertyType == oldType) {
          if (value instanceof byte[]
              && Arrays.equals((byte[]) value, (byte[]) oldValue)) {
            return value;
          }
          if (PropertyTypeInternal.isSingleValueType(value) && Objects.equals(oldValue, value)) {
            return value;
          }
        }
      } catch (Exception e) {
        LogManager.instance()
            .warn(
                this,
                "Error on checking the value of property %s against the record %s",
                e,
                name,
                getIdentity());
      }
    }

    preprocessRemovedValue(oldValue);
    preprocessAssignedValue(name, value, propertyType);

    if (oldType != propertyType) {
      entry.type = propertyType;
    }

    entry.disableTracking(this, oldValue);
    entry.value = value;

    if (!entry.exists()) {
      entry.setExists(true);
      propertiesCount++;
    }

    entry.enableTracking(this);

    if (!entry.isChanged()) {
      entry.original = oldValue;
      entry.markChanged();
    }

    setDirty();
    return value;
  }

  private void preprocessRemovedValue(Object oldValue) {
    switch (oldValue) {
      case LinkBag linkBag -> linkBag.setOwner(null);
      case EntityImpl entity -> entity.removeOwner(this);
      case RecordElement recordElement -> {
        if (!(oldValue instanceof Blob)) {
          recordElement.setOwner(null);
        }
      }
      case null, default -> {
      }
    }
  }


  public void setDeserializedPropertyInternal(String name, Object value,
      PropertyTypeInternal propertyType) {
    if (this.properties == null) {
      this.properties = new HashMap<>();
    }

    var entry = new EntityEntry();
    propertiesCount++;
    properties.put(name, entry);

    value = preprocessAssignedValue(name, value, propertyType);

    if (propertyType == null) {
      assert value == null;
      propertyType = derivePropertyType(name, null, value);
    }

    entry.type = propertyType;
    entry.value = value;

    entry.enableTracking(this);
  }

  @Nullable
  private Object preprocessAssignedValue(String name, Object value,
      PropertyTypeInternal propertyType) {
    switch (value) {
      case EntityImpl entity -> {
        if (propertyType == PropertyTypeInternal.EMBEDDED) {
          entity.setOwner(this);
        }
      }
      case StorageBackedMultiValue storageBackedMultiValue -> {
        storageBackedMultiValue.setOwner(this);
        storageBackedMultiValue.setOwnerFieldName(name);
      }
      case RecordElement element -> {
        if (!(element instanceof Blob)) {
          element.setOwner(this);
        }
      }
      case RID rid -> {
        value = session.refreshRid(rid);
      }
      case null, default -> {
      }
    }

    return value;
  }

  @Override
  public <RET> RET removeProperty(@Nonnull final String iFieldName) {
    validatePropertyName(iFieldName, false);
    return removePropertyInternal(iFieldName);
  }


  @Nullable
  public <RET> RET removePropertyInternal(String name) {
    checkForBinding();
    checkForProperties();

    if (EntityHelper.ATTRIBUTE_RID.equalsIgnoreCase(name)) {
      throw new DatabaseException(session.getDatabaseName(),
          "Attribute " + EntityHelper.ATTRIBUTE_RID + " is read-only");
    } else if (EntityHelper.ATTRIBUTE_VERSION.equalsIgnoreCase(name)) {
      if (EntityHelper.ATTRIBUTE_VERSION.equalsIgnoreCase(name)) {
        throw new DatabaseException(session.getDatabaseName(),
            "Attribute " + EntityHelper.ATTRIBUTE_VERSION + " is read-only");
      }
    } else if (EntityHelper.ATTRIBUTE_CLASS.equalsIgnoreCase(name)) {
      throw new DatabaseException(session.getDatabaseName(),
          "Attribute " + EntityHelper.ATTRIBUTE_CLASS + " is read-only");
    }

    final var entry = properties.get(name);
    if (entry == null) {
      return null;
    }

    var oldValue = entry.value;

    if (entry.exists()) {
      // SAVE THE OLD VALUE IN A SEPARATE MAP
      if (entry.original == null) {
        entry.original = entry.value;
      }
      entry.value = null;
      entry.setExists(false);
      entry.markChanged();
    } else {
      properties.remove(name);
    }

    propertiesCount--;
    entry.disableTracking(this, oldValue);

    preprocessRemovedValue(oldValue);

    setDirty();

    return (RET) oldValue;
  }

  private static void validatePropertiesSecurity(@Nonnull DatabaseSessionInternal session,
      EntityImpl iRecord)
      throws ValidationException {
    iRecord.checkForBinding();

    var security = session.getSharedContext().getSecurity();

    for (var mapEntry : iRecord.properties.entrySet()) {
      var entry = mapEntry.getValue();
      if (entry != null && (entry.isTxChanged() || entry.isTxTrackedModified())) {
        if (!security.isAllowedWrite(session, iRecord, mapEntry.getKey())) {
          throw new SecurityException(session.getDatabaseName(),
              String.format(
                  "Change of property '%s' is not allowed for user '%s'",
                  iRecord.getSchemaClassName() + "." + mapEntry.getKey(),
                  session.getCurrentUser().getName(session)));
        }
      }
    }
  }

  private static void validateProperty(
      DatabaseSessionInternal session, ImmutableSchema schema, EntityImpl iRecord,
      ImmutableSchemaProperty p)
      throws ValidationException {
    iRecord.checkForBinding();

    final Object propertyValue;
    var entry = iRecord.properties.get(p.getName());
    if (entry != null && entry.exists()) {
      // AVOID CONVERSIONS: FASTER!
      propertyValue = entry.value;

      if (p.isNotNull() && propertyValue == null)
      // NULLITY
      {
        throw new ValidationException(session.getDatabaseName(),
            "The property '" + p.getFullName() + "' cannot be null, record: " + iRecord);
      }

      if (propertyValue != null && p.getRegexp() != null && p.getType() == PropertyType.STRING) {
        // REGEXP
        if (!((String) propertyValue).matches(p.getRegexp())) {
          throw new ValidationException(session.getDatabaseName(),
              "The property '"
                  + p.getFullName()
                  + "' does not match the regular expression '"
                  + p.getRegexp()
                  + "'. Field value is: "
                  + propertyValue
                  + ", record: "
                  + iRecord);
        }
      }

    } else {
      if (p.isMandatory()) {
        throw new ValidationException(session.getDatabaseName(),
            "The property '"
                + p.getFullName()
                + "' is mandatory, but not found on record: "
                + iRecord);
      }
      propertyValue = null;
    }

    final var type = p.getType();

    if (propertyValue != null && type != null) {
      // CHECK TYPE
      switch (type) {
        case LINK:
          validateLink(schema, session, p, propertyValue, false);
          break;
        case LINKLIST:
          if (!(propertyValue instanceof EntityLinkListImpl)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as LINKLIST but an incompatible type is used. Value: "
                    + propertyValue);
          }
          validateLinkCollection(session, schema, p, (Collection<Object>) propertyValue, entry);
          break;
        case LINKSET:
          if (!(propertyValue instanceof EntityLinkSetImpl)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as LINKSET but an incompatible type is used. Value: "
                    + propertyValue);
          }
          validateLinkCollection(session, schema, p, (Collection<Object>) propertyValue, entry);
          break;
        case LINKMAP:
          if (!(propertyValue instanceof EntityLinkMapIml)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as LINKMAP but an incompatible type is used. Value: "
                    + propertyValue);
          }
          validateLinkCollection(session, schema, p, ((Map<?, Object>) propertyValue).values(),
              entry);
          break;

        case LINKBAG:
          if (!(propertyValue instanceof LinkBag)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as LINKBAG but an incompatible type is used. Value: "
                    + propertyValue);
          }
          validateLinkCollection(session, schema, p, (Iterable<Object>) propertyValue, entry);
          break;
        case EMBEDDED:
          validateEmbedded(session, p, propertyValue);
          break;
        case EMBEDDEDLIST:
          if (!(propertyValue instanceof EntityEmbeddedListImpl<?>)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as EMBEDDEDLIST but an incompatible type is used. Value:"
                    + " "
                    + propertyValue);
          }
          if (p.getLinkedClass() != null) {
            for (var item : ((List<?>) propertyValue)) {
              validateEmbedded(session, p, item);
            }
          } else {
            if (p.getLinkedType() != null) {
              for (var item : ((List<?>) propertyValue)) {
                validateType(session, p, item);
              }
            }
          }
          break;
        case EMBEDDEDSET:
          if (!(propertyValue instanceof EntityEmbeddedSetImpl<?>)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as EMBEDDEDSET but an incompatible type is used. Value: "
                    + propertyValue);
          }
          if (p.getLinkedClass() != null) {
            for (var item : ((Set<?>) propertyValue)) {
              validateEmbedded(session, p, item);
            }
          } else {
            if (p.getLinkedType() != null) {
              for (var item : ((Set<?>) propertyValue)) {
                validateType(session, p, item);
              }
            }
          }
          break;
        case EMBEDDEDMAP:
          if (!(propertyValue instanceof EntityEmbeddedMapImpl<?>)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as EMBEDDEDMAP but an incompatible type is used. Value: "
                    + propertyValue);
          }
          if (p.getLinkedClass() != null) {
            for (var colleEntry : ((Map<?, ?>) propertyValue).entrySet()) {
              validateEmbedded(session, p, colleEntry.getValue());
            }
          } else {
            if (p.getLinkedType() != null) {
              for (var collEntry : ((Map<?, ?>) propertyValue).entrySet()) {
                validateType(session, p, collEntry.getValue());
              }
            }
          }
          break;
      }
    }

    if (p.getMin() != null && propertyValue != null) {
      // MIN
      final var min = p.getMin();
      if (p.getMinComparable().compareTo(propertyValue) > 0) {
        switch (p.getType()) {
          case STRING:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains fewer characters than "
                    + min
                    + " requested");
          case DATE:
          case DATETIME:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains the date "
                    + propertyValue
                    + " which precedes the first acceptable date ("
                    + min
                    + ")");
          case BINARY:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains fewer bytes than "
                    + min
                    + " requested");
          case EMBEDDEDLIST:
          case EMBEDDEDSET:
          case LINKLIST:
          case LINKSET:
          case EMBEDDEDMAP:
          case LINKMAP:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains fewer items than "
                    + min
                    + " requested");
          default:
            throw new ValidationException(session.getDatabaseName(),
                "The property '" + p.getFullName() + "' is less than " + min);
        }
      }
    }

    if (p.getMaxComparable() != null && propertyValue != null) {
      final var max = p.getMax();
      if (p.getMaxComparable().compareTo(propertyValue) < 0) {
        switch (p.getType()) {
          case STRING:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains more characters than "
                    + max
                    + " requested");
          case DATE:
          case DATETIME:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains the date "
                    + propertyValue
                    + " which is after the last acceptable date ("
                    + max
                    + ")");
          case BINARY:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains more bytes than "
                    + max
                    + " requested");
          case EMBEDDEDLIST:
          case EMBEDDEDSET:
          case LINKLIST:
          case LINKSET:
          case EMBEDDEDMAP:
          case LINKMAP:
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' contains more items than "
                    + max
                    + " requested");
          default:
            throw new ValidationException(session.getDatabaseName(),
                "The property '" + p.getFullName() + "' is greater than " + max);
        }
      }
    }

    if (p.isReadonly()) {
      if (entry != null
          && (entry.isTxChanged() || entry.isTxTrackedModified())
          && !entry.isTxCreated()) {
        // check if the property is actually changed by equal.
        // this is due to a limitation in the merge algorithm used server side marking all
        // non-simple properties as dirty
        var orgVal = entry.getOnLoadValue(session);
        var simple =
            propertyValue != null ? PropertyTypeInternal.isSimpleValueType(propertyValue)
                : PropertyTypeInternal.isSimpleValueType(orgVal);
        if (simple || propertyValue != null && orgVal == null || propertyValue == null
            || !Objects.deepEquals(propertyValue, orgVal)) {
          throw new ValidationException(session.getDatabaseName(),
              "The property '"
                  + p.getFullName()
                  + "' is immutable and cannot be altered. Field value is: "
                  + entry.value);
        }
      }
    }
  }

  private static void validateLinkCollection(
      DatabaseSessionInternal session, ImmutableSchema schema,
      final SchemaProperty property,
      Iterable<Object> values,
      EntityEntry value) {
    if (property.getLinkedClass() != null) {
      if (value.getTimeLine() != null) {
        var event =
            value.getTimeLine().getMultiValueChangeEvents();
        for (var object : event) {
          if (object.getChangeType() == ChangeType.ADD
              || object.getChangeType() == ChangeType.UPDATE
              && object.getValue() != null) {
            validateLink(schema, session, property, object.getValue(), true);
          }
        }
      } else {
        for (var object : values) {
          validateLink(schema, session, property, object, true);
        }
      }
    }
  }

  private static void validateType(DatabaseSessionInternal session, final SchemaProperty p,
      final Object value) {
    if (value != null) {
      try {
        if (PropertyTypeInternal.convertFromPublicType(p.getLinkedType())
            .convert(value, PropertyTypeInternal.convertFromPublicType(p.getLinkedType()),
                p.getLinkedClass(), session)
            == null) {
          throw new ValidationException(session.getDatabaseName(),
              "The property '"
                  + p.getFullName()
                  + "' has been declared as "
                  + p.getType()
                  + " of type '"
                  + p.getLinkedType()
                  + "' but the value is "
                  + value);
        }
      } catch (DatabaseException e) {
        throw BaseException.wrapException(new ValidationException(session.getDatabaseName(),
            "The property '"
                + p.getFullName()
                + "' has been declared as "
                + p.getType()
                + " of type '"
                + p.getLinkedType()
                + "' but the value is "
                + value), e, session);
      }

    }
  }

  private static void validateLink(
      ImmutableSchema schema, @Nonnull DatabaseSessionInternal session, final SchemaProperty p,
      final Object propertyValue, boolean allowNull) {
    if (propertyValue == null) {
      if (allowNull) {
        return;
      } else {
        throw new ValidationException(session.getDatabaseName(),
            "The property '"
                + p.getFullName()
                + "' has been declared as "
                + p.getType()
                + " but contains a null record (probably a deleted record?)");
      }
    }

    if (!(propertyValue instanceof Identifiable)) {
      throw new ValidationException(session.getDatabaseName(),
          "The property '"
              + p.getFullName()
              + "' has been declared as "
              + p.getType()
              + " but the value is not a record or a record-id");
    }

    final var schemaClass = p.getLinkedClass();
    if (schemaClass != null && !schemaClass.isSubClassOf(Identity.CLASS_NAME)) {
      // DON'T VALIDATE OUSER AND OROLE FOR SECURITY RESTRICTIONS
      var identifiable = (Identifiable) propertyValue;
      final var rid = identifiable.getIdentity();
      if (!schemaClass.hasPolymorphicCollectionId(rid.getCollectionId())) {
        // AT THIS POINT CHECK THE CLASS ONLY IF != NULL BECAUSE IN CASE OF GRAPHS THE RECORD
        // COULD BE PARTIAL
        SchemaClass cls;
        var collectionId = rid.getCollectionId();
        if (collectionId != RID.COLLECTION_ID_INVALID) {
          cls = schema.getClassByCollectionId(rid.getCollectionId());
        } else if (identifiable instanceof EntityImpl entity) {
          cls = entity.getImmutableSchemaClass(session);
        } else {
          cls = null;
        }

        if (cls != null && !schemaClass.isSuperClassOf(cls)) {
          throw new ValidationException(session.getDatabaseName(),
              "The property '"
                  + p.getFullName()
                  + "' has been declared as "
                  + p.getType()
                  + " of type '"
                  + schemaClass.getName()
                  + "' but the value is the entity "
                  + rid
                  + " of class '"
                  + cls
                  + "'");
        }
      }
    }
  }

  private static void validateEmbedded(@Nonnull DatabaseSessionInternal session,
      final SchemaProperty p,
      final Object propertyValue) {
    if (propertyValue == null) {
      return;
    }
    if (propertyValue instanceof RecordId) {
      throw new ValidationException(session.getDatabaseName(),
          "The property '"
              + p.getFullName()
              + "' has been declared as "
              + p.getType()
              + " but the value is the RecordID "
              + propertyValue);
    } else {
      if (propertyValue instanceof Identifiable embedded) {
        if (((RecordId) embedded.getIdentity()).isValidPosition()) {
          throw new ValidationException(session.getDatabaseName(),
              "The property '"
                  + p.getFullName()
                  + "' has been declared as "
                  + p.getType()
                  + " but the value is a entity with the valid RecordID "
                  + propertyValue);
        }

        if (embedded instanceof EntityImpl entity) {
          final var embeddedClass = p.getLinkedClass();
          if (entity.isVertex()) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record is of class '"
                    + entity.getImmutableSchemaClass(session).getName()
                    + "' that is vertex class");
          }

          if (entity.isStatefulEdge()) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record is of class '"
                    + entity.getImmutableSchemaClass(session).getName()
                    + "' that is edge class");
          }
        }

        final var embeddedClass = p.getLinkedClass();
        if (embeddedClass != null) {
          if (!(embedded instanceof EntityImpl entity)) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record was not a entity");
          }

          if (entity.getImmutableSchemaClass(session) == null) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record has no class");
          }

          if (!(entity.getImmutableSchemaClass(session).isSubClassOf(embeddedClass))) {
            throw new ValidationException(session.getDatabaseName(),
                "The property '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record is of class '"
                    + entity.getImmutableSchemaClass(session).getName()
                    + "' that is not a subclass of that");
          }

          entity.validate();
        }

      } else {
        throw new ValidationException(session.getDatabaseName(),
            "The property '"
                + p.getFullName()
                + "' has been declared as "
                + p.getType()
                + " but an incompatible type is used. Value: "
                + propertyValue + " . Value class is :" + propertyValue.getClass());
      }
    }
  }

  public boolean hasSameContentOf(final EntityImpl iOther) {
    iOther.checkForBinding();
    checkForBinding();

    return EntityHelper.hasSameContentOf(this, session, iOther, session, null);
  }

  @Override
  public byte[] toStream() {
    checkForBinding();

    var prev = status;
    status = STATUS.MARSHALLING;
    try {
      if (source == null) {
        source = recordSerializer.toStream(session, this);
      }
    } finally {
      status = prev;
    }

    return source;
  }

  public void copyProperties(EntityImpl entity) {
    for (var propertyName : entity.getPropertyNames()) {
      var propertyValue = entity.getProperty(propertyName);

      if (propertyValue == null) {
        setProperty(propertyName, null);
      } else {
        var type = PropertyTypeInternal.convertFromPublicType(entity.getPropertyType(propertyName));
        setProperty(propertyName, type.copy(propertyValue, session));
      }
    }
  }

  /**
   * Returns the entity as Map String,Object . If the entity has identity, then the @rid entry is
   * valued. If the entity has a class, then the @class entry is valued.
   *
   * @since 2.0
   */
  @Override
  public @Nonnull Map<String, Object> toMap() {
    return toMap(true);
  }

  @Override
  @Nonnull
  public Map<String, Object> toMap(boolean includeMetadata) {
    checkForBinding();
    checkForProperties();

    final Map<String, Object> map = new LinkedHashMap<>();
    if (includeMetadata) {
      if (isEmbedded()) {
        map.put(EntityHelper.ATTRIBUTE_EMBEDDED, true);
      } else {
        if (recordId.isValidPosition()) {
          map.put(EntityHelper.ATTRIBUTE_RID, recordId.copy());
        }
      }

      if (className != null) {
        map.put(EntityHelper.ATTRIBUTE_CLASS, className);
      }

      if (!isEmbedded()) {
        if (isDirty()) {
          map.put(EntityHelper.ATTRIBUTE_VERSION, getVersion() + 1);
        } else {
          map.put(EntityHelper.ATTRIBUTE_VERSION, getVersion());
        }
      }
    }

    for (var entry : properties.entrySet()) {
      var propertyName = entry.getKey();
      var value = entry.getValue().value;

      if (propertyAccess == null || propertyAccess.isReadable(propertyName)) {
        if (!isSystemProperty(propertyName)) {
          map.put(propertyName, ResultInternal.toMapValue(value, true));
        }
      }
    }

    return map;
  }

  /**
   * Dumps the instance as string.
   */
  @Override
  public String toString() {
    if (isUnloaded()) {
      return "Unloaded record {" + getIdentity() + ", v" + getVersion() + "}";
    }

    return toString(new HashSet<>());
  }

  /**
   * Returns the set of property names.
   */
  public String[] propertyNames() {
    return getPropertyNames().toArray(new String[0]);
  }

  /**
   * Returns the array of property values.
   */
  public Object[] propertyValues() {
    var propertyNames = calculatePropertyNames(false, true);

    if (propertyNames != null) {
      var values = new Object[propertyNames.size()];

      var index = 0;
      for (var name : propertyNames) {
        values[index] = getProperty(name);
        index++;
      }
      return values;
    }

    return new Object[0];
  }

  public EntityImpl setPropertyInChain(final String iFieldName, Object iPropertyValue) {
    setProperty(iFieldName, iPropertyValue);
    return this;
  }

  /**
   * Fills a entity passing the property names/values.
   */
  public EntityImpl properties(
      final String propertyName, final Object propertyValue, final Object... properties) {
    checkForBinding();

    if (properties != null && properties.length % 2 != 0) {
      throw new IllegalArgumentException("Fields must be passed in pairs as name and value");
    }

    setProperty(propertyName, propertyValue);
    if (properties != null && properties.length > 0) {
      for (var i = 0; i < properties.length; i += 2) {
        final var iFieldName1 = properties[i].toString();
        setProperty(iFieldName1, properties[i + 1]);
      }
    }
    return this;
  }

  @Override
  public void updateFromResult(@Nonnull Result result) {
    checkForBinding();

    var cls = getImmutableSchemaClass(this.session);
    Map<String, String> propertyTypes = null;

    if (result instanceof ResultInternal resultInternal) {
      propertyTypes = (Map<String, String>) resultInternal.getMetadata(RESULT_PROPERTY_TYPES);
    }
    if (propertyTypes == null) {
      propertyTypes = Collections.emptyMap();
    }

    for (var propertyName : result.getPropertyNames()) {
      var value = result.getProperty(propertyName);
      if (propertyName.charAt(0) == '@') {
        switch (propertyName) {
          case EntityHelper.ATTRIBUTE_CLASS -> {
            if (!Objects.equals(getSchemaClassName(), value)) {
              throw new IllegalArgumentException("Invalid  entity class name provided: "
                  + value + " expected: " + getSchemaClassName());
            }
          }
          case EntityHelper.ATTRIBUTE_RID -> {
            if (value instanceof RecordId rid) {
              if (!rid.equals(recordId)) {
                throw new IllegalArgumentException("Invalid  entity record id provided: "
                    + rid + " expected: " + recordId);
              }
            } else {
              throw new IllegalArgumentException("Invalid  entity record id provided: "
                  + value + " expected: " + recordId);
            }
          }
          case EntityHelper.ATTRIBUTE_EMBEDDED -> {
            if (Boolean.parseBoolean(value.toString()) != isEmbedded()) {
              throw new IllegalArgumentException("Invalid  entity embedded flag provided: "
                  + value + " expected: " + isEmbedded());
            }
          }
          case EntityHelper.ATTRIBUTE_VERSION -> {
            //skip it
          }
          default -> {
            throw new IllegalArgumentException(
                "Invalid  entity attribute provided: " + propertyName);
          }
        }
      }
      if (isSystemProperty(propertyName)) {
        throw new IllegalArgumentException(
            "System properties can not be updated from result : " + propertyName);
      }

      var property = cls != null ? cls.getProperty(propertyName) : null;
      var type = property != null ? property.getType() : null;

      if (type == null) {
        var typeName = propertyTypes.get(propertyName);

        if (typeName != null) {
          type = PropertyTypeInternal.valueOf(typeName).getPublicPropertyType();
        }
      }

      switch (type) {
        case LINKLIST: {
          updateLinkListFromMapValue(value, propertyName);
          break;
        }
        case LINKSET: {
          updateLinkSetFromMapValue(value, propertyName);
          break;
        }
        case LINKBAG: {
          updateLinkBagFromMapValue(value, session, propertyName);
          break;
        }
        case LINKMAP: {
          updateLinkMapFromMapValue(value, propertyName);
          break;
        }
        case EMBEDDEDLIST: {
          updateEmbeddedListFromMapValue(session, value, propertyName);
          break;
        }
        case EMBEDDEDSET: {
          updateEmbeddedSetFromMapValue(session, value, propertyName);
          break;
        }
        case EMBEDDEDMAP: {
          updateEmbeddedMapFromMapValue(session, value, propertyName);
          break;
        }
        case EMBEDDED: {
          updateEmbeddedFromMapValue(value, session, propertyName);
          break;
        }
        case null: {
          updatePropertyFromNonTypedMapValue(value, session, propertyName);
          break;
        }
        default: {
          setPropertyInternal(propertyName, value);
        }
      }
    }
  }

  /**
   * Fills a entity passing the property names/values as a Map String,Object where the keys are the
   * property names and the values are the property values. It accepts also @rid for record id and
   */
  @Override
  public void updateFromMap(@Nonnull final Map<String, ?> map) {
    checkForBinding();

    var cls = getImmutableSchemaClass(this.session);
    for (var entry : map.entrySet()) {
      var key = entry.getKey();
      if (key.isEmpty()) {
        continue;
      }

      if (key.equals(EntityHelper.ATTRIBUTE_CLASS)) {
        var className = (String) entry.getValue();

        if (className == null) {
          throw new IllegalArgumentException("Invalid  entity class name provided: " + className);
        }

        if (!Objects.equals(getSchemaClassName(), className)) {
          var immutableSchemaClass = getImmutableSchemaClass(session);
          var providedClass = schema.getClass(className);

          if (!providedClass.equals(immutableSchemaClass)) {
            throw new IllegalArgumentException("Invalid  entity class name provided: "
                + className + " expected: " + getSchemaClassName());
          }
        }
      }

      if (key.equals(EntityHelper.ATTRIBUTE_RID)) {
        var ridValue = entry.getValue();
        RecordId rid;

        if (ridValue instanceof RecordId ridVal) {
          rid = ridVal;
        } else if (ridValue instanceof String ridString) {
          rid = new RecordId(ridString);
        } else {
          throw new IllegalArgumentException("Invalid  entity record id provided: " + ridValue);
        }

        if (!rid.equals(recordId)) {
          throw new IllegalArgumentException("Invalid  entity record id provided: "
              + rid + " expected: " + recordId);
        }
      }

      if (key.equals(EntityHelper.ATTRIBUTE_EMBEDDED)) {
        var embedded = (Boolean) entry.getValue();

        if (embedded == null) {
          throw new IllegalArgumentException("Invalid  entity embedded flag provided: " + embedded);
        }

        if (embedded != isEmbedded()) {
          throw new IllegalArgumentException("Invalid  entity embedded flag provided: "
              + embedded + " expected: " + isEmbedded());
        }
      }

      if (key.equals(EntityHelper.ATTRIBUTE_VERSION)) {
        var version = (Integer) entry.getValue();
        if (version == null) {
          throw new IllegalArgumentException("Invalid  entity version provided: " + version);
        }
        if (version != getVersion()) {
          throw new IllegalArgumentException("Invalid  entity version provided: "
              + version + " expected: " + getVersion());
        }
      }

      if (key.charAt(0) == '@') {
        continue;
      }
      if (isSystemProperty(key)) {
        throw new IllegalArgumentException(
            "System properties can not be updated from map : " + key);
      }

      var property = cls != null ? cls.getProperty(key) : null;
      var type = property != null ? property.getType() : null;

      switch (type) {
        case LINKLIST: {
          updateLinkListFromMapValue(entry.getValue(), key);
          break;
        }
        case LINKSET: {
          updateLinkSetFromMapValue(entry.getValue(), key);
          break;
        }
        case LINKBAG: {
          updateLinkBagFromMapValue(entry.getValue(), session, key);
          break;
        }
        case LINKMAP: {
          updateLinkMapFromMapValue(entry.getValue(), key);
          break;
        }
        case EMBEDDEDLIST: {
          updateEmbeddedListFromMapValue(session, entry.getValue(), key);
          break;
        }
        case EMBEDDEDSET: {
          updateEmbeddedSetFromMapValue(session, entry.getValue(), key);
          break;
        }
        case EMBEDDEDMAP: {
          updateEmbeddedMapFromMapValue(session, entry.getValue(), key);
          break;
        }
        case EMBEDDED: {
          updateEmbeddedFromMapValue(entry.getValue(), session, key);
          break;
        }
        case null: {
          updatePropertyFromNonTypedMapValue(entry.getValue(), session, key);
          break;
        }
        default: {
          setPropertyInternal(key, entry.getValue());
        }
      }
    }
  }


  private void updatePropertyFromNonTypedMapValue(Object value,
      DatabaseSessionInternal session, String key) {
    value = convertMapValue(session, value);
    setPropertyInternal(key, value);
  }

  private Object convertMapValue(DatabaseSessionInternal session, Object value) {
    if (value instanceof Map<?, ?>) {
      var mapValue = (Map<String, ?>) value;

      var className = mapValue.get(EntityHelper.ATTRIBUTE_CLASS);
      var rid = mapValue.get(EntityHelper.ATTRIBUTE_RID);
      var embedded = mapValue.get(EntityHelper.ATTRIBUTE_EMBEDDED);

      if (embedded != null && Boolean.parseBoolean(embedded.toString())) {
        Entity embeddedEntity;
        if (className != null) {
          embeddedEntity = session.newEmbeddedEntity(className.toString());
        } else {
          embeddedEntity = session.newEmbeddedEntity();
        }

        embeddedEntity.updateFromMap(mapValue);
        value = embeddedEntity;
      } else if (rid != null) {
        var record = session.load(new RecordId(rid.toString()));
        if (record instanceof EntityImpl entity) {
          if (className != null && !className.equals(entity.getSchemaClassName())) {
            throw new IllegalArgumentException("Invalid  entity class name provided: "
                + className + " expected: " + entity.getSchemaClassName());
          }
          entity.updateFromMap(mapValue);
          value = entity;
        } else if (record instanceof Blob) {
          if (mapValue.size() > 1) {
            throw new IllegalArgumentException(
                "Invalid value for LINK: " + value);
          }
          value = record;
        } else {
          throw new IllegalArgumentException(
              "Invalid value, record expectd, provided : " + value);
        }
      } else if (className != null) {
        var entity = session.newEntity(className.toString());
        entity.updateFromMap(mapValue);
        value = entity;
      } else {
        var trackedMap = new EntityEmbeddedMapImpl<>(this);
        for (var mapEntry : mapValue.entrySet()) {
          trackedMap.put(mapEntry.getKey(), convertMapValue(session, mapEntry.getValue()));
        }
        value = trackedMap;
      }
    } else if (value instanceof List<?> list) {
      var trackedList = new EntityEmbeddedListImpl<>(this);
      for (var item : list) {
        trackedList.add(convertMapValue(session, item));
      }
      value = trackedList;
    } else if (value instanceof Set<?> set) {
      var trackedSet = new EntityEmbeddedSetImpl<>(this);
      for (var item : set) {
        trackedSet.add(convertMapValue(session, item));
      }
      value = trackedSet;
    }

    return value;
  }

  private void updateEmbeddedFromMapValue(Object value, DatabaseSessionInternal session,
      String key) {
    Entity embedded;
    if (value instanceof Map<?, ?> mapValue) {
      embedded = session.newEmbeddedEntity();
      embedded.updateFromMap((Map<String, ?>) mapValue);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for EMBEDDED: " + value);
    }

    setPropertyInternal(key, embedded);
  }

  private void updateEmbeddedMapFromMapValue(DatabaseSessionInternal session,
      Object value, String key) {
    if (value instanceof Map<?, ?> mapValue) {
      var embeddedMap = new EntityEmbeddedMapImpl<>(this);
      for (var mapEntry : mapValue.entrySet()) {
        embeddedMap.put(mapEntry.getKey().toString(),
            convertMapValue(session, mapEntry.getValue()));
      }
      setPropertyInternal(key, embeddedMap);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for EMBEDDEDMAP: " + value);
    }
  }

  private void updateEmbeddedSetFromMapValue(DatabaseSessionInternal session,
      Object value, String key) {
    if (value instanceof Collection<?> collection) {
      var embeddedSet = new EntityEmbeddedSetImpl<>(this);
      for (var item : collection) {
        embeddedSet.add(convertMapValue(session, item));
      }
      setPropertyInternal(key, embeddedSet);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for EMBEDDEDSET: " + value);
    }
  }

  private void updateEmbeddedListFromMapValue(DatabaseSessionInternal session,
      Object value, String key) {
    if (value instanceof Collection<?> collection) {
      var embeddedList = new EntityEmbeddedListImpl<>(this);
      for (var item : collection) {
        embeddedList.add(convertMapValue(session, item));
      }
      setPropertyInternal(key, embeddedList);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for EMBEDDEDLIST: " + value);
    }
  }

  private void updateLinkMapFromMapValue(Object value, String key) {
    if (value instanceof Map<?, ?> mapValue) {
      var linkMap = new EntityLinkMapIml(this);
      for (var mapEntry : mapValue.entrySet()) {
        if (mapEntry.getKey() instanceof String keyString) {
          if (mapEntry.getValue() instanceof Identifiable identifiable) {
            linkMap.put(keyString, identifiable);
          } else {
            throw new IllegalArgumentException(
                "Invalid value for LINKMAP: " + mapEntry.getValue());
          }
        } else {
          throw new IllegalArgumentException(
              "Invalid key for LINKMAP: " + mapEntry.getKey());
        }
      }
      setPropertyInternal(key, linkMap);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for LINKMAP: " + value);
    }
  }

  private void updateLinkBagFromMapValue(Object value, DatabaseSessionInternal session,
      String key) {
    if (value instanceof Collection<?> collection) {
      var linkBag = new LinkBag(session);
      for (var item : collection) {
        if (item instanceof Identifiable identifiable) {
          linkBag.add(identifiable.getIdentity());
        } else {
          throw new IllegalArgumentException("Invalid value for LINKBAG: " + item);
        }
      }
      setPropertyInternal(key, linkBag);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for LINKBAG: " + value);
    }
  }

  private void updateLinkSetFromMapValue(Object value, String key) {
    if (value instanceof Collection<?> collection) {
      var linkSet = new EntityLinkSetImpl(this);
      for (var item : collection) {
        if (item instanceof Identifiable identifiable) {
          linkSet.add(identifiable);
        } else {
          throw new IllegalArgumentException("Invalid value for LINKSET: " + item);
        }
      }
      setPropertyInternal(key, linkSet);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for LINKSET: " + value);
    }
  }

  private void updateLinkListFromMapValue(Object value, String key) {
    if (value instanceof Collection<?> collection) {
      var linkList = new EntityLinkListImpl(session);
      for (var item : collection) {
        if (item instanceof Identifiable identifiable) {
          linkList.add(identifiable);
        } else {
          throw new IllegalArgumentException("Invalid value for LINKLIST: " + item);
        }
      }
      setPropertyInternal(key, linkList);
    } else {
      throw new IllegalArgumentException(
          "Invalid value for LINKLIST: " + value);
    }
  }


  @Override
  public final EntityImpl updateFromJSON(final String iSource, final String iOptions) {
    return super.updateFromJSON(iSource, iOptions);
  }

  /**
   * Returns list of changed properties. There are two types of changes:
   *
   * <ol>
   *   <li>Value of property itself was changed by calling of {@link #setProperty(String, Object)} method for
   *       example.
   *   <li>Internal state of property was changed but was not saved. This case currently is applicable
   *       for for collections only.
   * </ol>
   */
  public List<String> getDirtyPropertiesBetweenCallbacksInternal(boolean includeSystemFields,
      boolean checkAccess) {
    checkForBinding();

    if (properties == null || properties.isEmpty()) {
      return Collections.emptyList();
    }

    final List<String> dirtyFields = new ArrayList<>();
    for (var entry : properties.entrySet()) {
      var propertyName = entry.getKey();

      if (checkAccess) {
        if (includeSystemFields || !isSystemProperty(propertyName)) {
          if (propertyAccess == null || propertyAccess.isReadable(propertyName)) {
            if (entry.getValue().isChanged() || entry.getValue().isTrackedModified()) {
              dirtyFields.add(propertyName);
            }
          }
        }
      } else if (includeSystemFields || !isSystemProperty(propertyName)) {
        if (entry.getValue().isChanged() || entry.getValue().isTrackedModified()) {
          dirtyFields.add(propertyName);
        }
      }
    }

    return dirtyFields;
  }

  @Nonnull
  public List<String> getDirtyPropertiesInternal(boolean includeSystemProperties,
      boolean checkAccess) {
    checkForBinding();

    if (properties == null || properties.isEmpty()) {
      return Collections.emptyList();
    }

    final List<String> dirtyFields = new ArrayList<>();
    for (var entry : properties.entrySet()) {
      var propertyName = entry.getKey();
      if (checkAccess) {
        if (includeSystemProperties || !isSystemProperty(propertyName)) {
          if (propertyAccess == null || propertyAccess.isReadable(propertyName)) {
            if (entry.getValue().isTxChanged() || entry.getValue().isTxTrackedModified()) {
              dirtyFields.add(propertyName);
            }
          }
        }
      } else if (includeSystemProperties || !isSystemProperty(propertyName)) {
        if (entry.getValue().isTxChanged() || entry.getValue().isTxTrackedModified()) {
          dirtyFields.add(propertyName);
        }
      }
    }

    return dirtyFields;
  }

  /**
   * Returns the original value of a property before it has been changed.
   *
   * @param iFieldName Property name to retrieve the original value
   */
  @Nullable
  public Object getOriginalValue(final String iFieldName) {
    checkForBinding();

    if (properties != null) {
      var entry = properties.get(iFieldName);
      if (entry != null) {
        return entry.original;
      }
    }

    return null;
  }

  @Nullable
  public MultiValueChangeTimeLine<Object, Object> getCollectionTimeLine(final String iFieldName) {
    checkForBinding();

    var entry = properties != null ? properties.get(iFieldName) : null;
    return entry != null ? entry.getTimeLine() : null;
  }

  /**
   * Checks if a property exists.
   *
   * @return True if exists, otherwise false.
   */
  @Override
  public boolean hasProperty(final @Nonnull String propertyName) {
    checkForBinding();

    if (checkForProperties(propertyName)
        && (propertyAccess == null || propertyAccess.isReadable(propertyName))) {
      var entry = properties.get(propertyName);
      return entry != null && entry.exists();
    } else {
      return false;
    }
  }

  @Override
  public @Nonnull Result detach() {
    checkForBinding();
    checkForProperties();

    var result = new ResultInternal(session);
    convertToResult(result);
    result.setSession(null);
    return result;
  }

  public static boolean isSystemProperty(String propertyName) {
    if (propertyName != null && !propertyName.isEmpty()) {
      var firstChar = propertyName.charAt(0);
      return firstChar != '_' && !Character.isLetter(firstChar);
    }

    return false;
  }

  private void convertToResult(ResultInternal result) {
    var propertyTypes = new HashMap<String, String>();

    var cls = getImmutableSchemaClass(session);
    for (var entry : properties.entrySet()) {
      var name = entry.getKey();

      if (propertyAccess == null || propertyAccess.isReadable(name)) {
        result.setProperty(name, entry.getValue().value);

        SchemaProperty prop = null;
        if (cls != null) {
          prop = cls.getProperty(name);
        }
        PropertyTypeInternal propertyType = null;
        if (prop != null) {
          propertyType = PropertyTypeInternal.convertFromPublicType(prop.getType());
        }
        if (propertyType == null) {
          propertyType = entry.getValue().type;
        }
        if (propertyType == null) {
          propertyType = PropertyTypeInternal.getTypeByValue(entry.getValue().value);
        }
        if (propertyType != null) {
          propertyTypes.put(name, propertyType.getName());
        }
      }
    }

    if (className != null) {
      result.setProperty(EntityHelper.ATTRIBUTE_CLASS, className);
    }
    if (!isEmbedded()) {
      result.setProperty(EntityHelper.ATTRIBUTE_RID, recordId);
      if (isDirty()) {
        result.setProperty(EntityHelper.ATTRIBUTE_VERSION, recordVersion + 1);
      } else {
        result.setProperty(EntityHelper.ATTRIBUTE_VERSION, recordVersion);
      }
    } else {
      result.setProperty(EntityHelper.ATTRIBUTE_EMBEDDED, true);
    }

    result.setMetadata(RESULT_PROPERTY_TYPES, propertyTypes);
  }

  /**
   * Returns true if the record has some owner.
   */
  public boolean hasOwners() {
    return owner != null && owner.get() != null;
  }

  @Override
  public RecordElement getOwner() {
    if (owner == null) {
      return null;
    }
    return owner.get();
  }

  @Deprecated
  public Iterable<RecordElement> getOwners() {
    if (owner == null || owner.get() == null) {
      return Collections.emptyList();
    }

    final List<RecordElement> result = new ArrayList<>();
    result.add(owner.get());
    return result;
  }

  /**
   * Propagates the dirty status to the owner, if any. This happens when the object is embedded in
   * another one.
   */
  @Override
  public void setDirty() {
    if (propertyConversionInProgress) {
      return;
    }

    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD
    // _SOURCE IS NULL
    checkForProperties();
    super.setDirty();

    if (status != STATUS.UNMARSHALLING) {
      source = null;
    }

    if (owner != null) {
      // PROPAGATES TO THE OWNER
      var ownerEntity = owner.get();

      if (ownerEntity != null) {
        ownerEntity.setDirty();
      }
    }
  }

  @Override
  public void setDirtyNoChanged() {
    if (owner != null) {
      // PROPAGATES TO THE OWNER
      var ownerEntity = owner.get();
      if (ownerEntity != null) {
        ownerEntity.setDirtyNoChanged();
      }
    }

    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD
    // _SOURCE IS NULL
    checkForProperties();

    super.setDirtyNoChanged();
  }

  @Override
  public final EntityImpl fromStream(final byte[] iRecordBuffer) {
    var session = getSession();
    if (dirty > 0) {
      throw new DatabaseException(session.getDatabaseName(),
          "Cannot call fromStream() on dirty records");
    }

    status = STATUS.UNMARSHALLING;
    try {
      removeAllCollectionChangeListeners();

      properties = null;
      propertiesCount = 0;
      contentChanged = false;
      schema = null;

      fetchSchema();
      super.fromStream(iRecordBuffer);

      return this;
    } finally {
      status = STATUS.LOADED;
    }
  }

  /**
   * Returns the forced property type if any.
   *
   * @param propertyName name of property to check
   */
  @Override
  @Nullable
  public PropertyType getPropertyType(final @Nonnull String propertyName) {
    checkForBinding();
    validatePropertyName(propertyName, false);

    checkForProperties(propertyName);

    var entry = properties.get(propertyName);
    if (entry != null && entry.exists()) {
      if (propertyAccess == null || propertyAccess.isReadable(propertyName)) {
        if (entry.type != null) {
          return entry.type.getPublicPropertyType();
        }

        return null;
      } else {
        return null;
      }
    }

    return null;
  }

  @Nullable
  public PropertyTypeInternal getPropertyTypeInternal(String propertyName) {
    checkForBinding();
    checkForProperties(propertyName);

    var entry = properties.get(propertyName);
    if (entry != null) {
      return entry.type;
    }

    return null;
  }

  @Override
  public void unload() {
    if (status == RecordElement.STATUS.NOT_LOADED) {
      return;
    }

    if (dirty > 0) {
      throw new IllegalStateException("Can not unload dirty entity");
    }

    internalReset();
    super.unload();
  }

  @Override
  public void delete() {
    checkForBinding();
    checkForProperties();

    linkBagsToDelete = new ArrayList<>();

    for (var entry : properties.entrySet()) {
      var value = entry.getValue();
      if (value.exists()) {
        var propertyValue = value.value;
        var originalValue = value.original;

        if (propertyValue != null) {
          if (propertyValue instanceof LinkBag linkBag && !linkBag.isEmbedded()) {
            linkBagsToDelete.add((BTreeBasedLinkBag) linkBag.getDelegate());
          } else if (propertyValue instanceof EntityLinkSetImpl linkSet && !linkSet.isEmbedded()) {
            linkBagsToDelete.add((BTreeBasedLinkBag) linkSet.getDelegate());
          }
        } else if (originalValue instanceof LinkBag linkBag && !linkBag.isEmbedded()) {
          linkBagsToDelete.add((BTreeBasedLinkBag) linkBag.getDelegate());
        } else if (originalValue instanceof EntityLinkSetImpl linkSet && !linkSet.isEmbedded()) {
          linkBagsToDelete.add((BTreeBasedLinkBag) linkSet.getDelegate());
        }
      }
    }

    try {
      super.delete();
    } catch (Exception e) {
      linkBagsToDelete = null;
      throw e;
    }
    internalReset();
  }

  @Override
  public void markDeletedInServerTx() {
    super.markDeletedInServerTx();

    internalReset();
  }

  @Nullable
  public ArrayList<BTreeBasedLinkBag> getLinkBagsToDelete() {
    return linkBagsToDelete;
  }

  /**
   * Resets the record values and class type to being reused. It's like you create a EntityImpl from
   * scratch.
   */
  @Override
  public EntityImpl reset() {
    checkForBinding();

    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException(
          "Cannot reset entities during a transaction. Create a new one each time");
    }

    super.reset();

    propertyAccess = null;
    className = null;
    immutableClazz = null;
    immutableSchemaVersion = -1;

    internalReset();

    owner = null;
    return this;
  }

  /**
   * Rollbacks changes to the loaded version without reloading the entity.
   */
  public void undo() {
    if (properties != null) {
      final var vals = properties.entrySet().iterator();

      while (vals.hasNext()) {
        final var next = vals.next();
        final var val = next.getValue();
        if (val.isCreated()) {
          vals.remove();
        } else {
          val.undo();
        }
      }

      propertiesCount = properties.size();
    }
  }

  public void undo(final String property) {
    checkForBinding();

    if (properties != null) {
      final var value = properties.get(property);
      if (value != null) {
        if (value.isCreated()) {
          properties.remove(property);
        } else {
          value.undo();
        }
      }
    }

  }

  public boolean isLazyLoad() {
    checkForBinding();

    return lazyLoad;
  }

  public void setLazyLoad(final boolean iLazyLoad) {
    checkForBinding();

    this.lazyLoad = iLazyLoad;
    checkForProperties();
  }


  public void clearTrackData() {
    if (properties != null) {
      // FREE RESOURCES
      for (var cur : properties.entrySet()) {
        if (cur.getValue().exists()) {
          cur.getValue().clear();
          cur.getValue().enableTracking(this);
        } else {
          cur.getValue().clearNotExists();
        }
      }
    }
  }

  public void clearTransactionTrackData() {
    if (properties != null) {
      // FREE RESOURCES
      var iter = properties.entrySet().iterator();
      while (iter.hasNext()) {
        var cur = iter.next();
        if (cur.getValue().exists()) {
          cur.getValue().transactionClear();
        } else {
          iter.remove();
        }
      }
    }
  }


  public int getPropertiesCount() {
    checkForBinding();
    checkForProperties();

    return propertiesCount;
  }

  public boolean isEmpty() {
    checkForBinding();

    checkForProperties();
    return properties == null || properties.isEmpty();
  }

  @Override
  public boolean isEmbedded() {
    return owner != null;
  }

  /*
   * Initializes the object if has been unserialized
   */
  public boolean deserializeProperties(String... propertyNames) {
    if (status != STATUS.LOADED) {
      return false;
    }

    List<String> additional = null;
    if (source == null)
    // ALREADY UNMARSHALLED OR JUST EMPTY
    {
      return true;
    }

    checkForBinding();
    if (propertyNames != null && propertyNames.length > 0) {
      // EXTRACT REAL FIELD NAMES
      for (final var f : propertyNames) {
        if (f != null && !(!f.isEmpty() && f.charAt(0) == '@')) {
          var pos1 = f.indexOf('[');
          var pos2 = f.indexOf('.');
          if (pos1 > -1 || pos2 > -1) {
            var pos = pos1 > -1 ? pos1 : pos2;
            if (pos2 > -1 && pos2 < pos) {
              pos = pos2;
            }

            // REPLACE THE FIELD NAME
            if (additional == null) {
              additional = new ArrayList<>();
            }
            additional.add(f.substring(0, pos));
          }
        }
      }

      if (additional != null) {
        var copy = new String[propertyNames.length + additional.size()];
        System.arraycopy(propertyNames, 0, copy, 0, propertyNames.length);
        var next = propertyNames.length;
        for (var s : additional) {
          copy[next++] = s;
        }
        propertyNames = copy;
      }

      // CHECK IF HAS BEEN ALREADY UNMARSHALLED
      if (properties != null && !properties.isEmpty()) {
        var allFound = true;
        for (var f : propertyNames) {
          if (f != null && !(!f.isEmpty() && f.charAt(0) == '@') && !properties.containsKey(
              f)) {
            allFound = false;
            break;
          }
        }

        if (allFound)
        // ALL THE REQUESTED FIELDS HAVE BEEN LOADED BEFORE AND AVAILABLE, AVOID UNMARSHALLING
        {
          return true;
        }
      }
    }

    status = RecordElement.STATUS.UNMARSHALLING;
    try {
      checkForProperties();
      recordSerializer.fromStream(session, source, this, propertyNames);
    } finally {
      status = RecordElement.STATUS.LOADED;
    }

    if (propertyNames != null && propertyNames.length > 0) {
      for (var property : propertyNames) {
        if (property != null && !property.isEmpty() && property.charAt(0) == '@')
        // ATTRIBUTE
        {
          return true;
        }
      }

      // PARTIAL UNMARSHALLING
      if (properties != null && !properties.isEmpty()) {
        for (var f : propertyNames) {
          if (f != null && properties.containsKey(f)) {
            return true;
          }
        }
      }

      // NO FIELDS FOUND
      return false;
    } else {
      if (source != null)
      // FULL UNMARSHALLING
      {
        source = null;
      }
    }

    return true;
  }

  public void setClassNameIfExists(final String iClassName) {
    if (Objects.equals(className, iClassName)) {
      return;
    }

    checkForBinding();

    immutableClazz = null;
    immutableSchemaVersion = -1;
    className = iClassName;

    if (iClassName == null) {
      initPropertyAccess();
      return;
    }

    final var _clazz = session.getMetadata().getImmutableSchemaSnapshot()
        .getClass(iClassName);
    if (_clazz != null) {
      className = _clazz.getName();
      convertPropertiesToClassAndInitDefaultValues(_clazz);
    }
  }

  @Nullable
  @Override
  public SchemaClass getSchemaClass() {
    checkForBinding();

    if (className == null) {
      fetchClassName(session);
    }

    if (className == null) {
      return null;
    }

    return session.getMetadata().getSchema().getClass(className);
  }

  @Override
  @Nullable
  public String getSchemaClassName() {
    if (className == null) {
      fetchClassName(session);
    }

    return className;
  }

  public void setClassNameWithoutPropertiesPostProcessing(@Nullable final String className) {
    if (Objects.equals(className, this.className)) {
      return;
    }

    immutableClazz = null;
    immutableSchemaVersion = -1;

    this.className = className;

    if (className == null) {
      return;
    }

    var metadata = session.getMetadata();

    var schemaSnapshot = metadata.getImmutableSchemaSnapshot();
    this.immutableClazz = (SchemaImmutableClass) schemaSnapshot.getClass(className);

    if (this.immutableClazz != null) {
      this.immutableSchemaVersion = schemaSnapshot.getVersion();
      this.schema = schemaSnapshot;
    } else {
      metadata.getSchema().getOrCreateClass(className);
      schemaSnapshot = metadata.getImmutableSchemaSnapshot();

      this.immutableClazz = (SchemaImmutableClass) schemaSnapshot.getClass(className);
      this.immutableSchemaVersion = schemaSnapshot.getVersion();
      this.schema = schemaSnapshot;
    }

    if (this.immutableClazz == null) {
      throw new DatabaseException(session,
          "Class '" + className + "' not found in the database");
    }

    this.className = this.immutableClazz.getName();
  }


  /**
   * Validates the record following the declared constraints defined in schema such as mandatory,
   * notNull, min, max, regexp, etc. If the schema is not defined for the current class or there are
   * no constraints then the validation is ignored.
   *
   * @throws ValidationException if the entity breaks some validation constraints defined in the
   *                             schema
   * @see SchemaProperty
   */
  public void validate() throws ValidationException {
    checkForBinding();
    checkForProperties();

    validatePropertiesSecurity(session, this);
    if (!session.isValidationEnabled()) {
      return;
    }

    final var immutableSchemaClass = getImmutableSchemaClass(session);
    if (immutableSchemaClass != null) {
      if (immutableSchemaClass.isStrictMode()) {
        // CHECK IF ALL FIELDS ARE DEFINED
        for (var f : propertyNames()) {
          if (immutableSchemaClass.getProperty(f) == null) {
            throw new ValidationException(session.getDatabaseName(),
                "Found additional property '"
                    + f
                    + "'. It cannot be added because the schema class '"
                    + immutableSchemaClass.getName()
                    + "' is defined as STRICT");
          }
        }
      }

      final var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();
      for (var p : immutableSchemaClass.getProperties()) {
        validateProperty(session, immutableSchema, this, (ImmutableSchemaProperty) p);
      }
    }
  }

  protected String toString(Set<DBRecord> inspected) {
    checkForBinding();

    if (inspected.contains(this)) {
      return "<recursion:rid=" + recordId + ">";
    } else {
      inspected.add(this);
    }

    final var buffer = new StringBuilder(128);

    if (!session.isClosed()) {
      final var clsName = getSchemaClassName();
      if (clsName != null) {
        buffer.append(clsName);
      }
    }

    if (recordId.isValidPosition()) {
      buffer.append(recordId);
    }

    var first = true;
    if (sourceIsParsedByProperties()) {
      for (var propertyName : calculatePropertyNames(false, true)) {
        buffer.append(first ? '{' : ',');
        buffer.append(propertyName);
        buffer.append(':');
        var propertyValue = getPropertyInternal(propertyName);
        if (propertyValue == null) {
          buffer.append("null");
        } else {
          if (propertyValue instanceof Collection<?>
              || propertyValue instanceof Map<?, ?>
              || propertyValue.getClass().isArray()) {
            buffer.append('[');
            buffer.append(MultiValue.getSize(propertyValue));
            buffer.append(']');
          } else {
            if (propertyValue instanceof RecordAbstract record) {
              if (record.getIdentity().isValidPosition()) {
                record.getIdentity().toString(buffer);
              } else {
                if (record instanceof EntityImpl) {
                  buffer.append(((EntityImpl) record).toString(inspected));
                } else {
                  buffer.append(record);
                }
              }
            } else {
              buffer.append(propertyValue);
            }
          }
        }

        if (first) {
          first = false;
        }
      }
      if (!first) {
        buffer.append('}');
      }
    }

    if (recordId.isValidPosition()) {
      buffer.append(" v");
      buffer.append(recordVersion);
    }

    return buffer.toString();
  }

  @Override
  public final RecordAbstract fill(
      final @Nonnull RID rid, final int version, final byte[] buffer, final boolean dirty) {
    var session = getSession();
    if (this.dirty > 0) {
      throw new DatabaseException(session.getDatabaseName(),
          "Cannot call fill() on dirty records");
    }

    schema = null;
    fetchSchema();
    return super.fill(rid, version, buffer, dirty);
  }

  @Override
  public void clearSource() {
    super.clearSource();
    schema = null;
  }

  public GlobalProperty getGlobalPropertyById(int id) {
    checkForBinding();
    if (schema == null) {
      var metadata = session.getMetadata();
      schema = metadata.getImmutableSchemaSnapshot();
    }
    var prop = schema.getGlobalPropertyById(id);
    if (prop == null) {
      if (session.isClosed()) {
        throw new DatabaseException(session.getDatabaseName(),
            "Cannot unmarshall the entity because no database is active, use detach for use the"
                + " entity outside the database session scope");
      }

      var metadata = session.getMetadata();
      if (metadata.getImmutableSchemaSnapshot() != null) {
        metadata.clearThreadLocalSchemaSnapshot();
      }
      metadata.reload();
      metadata.makeThreadLocalSchemaSnapshot();
      schema = metadata.getImmutableSchemaSnapshot();
      prop = schema.getGlobalPropertyById(id);
    }
    return prop;
  }

  @Nullable
  public SchemaImmutableClass getImmutableSchemaClass(
      @Nonnull DatabaseSessionInternal session) {
    if (this.session != null && this.session != session) {
      throw new DatabaseException("The entity is bounded to another session");
    }

    var immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();
    if (immutableClazz == null) {
      if (className == null) {
        fetchClassName(session);
      }

      if (className != null) {
        if (immutableSchema == null) {
          return null;
        }
        //noinspection deprecation
        immutableSchemaVersion = immutableSchema.getVersion();
        immutableClazz = (SchemaImmutableClass) immutableSchema.getClass(className);
      }
    } else if (immutableSchemaVersion != immutableSchema.getVersion()) {
      immutableClazz = null;
      return getImmutableSchemaClass(session);
    }

    return immutableClazz;
  }

  public boolean rawContainsProperty(final String iFiledName) {
    checkForBinding();
    return properties != null && properties.containsKey(iFiledName);
  }

  /**
   * Internal.
   */
  @Override
  public byte getRecordType() {
    return RECORD_TYPE;
  }

  /**
   * Internal.
   */
  @Override
  public void setOwner(final RecordElement iOwner) {
    if (iOwner == null) {
      return;
    }

    checkForBinding();
    if (!isEmbedded()) {
      throw new IllegalStateException(
          "Only embedded entities (created using DatabaseSession.newEmbeddedEntity) can have an owner");
    }
    var owner = getOwner();
    if (owner != null && !owner.equals(iOwner)) {
      throw new IllegalStateException(
          "This entity is already owned by data container "
              + owner
              + " if you want to use it in other data container create new entity instance and copy"
              + " content of current one.");
    }

    if (recordId.isPersistent()) {
      throw new DatabaseException(session.getDatabaseName(),
          "Cannot add owner to a persistent entity");
    }

    this.owner = new WeakReference<>(iOwner);
  }

  public void removeOwner(final RecordElement iRecordElement) {
    if (owner != null && owner.get() == iRecordElement) {
      assert !recordId.isPersistent();
      owner = null;
    }
  }

  public boolean checkPropertyAccess(String propertyName) {
    if (propertyAccess != null) {
      return propertyAccess.isReadable(propertyName);
    }

    return true;
  }

  public void checkAllMultiValuesAreTrackedVersions() {
    checkForBinding();
    if (properties == null) {
      return;
    }
    propertyConversionInProgress = true;
    try {

      for (var propertyEntry : properties.entrySet()) {
        var entry = propertyEntry.getValue();
        final var propertyValue = entry.value;
        if (propertyValue instanceof LinkBag) {
          if (isEmbedded()) {
            throw new DatabaseException(session.getDatabaseName(),
                "RidBag are supported only at entity root");
          }
          ((LinkBag) propertyValue).checkAndConvert();
        }
        if (!(propertyValue instanceof Collection<?>)
            && !(propertyValue instanceof Map<?, ?>)
            && !(propertyValue instanceof EntityImpl)) {
          continue;
        }

        if (propertyValue instanceof EntityImpl && ((EntityImpl) propertyValue).isEmbedded()) {
          ((EntityImpl) propertyValue).checkAllMultiValuesAreTrackedVersions();
          continue;
        }

        var propertyType = entry.type;
        if (propertyType == null) {
          SchemaClass clazz = getImmutableSchemaClass(session);
          if (clazz != null) {
            final var prop = clazz.getProperty(propertyEntry.getKey());
            propertyType =
                prop != null ? PropertyTypeInternal.convertFromPublicType(prop.getType()) : null;
          }
        }
        if (propertyType == null) {
          propertyType = PropertyTypeInternal.getTypeByValue(propertyValue);
        }

        switch (propertyType) {
          case EMBEDDEDLIST:
            if (propertyValue instanceof List<?>
                && !(propertyValue instanceof EntityEmbeddedListImpl<?>)) {
              throw new DatabaseException(session.getDatabaseName(),
                  "Property " + propertyEntry.getKey() + " is supposed to be TrackedList but is "
                      + propertyValue.getClass());
            }
            break;
          case EMBEDDEDSET:
            if (propertyValue instanceof Set<?>
                && !(propertyValue instanceof EntityEmbeddedSetImpl<?>)) {
              throw new DatabaseException(session.getDatabaseName(),
                  "Property " + propertyEntry.getKey() + " is supposed to be TrackedSet but is "
                      + propertyValue.getClass());

            }
            break;
          case EMBEDDEDMAP:
            if (propertyValue instanceof Map<?, ?>
                && !(propertyValue instanceof EntityEmbeddedMapImpl)) {
              throw new DatabaseException(session.getDatabaseName(),
                  "Property " + propertyEntry.getKey() + " is supposed to be TrackedMap but is "
                      + propertyValue.getClass());
            }
            break;
          case LINKLIST:
            if (propertyValue instanceof List<?>
                && !(propertyValue instanceof EntityLinkListImpl)) {
              throw new DatabaseException(session.getDatabaseName(),
                  "Property " + propertyEntry.getKey() + " is supposed to be LinkList but is "
                      + propertyValue.getClass());
            }
            break;
          case LINKSET:
            if (propertyValue instanceof Set<?> && !(propertyValue instanceof EntityLinkSetImpl)) {
              throw new DatabaseException(session.getDatabaseName(),
                  "Property " + propertyEntry.getKey() + " is supposed to be LinkSet but is "
                      + propertyValue.getClass());
            }
            break;
          case LINKMAP:
            if (propertyValue instanceof Map<?, ?>
                && !(propertyValue instanceof EntityLinkMapIml)) {
              throw new DatabaseException(session.getDatabaseName(),
                  "Property " + propertyEntry.getKey() + " is supposed to be LinkMap but is "
                      + propertyValue.getClass());
            }
            break;
          case LINKBAG:
            if (!(propertyValue instanceof LinkBag)) {
              throw new DatabaseException(session.getDatabaseName(),
                  "Property " + propertyEntry.getKey() + " is supposed to be RidBag but is "
                      + propertyValue.getClass());
            }
            break;
          default:
            break;
        }
      }
    } finally {
      propertyConversionInProgress = false;
    }
  }

  @Override
  protected void internalReset() {
    removeAllCollectionChangeListeners();
    if (properties != null) {
      properties.clear();
    }

    propertiesCount = 0;
  }

  public boolean checkForProperties(final String... properties) {
    checkForBinding();
    if (status == RecordElement.STATUS.LOADED || status == RecordElement.STATUS.UNMARSHALLING) {
      if (this.properties == null) {
        this.properties = new HashMap<>();
      }

      if (source != null) {
        return deserializeProperties(properties);
      }

      return true;
    }

    return false;
  }

  public void initPropertyAccess() {
    propertyAccess = new PropertyAccess(session, this,
        session.getSharedContext().getSecurity());
    propertyEncryption = PropertyEncryptionNone.instance();
  }

  @Nullable
  Object accessProperty(final String property) {
    checkForBinding();

    if (checkForProperties(property)) {
      if (propertyAccess == null || propertyAccess.isReadable(property)) {
        var entry = properties.get(property);
        if (entry != null) {
          return entry.value;
        } else {
          return null;
        }
      } else {
        return null;
      }
    } else {
      return null;
    }
  }

  private boolean isPropertyAccessible(final String property) {
    return propertyAccess == null || propertyAccess.isReadable(property);
  }

  private void setup() {
    if (session != null) {
      recordSerializer = session.getSerializer();
    }

    if (recordSerializer == null) {
      recordSerializer = DatabaseSessionAbstract.getDefaultSerializer();
    }
  }

  public Set<Entry<String, EntityEntry>> getRawEntries() {
    checkForBinding();

    checkForProperties();
    return properties == null ? new HashSet<>() : properties.entrySet();
  }

  public Iterable<Entity> getEntities(Direction direction, String... linkNames) {
    checkForBinding();
    if (direction == Direction.BOTH) {
      return IterableUtils.chainedIterable(
          getEntities(Direction.OUT, linkNames),
          getEntities(Direction.IN, linkNames));
    } else {
      var links = getBidirectionalLinksInternal(direction, linkNames);
      return new BidirectionalLinksIterable<>(links, direction);
    }
  }

  public Iterable<? extends Relation<Entity>> getBidirectionalLinks(
      Direction direction, String... linkNames) {
    checkForBinding();
    if (direction == Direction.BOTH) {
      return IterableUtils.chainedIterable(
          getBidirectionalLinks(Direction.OUT, linkNames),
          getBidirectionalLinks(Direction.IN, linkNames));
    } else {
      return getBidirectionalLinksInternal(direction, linkNames);
    }
  }

  protected Iterable<? extends Relation<Entity>> getBidirectionalLinksInternal(
      Direction direction, String... linkNames) {
    if (linkNames == null || linkNames.length == 0) {
      var propertyNames = getPropertyNames();
      var linkCandidates = new ArrayList<String>(propertyNames.size());

      for (var propertyName : propertyNames) {
        var propertyType = getPropertyTypeInternal(propertyName);
        if (propertyType != null && propertyType.isLink()) {
          linkCandidates.add(propertyName);
        }
      }

      linkNames = linkCandidates.toArray(new String[0]);
    }
    var iterables = new ArrayList<Iterable<LightweightRelationImpl<Entity>>>(
        linkNames.length);
    Object fieldValue;

    for (var linkName : linkNames) {
      if (!isPropertyAccessible(linkName)) {
        return Collections.emptyList();
      }

      String propertyName;
      if (direction == Direction.OUT) {
        propertyName = linkName;
      } else {
        propertyName = OPPOSITE_LINK_CONTAINER_PREFIX + linkName;
      }

      fieldValue = getPropertyInternal(propertyName);
      if (fieldValue != null) {
        switch (fieldValue) {
          case Identifiable identifiable -> {
            if (identifiable instanceof Entity entity && entity.isEmbedded()) {
              throw new IllegalArgumentException("Embedded entities are not supported");
            }
            var coll = Collections.singleton(identifiable);
            iterables.add(
                new EntityRelationsIterable(this, new Pair<>(direction, linkName), linkNames,
                    session,
                    coll, 1, coll));
          }
          case EntityLinkSetImpl set -> iterables.add(
              new EntityRelationsIterable(this, new Pair<>(direction, linkName), linkNames, session,
                  set, -1, set));
          case EntityLinkListImpl list -> iterables.add(
              new EntityRelationsIterable(this, new Pair<>(direction, linkName), linkNames, session,
                  list, -1, list));
          case LinkBag bag -> iterables.add(
              new EntityRelationsIterable(
                  this, new Pair<>(direction, linkName), linkNames, session,
                  bag, -1, bag));
          case EntityLinkMapIml map -> {
            var values = map.values();
            iterables.add(
                new EntityRelationsIterable(this, new Pair<>(direction, linkName), linkNames,
                    session,
                    values, -1, values));
          }

          default -> {
            throw new IllegalArgumentException(
                "Unsupported property type: " + getPropertyType(propertyName));
          }
        }
      }
    }

    if (iterables.size() == 1) {
      return iterables.getFirst();
    } else if (iterables.isEmpty()) {
      return Collections.emptyList();
    }

    //noinspection unchecked
    return IterableUtils.chainedIterable(iterables.toArray(new Iterable[0]));
  }

  public List<Entry<String, EntityEntry>> getFilteredEntries() {
    checkForBinding();
    checkForProperties();

    if (properties == null) {
      return Collections.emptyList();
    } else {
      if (propertyAccess == null) {
        return properties.entrySet().stream()
            .filter((x) -> x.getValue().exists())
            .collect(Collectors.toList());
      } else {
        return properties.entrySet().stream()
            .filter((x) -> x.getValue().exists() && propertyAccess.isReadable(x.getKey()))
            .collect(Collectors.toList());
      }
    }
  }

  private void fetchSchema() {
    if (schema == null) {
      var metadata = session.getMetadata();
      schema = metadata.getImmutableSchemaSnapshot();
    }
  }

  private void fetchClassName(DatabaseSessionInternal session) {
    if (recordId.getCollectionId() >= 0) {
      final Schema schema = session.getMetadata().getImmutableSchemaSnapshot();
      if (schema != null) {
        var clazz = schema.getClassByCollectionId(recordId.getCollectionId());
        if (clazz != null) {
          className = clazz.getName();
        }
      }
    }
  }

  /**
   * Checks and convert the property of the entity matching the types specified by the class.
   */
  public final void convertPropertiesToClassAndInitDefaultValues(final SchemaClass clazz) {
    for (var prop : clazz.getProperties()) {
      var entry = properties != null ? properties.get(prop.getName()) : null;
      if (entry != null && entry.exists()) {
        if (entry.type == null || entry.type != PropertyTypeInternal.convertFromPublicType(
            prop.getType())) {
          var preChanged = entry.isChanged();
          var preCreated = entry.isCreated();
          var propertyName = prop.getName();
          var propertyType = prop.getType();
          setProperty(propertyName, entry.value, propertyType);
          if (recordId.isNew()) {
            if (preChanged) {
              entry.markChanged();
            } else {
              entry.unmarkChanged();
            }
            if (preCreated) {
              entry.markCreated();
            } else {
              entry.unmarkCreated();
            }
          }
        }
      } else {
        var defValue = prop.getDefaultValue();
        if (defValue != null && !hasProperty(prop.getName())) {
          var curFieldValue = SQLHelper.parseDefaultValue(session, this, defValue, prop);
          var propertyValue = convertField(session,
              this, prop.getName(), PropertyTypeInternal.convertFromPublicType(prop.getType()),
              PropertyTypeInternal.convertFromPublicType(prop.getLinkedType()), curFieldValue);
          final var propertyName = prop.getName();
          setPropertyInternal(propertyName, propertyValue,
              PropertyTypeInternal.convertFromPublicType(prop.getType()));
        }
      }
    }
  }

  private PropertyTypeInternal derivePropertyType(String propertyName,
      PropertyTypeInternal propertyType, Object value) {
    SchemaClass clazz = getImmutableSchemaClass(session);
    if (clazz != null) {
      // SCHEMA-FULL?
      final var prop = clazz.getProperty(propertyName);
      if (prop != null) {
        propertyType = PropertyTypeInternal.convertFromPublicType(prop.getType());
      }
    }

    if (propertyType == null) {
      propertyType = PropertyTypeInternal.getTypeByValue(value);
    }

    if (propertyType == null && value != null) {
      throw new DatabaseException(session,
          "Cannot determine the type of the property " + propertyName);
    }

    return propertyType;
  }

  private void validatePropertyValue(String propertyName, @Nullable Object propertyValue) {
    var error = checkPropertyValue(propertyName, propertyValue);
    if (error != null) {
      throw new DatabaseException(session.getDatabaseName(), error);
    }
  }

  @Nullable
  protected String checkPropertyValue(String propertyName, @Nullable Object propertyValue) {
    if (propertyValue == null) {
      return null;
    }
    if (PropertyTypeInternal.isSingleValueType(propertyValue)) {
      return null;
    }

    var cls = propertyValue.getClass();
    if (cls.isArray() && cls.getComponentType() == byte.class) {
      return null;
    }

    if (cls.isEnum()) {
      return null;
    }

    if (propertyValue instanceof TrackedMultiValue<?, ?> trackedMultiValue) {
      var owner = trackedMultiValue.getOwner();
      if (owner != null && owner != this) {
        return "The collection is already owned by another entity : " + owner;
      }

      return null;
    }

    if (propertyValue instanceof Collection<?> || propertyValue instanceof Map<?, ?>) {
      return "Data containers have to be created using appropriate getOrCreateXxx methods";
    }

    if (propertyValue instanceof RecordAbstract recordAbstract) {
      recordAbstract.checkForBinding();

      if (recordAbstract.getSession() != session) {
        throw new DatabaseException(getSession().getDatabaseName(),
            "Entity instance is bound to another session instance");
      }
    }

    if (propertyValue instanceof Identifiable) {
      return null;
    }

    return "Invalid value for property. " + propertyName + " : " + propertyValue.getClass() + " : "
        + propertyValue;
  }

  private void removeAllCollectionChangeListeners() {
    if (properties == null) {
      return;
    }

    for (final var property : properties.entrySet()) {
      var entityEntry = property.getValue();

      var value = entityEntry.value;
      entityEntry.disableTracking(this, value);
    }
  }

  public void checkClass(DatabaseSessionInternal session) {
    checkForBinding();
    if (className == null) {
      fetchClassName(session);
    }

    final Schema immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();
    if (immutableSchema == null) {
      return;
    }

    if (immutableClazz == null) {
      //noinspection deprecation
      immutableSchemaVersion = immutableSchema.getVersion();
      immutableClazz = (SchemaImmutableClass) immutableSchema.getClass(className);
    } else {
      //noinspection deprecation
      if (immutableSchemaVersion < immutableSchema.getVersion()) {
        //noinspection deprecation
        immutableSchemaVersion = immutableSchema.getVersion();
        immutableClazz = (SchemaImmutableClass) immutableSchema.getClass(className);
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Nullable
  private static <RET> RET convertField(
      @Nonnull DatabaseSessionInternal session, @Nonnull final EntityImpl entity,
      @Nonnull final String fieldName,
      @Nullable PropertyTypeInternal type,
      @Nullable PropertyTypeInternal linkedType,
      @Nullable Object value) {
    if (value == null) {
      return null;
    }

    if (type == null) {
      type = PropertyTypeInternal.getTypeByValue(value);
    }

    if (type == null) {
      return (RET) value;
    }

    var immutableSchemaClass = entity.getImmutableSchemaClass(session);
    var property =
        immutableSchemaClass != null ? immutableSchemaClass.getProperty(fieldName) : null;
    if (linkedType == null) {
      linkedType =
          property != null ? PropertyTypeInternal.convertFromPublicType(property.getLinkedType())
              : null;
    }

    value = type.convert(value, linkedType,
        property != null ? property.getLinkedClass() : null, session);

    return (RET) value;
  }

  public ImmutableSchema getImmutableSchema() {
    return schema;
  }

  void checkEmbeddable() {
    checkForBinding();

    var cls = getImmutableSchemaClass(session);
    if (cls != null && !cls.isAbstract()) {
      throw new DatabaseException(session,
          "Embedded entities can be only of abstract classes. Provided class : " + cls.getName(
          ) + " is not abstract");
    }
    if (isVertex() || isStatefulEdge()) {
      throw new DatabaseException(session.getDatabaseName(),
          "Vertices or Edges cannot be stored as embedded");
    }
  }

  public void clearSystemProps() {
    checkForBinding();
    checkForProperties();

    for (var prop : getPropertyNamesInternal(true, false)) {
      if (isSystemProperty(prop)) {
        removePropertyInternal(prop);
      }
    }
  }

  public void markAllLinksAsChanged() {
    checkForBinding();
    checkForProperties();

    var dirty = false;
    for (var rawEntry : getRawEntries()) {
      final var value = rawEntry.getValue();

      if (value.type.isLink()) {
        value.markCreated();
        value.markChanged();
        dirty = true;
      }
    }
    if (dirty) {
      setDirty();
    }
  }
}
