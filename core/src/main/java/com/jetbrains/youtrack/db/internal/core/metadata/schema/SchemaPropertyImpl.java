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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.comparator.CaseInsentiveComparator;
import com.jetbrains.youtrack.db.internal.common.util.Collections;
import com.jetbrains.youtrack.db.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Contains the description of a persistent class property.
 */
public abstract class SchemaPropertyImpl implements SchemaPropertyInternal {

  protected final SchemaClassImpl owner;
  protected PropertyType linkedType;
  protected SchemaClass linkedClass;
  private transient String linkedClassName;

  protected String description;
  protected boolean mandatory;
  protected boolean notNull = false;
  protected String min;
  protected String max;
  protected String defaultValue;
  protected String regexp;
  protected boolean readonly;
  protected Map<String, String> customFields;
  protected Collate collate = new DefaultCollate();
  protected GlobalProperty globalRef;

  private volatile int hashCode;

  public SchemaPropertyImpl(final SchemaClassImpl owner) {
    this.owner = owner;
  }

  public SchemaPropertyImpl(SchemaClassImpl oClassImpl, GlobalProperty global) {
    this(oClassImpl);
    this.globalRef = global;
  }

  public String getName() {
    acquireSchemaReadLock();
    try {
      return globalRef.getName();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getFullName() {
    acquireSchemaReadLock();
    try {
      return owner.getName() + "." + globalRef.getName();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getFullNameQuoted() {
    acquireSchemaReadLock();
    try {
      return "`" + owner.getName() + "`.`" + globalRef.getName() + "`";
    } finally {
      releaseSchemaReadLock();
    }
  }

  public PropertyType getType() {
    acquireSchemaReadLock();
    try {
      return globalRef.getType();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public int compareTo(final SchemaProperty o) {
    acquireSchemaReadLock();
    try {
      return globalRef.getName().compareTo(o.getName());
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param iType One of types supported.
   *              <ul>
   *                <li>UNIQUE: Doesn't allow duplicates
   *                <li>NOTUNIQUE: Allow duplicates
   *                <li>FULLTEXT: Indexes single word for full text search
   *              </ul>
   */
  @Override
  public String createIndex(DatabaseSession session, final INDEX_TYPE iType) {
    return createIndex(session, iType.toString());
  }

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @return the index name
   */
  @Override
  public String createIndex(DatabaseSession session, final String iType) {
    acquireSchemaReadLock();
    try {
      var indexName = getFullName();
      owner.createIndex(session, indexName, iType, globalRef.getName());
      return indexName;
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Override
  public String createIndex(DatabaseSession session, INDEX_TYPE iType, Map<String, ?> metadata) {
    return createIndex(session, iType.name(), metadata);
  }

  public String createIndex(DatabaseSession session, String iType, Map<String, ?> metadata) {
    acquireSchemaReadLock();
    try {
      var indexName = getFullName();
      owner.createIndex(session,
          indexName, iType, null, metadata, new String[]{globalRef.getName()});
      return indexName;
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * Remove the index on property
   *
   * @deprecated Use SQL command instead.
   */
  @Deprecated
  public SchemaPropertyImpl dropIndexes(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    acquireSchemaReadLock();
    try {
      final IndexManagerAbstract indexManager = session.getMetadata().getIndexManagerInternal();

      final ArrayList<Index> relatedIndexes = new ArrayList<Index>();
      for (final Index index : indexManager.getClassIndexes(session, owner.getName())) {
        final IndexDefinition definition = index.getDefinition();

        if (Collections.indexOf(
            definition.getFields(), globalRef.getName(), new CaseInsentiveComparator())
            > -1) {
          if (definition instanceof PropertyIndexDefinition) {
            relatedIndexes.add(index);
          } else {
            throw new IllegalArgumentException(
                "This operation applicable only for property indexes. "
                    + index.getName()
                    + " is "
                    + index.getDefinition());
          }
        }
      }

      for (final Index index : relatedIndexes) {
        session.getMetadata().getIndexManagerInternal().dropIndex(session, index.getName());
      }

      return this;
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * Remove the index on property
   *
   * @deprecated
   */
  @Deprecated
  public void dropIndexesInternal(DatabaseSessionInternal session) {
    dropIndexes(session);
  }

  /**
   * @deprecated Use {@link SchemaClass#areIndexed(DatabaseSession, String...)} instead.
   */
  @Deprecated
  public boolean isIndexed(DatabaseSession session) {
    acquireSchemaReadLock();
    try {
      return owner.areIndexed(session, globalRef.getName());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClass getOwnerClass() {
    return owner;
  }

  /**
   * Returns the linked class in lazy mode because while unmarshalling the class could be not loaded
   * yet.
   *
   * @return
   */
  public SchemaClass getLinkedClass(DatabaseSession session) {
    acquireSchemaReadLock();
    try {
      if (this.linkedClass == null && linkedClassName != null) {
        this.linkedClass = owner.owner.getClass((DatabaseSessionInternal) session, linkedClassName);
      }
      return linkedClass;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public static void checkSupportLinkedClass(PropertyType type) {
    if (type != PropertyType.LINK
        && type != PropertyType.LINKSET
        && type != PropertyType.LINKLIST
        && type != PropertyType.LINKMAP
        && type != PropertyType.EMBEDDED
        && type != PropertyType.EMBEDDEDSET
        && type != PropertyType.EMBEDDEDLIST
        && type != PropertyType.EMBEDDEDMAP
        && type != PropertyType.LINKBAG) {
      throw new SchemaException("Linked class is not supported for type: " + type);
    }
  }

  public PropertyType getLinkedType() {
    acquireSchemaReadLock();
    try {
      return linkedType;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public static void checkLinkTypeSupport(PropertyType type) {
    if (type != PropertyType.EMBEDDEDSET && type != PropertyType.EMBEDDEDLIST
        && type != PropertyType.EMBEDDEDMAP) {
      throw new SchemaException("Linked type is not supported for type: " + type);
    }
  }

  public boolean isNotNull() {
    acquireSchemaReadLock();
    try {
      return notNull;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public boolean isMandatory() {
    acquireSchemaReadLock();
    try {
      return mandatory;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public boolean isReadonly() {
    acquireSchemaReadLock();
    try {
      return readonly;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getMin() {
    acquireSchemaReadLock();
    try {
      return min;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getMax() {
    acquireSchemaReadLock();
    try {
      return max;
    } finally {
      releaseSchemaReadLock();
    }
  }

  protected static Object quoteString(String s) {
    if (s == null) {
      return "null";
    }
    String result = "\"" + (s.replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"")) + "\"";
    return result;
  }

  public String getDefaultValue() {
    acquireSchemaReadLock();
    try {
      return defaultValue;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getRegexp() {
    acquireSchemaReadLock();
    try {
      return regexp;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getCustom(final String iName) {
    acquireSchemaReadLock();
    try {
      if (customFields == null) {
        return null;
      }

      return customFields.get(iName);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Map<String, String> getCustomInternal() {
    acquireSchemaReadLock();
    try {
      if (customFields != null) {
        return java.util.Collections.unmodifiableMap(customFields);
      }
      return null;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void removeCustom(DatabaseSession session, final String iName) {
    setCustom(session, iName, null);
  }

  public Set<String> getCustomKeys() {
    acquireSchemaReadLock();
    try {
      if (customFields != null) {
        return customFields.keySet();
      }

      return new HashSet<String>();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Object get(DatabaseSession session, final ATTRIBUTES attribute) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    switch (attribute) {
      case LINKEDCLASS:
        return getLinkedClass(session);
      case LINKEDTYPE:
        return getLinkedType();
      case MIN:
        return getMin();
      case MANDATORY:
        return isMandatory();
      case READONLY:
        return isReadonly();
      case MAX:
        return getMax();
      case DEFAULT:
        return getDefaultValue();
      case NAME:
        return getName();
      case NOTNULL:
        return isNotNull();
      case REGEXP:
        return getRegexp();
      case TYPE:
        return getType();
      case COLLATE:
        return getCollate();
      case DESCRIPTION:
        return getDescription();
    }

    throw new IllegalArgumentException("Cannot find attribute '" + attribute + "'");
  }

  public void set(DatabaseSession session, final ATTRIBUTES attribute, final Object iValue) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final String stringValue = iValue != null ? iValue.toString() : null;
    var sessionInternal = (DatabaseSessionInternal) session;

    switch (attribute) {
      case LINKEDCLASS:
        setLinkedClass(session, sessionInternal.getMetadata().getSchema().getClass(stringValue));
        break;
      case LINKEDTYPE:
        if (stringValue == null) {
          setLinkedType(session, null);
        } else {
          setLinkedType(session, PropertyType.valueOf(stringValue));
        }
        break;
      case MIN:
        setMin(session, stringValue);
        break;
      case MANDATORY:
        setMandatory(session, Boolean.parseBoolean(stringValue));
        break;
      case READONLY:
        setReadonly(session, Boolean.parseBoolean(stringValue));
        break;
      case MAX:
        setMax(session, stringValue);
        break;
      case DEFAULT:
        setDefaultValue(session, stringValue);
        break;
      case NAME:
        setName(session, stringValue);
        break;
      case NOTNULL:
        setNotNull(session, Boolean.parseBoolean(stringValue));
        break;
      case REGEXP:
        setRegexp(session, stringValue);
        break;
      case TYPE:
        setType(session, PropertyType.valueOf(stringValue.toUpperCase(Locale.ENGLISH)));
        break;
      case COLLATE:
        setCollate(session, stringValue);
        break;
      case CUSTOM:
        int indx = stringValue != null ? stringValue.indexOf('=') : -1;
        if (indx < 0) {
          if ("clear".equalsIgnoreCase(stringValue)) {
            clearCustom(session);
          } else {
            throw new IllegalArgumentException(
                "Syntax error: expected <name> = <value> or clear, instead found: " + iValue);
          }
        } else {
          String customName = stringValue.substring(0, indx).trim();
          String customValue = stringValue.substring(indx + 1).trim();
          if (isQuoted(customValue)) {
            customValue = removeQuotes(customValue);
          }
          if (customValue.isEmpty()) {
            removeCustom(session, customName);
          } else {
            setCustom(session, customName, customValue);
          }
        }
        break;
      case DESCRIPTION:
        setDescription(session, stringValue);
        break;
    }
  }

  private String removeQuotes(String s) {
    s = s.trim();
    return s.substring(1, s.length() - 1);
  }

  private boolean isQuoted(String s) {
    s = s.trim();
    if (s.startsWith("\"") && s.endsWith("\"")) {
      return true;
    }
    if (s.startsWith("'") && s.endsWith("'")) {
      return true;
    }
    return s.startsWith("`") && s.endsWith("`");
  }

  public Collate getCollate() {
    acquireSchemaReadLock();
    try {
      return collate;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaProperty setCollate(DatabaseSession session, final Collate collate) {
    setCollate(session, collate.getName());
    return this;
  }

  @Override
  public String getDescription() {
    acquireSchemaReadLock();
    try {
      return description;
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Override
  public String toString() {
    acquireSchemaReadLock();
    try {
      return globalRef.getName() + " (type=" + globalRef.getType() + ")";
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Override
  public int hashCode() {
    int sh = hashCode;
    if (sh != 0) {
      return sh;
    }

    acquireSchemaReadLock();
    try {
      sh = hashCode;
      if (sh != 0) {
        return sh;
      }

      calculateHashCode();
      return hashCode;
    } finally {
      releaseSchemaReadLock();
    }
  }

  private void calculateHashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + ((owner == null) ? 0 : owner.hashCode());
    hashCode = result;
  }

  @Override
  public boolean equals(final Object obj) {
    acquireSchemaReadLock();
    try {
      if (this == obj) {
        return true;
      }
      if (obj == null || !SchemaProperty.class.isAssignableFrom(obj.getClass())) {
        return false;
      }
      SchemaProperty other = (SchemaProperty) obj;
      if (owner == null) {
        if (other.getOwnerClass() != null) {
          return false;
        }
      } else if (!owner.equals(other.getOwnerClass())) {
        return false;
      }
      return this.getName().equals(other.getName());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void fromStream(EntityImpl entity) {
    String name = entity.field("name");
    PropertyType type = null;
    if (entity.field("type") != null) {
      type = PropertyType.getById(((Integer) entity.field("type")).byteValue());
    }
    Integer globalId = entity.field("globalId");
    if (globalId != null) {
      globalRef = owner.owner.getGlobalPropertyById(globalId);
    } else {
      if (type == null) {
        type = PropertyType.ANY;
      }
      globalRef = owner.owner.findOrCreateGlobalProperty(name, type);
    }

    mandatory = entity.containsField("mandatory") ? (Boolean) entity.field("mandatory") : false;
    readonly = entity.containsField("readonly") ? (Boolean) entity.field("readonly") : false;
    notNull = entity.containsField("notNull") ? (Boolean) entity.field("notNull") : false;
    defaultValue = entity.containsField("defaultValue") ? entity.field("defaultValue") : null;
    if (entity.containsField("collate")) {
      collate = SQLEngine.getCollate(entity.field("collate"));
    }

    min = entity.containsField("min") ? entity.field("min") : null;
    max = entity.containsField("max") ? entity.field("max") : null;
    regexp = entity.containsField("regexp") ? entity.field("regexp") : null;
    linkedClassName = entity.containsField("linkedClass") ? entity.field("linkedClass") : null;
    linkedType =
        entity.field("linkedType") != null
            ? PropertyType.getById(((Integer) entity.field("linkedType")).byteValue())
            : null;
    if (entity.containsField("customFields")) {
      customFields = entity.field("customFields", PropertyType.EMBEDDEDMAP);
    } else {
      customFields = null;
    }
    description = entity.containsField("description") ? entity.field("description") : null;
  }

  @Override
  public Collection<String> getAllIndexes(DatabaseSession session) {
    return getAllIndexesInternal(session).stream().map(Index::getName).toList();
  }

  @Override
  public Collection<Index> getAllIndexesInternal(DatabaseSession session) {
    acquireSchemaReadLock();
    try {
      final Set<Index> indexes = new HashSet<Index>();
      owner.getIndexesInternal(session, indexes);

      final List<Index> indexList = new LinkedList<Index>();
      for (final Index index : indexes) {
        final IndexDefinition indexDefinition = index.getDefinition();
        if (indexDefinition.getFields().contains(globalRef.getName())) {
          indexList.add(index);
        }
      }

      return indexList;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public EntityImpl toStream() {
    EntityImpl entity = new EntityImpl();
    entity.field("name", getName());
    entity.field("type", getType().getId());
    entity.field("globalId", globalRef.getId());
    entity.field("mandatory", mandatory);
    entity.field("readonly", readonly);
    entity.field("notNull", notNull);
    entity.field("defaultValue", defaultValue);

    entity.field("min", min);
    entity.field("max", max);
    if (regexp != null) {
      entity.field("regexp", regexp);
    } else {
      entity.removeField("regexp");
    }
    if (linkedType != null) {
      entity.field("linkedType", linkedType.getId());
    }
    if (this.linkedClass != null || linkedClassName != null) {
      entity.field("linkedClass", linkedClass != null ? linkedClass.getName() : linkedClassName);
    }

    entity.field(
        "customFields",
        customFields != null && customFields.size() > 0 ? customFields : null,
        PropertyType.EMBEDDEDMAP);
    if (collate != null) {
      entity.field("collate", collate.getName());
    }
    entity.field("description", description);
    return entity;
  }

  public void acquireSchemaReadLock() {
    owner.acquireSchemaReadLock();
  }

  public void releaseSchemaReadLock() {
    owner.releaseSchemaReadLock();
  }

  public void acquireSchemaWriteLock(DatabaseSessionInternal session) {
    owner.acquireSchemaWriteLock(session);
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal session) {
    calculateHashCode();
    owner.releaseSchemaWriteLock(session);
  }

  public static void checkEmbedded(DatabaseSessionInternal session) {
    if (session.isRemote()) {
      throw new SchemaException(
          "'Internal' schema modification methods can be used only inside of embedded database");
    }
  }

  protected void checkForDateFormat(DatabaseSessionInternal session, final String iDateAsString) {
    if (iDateAsString != null) {
      if (globalRef.getType() == PropertyType.DATE) {
        try {
          DateHelper.getDateFormatInstance(session).parse(iDateAsString);
        } catch (ParseException e) {
          throw BaseException.wrapException(
              new SchemaException(
                  "Invalid date format while formatting date '" + iDateAsString + "'"),
              e);
        }
      } else if (globalRef.getType() == PropertyType.DATETIME) {
        try {
          DateHelper.getDateTimeFormatInstance(session).parse(iDateAsString);
        } catch (ParseException e) {
          throw BaseException.wrapException(
              new SchemaException(
                  "Invalid datetime format while formatting date '" + iDateAsString + "'"),
              e);
        }
      }
    }
  }

  @Override
  public Integer getId() {
    return globalRef.getId();
  }

  public EntityImpl toNetworkStream() {
    EntityImpl entity = new EntityImpl();
    entity.setTrackingChanges(false);
    entity.field("name", getName());
    entity.field("type", getType().getId());
    entity.field("globalId", globalRef.getId());
    entity.field("mandatory", mandatory);
    entity.field("readonly", readonly);
    entity.field("notNull", notNull);
    entity.field("defaultValue", defaultValue);

    entity.field("min", min);
    entity.field("max", max);
    if (regexp != null) {
      entity.field("regexp", regexp);
    } else {
      entity.removeField("regexp");
    }
    if (linkedType != null) {
      entity.field("linkedType", linkedType.getId());
    }
    if (linkedClass != null || linkedClassName != null) {
      entity.field("linkedClass", linkedClass != null ? linkedClass.getName() : linkedClassName);
    }

    entity.field(
        "customFields",
        customFields != null && customFields.size() > 0 ? customFields : null,
        PropertyType.EMBEDDEDMAP);
    if (collate != null) {
      entity.field("collate", collate.getName());
    }
    entity.field("description", description);

    return entity;
  }
}
