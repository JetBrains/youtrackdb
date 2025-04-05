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

import static com.jetbrains.youtrack.db.api.config.GlobalConfiguration.DB_CUSTOM_SUPPORT;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.ClassIndexManager;
import com.jetbrains.youtrack.db.internal.core.iterator.EmptyMapEntryIterator;
import com.jetbrains.youtrack.db.internal.core.metadata.MetadataInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Identity;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyAccess;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.record.RecordSchemaAware;
import com.jetbrains.youtrack.db.internal.core.record.RecordVersionHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLPredicate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Entity representation to handle values dynamically. Can be used in schema-less, schema-mixed and
 * schema-full modes. Fields can be added at run-time. Instances can be reused across calls by using
 * the reset() before to re-use.
 */
@SuppressWarnings({"unchecked"})
public class EntityImpl extends RecordAbstract
    implements Iterable<Entry<String, Object>>,
    RecordSchemaAware,
    EntityInternal {

  public static final byte RECORD_TYPE = 'd';
  private static final String[] EMPTY_STRINGS = new String[]{};
  private int fieldSize;

  protected Map<String, EntityEntry> fields;

  private boolean trackingChanges = true;
  protected boolean ordered = true;
  private boolean lazyLoad = true;
  private boolean allowChainedAccess = true;
  protected transient WeakReference<RecordElement> owner = null;

  protected ImmutableSchema schema;
  private String className;
  private SchemaImmutableClass immutableClazz;
  private int immutableSchemaVersion = 1;
  PropertyAccess propertyAccess;
  PropertyEncryption propertyEncryption;

  /**
   * Internal constructor used on unmarshalling.
   */
  public EntityImpl() {
    setup(DatabaseRecordThreadLocal.instance().getIfDefined());
  }

  /**
   * Internal constructor used on unmarshalling.
   */
  public EntityImpl(DatabaseSessionInternal database) {
    assert database == null || database.assertIfNotActive();
    setup(database);
  }

  public EntityImpl(DatabaseSession database, RecordId rid) {
    setup((DatabaseSessionInternal) database);
    this.recordId = rid.copy();
  }

  /**
   * Creates a new instance by the raw stream usually read from the database. New instances are not
   * persistent until {@link #save()} is called.
   *
   * @param iSource Raw stream
   */
  @Deprecated
  public EntityImpl(final byte[] iSource) {
    source = iSource;
    setup(DatabaseRecordThreadLocal.instance().getIfDefined());
  }

  /**
   * Creates a new instance by the raw stream usually read from the database. New instances are not
   * persistent until {@link #save()} is called.
   *
   * @param iSource Raw stream as InputStream
   */
  public EntityImpl(final InputStream iSource) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    IOUtils.copyStream(iSource, out);
    source = out.toByteArray();
    setup(DatabaseRecordThreadLocal.instance().getIfDefined());
  }

  /**
   * Creates a new instance in memory linked by the Record Id to the persistent one. New instances
   * are not persistent until {@link #save()} is called.
   *
   * @param recordId Record Id
   */
  public EntityImpl(final RecordId recordId) {
    setup(DatabaseRecordThreadLocal.instance().getIfDefined());
    this.recordId = recordId.copy();
    status = STATUS.NOT_LOADED;
    dirty = false;
    contentChanged = false;
  }

  /**
   * Creates a new instance in memory of the specified class, linked by the Record Id to the
   * persistent one. New instances are not persistent until {@link #save()} is called.
   *
   * @param iClassName Class name
   * @param recordId   Record Id
   */
  public EntityImpl(final String iClassName, final RecordId recordId) {
    this(iClassName);

    this.recordId = recordId.copy();

    final DatabaseSessionInternal database = getSession();
    if (this.recordId.getClusterId() > -1) {
      final Schema schema = database.getMetadata().getImmutableSchemaSnapshot();
      final SchemaClass cls = schema.getClassByClusterId(this.recordId.getClusterId());
      if (cls != null && !cls.getName().equals(iClassName)) {
        throw new IllegalArgumentException(
            "Cluster id does not correspond class name should be "
                + iClassName
                + " but found "
                + cls.getName());
      }
    }

    dirty = false;
    contentChanged = false;
    status = STATUS.NOT_LOADED;
  }

  /**
   * Creates a new instance in memory of the specified class. New instances are not persistent until
   * {@link #save()} is called.
   *
   * @param iClassName Class name
   */
  public EntityImpl(final String iClassName) {
    setup(DatabaseRecordThreadLocal.instance().getIfDefined());
    setClassName(iClassName);
  }

  public EntityImpl(final String iClassName, DatabaseSessionInternal session) {
    assert session == null || session.assertIfNotActive();
    setup(session);
    setClassName(iClassName);
  }

  /**
   * Creates a new instance in memory of the specified class. New instances are not persistent until
   * {@link #save()} is called.
   *
   * @param session    the session the instance will be attached to
   * @param iClassName Class name
   */
  public EntityImpl(DatabaseSessionInternal session, final String iClassName) {
    assert session == null || session.assertIfNotActive();
    setup(session);
    setClassName(iClassName);
  }

  /**
   * Creates a new instance in memory of the specified schema class. New instances are not
   * persistent until {@link #save()} is called. The database reference is taken from the thread
   * local.
   *
   * @param iClass SchemaClass instance
   */
  public EntityImpl(final SchemaClass iClass) {
    this(iClass != null ? iClass.getName() : null);
  }

  /**
   * Fills a entity passing the field array in form of pairs of field name and value.
   *
   * @param iFields Array of field pairs
   */
  public EntityImpl(final Object[] iFields) {
    this(DEFAULT_CLASS_NAME);

    setup(DatabaseRecordThreadLocal.instance().getIfDefined());
    if (iFields != null && iFields.length > 0) {
      for (int i = 0; i < iFields.length; i += 2) {
        field(iFields[i].toString(), iFields[i + 1]);
      }
    }
  }

  /**
   * Fills a entity passing a map of key/values where the key is the field name and the value the
   * field's value.
   *
   * @param iFieldMap Map of Object/Object
   */
  public EntityImpl(final Map<?, Object> iFieldMap) {
    setup(DatabaseRecordThreadLocal.instance().getIfDefined());
    if (iFieldMap != null && !iFieldMap.isEmpty()) {
      for (Entry<?, Object> entry : iFieldMap.entrySet()) {
        field(entry.getKey().toString(), entry.getValue());
      }
    }
  }

  /**
   * Fills a entity passing the field names/values pair, where the first pair is mandatory.
   */
  public EntityImpl(final String iFieldName, final Object iFieldValue, final Object... iFields) {
    this(iFields);
    field(iFieldName, iFieldValue);
  }

  @Override
  public Optional<Vertex> asVertex() {
    if (this instanceof Vertex) {
      return Optional.of((Vertex) this);
    }
    SchemaClass type = this.getImmutableSchemaClass();

    if (type == null) {
      return Optional.empty();
    }
    if (type.isVertexType(getSession())) {
      return Optional.of(new VertexDelegate(this));
    }
    return Optional.empty();
  }

  @Override
  public @Nullable Vertex toVertex() {
    if (this instanceof Vertex vertex) {
      return vertex;
    }

    SchemaClass type = this.getImmutableSchemaClass();
    if (type == null) {
      return null;
    }
    if (type.isVertexType(getSession())) {
      return new VertexDelegate(this);
    }
    return null;
  }

  @Override
  public Optional<Edge> asEdge() {
    if (this instanceof Edge) {
      return Optional.of((Edge) this);
    }
    SchemaClass type = this.getImmutableSchemaClass();
    if (type == null) {
      return Optional.empty();
    }
    if (type.isEdgeType(getSession())) {
      return Optional.of(new EdgeDelegate(this));
    }
    return Optional.empty();
  }

  @Override
  public @Nullable Edge toEdge() {
    if (this instanceof Edge edge) {
      return edge;
    }
    SchemaClass type = this.getImmutableSchemaClass();
    if (type == null) {
      return null;
    }
    if (type.isEdgeType(getSession())) {
      return new EdgeDelegate(this);
    }

    return null;
  }

  @Override
  public boolean isVertex() {
    if (this instanceof Vertex) {
      return true;
    }

    SchemaClass type = this.getImmutableSchemaClass();
    if (type == null) {
      return false;
    }

    return type.isVertexType(getSession());
  }

  @Override
  public boolean isEdge() {
    if (this instanceof Edge) {
      return true;
    }

    SchemaClass type = this.getImmutableSchemaClass();
    if (type == null) {
      return false;
    }

    return type.isEdgeType(getSession());
  }

  @Override
  public Optional<SchemaClass> getSchemaType() {
    checkForBinding();
    return Optional.ofNullable(getImmutableSchemaClass());
  }

  Set<String> calculatePropertyNames() {
    checkForBinding();

    var session = getSessionIfDefined();
    if (status == RecordElement.STATUS.LOADED
        && source != null
        && session != null
        && !session.isClosed()) {
      assert session.assertIfNotActive();
      // DESERIALIZE FIELD NAMES ONLY (SUPPORTED ONLY BY BINARY SERIALIZER)
      final String[] fieldNames = recordFormat.getFieldNames(session, this, source);
      if (fieldNames != null) {
        Set<String> fields = new LinkedHashSet<>();
        if (propertyAccess != null && propertyAccess.hasFilters()) {
          for (String fieldName : fieldNames) {
            if (propertyAccess.isReadable(fieldName)) {
              fields.add(fieldName);
            }
          }
        } else {

          Collections.addAll(fields, fieldNames);
        }
        return fields;
      }
    }

    checkForFields();

    if (fields == null || fields.isEmpty()) {
      return Collections.emptySet();
    }

    Set<String> fields = new LinkedHashSet<>();
    if (propertyAccess != null && propertyAccess.hasFilters()) {
      for (Map.Entry<String, EntityEntry> entry : this.fields.entrySet()) {
        if (entry.getValue().exists() && propertyAccess.isReadable(entry.getKey())) {
          fields.add(entry.getKey());
        }
      }
    } else {
      for (Map.Entry<String, EntityEntry> entry : this.fields.entrySet()) {
        if (entry.getValue().exists()) {
          fields.add(entry.getKey());
        }
      }
    }

    return fields;
  }

  @Override
  public Set<String> getPropertyNames() {
    return getPropertyNamesInternal();
  }

  @Override
  public Set<String> getPropertyNamesInternal() {
    return calculatePropertyNames();
  }

  /**
   * retrieves a property value from the current entity
   *
   * @param fieldName The field name, it can contain any character (it's not evaluated as an
   *                  expression, as in #eval()
   * @return the field value. Null if the field does not exist.
   */
  public <RET> RET getProperty(final String fieldName) {
    return getPropertyInternal(fieldName);
  }

  @Override
  public <RET> RET getPropertyInternal(String name) {
    return getPropertyInternal(name, isLazyLoad());
  }

  @Override
  public <RET> RET getPropertyInternal(String name, boolean lazyLoad) {
    if (name == null) {
      return null;
    }

    checkForBinding();
    RET value = (RET) DocumentHelper.getIdentifiableValue(this, name);
    if (!name.startsWith("@")
        && lazyLoad
        && value instanceof RID rid
        && (rid.isPersistent() || rid.isNew())
        && DatabaseRecordThreadLocal.instance().isDefined()) {
      // CREATE THE ENTITY OBJECT IN LAZY WAY
      var db = getSession();
      try {
        RET newValue = db.load((RID) value);

        unTrack(rid);
        track((Identifiable) newValue);
        value = newValue;
        if (trackingChanges) {
          RecordInternal.setDirtyManager((DBRecord) value, this.getDirtyManager());
        }
        EntityEntry entry = fields.get(name);
        entry.disableTracking(this, entry.value);
        entry.value = value;
        entry.enableTracking(this);
      } catch (RecordNotFoundException e) {
        return null;
      }
    }

    return convertToGraphElement(value);
  }

  @Override
  public <RET> RET getPropertyOnLoadValue(String name) {
    checkForBinding();

    Objects.requireNonNull(name, "Name argument is required.");
    VertexInternal.checkPropertyName(name);

    checkForFields();

    var field = fields.get(name);
    if (field != null) {
      RET onLoadValue = (RET) field.getOnLoadValue(getSession());
      if (onLoadValue instanceof RidBag) {
        throw new IllegalArgumentException(
            "getPropertyOnLoadValue(name) is not designed to work with Edge properties");
      }
      if (onLoadValue instanceof RID orid) {
        if (isLazyLoad()) {
          try {
            return getSession().load(orid);
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
    if (value instanceof Entity) {
      if (((Entity) value).isVertex()) {
        value = (RET) ((Entity) value).toVertex();
      } else {
        if (((Entity) value).isEdge()) {
          value = (RET) ((Entity) value).toEdge();
        }
      }
    }
    return value;
  }

  /**
   * This method similar to {@link #getProperty(String)} but unlike before mentioned method it does
   * not load link automatically.
   *
   * @param fieldName the name of the link property
   * @return the link property value, or null if the property does not exist
   * @throws IllegalArgumentException if requested property is not a link.
   * @see #getProperty(String)
   */
  @Nullable
  @Override
  public Identifiable getLinkProperty(String fieldName) {
    return getLinkPropertyInternal(fieldName);
  }

  @Nullable
  @Override
  public Identifiable getLinkPropertyInternal(String name) {
    if (name == null) {
      return null;
    }

    var result = accessProperty(name);
    if (result == null) {
      return null;
    }

    if (!(result instanceof Identifiable identifiable)
        || (result instanceof EntityImpl entity && entity.isEmbedded())) {
      throw new IllegalArgumentException("Requested property " + name + " is not a link.");
    }

    var id = identifiable.getIdentity();
    if (!(id.isPersistent() || id.isNew())) {
      throw new IllegalArgumentException("Requested property " + name + " is not a link.");
    }

    return (Identifiable) convertToGraphElement(result);
  }

  /**
   * retrieves a property value from the current entity, without evaluating it (eg. no conversion
   * from RID to entity)
   *
   * @param iFieldName The field name, it can contain any character (it's not evaluated as an
   *                   expression, as in #eval()
   * @return the field value. Null if the field does not exist.
   */
  <RET> RET getRawProperty(final String iFieldName) {
    checkForBinding();

    if (iFieldName == null) {
      return null;
    }

    return (RET) DocumentHelper.getIdentifiableValue(this, iFieldName);
  }

  /**
   * sets a property value on current entity
   *
   * @param iFieldName     The property name
   * @param iPropertyValue The property value
   */
  public void setProperty(final String iFieldName, Object iPropertyValue) {
    setPropertyInternal(iFieldName, iPropertyValue);
  }

  @Override
  public void setPropertyInternal(String name, Object value) {
    if (value instanceof Entity entity
        && entity.getSchemaClass() == null
        && !((RecordId) entity.getIdentity()).isValid()) {
      setProperty(name, value, PropertyType.EMBEDDED);
    } else {
      setPropertyInternal(name, value, CommonConst.EMPTY_TYPES_ARRAY);
    }
  }

  /**
   * Sets
   *
   * @param name  The property name
   * @param value The property value
   * @param types Forced type (not auto-determined)
   */
  public void setProperty(String name, Object value, PropertyType... types) {
    setPropertyInternal(name, value, types);
  }

  @Override
  public void setPropertyInternal(String name, Object value, PropertyType... type) {
    checkForBinding();

    if (name == null) {
      throw new IllegalArgumentException("Field is null");
    }

    if (name.isEmpty()) {
      throw new IllegalArgumentException("Field name is empty");
    }

    final char begin = name.charAt(0);
    if (begin == '@') {
      switch (name.toLowerCase(Locale.ROOT)) {
        case DocumentHelper.ATTRIBUTE_CLASS -> {
          setClassName(value.toString());
          return;
        }
        case DocumentHelper.ATTRIBUTE_RID -> {
          if (status == STATUS.UNMARSHALLING) {
            recordId = new RecordId(value.toString());
          } else {
            throw new DatabaseException(
                "Attribute " + DocumentHelper.ATTRIBUTE_RID + " is read-only");
          }

        }
        case DocumentHelper.ATTRIBUTE_VERSION -> {
          if (status == STATUS.UNMARSHALLING) {
            setVersion(Integer.parseInt(value.toString()));
          }
          throw new DatabaseException(
              "Attribute " + DocumentHelper.ATTRIBUTE_VERSION + " is read-only");
        }
      }
    }

    checkForFields();

    EntityEntry entry = fields.get(name);
    final boolean knownProperty;
    final Object oldValue;
    final PropertyType oldType;
    if (entry == null) {
      entry = new EntityEntry();
      fieldSize++;
      fields.put(name, entry);
      entry.markCreated();
      knownProperty = false;
      oldValue = null;
      oldType = null;
    } else {
      knownProperty = entry.exists();
      oldValue = entry.value;
      oldType = entry.type;
    }

    PropertyType fieldType = deriveFieldType(name, entry, type);
    if (value != null && fieldType != null) {
      value = DocumentHelper.convertField(getSessionIfDefined(), this, name, fieldType, null,
          value);
    } else {
      if (value instanceof Enum) {
        value = value.toString();
      }
    }

    if (knownProperty)

    // CHECK IF IS REALLY CHANGED
    {
      if (value == null) {
        if (oldValue == null)
        // BOTH NULL: UNCHANGED
        {
          return;
        }
      } else {

        try {
          if (value.equals(oldValue)) {
            if (fieldType == oldType) {
              if (!(value instanceof RecordElement))
              // SAME BUT NOT TRACKABLE: SET THE RECORD AS DIRTY TO BE SURE IT'S SAVED
              {
                setDirty();
              }

              // SAVE VALUE: UNCHANGED
              return;
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
    }

    if (oldValue instanceof
        RidBag ridBag) {
      ridBag.setOwner(null);
    } else {
      if (oldValue instanceof EntityImpl) {
        ((EntityImpl) oldValue).removeOwner(this);
      }
    }

    if (oldValue instanceof Identifiable) {
      unTrack((Identifiable) oldValue);
    }

    if (value != null) {
      if (value instanceof EntityImpl) {
        if (PropertyType.EMBEDDED.equals(fieldType)) {
          final EntityImpl embeddedEntity = (EntityImpl) value;
          EntityInternalUtils.addOwner(embeddedEntity, this);
        }
      }
      if (value instanceof Identifiable) {
        track((Identifiable) value);
      }

      if (value instanceof RidBag ridBag) {
        ridBag.setOwner(
            null); // in order to avoid IllegalStateException when ridBag changes the owner
        ridBag.setOwner(this);
        ridBag.setRecordAndField(recordId, name);
      }
    }

    if (fieldType == PropertyType.CUSTOM) {
      if (!DB_CUSTOM_SUPPORT.getValueAsBoolean()) {
        throw new DatabaseException(
            String.format(
                "PropertyType CUSTOM used by serializable types, for value  '%s' is not enabled, set"
                    + " `db.custom.support` to true for enable it",
                value));
      }
    }
    if (oldType != fieldType && oldType != null) {
      // can be made in a better way, but "keeping type" issue should be solved before
      if (value == null || fieldType != null || oldType != PropertyType.getTypeByValue(value)) {
        entry.type = fieldType;
      }
    }
    entry.disableTracking(this, oldValue);
    entry.value = value;
    if (!entry.exists()) {
      entry.setExists(true);
      fieldSize++;
    }
    entry.enableTracking(this);

    setDirty();
    if (!entry.isChanged()) {
      entry.original = oldValue;
      entry.markChanged();
    }
  }

  public <RET> RET removeProperty(final String iFieldName) {
    return removePropertyInternal(iFieldName);
  }

  @Override
  public <RET> RET removePropertyInternal(String name) {
    checkForBinding();
    checkForFields();

    if (DocumentHelper.ATTRIBUTE_CLASS.equalsIgnoreCase(name)) {
      setClassName(null);
    } else {
      if (DocumentHelper.ATTRIBUTE_RID.equalsIgnoreCase(name)) {
        throw new DatabaseException(
            "Attribute " + DocumentHelper.ATTRIBUTE_RID + " is read-only");
      } else if (DocumentHelper.ATTRIBUTE_VERSION.equalsIgnoreCase(name)) {
        if (DocumentHelper.ATTRIBUTE_VERSION.equalsIgnoreCase(name)) {
          throw new DatabaseException(
              "Attribute " + DocumentHelper.ATTRIBUTE_VERSION + " is read-only");
        }
      }
    }

    final EntityEntry entry = fields.get(name);
    if (entry == null) {
      return null;
    }

    Object oldValue = entry.value;

    if (entry.exists() && trackingChanges) {
      // SAVE THE OLD VALUE IN A SEPARATE MAP
      if (entry.original == null) {
        entry.original = entry.value;
      }
      entry.value = null;
      entry.setExists(false);
      entry.markChanged();
    } else {
      fields.remove(name);
    }
    fieldSize--;
    entry.disableTracking(this, oldValue);
    if (oldValue instanceof Identifiable) {
      unTrack((Identifiable) oldValue);
    }
    if (oldValue instanceof RidBag) {
      ((RidBag) oldValue).setOwner(null);
    }

    setDirty();
    return (RET) oldValue;
  }

  private static void validateFieldsSecurity(DatabaseSessionInternal internal,
      EntityImpl iRecord)
      throws ValidationException {
    if (internal == null) {
      return;
    }

    iRecord.checkForBinding();
    iRecord = (EntityImpl) iRecord.getRecord();

    SecurityInternal security = internal.getSharedContext().getSecurity();
    for (Entry<String, EntityEntry> mapEntry : iRecord.fields.entrySet()) {
      EntityEntry entry = mapEntry.getValue();
      if (entry != null && (entry.isTxChanged() || entry.isTxTrackedModified())) {
        if (!security.isAllowedWrite(internal, iRecord, mapEntry.getKey())) {
          throw new SecurityException(
              String.format(
                  "Change of field '%s' is not allowed for user '%s'",
                  iRecord.getClassName() + "." + mapEntry.getKey(),
                  internal.geCurrentUser().getName(internal)));
        }
      }
    }
  }

  private static void validateField(
      DatabaseSessionInternal session, ImmutableSchema schema, EntityImpl iRecord,
      ImmutableSchemaProperty p)
      throws ValidationException {
    iRecord.checkForBinding();
    iRecord = (EntityImpl) iRecord.getRecord();

    final Object fieldValue;
    EntityEntry entry = iRecord.fields.get(p.getName());
    if (entry != null && entry.exists()) {
      // AVOID CONVERSIONS: FASTER!
      fieldValue = entry.value;

      if (p.isNotNull() && fieldValue == null)
      // NULLITY
      {
        throw new ValidationException(
            "The field '" + p.getFullName() + "' cannot be null, record: " + iRecord);
      }

      if (fieldValue != null && p.getRegexp() != null && p.getType().equals(PropertyType.STRING)) {
        // REGEXP
        if (!((String) fieldValue).matches(p.getRegexp())) {
          throw new ValidationException(
              "The field '"
                  + p.getFullName()
                  + "' does not match the regular expression '"
                  + p.getRegexp()
                  + "'. Field value is: "
                  + fieldValue
                  + ", record: "
                  + iRecord);
        }
      }

    } else {
      if (p.isMandatory()) {
        throw new ValidationException(
            "The field '"
                + p.getFullName()
                + "' is mandatory, but not found on record: "
                + iRecord);
      }
      fieldValue = null;
    }

    final PropertyType type = p.getType();

    if (fieldValue != null && type != null) {
      // CHECK TYPE
      switch (type) {
        case LINK:
          validateLink(session, schema, p, fieldValue, false);
          break;
        case LINKLIST:
          if (!(fieldValue instanceof List)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as LINKLIST but an incompatible type is used. Value: "
                    + fieldValue);
          }
          validateLinkCollection(session, schema, p, (Collection<Object>) fieldValue, entry);
          break;
        case LINKSET:
          if (!(fieldValue instanceof Set)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as LINKSET but an incompatible type is used. Value: "
                    + fieldValue);
          }
          validateLinkCollection(session, schema, p, (Collection<Object>) fieldValue, entry);
          break;
        case LINKMAP:
          if (!(fieldValue instanceof Map)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as LINKMAP but an incompatible type is used. Value: "
                    + fieldValue);
          }
          validateLinkCollection(session, schema, p, ((Map<?, Object>) fieldValue).values(), entry);
          break;

        case LINKBAG:
          if (!(fieldValue instanceof RidBag)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as LINKBAG but an incompatible type is used. Value: "
                    + fieldValue);
          }
          validateLinkCollection(session, schema, p, (Iterable<Object>) fieldValue, entry);
          break;
        case EMBEDDED:
          validateEmbedded(session, p, fieldValue);
          break;
        case EMBEDDEDLIST:
          if (!(fieldValue instanceof List)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as EMBEDDEDLIST but an incompatible type is used. Value:"
                    + " "
                    + fieldValue);
          }
          if (p.getLinkedClass(session) != null) {
            for (Object item : ((List<?>) fieldValue)) {
              validateEmbedded(session, p, item);
            }
          } else {
            if (p.getLinkedType() != null) {
              for (Object item : ((List<?>) fieldValue)) {
                validateType(session, p, item);
              }
            }
          }
          break;
        case EMBEDDEDSET:
          if (!(fieldValue instanceof Set)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as EMBEDDEDSET but an incompatible type is used. Value: "
                    + fieldValue);
          }
          if (p.getLinkedClass(session) != null) {
            for (Object item : ((Set<?>) fieldValue)) {
              validateEmbedded(session, p, item);
            }
          } else {
            if (p.getLinkedType() != null) {
              for (Object item : ((Set<?>) fieldValue)) {
                validateType(session, p, item);
              }
            }
          }
          break;
        case EMBEDDEDMAP:
          if (!(fieldValue instanceof Map)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as EMBEDDEDMAP but an incompatible type is used. Value: "
                    + fieldValue);
          }
          if (p.getLinkedClass(session) != null) {
            for (Entry<?, ?> colleEntry : ((Map<?, ?>) fieldValue).entrySet()) {
              validateEmbedded(session, p, colleEntry.getValue());
            }
          } else {
            if (p.getLinkedType() != null) {
              for (Entry<?, ?> collEntry : ((Map<?, ?>) fieldValue).entrySet()) {
                validateType(session, p, collEntry.getValue());
              }
            }
          }
          break;
      }
    }

    if (p.getMin() != null && fieldValue != null) {
      // MIN
      final String min = p.getMin();
      if (p.getMinComparable().compareTo(fieldValue) > 0) {
        switch (p.getType()) {
          case STRING:
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' contains fewer characters than "
                    + min
                    + " requested");
          case DATE:
          case DATETIME:
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' contains the date "
                    + fieldValue
                    + " which precedes the first acceptable date ("
                    + min
                    + ")");
          case BINARY:
            throw new ValidationException(
                "The field '"
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
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' contains fewer items than "
                    + min
                    + " requested");
          default:
            throw new ValidationException(
                "The field '" + p.getFullName() + "' is less than " + min);
        }
      }
    }

    if (p.getMaxComparable() != null && fieldValue != null) {
      final String max = p.getMax();
      if (p.getMaxComparable().compareTo(fieldValue) < 0) {
        switch (p.getType()) {
          case STRING:
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' contains more characters than "
                    + max
                    + " requested");
          case DATE:
          case DATETIME:
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' contains the date "
                    + fieldValue
                    + " which is after the last acceptable date ("
                    + max
                    + ")");
          case BINARY:
            throw new ValidationException(
                "The field '"
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
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' contains more items than "
                    + max
                    + " requested");
          default:
            throw new ValidationException(
                "The field '" + p.getFullName() + "' is greater than " + max);
        }
      }
    }

    if (p.isReadonly() && !RecordVersionHelper.isTombstone(iRecord.getVersion())) {
      if (entry != null
          && (entry.isTxChanged() || entry.isTxTrackedModified())
          && !entry.isTxCreated()) {
        // check if the field is actually changed by equal.
        // this is due to a limitation in the merge algorithm used server side marking all
        // non-simple fields as dirty
        Object orgVal = entry.getOnLoadValue(session);
        boolean simple =
            fieldValue != null ? PropertyType.isSimpleType(fieldValue)
                : PropertyType.isSimpleType(orgVal);
        if ((simple)
            || (fieldValue != null && orgVal == null)
            || (fieldValue == null && orgVal != null)
            || (fieldValue != null && !fieldValue.equals(orgVal))) {
          throw new ValidationException(
              "The field '"
                  + p.getFullName()
                  + "' is immutable and cannot be altered. Field value is: "
                  + entry.value);
        }
      }
    }
  }

  private static void validateLinkCollection(
      DatabaseSession session,
      ImmutableSchema schema,
      final SchemaProperty property,
      Iterable<Object> values,
      EntityEntry value) {
    if (property.getLinkedClass(session) != null) {
      if (value.getTimeLine() != null) {
        List<MultiValueChangeEvent<Object, Object>> event =
            value.getTimeLine().getMultiValueChangeEvents();
        for (var object : event) {
          if (object.getChangeType() == ChangeType.ADD
              || object.getChangeType() == ChangeType.UPDATE
              && object.getValue() != null) {
            validateLink(session, schema, property, object.getValue(), true);
          }
        }
      } else {
        for (Object object : values) {
          validateLink(session, schema, property, object, true);
        }
      }
    }
  }

  private static void validateType(DatabaseSessionInternal session, final SchemaProperty p,
      final Object value) {
    if (value != null) {
      if (PropertyType.convert(session, value, p.getLinkedType().getDefaultJavaType()) == null) {
        throw new ValidationException(
            "The field '"
                + p.getFullName()
                + "' has been declared as "
                + p.getType()
                + " of type '"
                + p.getLinkedType()
                + "' but the value is "
                + value);
      }
    }
  }

  private static void validateLink(
      DatabaseSession session,
      ImmutableSchema schema,
      final SchemaProperty p,
      final Object fieldValue,
      boolean allowNull
  ) {
    if (fieldValue == null) {
      if (allowNull) {
        return;
      } else {
        throw new ValidationException(
            "The field '"
                + p.getFullName()
                + "' has been declared as "
                + p.getType()
                + " but contains a null record (probably a deleted record?)");
      }
    }

    if (!(fieldValue instanceof Identifiable)) {
      throw new ValidationException(
          "The field '"
              + p.getFullName()
              + "' has been declared as "
              + p.getType()
              + " but the value is not a record or a record-id");
    }

    final SchemaClass schemaClass = p.getLinkedClass(session);
    if (schemaClass != null && !schemaClass.isSubClassOf(session, Identity.CLASS_NAME)) {
      // DON'T VALIDATE OUSER AND OROLE FOR SECURITY RESTRICTIONS
      var identifiable = (Identifiable) fieldValue;
      final RID rid = identifiable.getIdentity();
      if (!schemaClass.hasPolymorphicClusterId(rid.getClusterId())) {
        // AT THIS POINT CHECK THE CLASS ONLY IF != NULL BECAUSE IN CASE OF GRAPHS THE RECORD
        // COULD BE PARTIAL
        SchemaClass cls;
        var clusterId = rid.getClusterId();
        if (clusterId != RID.CLUSTER_ID_INVALID) {
          cls = schema.getClassByClusterId(rid.getClusterId());
        } else if (identifiable instanceof Entity element) {
          cls = element.getSchemaClass();
        } else {
          cls = null;
        }

        if (cls != null && !schemaClass.isSuperClassOf(session, cls)) {
          throw new ValidationException(
              "The field '"
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

  private static void validateEmbedded(DatabaseSession session, final SchemaProperty p,
      final Object fieldValue) {
    if (fieldValue == null) {
      return;
    }
    if (fieldValue instanceof RecordId) {
      throw new ValidationException(
          "The field '"
              + p.getFullName()
              + "' has been declared as "
              + p.getType()
              + " but the value is the RecordID "
              + fieldValue);
    } else {
      if (fieldValue instanceof Identifiable embedded) {
        if (((RecordId) embedded.getIdentity()).isValid()) {
          throw new ValidationException(
              "The field '"
                  + p.getFullName()
                  + "' has been declared as "
                  + p.getType()
                  + " but the value is a entity with the valid RecordID "
                  + fieldValue);
        }

        final DBRecord embeddedRecord = embedded.getRecord();
        if (embeddedRecord instanceof EntityImpl entity) {
          final SchemaClass embeddedClass = p.getLinkedClass(session);
          if (entity.isVertex()) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record is of class '"
                    + entity.getImmutableSchemaClass().getName()
                    + "' that is vertex class");
          }

          if (entity.isEdge()) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record is of class '"
                    + entity.getImmutableSchemaClass().getName()
                    + "' that is edge class");
          }
        }

        final SchemaClass embeddedClass = p.getLinkedClass(session);
        if (embeddedClass != null) {

          if (!(embeddedRecord instanceof EntityImpl entity)) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record was not a entity");
          }

          if (entity.getImmutableSchemaClass() == null) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record has no class");
          }

          if (!(entity.getImmutableSchemaClass().isSubClassOf(session, embeddedClass))) {
            throw new ValidationException(
                "The field '"
                    + p.getFullName()
                    + "' has been declared as "
                    + p.getType()
                    + " with linked class '"
                    + embeddedClass
                    + "' but the record is of class '"
                    + entity.getImmutableSchemaClass().getName()
                    + "' that is not a subclass of that");
          }

          entity.validate();
        }

      } else {
        throw new ValidationException(
            "The field '"
                + p.getFullName()
                + "' has been declared as "
                + p.getType()
                + " but an incompatible type is used. Value: "
                + fieldValue);
      }
    }
  }

  /**
   * Copies the current instance to a new one. Hasn't been choose the clone() to let EntityImpl
   * return type. Once copied the new instance has the same identity and values but all the internal
   * structure are totally independent by the source.
   */
  public EntityImpl copy() {
    checkForBinding();

    var entity = new EntityImpl();
    RecordInternal.unsetDirty(entity);
    var newEntity = (EntityImpl) copyTo(entity);
    newEntity.dirty = true;

    return newEntity;
  }

  public EntityImpl copy(DatabaseSessionInternal session) {
    var newEntity = copy();
    newEntity.setup(session);

    return newEntity;
  }

  /**
   * Copies all the fields into iDestination entity.
   */
  @Override
  public final RecordAbstract copyTo(final RecordAbstract iDestination) {
    checkForBinding();

    if (iDestination.isDirty()) {
      throw new DatabaseException("Cannot copy to dirty records");
    }

    checkForFields();

    EntityImpl destination = (EntityImpl) iDestination;

    super.copyTo(iDestination);

    destination.ordered = ordered;

    destination.className = className;
    destination.immutableSchemaVersion = -1;
    destination.immutableClazz = null;

    destination.trackingChanges = trackingChanges;
    destination.owner = owner;

    if (fields != null) {
      destination.fields =
          fields instanceof LinkedHashMap ? new LinkedHashMap<>() : new HashMap<>();
      for (Entry<String, EntityEntry> entry : fields.entrySet()) {
        var originalEntry = entry.getValue();
        EntityEntry entityEntry = originalEntry.clone();
        destination.fields.put(entry.getKey(), entityEntry);
        entityEntry.value = DocumentHelper.cloneValue(getSession(), destination,
            entry.getValue().value);
      }
    } else {
      destination.fields = null;
    }
    destination.fieldSize = fieldSize;
    destination.addAllMultiValueChangeListeners();

    destination.dirty = dirty; // LEAVE IT AS LAST TO AVOID SOMETHING SET THE FLAG TO TRUE
    destination.contentChanged = contentChanged;

    var dirtyManager = new DirtyManager();
    if (dirty) {
      dirtyManager.setDirty(this);
    }

    destination.dirtyManager = dirtyManager;

    return destination;
  }

  public boolean hasSameContentOf(final EntityImpl iOther) {
    iOther.checkForBinding();
    checkForBinding();

    final DatabaseSessionInternal currentDb = DatabaseRecordThreadLocal.instance()
        .getIfDefined();
    return DocumentHelper.hasSameContentOf(this, currentDb, iOther, currentDb, null);
  }

  @Override
  public byte[] toStream() {
    checkForBinding();
    if (recordFormat == null) {
      setup(DatabaseRecordThreadLocal.instance().getIfDefined());
    }

    STATUS prev = status;
    status = STATUS.MARSHALLING;
    try {
      if (source == null) {
        source = recordFormat.toStream(getSession(), this);
      }
    } finally {
      status = prev;
    }

    return source;
  }

  /**
   * Returns the entity as Map String,Object . If the entity has identity, then the @rid entry is
   * valued. If the entity has a class, then the @class entry is valued.
   *
   * @since 2.0
   */
  public Map<String, Object> toMap() {
    checkForBinding();
    final Map<String, Object> map = new HashMap<>();
    for (String field : fieldNames()) {
      map.put(field, field(field));
    }

    final RecordId id = getIdentity();
    if (id.isValid()) {
      map.put(DocumentHelper.ATTRIBUTE_RID, id);
    }

    final String className = getClassName();
    if (className != null) {
      map.put(DocumentHelper.ATTRIBUTE_CLASS, className);
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
   * Fills the EntityImpl directly with the string representation of the entity itself. Use it for
   * faster insertion but pay attention to respect the YouTrackDB record format.
   *
   * <p><code> record.reset();<br> record.setClassName("Account");<br>
   * record.fromString(new String("Account@id:" + data.getCyclesDone() +
   * ",name:'Luca',surname:'Garulli',birthDate:" + date.getTime()<br> + ",salary:" + 3000f +
   * i));<br> record.save();<br> </code>
   *
   * @param iValue String representation of the record.
   */
  @Deprecated
  public void fromString(final String iValue) {
    incrementLoading();
    try {
      dirty = true;
      contentChanged = true;
      source = iValue.getBytes(StandardCharsets.UTF_8);

      removeAllCollectionChangeListeners();

      fields = null;
      fieldSize = 0;
    } finally {
      decrementLoading();
    }
  }

  /**
   * Returns the set of field names.
   */
  public String[] fieldNames() {
    return calculatePropertyNames().toArray(new String[]{});
  }

  /**
   * Returns the array of field values.
   */
  public Object[] fieldValues() {
    checkForBinding();

    checkForFields();
    final List<Object> res = new ArrayList<>(fields.size());
    for (Map.Entry<String, EntityEntry> entry : fields.entrySet()) {
      if (entry.getValue().exists()
          && (propertyAccess == null || propertyAccess.isReadable(entry.getKey()))) {
        res.add(entry.getValue().value);
      }
    }
    return res.toArray();
  }

  public <RET> RET rawField(final String iFieldName) {
    if (iFieldName == null || iFieldName.isEmpty()) {
      return null;
    }

    checkForBinding();
    if (!checkForFields(iFieldName))
    // NO FIELDS
    {
      return null;
    }

    // OPTIMIZATION
    if (!allowChainedAccess
        || (iFieldName.charAt(0) != '@'
        && StringSerializerHelper.indexOf(iFieldName, 0, '.', '[') == -1)) {
      return (RET) accessProperty(iFieldName);
    }

    // NOT FOUND, PARSE THE FIELD NAME
    return DocumentHelper.getFieldValue(getSession(), this, iFieldName);
  }

  /**
   * Evaluates a SQL expression against current entity. Example: <code> long amountPlusVat =
   * entity.eval("amount * 120 / 100");</code>
   *
   * @param iExpression SQL expression to evaluate.
   * @return The result of expression
   * @throws QueryParsingException in case the expression is not valid
   */
  public Object eval(final String iExpression) {
    checkForBinding();

    var context = new BasicCommandContext();
    context.setDatabase(getSession());

    return eval(iExpression, context);
  }

  /**
   * Evaluates a SQL expression against current entity by passing a context. The expression can
   * refer to the variables contained in the context. Example: <code> CommandContext context = new
   * BasicCommandContext().setVariable("vat", 20); long amountPlusVat = entity.eval("amount *
   * (100+$vat) / 100", context); </code>
   *
   * @param iExpression SQL expression to evaluate.
   * @return The result of expression
   * @throws QueryParsingException in case the expression is not valid
   */
  public Object eval(final String iExpression, @Nonnull final CommandContext iContext) {
    checkForBinding();

    if (iContext.getDatabase() != getSession()) {
      throw new DatabaseException(
          "The context is bound to a different database instance, use the context from the same database instance");
    }

    return new SQLPredicate(iContext, iExpression).evaluate(this, null, iContext);
  }

  /**
   * Reads the field value.
   *
   * @param iFieldName field name
   * @return field value if defined, otherwise null
   */
  @Override
  public <RET> RET field(final String iFieldName) {
    checkForBinding();

    RET value = this.rawField(iFieldName);

    if (!iFieldName.startsWith("@")
        && lazyLoad
        && value instanceof RID
        && (((RID) value).isPersistent() || ((RID) value).isNew())
        && DatabaseRecordThreadLocal.instance().isDefined()) {
      // CREATE THE ENTITY OBJECT IN LAZY WAY
      var db = getSession();
      try {
        RET newValue = db.load((RID) value);
        unTrack((RID) value);
        track((Identifiable) newValue);
        value = newValue;
        if (this.trackingChanges) {
          RecordInternal.setDirtyManager((DBRecord) value, this.getDirtyManager());
        }
        if (!iFieldName.contains(".")) {
          EntityEntry entry = fields.get(iFieldName);
          entry.disableTracking(this, entry.value);
          entry.value = value;
          entry.enableTracking(this);
        }
      } catch (RecordNotFoundException e) {
        return null;
      }
    }
    return value;
  }

  /**
   * Reads the field value forcing the return type. Use this method to force return of RID instead
   * of the entire entity by passing RID.class as iFieldType.
   *
   * @param iFieldName field name
   * @param iFieldType Forced type.
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName, final Class<?> iFieldType) {
    checkForBinding();
    RET value = this.rawField(iFieldName);

    if (value != null) {
      value =
          DocumentHelper.convertField(getSession()
              , this, iFieldName, PropertyType.getTypeByClass(iFieldType), iFieldType, value);
    }

    return value;
  }

  /**
   * Reads the field value forcing the return type. Use this method to force return of binary data.
   *
   * @param iFieldName field name
   * @param iFieldType Forced type.
   * @return field value if defined, otherwise null
   */
  public <RET> RET field(final String iFieldName, final PropertyType iFieldType) {
    checkForBinding();

    var session = getSessionIfDefined();
    RET value = field(iFieldName);
    PropertyType original;
    if (iFieldType != null && iFieldType != (original = fieldType(iFieldName))) {
      // this is needed for the csv serializer that don't give back values
      if (original == null) {
        original = PropertyType.getTypeByValue(value);
        if (iFieldType == original) {
          return value;
        }
      }

      final Object newValue;

      if (iFieldType == PropertyType.BINARY && value instanceof String) {
        newValue = StringSerializerHelper.getBinaryContent(value);
      } else {
        if (iFieldType == PropertyType.DATE && value instanceof Long) {
          newValue = new Date((Long) value);
        } else {
          if ((iFieldType == PropertyType.EMBEDDEDSET || iFieldType == PropertyType.LINKSET)
              && value instanceof List) {
            newValue =
                Collections.unmodifiableSet(
                    (Set<?>)
                        DocumentHelper.convertField(getSession(), this, iFieldName, iFieldType,
                            null,
                            value));
          } else {
            if ((iFieldType == PropertyType.EMBEDDEDLIST || iFieldType == PropertyType.LINKLIST)
                && value instanceof Set) {
              newValue =
                  Collections.unmodifiableList(
                      (List<?>)
                          DocumentHelper.convertField(session, this, iFieldName,
                              iFieldType, null, value));
            } else {
              if ((iFieldType == PropertyType.EMBEDDEDMAP || iFieldType == PropertyType.LINKMAP)
                  && value instanceof Map) {
                newValue =
                    Collections.unmodifiableMap(
                        (Map<?, ?>)
                            DocumentHelper.convertField(session,
                                this, iFieldName, iFieldType, null, value));
              } else {
                newValue = PropertyType.convert(session, value, iFieldType.getDefaultJavaType());
              }
            }
          }
        }
      }

      if (newValue != null) {
        value = (RET) newValue;
      }
    }
    return value;
  }

  /**
   * Writes the field value. This method sets the current entity as dirty.
   *
   * @param iFieldName     field name. If contains dots (.) the change is applied to the nested
   *                       documents in chain. To disable this feature call
   *                       {@link #setAllowChainedAccess(boolean)} to false.
   * @param iPropertyValue field value
   * @return The Record instance itself giving a "fluent interface". Useful to call multiple methods
   * in chain.
   */
  public EntityImpl field(final String iFieldName, Object iPropertyValue) {
    return field(iFieldName, iPropertyValue, CommonConst.EMPTY_TYPES_ARRAY);
  }

  /**
   * Fills a entity passing the field names/values.
   */
  public EntityImpl fields(
      final String iFieldName, final Object iFieldValue, final Object... iFields) {
    checkForBinding();

    if (iFields != null && iFields.length % 2 != 0) {
      throw new IllegalArgumentException("Fields must be passed in pairs as name and value");
    }

    field(iFieldName, iFieldValue);
    if (iFields != null && iFields.length > 0) {
      for (int i = 0; i < iFields.length; i += 2) {
        field(iFields[i].toString(), iFields[i + 1]);
      }
    }
    return this;
  }

  /**
   * Deprecated. Use fromMap(Map) instead.<br> Fills a entity passing the field names/values as a
   * Map String,Object where the keys are the field names and the values are the field values.
   *
   * @see #fromMap(Map)
   */
  @Deprecated
  public EntityImpl fields(final Map<String, Object> iMap) {
    fromMap(iMap);
    return this;
  }

  /**
   * Fills a entity passing the field names/values as a Map String,Object where the keys are the
   * field names and the values are the field values. It accepts also @rid for record id and @class
   * for class name.
   *
   * @since 2.0
   */
  public void fromMap(final Map<String, ?> map) {
    checkForBinding();

    status = STATUS.UNMARSHALLING;
    try {
      if (map != null) {
        for (Entry<String, ?> entry : map.entrySet()) {
          var key = entry.getKey();
          if (key.isEmpty()) {
            continue;
          }
          if (key.charAt(0) == '@') {
            continue;
          }

          setProperty(entry.getKey(), entry.getValue());
        }
      }
    } finally {
      status = STATUS.LOADED;
    }
  }

  public final EntityImpl fromJSON(final String iSource, final String iOptions) {
    return super.fromJSON(iSource, iOptions);
  }

  /**
   * Writes the field value forcing the type. This method sets the current entity as dirty.
   *
   * <p>if there's a schema definition for the specified field, the value will be converted to
   * respect the schema definition if needed. if the type defined in the schema support less
   * precision than the iPropertyValue provided, the iPropertyValue will be converted following the
   * java casting rules with possible precision loss.
   *
   * @param iFieldName     field name. If contains dots (.) the change is applied to the nested
   *                       documents in chain. To disable this feature call
   *                       {@link #setAllowChainedAccess(boolean)} to false.
   * @param iPropertyValue field value.
   * @param iFieldType     Forced type (not auto-determined)
   * @return The Record instance itself giving a "fluent interface". Useful to call multiple methods
   * in chain. If the updated entity is another entity (using the dot (.) notation) then the entity
   * returned is the changed one or NULL if no entity has been found in chain
   */
  public EntityImpl field(String iFieldName, Object iPropertyValue, PropertyType... iFieldType) {
    checkForBinding();

    if (iFieldName == null) {
      throw new IllegalArgumentException("Field is null");
    }

    if (iFieldName.isEmpty()) {
      throw new IllegalArgumentException("Field name is empty");
    }

    switch (iFieldName) {
      case DocumentHelper.ATTRIBUTE_CLASS -> {
        setClassName(iPropertyValue.toString());
        return this;
      }
      case DocumentHelper.ATTRIBUTE_RID -> {
        recordId.fromString(iPropertyValue.toString());
        return this;
      }
      case DocumentHelper.ATTRIBUTE_VERSION -> {
        if (iPropertyValue != null) {
          int v;

          if (iPropertyValue instanceof Number) {
            v = ((Number) iPropertyValue).intValue();
          } else {
            v = Integer.parseInt(iPropertyValue.toString());
          }

          recordVersion = v;
        }
        return this;
      }
    }

    final int lastDotSep = allowChainedAccess ? iFieldName.lastIndexOf('.') : -1;
    final int lastArraySep = allowChainedAccess ? iFieldName.lastIndexOf('[') : -1;

    final int lastSep = Math.max(lastArraySep, lastDotSep);
    final boolean lastIsArray = lastArraySep > lastDotSep;

    if (lastSep > -1) {
      // SUB PROPERTY GET 1 LEVEL BEFORE LAST
      final Object subObject = field(iFieldName.substring(0, lastSep));
      if (subObject != null) {
        final String subFieldName =
            lastIsArray ? iFieldName.substring(lastSep) : iFieldName.substring(lastSep + 1);
        if (subObject instanceof EntityImpl) {
          // SUB-ENTITY
          ((EntityImpl) subObject).field(subFieldName, iPropertyValue);
          return (EntityImpl) (((EntityImpl) subObject).isEmbedded() ? this : subObject);
        } else {
          if (subObject instanceof Map<?, ?>) {
            // KEY/VALUE
            ((Map<String, Object>) subObject).put(subFieldName, iPropertyValue);
          } else {
            if (MultiValue.isMultiValue(subObject)) {
              if ((subObject instanceof List<?> || subObject.getClass().isArray()) && lastIsArray) {
                // List // Array Type with a index subscript.
                final int subFieldNameLen = subFieldName.length();

                if (subFieldName.charAt(subFieldNameLen - 1) != ']') {
                  throw new IllegalArgumentException("Missed closing ']'");
                }

                final String indexPart = subFieldName.substring(1, subFieldNameLen - 1);
                final Object indexPartObject = DocumentHelper.getIndexPart(null, indexPart);
                final String indexAsString =
                    indexPartObject == null ? null : indexPartObject.toString();

                if (indexAsString == null) {
                  throw new IllegalArgumentException(
                      "List / array subscripts must resolve to integer values.");
                }
                try {
                  final int index = Integer.parseInt(indexAsString);
                  MultiValue.setValue(subObject, iPropertyValue, index);
                } catch (NumberFormatException e) {
                  throw new IllegalArgumentException(
                      "List / array subscripts must resolve to integer values.", e);
                }
              } else {
                // APPLY CHANGE TO ALL THE ITEM IN SUB-COLLECTION
                for (Object subObjectItem : MultiValue.getMultiValueIterable(subObject)) {
                  if (subObjectItem instanceof EntityImpl) {
                    // SUB-ENTITY, CHECK IF IT'S NOT LINKED
                    if (!((EntityImpl) subObjectItem).isEmbedded()) {
                      throw new IllegalArgumentException(
                          "Property '"
                              + iFieldName
                              + "' points to linked collection of items. You can only change"
                              + " embedded entities in this way");
                    }
                    ((EntityImpl) subObjectItem).field(subFieldName, iPropertyValue);
                  } else {
                    if (subObjectItem instanceof Map<?, ?>) {
                      // KEY/VALUE
                      ((Map<String, Object>) subObjectItem).put(subFieldName, iPropertyValue);
                    }
                  }
                }
              }
              return this;
            }
          }
        }
      } else {
        throw new IllegalArgumentException(
            "Property '"
                + iFieldName.substring(0, lastSep)
                + "' is null, is possible to set a value with dotted notation only on not null"
                + " property");
      }
      return null;
    }

    iFieldName = checkFieldName(iFieldName);

    checkForFields();

    EntityEntry entry = fields.get(iFieldName);
    final boolean knownProperty;
    final Object oldValue;
    final PropertyType oldType;
    if (entry == null) {
      entry = new EntityEntry();
      fieldSize++;
      fields.put(iFieldName, entry);
      entry.markCreated();
      knownProperty = false;
      oldValue = null;
      oldType = null;
    } else {
      knownProperty = entry.exists();
      oldValue = entry.value;
      oldType = entry.type;
    }

    PropertyType fieldType = deriveFieldType(iFieldName, entry, iFieldType);
    if (iPropertyValue != null && fieldType != null) {
      iPropertyValue =
          DocumentHelper.convertField(getSession(), this, iFieldName, fieldType, null,
              iPropertyValue);
    } else {
      if (iPropertyValue instanceof Enum) {
        iPropertyValue = iPropertyValue.toString();
      }
    }

    if (knownProperty)
    // CHECK IF IS REALLY CHANGED
    {
      if (iPropertyValue == null) {
        if (oldValue == null)
        // BOTH NULL: UNCHANGED
        {
          return this;
        }
      } else {

        try {
          if (iPropertyValue.equals(oldValue)) {
            if (fieldType == oldType) {
              if (!(iPropertyValue instanceof RecordElement))
              // SAME BUT NOT TRACKABLE: SET THE RECORD AS DIRTY TO BE SURE IT'S SAVED
              {
                setDirty();
              }

              // SAVE VALUE: UNCHANGED
              return this;
            }
          } else {
            if (iPropertyValue instanceof byte[]
                && Arrays.equals((byte[]) iPropertyValue, (byte[]) oldValue)) {
              // SAVE VALUE: UNCHANGED
              return this;
            }
          }
        } catch (Exception e) {
          LogManager.instance()
              .warn(
                  this,
                  "Error on checking the value of property %s against the record %s",
                  e,
                  iFieldName,
                  getIdentity());
        }
      }
    }

    if (oldValue instanceof RidBag ridBag) {
      ridBag.setOwner(null);
      ridBag.setRecordAndField(recordId, iFieldName);
    } else {
      if (oldValue instanceof EntityImpl) {
        ((EntityImpl) oldValue).removeOwner(this);
      }
    }

    if (oldValue instanceof Identifiable) {
      unTrack((Identifiable) oldValue);
    }

    if (iPropertyValue != null) {
      if (iPropertyValue instanceof EntityImpl) {
        if (PropertyType.EMBEDDED.equals(fieldType)) {
          final EntityImpl embeddedEntity = (EntityImpl) iPropertyValue;
          EntityInternalUtils.addOwner(embeddedEntity, this);
        } else {
          if (PropertyType.LINK.equals(fieldType)) {
            final EntityImpl embeddedEntity = (EntityImpl) iPropertyValue;
            EntityInternalUtils.removeOwner(embeddedEntity, this);
          }
        }
      }
      if (iPropertyValue instanceof Identifiable) {
        track((Identifiable) iPropertyValue);
      }

      if (iPropertyValue instanceof RidBag ridBag) {
        ridBag.setOwner(
            null); // in order to avoid IllegalStateException when ridBag changes the owner
        // (EntityImpl.merge)
        ridBag.setOwner(this);
        ridBag.setRecordAndField(recordId, iFieldName);
      }
    }

    if (fieldType == PropertyType.CUSTOM) {
      if (!DB_CUSTOM_SUPPORT.getValueAsBoolean()) {
        throw new DatabaseException(
            String.format(
                "PropertyType CUSTOM used by serializable types, for value  '%s' is not enabled, set"
                    + " `db.custom.support` to true for enable it",
                iPropertyValue));
      }
    }

    if (oldType != fieldType && oldType != null) {
      // can be made in a better way, but "keeping type" issue should be solved before
      if (iPropertyValue == null
          || fieldType != null
          || oldType != PropertyType.getTypeByValue(iPropertyValue)) {
        entry.type = fieldType;
      }
    }
    entry.disableTracking(this, oldValue);
    entry.value = iPropertyValue;
    if (!entry.exists()) {
      entry.setExists(true);
      fieldSize++;
    }
    entry.enableTracking(this);

    setDirty();
    if (!entry.isChanged()) {
      entry.original = oldValue;
      entry.markChanged();
    }

    return this;
  }

  /**
   * Removes a field.
   */
  @Override
  public Object removeField(final String iFieldName) {
    checkForBinding();
    checkForFields();

    if (DocumentHelper.ATTRIBUTE_CLASS.equalsIgnoreCase(iFieldName)) {
      setClassName(null);
    } else {
      if (DocumentHelper.ATTRIBUTE_RID.equalsIgnoreCase(iFieldName)) {
        recordId = new ChangeableRecordId();
      }
    }

    final EntityEntry entry = fields.get(iFieldName);
    if (entry == null) {
      return null;
    }
    Object oldValue = entry.value;

    if (entry.exists() && trackingChanges) {
      // SAVE THE OLD VALUE IN A SEPARATE MAP
      if (entry.original == null) {
        entry.original = entry.value;
      }
      entry.value = null;
      entry.setExists(false);
      entry.markChanged();
    } else {
      fields.remove(iFieldName);
    }
    fieldSize--;

    entry.disableTracking(this, oldValue);
    if (oldValue instanceof Identifiable) {
      unTrack((Identifiable) oldValue);
    }
    if (oldValue instanceof RidBag) {
      ((RidBag) oldValue).setOwner(null);
    }
    setDirty();
    return oldValue;
  }

  /**
   * Merge current entity with the entity passed as parameter. If the field already exists then the
   * conflicts are managed based on the value of the parameter 'iUpdateOnlyMode'.
   *
   * @param iOther                              Other EntityImpl instance to merge
   * @param iUpdateOnlyMode                     if true, the other entity properties will always be
   *                                            added or overwritten. If false, the missed
   *                                            properties in the "other" entity will be removed by
   *                                            original entity
   * @param iMergeSingleItemsOfMultiValueFields If true, merges single items of multi field fields
   *                                            (collections, maps, arrays, etc)
   */
  public EntityImpl merge(
      final EntityImpl iOther,
      boolean iUpdateOnlyMode,
      boolean iMergeSingleItemsOfMultiValueFields) {
    iOther.checkForBinding();

    checkForBinding();

    iOther.checkForFields();

    if (className == null && iOther.getImmutableSchemaClass() != null) {
      className = iOther.getImmutableSchemaClass().getName();
    }

    return mergeMap(
        ((EntityImpl) iOther.getRecord()).fields,
        iUpdateOnlyMode,
        iMergeSingleItemsOfMultiValueFields);
  }

  /**
   * Returns list of changed fields. There are two types of changes:
   *
   * <ol>
   *   <li>Value of field itself was changed by calling of {@link #field(String, Object)} method for
   *       example.
   *   <li>Internal state of field was changed but was not saved. This case currently is applicable
   *       for for collections only.
   * </ol>
   *
   * @return Array of fields, values of which were changed.
   */
  public String[] getDirtyFields() {
    checkForBinding();

    if (fields == null || fields.isEmpty()) {
      return EMPTY_STRINGS;
    }

    final Set<String> dirtyFields = new HashSet<>();
    for (Entry<String, EntityEntry> entry : fields.entrySet()) {
      if (entry.getValue().isChanged() || entry.getValue().isTrackedModified()) {
        dirtyFields.add(entry.getKey());
      }
    }
    return dirtyFields.toArray(new String[0]);
  }

  /**
   * Returns the original value of a field before it has been changed.
   *
   * @param iFieldName Property name to retrieve the original value
   */
  public Object getOriginalValue(final String iFieldName) {
    checkForBinding();

    if (fields != null) {
      EntityEntry entry = fields.get(iFieldName);
      if (entry != null) {
        return entry.original;
      }
    }
    return null;
  }

  public MultiValueChangeTimeLine<Object, Object> getCollectionTimeLine(final String iFieldName) {
    checkForBinding();

    EntityEntry entry = fields != null ? fields.get(iFieldName) : null;
    return entry != null ? entry.getTimeLine() : null;
  }

  /**
   * Returns the iterator fields
   */
  @Override
  @Nonnull
  public Iterator<Entry<String, Object>> iterator() {
    checkForBinding();
    checkForFields();

    if (fields == null) {
      return EmptyMapEntryIterator.INSTANCE;
    }

    final Iterator<Entry<String, EntityEntry>> iterator = fields.entrySet().iterator();
    return new Iterator<>() {
      private Entry<String, EntityEntry> current;
      private boolean read = true;

      @Override
      public boolean hasNext() {
        while (iterator.hasNext()) {
          current = iterator.next();
          if (current.getValue().exists()
              && (propertyAccess == null || propertyAccess.isReadable(current.getKey()))) {
            read = false;
            return true;
          }
        }
        return false;
      }

      @Override
      public Entry<String, Object> next() {
        if (read) {
          if (!hasNext()) {
            // Look wrong but is correct, it need to fail if there isn't next.
            iterator.next();
          }
        }
        final Entry<String, Object> toRet =
            new Entry<>() {
              private final Entry<String, EntityEntry> intern = current;

              @Override
              public Object setValue(Object value) {
                throw new UnsupportedOperationException();
              }

              @Override
              public Object getValue() {
                return intern.getValue().value;
              }

              @Override
              public String getKey() {
                return intern.getKey();
              }

              @Override
              public int hashCode() {
                return intern.hashCode();
              }

              @Override
              public boolean equals(Object obj) {
                //noinspection rawtypes
                if (obj instanceof Entry entry) {
                  return intern.getKey().equals(entry.getKey())
                      && intern.getValue().value.equals(entry.getValue());
                }

                return intern.equals(obj);
              }
            };
        read = true;
        return toRet;
      }

      @Override
      public void remove() {
        var entry = current.getValue();
        if (trackingChanges) {
          if (entry.isChanged()) {
            entry.original = entry.value;
          }
          entry.value = null;
          entry.setExists(false);
          entry.markChanged();
        } else {
          iterator.remove();
        }
        fieldSize--;

        entry.disableTracking(EntityImpl.this, entry.value);
      }
    };
  }

  /**
   * Checks if a field exists.
   *
   * @return True if exists, otherwise false.
   */
  @Override
  public boolean containsField(final String iFieldName) {
    return hasProperty(iFieldName);
  }

  /**
   * Checks if a property exists.
   *
   * @return True if exists, otherwise false.
   */
  @Override
  public boolean hasProperty(final String propertyName) {
    checkForBinding();
    if (propertyName == null) {
      return false;
    }

    if (checkForFields(propertyName)
        && (propertyAccess == null || propertyAccess.isReadable(propertyName))) {
      EntityEntry entry = fields.get(propertyName);
      return entry != null && entry.exists();
    } else {
      return false;
    }
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
  public RecordAbstract setDirty() {
    if (owner != null) {
      // PROPAGATES TO THE OWNER
      var ownerEntity = owner.get();
      if (ownerEntity != null) {
        ownerEntity.setDirty();
      }
    } else {
      if (!isDirty()) {
        checkForBinding();
        getDirtyManager().setDirty(this);
      }
    }

    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD
    // _SOURCE IS NULL
    checkForFields();
    super.setDirty();

    return this;
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

    getDirtyManager().setDirty(this);
    // THIS IS IMPORTANT TO BE SURE THAT FIELDS ARE LOADED BEFORE IT'S TOO LATE AND THE RECORD
    // _SOURCE IS NULL
    checkForFields();

    super.setDirtyNoChanged();
  }

  @Override
  public final EntityImpl fromStream(final byte[] iRecordBuffer) {
    if (dirty) {
      throw new DatabaseException("Cannot call fromStream() on dirty records");
    }

    status = STATUS.UNMARSHALLING;
    try {
      removeAllCollectionChangeListeners();

      fields = null;
      fieldSize = 0;
      contentChanged = false;
      schema = null;

      fetchSchemaIfCan();
      super.fromStream(iRecordBuffer);

      return this;
    } finally {
      status = STATUS.LOADED;
    }
  }

  @Override
  protected final EntityImpl fromStream(final byte[] iRecordBuffer,
      DatabaseSessionInternal db) {
    if (dirty) {
      throw new DatabaseException("Cannot call fromStream() on dirty records");
    }

    status = STATUS.UNMARSHALLING;
    try {
      removeAllCollectionChangeListeners();

      fields = null;
      fieldSize = 0;
      contentChanged = false;
      schema = null;
      fetchSchemaIfCan(db);
      super.fromStream(iRecordBuffer);

      return this;
    } finally {
      status = STATUS.LOADED;
    }
  }

  /**
   * Returns the forced field type if any.
   *
   * @param iFieldName name of field to check
   */
  public PropertyType fieldType(final String iFieldName) {
    checkForBinding();
    checkForFields(iFieldName);

    EntityEntry entry = fields.get(iFieldName);
    if (entry != null) {
      if (propertyAccess == null || propertyAccess.isReadable(iFieldName)) {
        return entry.type;
      } else {
        return null;
      }
    }

    return null;
  }

  @Override
  public void unload() {
    if (status == RecordElement.STATUS.NOT_LOADED) {
      return;
    }

    if (dirty) {
      throw new IllegalStateException("Can not unload dirty entity");
    }

    internalReset();

    super.unload();
  }

  /**
   * Clears all the field values and types. Clears only record content, but saves its identity.
   *
   * <p>
   *
   * <p>The following code will clear all data from specified entity. <code>
   * entity.clear(); entity.save(); </code>
   *
   * @see #reset()
   */
  @Override
  public void clear() {
    checkForBinding();

    super.clear();
    internalReset();
    owner = null;
  }

  /**
   * Resets the record values and class type to being reused. It's like you create a EntityImpl from
   * scratch.
   */
  @Override
  public EntityImpl reset() {
    checkForBinding();

    var db = DatabaseRecordThreadLocal.instance().getIfDefined();
    if (db != null && db.getTransaction().isActive()) {
      throw new IllegalStateException(
          "Cannot reset entities during a transaction. Create a new one each time");
    }

    super.reset();

    className = null;
    immutableClazz = null;
    immutableSchemaVersion = -1;

    internalReset();

    owner = null;
    return this;
  }

  /**
   * Rollbacks changes to the loaded version without reloading the entity. Works only if tracking
   * changes is enabled @see {@link #isTrackingChanges()} and {@link #setTrackingChanges(boolean)}
   * methods.
   */
  public void undo() {
    if (!trackingChanges) {
      throw new ConfigurationException(
          "Cannot undo the entity because tracking of changes is disabled");
    }

    if (fields != null) {
      final Iterator<Entry<String, EntityEntry>> vals = fields.entrySet().iterator();
      while (vals.hasNext()) {
        final Entry<String, EntityEntry> next = vals.next();
        final EntityEntry val = next.getValue();
        if (val.isCreated()) {
          vals.remove();
        } else {
          val.undo();
        }
      }
      fieldSize = fields.size();
    }
  }

  public EntityImpl undo(final String field) {
    if (!trackingChanges) {
      throw new ConfigurationException(
          "Cannot undo the entity because tracking of changes is disabled");
    }

    if (fields != null) {
      final EntityEntry value = fields.get(field);
      if (value != null) {
        if (value.isCreated()) {
          fields.remove(field);
        } else {
          value.undo();
        }
      }
    }
    return this;
  }

  public boolean isLazyLoad() {
    checkForBinding();

    return lazyLoad;
  }

  public void setLazyLoad(final boolean iLazyLoad) {
    checkForBinding();

    this.lazyLoad = iLazyLoad;
    checkForFields();
  }

  public boolean isTrackingChanges() {
    return trackingChanges;
  }

  /**
   * Enabled or disabled the tracking of changes in the entity. This is needed by some triggers like
   * {@link ClassIndexManager} to determine what fields are changed to update indexes.
   *
   * @param iTrackingChanges True to enable it, otherwise false
   * @return this
   */
  public EntityImpl setTrackingChanges(final boolean iTrackingChanges) {
    checkForBinding();

    this.trackingChanges = iTrackingChanges;
    if (!iTrackingChanges && fields != null) {
      // FREE RESOURCES
      Iterator<Entry<String, EntityEntry>> iter = fields.entrySet().iterator();
      while (iter.hasNext()) {
        Entry<String, EntityEntry> cur = iter.next();
        if (!cur.getValue().exists()) {
          iter.remove();
        } else {
          cur.getValue().clear();
        }
      }
      removeAllCollectionChangeListeners();
    } else {
      addAllMultiValueChangeListeners();
    }
    return this;
  }

  protected void clearTrackData() {
    if (fields != null) {
      // FREE RESOURCES
      for (Entry<String, EntityEntry> cur : fields.entrySet()) {
        if (cur.getValue().exists()) {
          cur.getValue().clear();
          cur.getValue().enableTracking(this);
        } else {
          cur.getValue().clearNotExists();
        }
      }
    }
  }

  void clearTransactionTrackData() {
    if (fields != null) {
      // FREE RESOURCES
      Iterator<Entry<String, EntityEntry>> iter = fields.entrySet().iterator();
      while (iter.hasNext()) {
        Entry<String, EntityEntry> cur = iter.next();
        if (cur.getValue().exists()) {
          cur.getValue().transactionClear();
        } else {
          iter.remove();
        }
      }
    }
  }

  public boolean isOrdered() {
    return ordered;
  }

  public EntityImpl setOrdered(final boolean iOrdered) {
    checkForBinding();

    this.ordered = iOrdered;
    return this;
  }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj)) {
      return false;
    }

    return this == obj || recordId.isValid();
  }

  @Override
  public int hashCode() {
    if (recordId.isValid()) {
      return super.hashCode();
    }

    return System.identityHashCode(this);
  }

  /**
   * Returns the number of fields in memory.
   */
  @Override
  public int fields() {
    checkForBinding();

    checkForFields();
    return fieldSize;
  }

  public boolean isEmpty() {
    checkForBinding();

    checkForFields();
    return fields == null || fields.isEmpty();
  }

  public boolean isEmbedded() {
    return owner != null;
  }

  /**
   * Sets the field type. This overrides the schema property settings if any.
   *
   * @param iFieldName Field name
   * @param iFieldType Type to set between PropertyType enumeration values
   */
  public EntityImpl setFieldType(final String iFieldName, final PropertyType iFieldType) {
    checkForBinding();

    checkForFields(iFieldName);
    if (iFieldType != null) {
      if (fields == null) {
        fields = ordered ? new LinkedHashMap<>() : new HashMap<>();
      }

      if (iFieldType == PropertyType.CUSTOM) {
        if (!DB_CUSTOM_SUPPORT.getValueAsBoolean()) {
          throw new DatabaseException(
              "PropertyType CUSTOM used by serializable types is not enabled, set `db.custom.support`"
                  + " to true for enable it");
        }
      }
      // SET THE FORCED TYPE
      EntityEntry entry = getOrCreate(iFieldName);
      if (entry.type != iFieldType) {
        field(iFieldName, field(iFieldName), iFieldType);
      }
    } else {
      if (fields != null) {
        // REMOVE THE FIELD TYPE
        EntityEntry entry = fields.get(iFieldName);
        if (entry != null)
        // EMPTY: OPTIMIZE IT BY REMOVING THE ENTIRE MAP
        {
          entry.type = null;
        }
      }
    }
    return this;
  }

  /*
   * Initializes the object if has been unserialized
   */
  public boolean deserializeFields(String... iFields) {
    checkForBinding();

    List<String> additional = null;
    if (source == null)
    // ALREADY UNMARSHALLED OR JUST EMPTY
    {
      return true;
    }

    if (iFields != null && iFields.length > 0) {
      // EXTRACT REAL FIELD NAMES
      for (final String f : iFields) {
        if (f != null && !f.startsWith("@")) {
          int pos1 = f.indexOf('[');
          int pos2 = f.indexOf('.');
          if (pos1 > -1 || pos2 > -1) {
            int pos = pos1 > -1 ? pos1 : pos2;
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
        String[] copy = new String[iFields.length + additional.size()];
        System.arraycopy(iFields, 0, copy, 0, iFields.length);
        int next = iFields.length;
        for (String s : additional) {
          copy[next++] = s;
        }
        iFields = copy;
      }

      // CHECK IF HAS BEEN ALREADY UNMARSHALLED
      if (fields != null && !fields.isEmpty()) {
        boolean allFound = true;
        for (String f : iFields) {
          if (f != null && !f.startsWith("@") && !fields.containsKey(f)) {
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
      recordFormat.fromStream(getSession(), source, this, iFields);
    } finally {
      status = RecordElement.STATUS.LOADED;
    }

    if (iFields != null && iFields.length > 0) {
      for (String field : iFields) {
        if (field != null && field.startsWith("@"))
        // ATTRIBUTE
        {
          return true;
        }
      }

      // PARTIAL UNMARSHALLING
      if (fields != null && !fields.isEmpty()) {
        for (String f : iFields) {
          if (f != null && fields.containsKey(f)) {
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

  /**
   * Change the behavior of field() methods allowing access to the sub documents with dot notation
   * ('.'). Default is true. Set it to false if you allow to store properties with the dot.
   */
  public EntityImpl setAllowChainedAccess(final boolean allowChainedAccess) {
    checkForBinding();

    this.allowChainedAccess = allowChainedAccess;
    return this;
  }

  public void setClassNameIfExists(final String iClassName) {
    checkForBinding();

    immutableClazz = null;
    immutableSchemaVersion = -1;

    className = iClassName;

    if (iClassName == null) {
      return;
    }

    final SchemaClass _clazz = getSession().getMetadata().getImmutableSchemaSnapshot()
        .getClass(iClassName);
    if (_clazz != null) {
      className = _clazz.getName();
      convertFieldsToClass(_clazz);
    }
  }

  @Override
  public SchemaClass getSchemaClass() {
    checkForBinding();

    if (className == null) {
      fetchClassName();
    }

    if (className == null) {
      return null;
    }

    return getSession().getMetadata().getSchema().getClass(className);
  }

  public String getClassName() {
    if (className == null) {
      fetchClassName();
    }

    return className;
  }

  public void setClassName(final String className) {
    checkForBinding();

    immutableClazz = null;
    immutableSchemaVersion = -1;

    this.className = className;

    if (className == null) {
      return;
    }

    MetadataInternal metadata = getSession().getMetadata();
    this.immutableClazz =
        (SchemaImmutableClass) metadata.getImmutableSchemaSnapshot().getClass(className);
    SchemaClass clazz;
    if (this.immutableClazz != null) {
      clazz = this.immutableClazz;
    } else {
      clazz = metadata.getSchema().getOrCreateClass(className);
    }
    if (clazz != null) {
      this.className = clazz.getName();
      convertFieldsToClass(clazz);
    }
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

    checkForFields();
    autoConvertValues();

    var session = getSession();

    validateFieldsSecurity(session, this);
    if (!session.isValidationEnabled()) {
      return;
    }

    final SchemaImmutableClass immutableSchemaClass = getImmutableSchemaClass();
    if (immutableSchemaClass != null) {
      if (immutableSchemaClass.isStrictMode()) {
        // CHECK IF ALL FIELDS ARE DEFINED
        for (String f : fieldNames()) {
          if (immutableSchemaClass.getProperty(session, f) == null) {
            throw new ValidationException(
                "Found additional field '"
                    + f
                    + "'. It cannot be added because the schema class '"
                    + immutableSchemaClass.getName()
                    + "' is defined as STRICT");
          }
        }
      }

      final ImmutableSchema immutableSchema = session.getMetadata().getImmutableSchemaSnapshot();
      for (SchemaProperty p : immutableSchemaClass.properties(session)) {
        validateField(session, immutableSchema, this, (ImmutableSchemaProperty) p);
      }
    }
  }

  protected String toString(Set<DBRecord> inspected) {
    checkForBinding();

    if (inspected.contains(this)) {
      return "<recursion:rid=" + (recordId != null ? recordId : "null") + ">";
    } else {
      inspected.add(this);
    }

    final boolean saveDirtyStatus = dirty;
    final boolean oldUpdateContent = contentChanged;

    try {
      final StringBuilder buffer = new StringBuilder(128);

      checkForFields();

      var session = getSessionIfDefined();
      if (session != null && !session.isClosed()) {
        final String clsName = getClassName();
        if (clsName != null) {
          buffer.append(clsName);
        }
      }

      if (recordId != null) {
        if (recordId.isValid()) {
          buffer.append(recordId);
        }
      }

      boolean first = true;
      for (Entry<String, EntityEntry> f : fields.entrySet()) {
        if (propertyAccess != null && !propertyAccess.isReadable(f.getKey())) {
          continue;
        }
        buffer.append(first ? '{' : ',');
        buffer.append(f.getKey());
        buffer.append(':');
        if (f.getValue().value == null) {
          buffer.append("null");
        } else {
          if (f.getValue().value instanceof Collection<?>
              || f.getValue().value instanceof Map<?, ?>
              || f.getValue().value.getClass().isArray()) {
            buffer.append('[');
            buffer.append(MultiValue.getSize(f.getValue().value));
            buffer.append(']');
          } else {
            if (f.getValue().value instanceof RecordAbstract record) {
              if (record.getIdentity().isValid()) {
                record.getIdentity().toString(buffer);
              } else {
                if (record instanceof EntityImpl) {
                  buffer.append(((EntityImpl) record).toString(inspected));
                } else {
                  buffer.append(record);
                }
              }
            } else {
              buffer.append(f.getValue().value);
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

      if (recordId != null && recordId.isValid()) {
        buffer.append(" v");
        buffer.append(recordVersion);
      }

      return buffer.toString();
    } finally {
      dirty = saveDirtyStatus;
      contentChanged = oldUpdateContent;
    }
  }

  private EntityImpl mergeMap(
      final Map<String, EntityEntry> iOther,
      final boolean iUpdateOnlyMode,
      boolean iMergeSingleItemsOfMultiValueFields) {
    checkForFields();
    source = null;

    for (Entry<String, EntityEntry> entry : iOther.entrySet()) {
      String f = entry.getKey();
      EntityEntry entityEntry = entry.getValue();
      if (!entityEntry.exists()) {
        continue;
      }
      final Object otherValue = entityEntry.value;

      EntityEntry curValue = fields.get(f);

      if (curValue != null && curValue.exists()) {
        final Object value = curValue.value;
        if (iMergeSingleItemsOfMultiValueFields) {
          if (value instanceof Map<?, ?>) {
            final Map<String, Object> map = (Map<String, Object>) value;
            final Map<String, Object> otherMap = (Map<String, Object>) otherValue;

            map.putAll(otherMap);
            continue;
          } else {
            if (MultiValue.isMultiValue(value) && !(value instanceof RidBag)) {
              for (Object item : MultiValue.getMultiValueIterable(otherValue)) {
                if (!MultiValue.contains(value, item)) {
                  MultiValue.add(value, item);
                }
              }
              continue;
            }
          }
        }
        boolean bagsMerged = false;
        if (value instanceof RidBag && otherValue instanceof RidBag) {
          bagsMerged =
              ((RidBag) value).tryMerge((RidBag) otherValue, iMergeSingleItemsOfMultiValueFields);
        }

        if (!bagsMerged && (value != null && !value.equals(otherValue))
            || (value == null && otherValue != null)) {
          setPropertyInternal(f, otherValue);
        }
      } else {
        setPropertyInternal(f, otherValue);
      }
    }

    if (!iUpdateOnlyMode) {
      // REMOVE PROPERTIES NOT FOUND IN OTHER ENTITY
      for (String f : getPropertyNamesInternal()) {
        if (!iOther.containsKey(f) || !iOther.get(f).exists()) {
          removePropertyInternal(f);
        }
      }
    }

    return this;
  }

  @Override
  protected final RecordAbstract fill(
      final RID iRid, final int iVersion, final byte[] iBuffer, final boolean iDirty) {
    if (dirty) {
      throw new DatabaseException("Cannot call fill() on dirty records");
    }

    schema = null;
    fetchSchemaIfCan();
    return super.fill(iRid, iVersion, iBuffer, iDirty);
  }

  @Override
  protected final RecordAbstract fill(
      final RID iRid,
      final int iVersion,
      final byte[] iBuffer,
      final boolean iDirty,
      DatabaseSessionInternal db) {
    if (dirty) {
      throw new DatabaseException("Cannot call fill() on dirty records");
    }

    schema = null;
    fetchSchemaIfCan(db);
    return super.fill(iRid, iVersion, iBuffer, iDirty, db);
  }

  @Override
  protected void clearSource() {
    super.clearSource();
    schema = null;
  }

  protected GlobalProperty getGlobalPropertyById(int id) {
    checkForBinding();
    var session = getSession();
    if (schema == null) {
      MetadataInternal metadata = session.getMetadata();
      schema = metadata.getImmutableSchemaSnapshot();
    }
    GlobalProperty prop = schema.getGlobalPropertyById(id);
    if (prop == null) {
      if (session.isClosed()) {
        throw new DatabaseException(
            "Cannot unmarshall the entity because no database is active, use detach for use the"
                + " entity outside the database session scope");
      }

      MetadataInternal metadata = session.getMetadata();
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

  void fillClassIfNeed(final String iClassName) {
    checkForBinding();

    if (this.className == null) {
      immutableClazz = null;
      immutableSchemaVersion = -1;
      className = iClassName;
    }
  }

  protected SchemaImmutableClass getImmutableSchemaClass() {
    return getImmutableSchemaClass(getSessionIfDefined());
  }

  protected SchemaImmutableClass getImmutableSchemaClass(
      @Nullable DatabaseSessionInternal database) {
    if (immutableClazz == null) {
      if (className == null) {
        fetchClassName();
      }

      if (className != null) {
        if (database != null && !database.isClosed()) {
          final Schema immutableSchema = database.getMetadata().getImmutableSchemaSnapshot();
          if (immutableSchema == null) {
            return null;
          }
          //noinspection deprecation
          immutableSchemaVersion = immutableSchema.getVersion();
          immutableClazz = (SchemaImmutableClass) immutableSchema.getClass(className);
        }
      }
    }

    return immutableClazz;
  }

  protected void rawField(
      final String iFieldName, final Object iFieldValue, final PropertyType iFieldType) {
    checkForBinding();

    if (fields == null) {
      fields = ordered ? new LinkedHashMap<>() : new HashMap<>();
    }

    EntityEntry entry = getOrCreate(iFieldName);
    entry.disableTracking(this, entry.value);
    entry.value = iFieldValue;
    entry.type = iFieldType;
    entry.enableTracking(this);
    if (iFieldValue instanceof RidBag) {
      ((RidBag) iFieldValue).setRecordAndField(recordId, iFieldName);
    }
    if (iFieldValue instanceof Identifiable
        && !((Identifiable) iFieldValue).getIdentity().isPersistent()) {
      track((Identifiable) iFieldValue);
    }
  }

  private EntityEntry getOrCreate(String key) {
    EntityEntry entry = fields.get(key);
    if (entry == null) {
      entry = new EntityEntry();
      fieldSize++;
      fields.put(key, entry);
    }
    return entry;
  }

  boolean rawContainsField(final String iFiledName) {
    checkForBinding();
    return fields != null && fields.containsKey(iFiledName);
  }

  public void autoConvertValues() {
    checkForBinding();

    var session = getSession();
    SchemaClass clazz = getImmutableSchemaClass();
    if (clazz != null) {
      for (SchemaProperty prop : clazz.properties(session)) {
        PropertyType type = prop.getType();
        PropertyType linkedType = prop.getLinkedType();
        SchemaClass linkedClass = prop.getLinkedClass(session);
        if (type == PropertyType.EMBEDDED && linkedClass != null) {
          convertToEmbeddedType(session, prop);
          continue;
        }
        if (fields == null) {
          continue;
        }
        final EntityEntry entry = fields.get(prop.getName());
        if (entry == null) {
          continue;
        }
        if (!entry.isCreated() && !entry.isChanged()) {
          continue;
        }
        Object value = entry.value;
        if (value == null) {
          continue;
        }
        try {
          if (type == PropertyType.LINKBAG
              && !(entry.value instanceof RidBag)
              && entry.value instanceof Collection) {
            RidBag newValue = new RidBag(session);
            newValue.setRecordAndField(recordId, prop.getName());
            for (Object o : ((Collection<Object>) entry.value)) {
              if (!(o instanceof Identifiable)) {
                throw new ValidationException("Invalid value in ridbag: " + o);
              }
              newValue.add((Identifiable) o);
            }
            entry.value = newValue;
          }
          if (type == PropertyType.LINKMAP) {
            if (entry.value instanceof Map) {
              Map<String, Object> map = (Map<String, Object>) entry.value;
              var newMap = new LinkMap(this);
              boolean changed = false;
              for (Entry<String, Object> stringObjectEntry : map.entrySet()) {
                Object val = stringObjectEntry.getValue();
                if (MultiValue.isMultiValue(val) && MultiValue.getSize(val) == 1) {
                  val = MultiValue.getFirstValue(val);
                  if (val instanceof Result) {
                    val = ((Result) val).getIdentity().orElse(null);
                  }
                  changed = true;
                }
                newMap.put(stringObjectEntry.getKey(), (Identifiable) val);
              }
              if (changed) {
                entry.value = newMap;
              }
            }
          }

          if (linkedType == null) {
            continue;
          }

          if (type == PropertyType.EMBEDDEDLIST) {
            TrackedList<Object> list = new TrackedList<>(this);
            Collection<Object> values = (Collection<Object>) value;
            for (Object object : values) {
              list.add(PropertyType.convert(session, object, linkedType.getDefaultJavaType()));
            }
            entry.value = list;
            replaceListenerOnAutoconvert(entry);
          } else {
            if (type == PropertyType.EMBEDDEDMAP) {
              Map<Object, Object> map = new TrackedMap<>(this);
              Map<Object, Object> values = (Map<Object, Object>) value;
              for (Entry<Object, Object> object : values.entrySet()) {
                map.put(
                    object.getKey(),
                    PropertyType.convert(session, object.getValue(),
                        linkedType.getDefaultJavaType()));
              }
              entry.value = map;
              replaceListenerOnAutoconvert(entry);
            } else {
              if (type == PropertyType.EMBEDDEDSET) {
                Set<Object> set = new TrackedSet<>(this);
                Collection<Object> values = (Collection<Object>) value;
                for (Object object : values) {
                  set.add(PropertyType.convert(session, object, linkedType.getDefaultJavaType()));
                }
                entry.value = set;
                replaceListenerOnAutoconvert(entry);
              }
            }
          }
        } catch (Exception e) {
          throw BaseException.wrapException(
              new ValidationException(
                  "impossible to convert value of field \"" + prop.getName() + "\""),
              e);
        }
      }
    }
  }

  private void convertToEmbeddedType(DatabaseSession session, SchemaProperty prop) {
    final EntityEntry entry = fields.get(prop.getName());
    SchemaClass linkedClass = prop.getLinkedClass(session);
    if (entry == null || linkedClass == null) {
      return;
    }
    if (!entry.isCreated() && !entry.isChanged()) {
      return;
    }
    Object value = entry.value;
    if (value == null) {
      return;
    }
    try {
      if (value instanceof EntityImpl) {
        SchemaClass entityClass = ((EntityImpl) value).getImmutableSchemaClass();
        if (entityClass == null) {
          ((EntityImpl) value).setClass(linkedClass);
        } else {
          if (!entityClass.isSubClassOf(session, linkedClass)) {
            throw new ValidationException(
                "impossible to convert value of field \""
                    + prop.getName()
                    + "\", incompatible with "
                    + linkedClass);
          }
        }
      } else {
        if (value instanceof Map) {
          entry.disableTracking(this, value);
          EntityImpl newValue = new EntityImpl(linkedClass);
          //noinspection rawtypes
          newValue.fromMap((Map) value);
          entry.value = newValue;
          newValue.addOwner(this);
        } else {
          throw new ValidationException(
              "impossible to convert value of field \"" + prop.getName() + "\"");
        }
      }

    } catch (Exception e) {
      throw BaseException.wrapException(
          new ValidationException(
              "impossible to convert value of field \"" + prop.getName() + "\""),
          e);
    }
  }

  private void replaceListenerOnAutoconvert(final EntityEntry entry) {
    entry.replaceListener(this);
  }

  /**
   * Internal.
   */
  @Override
  protected byte getRecordType() {
    return RECORD_TYPE;
  }

  /**
   * Internal.
   */
  protected void addOwner(final RecordElement iOwner) {
    checkForBinding();

    if (iOwner == null) {
      return;
    }

    if (recordId.isPersistent()) {
      throw new DatabaseException("Cannot add owner to a persistent entity");
    }

    if (owner == null) {
      if (dirtyManager != null && this.getIdentity().isNew()) {
        dirtyManager.removeNew(this);
      }
    }
    this.owner = new WeakReference<>(iOwner);
  }

  void removeOwner(final RecordElement iRecordElement) {
    if (owner != null && owner.get() == iRecordElement) {
      assert !recordId.isPersistent();
      owner = null;
    }
  }

  void convertAllMultiValuesToTrackedVersions() {
    checkForBinding();

    if (fields == null) {
      return;
    }

    var session = getSession();
    for (Map.Entry<String, EntityEntry> fieldEntry : fields.entrySet()) {
      EntityEntry entry = fieldEntry.getValue();
      final Object fieldValue = entry.value;
      if (fieldValue instanceof RidBag) {
        if (isEmbedded()) {
          throw new DatabaseException("RidBag are supported only at entity root");
        }
        ((RidBag) fieldValue).checkAndConvert();
      }
      if (!(fieldValue instanceof Collection<?>)
          && !(fieldValue instanceof Map<?, ?>)
          && !(fieldValue instanceof EntityImpl)) {
        continue;
      }
      if (entry.enableTracking(this)) {
        if (entry.getTimeLine() != null
            && !entry.getTimeLine().getMultiValueChangeEvents().isEmpty()) {
          //noinspection rawtypes
          checkTimelineTrackable(entry.getTimeLine(), (TrackedMultiValue) entry.value);
        }
        continue;
      }

      if (fieldValue instanceof EntityImpl && ((EntityImpl) fieldValue).isEmbedded()) {
        ((EntityImpl) fieldValue).convertAllMultiValuesToTrackedVersions();
        continue;
      }

      PropertyType fieldType = entry.type;
      if (fieldType == null) {
        SchemaClass clazz = getImmutableSchemaClass();
        if (clazz != null) {
          final SchemaProperty prop = clazz.getProperty(session, fieldEntry.getKey());
          fieldType = prop != null ? prop.getType() : null;
        }
      }
      if (fieldType == null) {
        fieldType = PropertyType.getTypeByValue(fieldValue);
      }

      RecordElement newValue = null;
      switch (fieldType) {
        case EMBEDDEDLIST:
          if (fieldValue instanceof List<?>) {
            newValue = new TrackedList<>(this);
            fillTrackedCollection(
                (Collection<Object>) newValue, newValue, (Collection<Object>) fieldValue);
          }
          break;
        case EMBEDDEDSET:
          if (fieldValue instanceof Set<?>) {
            newValue = new TrackedSet<>(this);
            fillTrackedCollection(
                (Collection<Object>) newValue, newValue, (Collection<Object>) fieldValue);
          }
          break;
        case EMBEDDEDMAP:
          if (fieldValue instanceof Map<?, ?>) {
            newValue = new TrackedMap<>(this);
            fillTrackedMap(
                (Map<Object, Object>) newValue, newValue, (Map<Object, Object>) fieldValue);
          }
          break;
        case LINKLIST:
          if (fieldValue instanceof List<?>) {
            newValue = new LinkList(this, (Collection<Identifiable>) fieldValue);
          }
          break;
        case LINKSET:
          if (fieldValue instanceof Set<?>) {
            newValue = new LinkSet(this, (Collection<Identifiable>) fieldValue);
          }
          break;
        case LINKMAP:
          if (fieldValue instanceof Map<?, ?>) {
            newValue = new LinkMap(this, (Map<Object, Identifiable>) fieldValue);
          }
          break;
        case LINKBAG:
          if (fieldValue instanceof Collection<?>) {
            RidBag bag = new RidBag(session);
            bag.setOwner(this);
            bag.setRecordAndField(recordId, fieldEntry.getKey());
            bag.addAll((Collection<Identifiable>) fieldValue);
            newValue = bag;
          }
          break;
        default:
          break;
      }

      if (newValue != null) {
        entry.enableTracking(this);
        entry.value = newValue;
        if (fieldType == PropertyType.LINKSET || fieldType == PropertyType.LINKLIST) {
          for (Identifiable rec : (Collection<Identifiable>) newValue) {
            if (rec instanceof EntityImpl) {
              ((EntityImpl) rec).convertAllMultiValuesToTrackedVersions();
            }
          }
        } else {
          if (fieldType == PropertyType.LINKMAP) {
            for (Identifiable rec : (Collection<Identifiable>) ((Map<?, ?>) newValue).values()) {
              if (rec instanceof EntityImpl) {
                ((EntityImpl) rec).convertAllMultiValuesToTrackedVersions();
              }
            }
          }
        }
      }
    }
  }

  private void checkTimelineTrackable(
      MultiValueChangeTimeLine<Object, Object> timeLine,
      TrackedMultiValue<Object, Object> origin) {
    List<MultiValueChangeEvent<Object, Object>> events = timeLine.getMultiValueChangeEvents();
    for (MultiValueChangeEvent<Object, Object> event : events) {
      Object value = event.getValue();
      if (event.getChangeType() == ChangeType.ADD
          && !(value instanceof TrackedMultiValue)) {
        if (value instanceof List) {
          var newCollection = new TrackedList<>(this);
          fillTrackedCollection(newCollection, newCollection, (Collection<Object>) value);
          origin.replace(event, newCollection);
        } else {
          if (value instanceof Set) {
            var newCollection = new TrackedSet<>(this);
            fillTrackedCollection(newCollection, newCollection, (Collection<Object>) value);
            origin.replace(event, newCollection);

          } else {
            if (value instanceof Map) {
              TrackedMap<Object> newMap = new TrackedMap<>(this);
              fillTrackedMap(newMap, newMap, (Map<Object, Object>) value);
              origin.replace(event, newMap);
            }
          }
        }
      }
    }
  }

  private void fillTrackedCollection(
      Collection<Object> dest, RecordElement parent, Collection<Object> source) {
    for (Object cur : source) {
      if (cur instanceof EntityImpl) {
        ((EntityImpl) cur).addOwner((RecordElement) dest);
        ((EntityImpl) cur).convertAllMultiValuesToTrackedVersions();
        ((EntityImpl) cur).clearTrackData();
      } else {
        if (cur instanceof List) {
          @SuppressWarnings("rawtypes")
          TrackedList newList = new TrackedList<>(parent);
          fillTrackedCollection(newList, newList, (Collection<Object>) cur);
          cur = newList;
        } else {
          if (cur instanceof Set) {
            TrackedSet<Object> newSet = new TrackedSet<>(parent);
            fillTrackedCollection(newSet, newSet, (Collection<Object>) cur);
            cur = newSet;
          } else {
            if (cur instanceof Map) {
              TrackedMap<Object> newMap = new TrackedMap<>(parent);
              fillTrackedMap(newMap, newMap, (Map<Object, Object>) cur);
              cur = newMap;
            } else {
              if (cur instanceof RidBag) {
                throw new DatabaseException("RidBag are supported only at entity root");
              }
            }
          }
        }
      }
      dest.add(cur);
    }
  }

  private void fillTrackedMap(
      Map<Object, Object> dest, RecordElement parent, Map<Object, Object> source) {
    for (Entry<Object, Object> cur : source.entrySet()) {
      Object value = cur.getValue();
      if (value instanceof EntityImpl) {
        ((EntityImpl) value).convertAllMultiValuesToTrackedVersions();
        ((EntityImpl) value).clearTrackData();
      } else {
        if (cur.getValue() instanceof List) {
          TrackedList<Object> newList = new TrackedList<>(parent);
          fillTrackedCollection(newList, newList, (Collection<Object>) value);
          value = newList;
        } else {
          if (value instanceof Set) {
            TrackedSet<Object> newSet = new TrackedSet<>(parent);
            fillTrackedCollection(newSet, newSet, (Collection<Object>) value);
            value = newSet;
          } else {
            if (value instanceof Map) {
              TrackedMap<Object> newMap = new TrackedMap<>(parent);
              fillTrackedMap(newMap, newMap, (Map<Object, Object>) value);
              value = newMap;
            } else {
              if (value instanceof RidBag) {
                throw new DatabaseException("RidBag are supported only at entity root");
              }
            }
          }
        }
      }
      dest.put(cur.getKey(), value);
    }
  }

  private void internalReset() {
    removeAllCollectionChangeListeners();
    if (fields != null) {
      fields.clear();
    }
    fieldSize = 0;
  }

  boolean checkForFields(final String... iFields) {
    if (fields == null) {
      fields = ordered ? new LinkedHashMap<>() : new HashMap<>();
    }

    if (source != null) {
      checkForBinding();

      if (status == RecordElement.STATUS.LOADED) {
        return deserializeFields(iFields);
      }
    }

    return true;
  }

  Object accessProperty(final String property) {
    checkForBinding();

    if (checkForFields(property)) {
      if (propertyAccess == null || propertyAccess.isReadable(property)) {
        EntityEntry entry = fields.get(property);
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

  /**
   * Internal.
   */
  @Override
  public void setup(DatabaseSessionInternal db) {
    super.setup(db);

    if (db != null) {
      recordFormat = db.getSerializer();
    }

    if (recordFormat == null)
    // GET THE DEFAULT ONE
    {
      recordFormat = DatabaseSessionAbstract.getDefaultSerializer();
    }

    if (fields != null) {
      var processedRecords = Collections.newSetFromMap(new IdentityHashMap<>());

      for (EntityEntry entry : fields.values()) {
        if (entry.value instanceof RecordAbstract recordAbstract) {
          if (processedRecords.add(recordAbstract)) {
            recordAbstract.setup(db);
          }
        } else if (entry.value instanceof Collection<?> collection) {
          for (var item : collection) {
            if (item instanceof RecordAbstract recordAbstract) {
              if (processedRecords.add(recordAbstract)) {
                recordAbstract.setup(db);
              }
            }
          }
        } else if (entry.value instanceof Map<?, ?> map) {
          for (var item : map.values()) {
            if (item instanceof RecordAbstract recordAbstract) {
              if (processedRecords.add(recordAbstract)) {
                recordAbstract.setup(db);
              }
            }
          }
        }
      }
    }
  }

  private static String checkFieldName(final String iFieldName) {
    final Character c = SchemaShared.checkFieldNameIfValid(iFieldName);
    if (c != null) {
      throw new IllegalArgumentException(
          "Invalid field name '" + iFieldName + "'. Character '" + c + "' is invalid");
    }

    return iFieldName;
  }

  void setClass(final SchemaClass iClass) {
    checkForBinding();

    if (iClass != null && iClass.isAbstract()) {
      throw new SchemaException(
          "Cannot create a entity of the abstract class '" + iClass + "'");
    }

    if (iClass == null) {
      className = null;
    } else {
      className = iClass.getName();
    }

    immutableClazz = null;
    immutableSchemaVersion = -1;
    if (iClass != null) {
      convertFieldsToClass(iClass);
    }
  }

  Set<Entry<String, EntityEntry>> getRawEntries() {
    checkForBinding();

    checkForFields();
    return fields == null ? new HashSet<>() : fields.entrySet();
  }

  List<Entry<String, EntityEntry>> getFilteredEntries() {
    checkForBinding();
    checkForFields();

    if (fields == null) {
      return Collections.emptyList();
    } else {
      if (propertyAccess == null) {
        return fields.entrySet().stream()
            .filter((x) -> x.getValue().exists())
            .collect(Collectors.toList());
      } else {
        return fields.entrySet().stream()
            .filter((x) -> x.getValue().exists() && propertyAccess.isReadable(x.getKey()))
            .collect(Collectors.toList());
      }
    }
  }

  private void fetchSchemaIfCan() {
    if (schema == null) {
      DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
      if (db != null && !db.isClosed()) {
        MetadataInternal metadata = db.getMetadata();
        schema = metadata.getImmutableSchemaSnapshot();
      }
    }
  }

  private void fetchSchemaIfCan(DatabaseSessionInternal db) {
    if (schema == null) {
      if (db != null) {
        MetadataInternal metadata = db.getMetadata();
        schema = metadata.getImmutableSchemaSnapshot();
      }
    }
  }

  private void fetchClassName() {
    final DatabaseSessionInternal database = getSessionIfDefined();

    if (database != null && !database.isClosed()) {
      if (recordId != null) {
        if (recordId.getClusterId() >= 0) {
          final Schema schema = database.getMetadata().getImmutableSchemaSnapshot();
          if (schema != null) {
            SchemaClass clazz = schema.getClassByClusterId(recordId.getClusterId());
            if (clazz != null) {
              className = clazz.getName();
            }
          }
        }
      }
    }
  }

  void autoConvertFieldsToClass(final DatabaseSessionInternal database) {
    checkForBinding();

    if (className != null) {
      SchemaClass klazz = database.getMetadata().getImmutableSchemaSnapshot().getClass(className);
      if (klazz != null) {
        convertFieldsToClass(klazz);
      }
    }
  }

  /**
   * Checks and convert the field of the entity matching the types specified by the class.
   */
  private void convertFieldsToClass(final SchemaClass clazz) {
    var session = getSession();

    for (SchemaProperty prop : clazz.properties(session)) {
      EntityEntry entry = fields != null ? fields.get(prop.getName()) : null;
      if (entry != null && entry.exists()) {
        if (entry.type == null || entry.type != prop.getType()) {
          boolean preChanged = entry.isChanged();
          boolean preCreated = entry.isCreated();
          field(prop.getName(), entry.value, prop.getType());
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
        String defValue = prop.getDefaultValue();
        if (defValue != null && /*defValue.length() > 0 && */ !containsField(prop.getName())) {
          Object curFieldValue = SQLHelper.parseDefaultValue(session, this, defValue);
          Object fieldValue =
              DocumentHelper.convertField(session,
                  this, prop.getName(), prop.getType(), null, curFieldValue);
          rawField(prop.getName(), fieldValue, prop.getType());
        }
      }
    }
  }

  private PropertyType deriveFieldType(String iFieldName, EntityEntry entry,
      PropertyType[] iFieldType) {
    PropertyType fieldType;

    if (iFieldType != null && iFieldType.length == 1) {
      entry.type = iFieldType[0];
      fieldType = iFieldType[0];
    } else {
      fieldType = null;
    }

    SchemaClass clazz = getImmutableSchemaClass();
    if (clazz != null) {
      // SCHEMA-FULL?
      final SchemaProperty prop = clazz.getProperty(getSession(), iFieldName);
      if (prop != null) {
        entry.property = prop;
        fieldType = prop.getType();
        if (fieldType != PropertyType.ANY) {
          entry.type = fieldType;
        }
      }
    }
    return fieldType;
  }

  private void removeAllCollectionChangeListeners() {
    if (fields == null) {
      return;
    }

    for (final Map.Entry<String, EntityEntry> field : fields.entrySet()) {
      var entityEntry = field.getValue();

      var value = entityEntry.value;
      entityEntry.disableTracking(this, value);
    }
  }

  private void addAllMultiValueChangeListeners() {
    if (fields == null) {
      return;
    }

    for (final Map.Entry<String, EntityEntry> field : fields.entrySet()) {
      field.getValue().enableTracking(this);
    }
  }

  void checkClass(DatabaseSessionInternal database) {
    checkForBinding();
    if (className == null) {
      fetchClassName();
    }

    final Schema immutableSchema = database.getMetadata().getImmutableSchemaSnapshot();
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

  @Override
  protected void track(Identifiable id) {
    if (trackingChanges && id.getIdentity().getClusterId() != -2) {
      super.track(id);
    }
  }

  @Override
  protected void unTrack(Identifiable id) {
    if (trackingChanges && id.getIdentity().getClusterId() != -2) {
      super.unTrack(id);
    }
  }

  ImmutableSchema getImmutableSchema() {
    return schema;
  }

  void checkEmbeddable() {
    if (isVertex() || isEdge()) {
      throw new DatabaseException("Vertices or Edges cannot be stored as embedded");
    }
  }
}
