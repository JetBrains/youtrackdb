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
import com.jetbrains.youtrack.db.api.record.Entity;
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
import java.util.regex.Pattern;

/**
 * Contains the description of a persistent class property.
 */
public abstract class SchemaPropertyImpl implements SchemaPropertyInternal {

  private static final Pattern DOUBLE_SLASH_PATTERN = Pattern.compile("\\\\");
  private static final Pattern QUOTATION_PATTERN = Pattern.compile("\"");
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

  public SchemaPropertyImpl(final SchemaClassImpl owner) {
    this.owner = owner;
  }

  public SchemaPropertyImpl(SchemaClassImpl oClassImpl, GlobalProperty global) {
    this(oClassImpl);
    this.globalRef = global;
  }

  public String getName(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return globalRef.getName();
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public String getFullName(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return owner.getName(session) + "." + globalRef.getName();
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public String getFullNameQuoted(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return "`" + owner.getName(session) + "`.`" + globalRef.getName() + "`";
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public PropertyType getType(DatabaseSession db) {
    var session = (DatabaseSessionInternal) db;
    acquireSchemaReadLock(session);
    try {
      return globalRef.getType();
    } finally {
      releaseSchemaReadLock(session);
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
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      var indexName = getFullName(sessionInternal);
      owner.createIndex(session, indexName, iType, globalRef.getName());
      return indexName;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  @Override
  public String createIndex(DatabaseSession session, INDEX_TYPE iType, Map<String, ?> metadata) {
    return createIndex(session, iType.name(), metadata);
  }

  public String createIndex(DatabaseSession session, String iType, Map<String, ?> metadata) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      var indexName = getFullName(sessionInternal);
      owner.createIndex(session,
          indexName, iType, null, metadata, new String[]{globalRef.getName()});
      return indexName;
    } finally {
      releaseSchemaReadLock(sessionInternal);
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

    acquireSchemaReadLock(session);
    try {
      final var indexManager = session.getMetadata().getIndexManagerInternal();

      final var relatedIndexes = new ArrayList<Index>();
      for (final var index : indexManager.getClassIndexes(session, owner.getName(session))) {
        final var definition = index.getDefinition();

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

      for (final var index : relatedIndexes) {
        session.getMetadata().getIndexManagerInternal().dropIndex(session, index.getName());
      }

      return this;
    } finally {
      releaseSchemaReadLock(session);
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

  @Deprecated
  public boolean isIndexed(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return owner.areIndexed(sessionInternal, globalRef.getName());
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public SchemaClass getOwnerClass() {
    return owner;
  }

  /**
   * Returns the linked class in lazy mode because while unmarshalling the class could be not loaded
   * yet.
   */
  public SchemaClass getLinkedClass(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      if (linkedClass == null && linkedClassName != null) {
        linkedClass = owner.owner.getClass(sessionInternal, linkedClassName);
      }
      return linkedClass;
    } finally {
      releaseSchemaReadLock(sessionInternal);
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

  public PropertyType getLinkedType(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return linkedType;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public static void checkLinkTypeSupport(PropertyType type) {
    if (type != PropertyType.EMBEDDEDSET && type != PropertyType.EMBEDDEDLIST
        && type != PropertyType.EMBEDDEDMAP) {
      throw new SchemaException("Linked type is not supported for type: " + type);
    }
  }

  public boolean isNotNull(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return notNull;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public boolean isMandatory(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return mandatory;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public boolean isReadonly(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return readonly;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public String getMin(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return min;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public String getMax(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return max;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  protected static Object quoteString(String s) {
    if (s == null) {
      return "null";
    }
    return "\"" + (QUOTATION_PATTERN.matcher(DOUBLE_SLASH_PATTERN.matcher(s).replaceAll("\\\\\\\\"))
        .replaceAll("\\\\\"")) + "\"";
  }

  public String getDefaultValue(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return defaultValue;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public String getRegexp(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return regexp;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public String getCustom(DatabaseSession db, final String iName) {
    var session = (DatabaseSessionInternal) db;
    acquireSchemaReadLock(session);
    try {
      if (customFields == null) {
        return null;
      }

      return customFields.get(iName);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Map<String, String> getCustomInternal(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      if (customFields != null) {
        return java.util.Collections.unmodifiableMap(customFields);
      }
      return null;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void removeCustom(DatabaseSession session, final String iName) {
    setCustom(session, iName, null);
  }

  public Set<String> getCustomKeys(DatabaseSession db) {
    var session = (DatabaseSessionInternal) db;
    acquireSchemaReadLock(session);
    try {
      if (customFields != null) {
        return customFields.keySet();
      }

      return new HashSet<>();
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Object get(DatabaseSession db, final ATTRIBUTES attribute) {
    var session = (DatabaseSessionInternal) db;
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    return switch (attribute) {
      case LINKEDCLASS -> getLinkedClass(session);
      case LINKEDTYPE -> getLinkedType(session);
      case MIN -> getMin(session);
      case MANDATORY -> isMandatory(session);
      case READONLY -> isReadonly(session);
      case MAX -> getMax(session);
      case DEFAULT -> getDefaultValue(session);
      case NAME -> getName(session);
      case NOTNULL -> isNotNull(session);
      case REGEXP -> getRegexp(session);
      case TYPE -> getType(session);
      case COLLATE -> getCollate(session);
      case DESCRIPTION -> getDescription(session);
      default -> throw new IllegalArgumentException("Cannot find attribute '" + attribute + "'");
    };
  }

  public void set(DatabaseSession session, final ATTRIBUTES attribute, final Object iValue) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = iValue != null ? iValue.toString() : null;
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
        var indx = stringValue != null ? stringValue.indexOf('=') : -1;
        if (indx < 0) {
          if ("clear".equalsIgnoreCase(stringValue)) {
            clearCustom(session);
          } else {
            throw new IllegalArgumentException(
                "Syntax error: expected <name> = <value> or clear, instead found: " + iValue);
          }
        } else {
          var customName = stringValue.substring(0, indx).trim();
          var customValue = stringValue.substring(indx + 1).trim();
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

  private static String removeQuotes(String s) {
    s = s.trim();
    return s.substring(1, s.length() - 1);
  }

  private static boolean isQuoted(String s) {
    s = s.trim();
    if (!s.isEmpty() && s.charAt(0) == '\"' && s.charAt(s.length() - 1) == '\"') {
      return true;
    }
    if (!s.isEmpty() && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
      return true;
    }
    return !s.isEmpty() && s.charAt(0) == '`' && s.charAt(s.length() - 1) == '`';
  }

  public Collate getCollate(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return collate;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public SchemaProperty setCollate(DatabaseSession session, final Collate collate) {
    setCollate(session, collate.getName());
    return this;
  }

  @Override
  public String getDescription(DatabaseSession session) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      return description;
    } finally {
      releaseSchemaReadLock(sessionInternal);
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
        throw new UnsupportedOperationException("Type is not defined for property " + name);
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
      customFields = entity.getProperty("customFields");
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
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaReadLock(sessionInternal);
    try {
      final Set<Index> indexes = new HashSet<>();
      owner.getIndexesInternal(sessionInternal, indexes);

      final List<Index> indexList = new LinkedList<>();
      for (final var index : indexes) {
        final var indexDefinition = index.getDefinition();
        if (indexDefinition.getFields().contains(globalRef.getName())) {
          indexList.add(index);
        }
      }

      return indexList;
    } finally {
      releaseSchemaReadLock(sessionInternal);
    }
  }

  public Entity toStream(DatabaseSessionInternal session) {
    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", getName(session));
    entity.setProperty("type", getType(session).getId());
    entity.setProperty("globalId", globalRef.getId());
    entity.setProperty("mandatory", mandatory);
    entity.setProperty("readonly", readonly);
    entity.setProperty("notNull", notNull);
    entity.setProperty("defaultValue", defaultValue);

    entity.setProperty("min", min);
    entity.setProperty("max", max);
    if (regexp != null) {
      entity.setProperty("regexp", regexp);
    }

    if (linkedType != null) {
      entity.setProperty("linkedType", linkedType.getId());
    }
    if (linkedClass != null || linkedClassName != null) {
      entity.setProperty("linkedClass",
          linkedClass != null ? linkedClass.getName(session) : linkedClassName);
    }

    if (customFields != null && customFields.isEmpty()) {
      var storedCustomFields = entity.getOrCreateEmbeddedMap("customFields");
      storedCustomFields.clear();
      storedCustomFields.putAll(customFields);
    } else {
      entity.removeProperty("customFields");
    }

    if (collate != null) {
      entity.setProperty("collate", collate.getName());
    }

    entity.setProperty("description", description);
    return entity;
  }

  public void acquireSchemaReadLock(DatabaseSessionInternal db) {
    owner.acquireSchemaReadLock(db);
  }

  public void releaseSchemaReadLock(DatabaseSessionInternal session) {
    owner.releaseSchemaReadLock(session);
  }

  public void acquireSchemaWriteLock(DatabaseSessionInternal session) {
    owner.acquireSchemaWriteLock(session);
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal session) {
    owner.releaseSchemaWriteLock(session);
  }

  public static void checkEmbedded(DatabaseSessionInternal session) {
    if (session.isRemote()) {
      throw new SchemaException(session.getDatabaseName(),
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
              new SchemaException(session.getDatabaseName(),
                  "Invalid date format while formatting date '" + iDateAsString + "'"),
              e, session.getDatabaseName());
        }
      } else if (globalRef.getType() == PropertyType.DATETIME) {
        try {
          DateHelper.getDateTimeFormatInstance(session).parse(iDateAsString);
        } catch (ParseException e) {
          throw BaseException.wrapException(
              new SchemaException(session.getDatabaseName(),
                  "Invalid datetime format while formatting date '" + iDateAsString + "'"),
              e, session.getDatabaseName());
        }
      }
    }
  }

  @Override
  public Integer getId() {
    return globalRef.getId();
  }

}
