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

import com.jetbrains.youtrackdb.internal.common.comparator.CaseInsentiveComparator;
import com.jetbrains.youtrackdb.internal.common.util.Collections;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
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
import javax.annotation.Nullable;

/**
 * Contains the description of a persistent class property.
 */
public abstract class SchemaPropertyImpl {

  private static final Pattern DOUBLE_SLASH_PATTERN = Pattern.compile("\\\\");
  private static final Pattern QUOTATION_PATTERN = Pattern.compile("\"");
  protected final SchemaClassImpl owner;
  protected PropertyTypeInternal linkedType;
  protected SchemaClassImpl linkedClass;
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
  protected GlobalPropertyImpl globalRef;

  public SchemaPropertyImpl(final SchemaClassImpl owner) {
    this.owner = owner;
  }

  public SchemaPropertyImpl(SchemaClassImpl oClassImpl, GlobalPropertyImpl global) {
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

  public String getFullName(DatabaseSessionEmbedded session) {
    acquireSchemaReadLock();
    try {
      return owner.getName() + "." + globalRef.getName();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getFullNameQuoted(DatabaseSessionEmbedded session) {
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

  public PropertyTypeInternal getTypeInternal() {
    acquireSchemaReadLock();
    try {
      return globalRef.getTypeInternal();
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
  public String createIndex(DatabaseSessionEmbedded session, final INDEX_TYPE iType) {
    return createIndex(session, iType.toString());
  }

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @return the index name
   */
  public String createIndex(DatabaseSessionEmbedded session, final String iType) {
    acquireSchemaReadLock();
    try {
      var indexName = getFullName(session);
      owner.createIndex(session, indexName, iType, globalRef.getName());
      return indexName;
    } finally {
      releaseSchemaReadLock();
    }
  }


  public String createIndex(DatabaseSessionEmbedded session, INDEX_TYPE iType,
      Map<String, Object> metadata) {
    return createIndex(session, iType.name(), metadata);
  }

  public String createIndex(DatabaseSessionEmbedded session, String iType,
      Map<String, Object> metadata) {
    acquireSchemaReadLock();
    try {
      var indexName = getFullName(session);
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
  public void dropIndexes(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    acquireSchemaReadLock();
    try {
      final var indexManager = session.getSharedContext().getIndexManager();

      final var relatedIndexes = new ArrayList<Index>();
      for (final var index : indexManager.getClassIndexes(session, owner.getName())) {
        final var definition = index.getDefinition();

        if (Collections.indexOf(
            definition.getProperties(), globalRef.getName(), new CaseInsentiveComparator())
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
        session.getSharedContext().getIndexManager()
            .dropIndex(session, index.getName());
      }
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
  public void dropIndexesInternal(DatabaseSessionEmbedded session) {
    dropIndexes(session);
  }

  @Deprecated
  public boolean isIndexed(DatabaseSessionEmbedded session) {
    acquireSchemaReadLock();
    try {
      return owner.areIndexed(session, globalRef.getName());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClassImpl getOwnerClass() {
    return owner;
  }

  /**
   * Returns the linked class in lazy mode because while unmarshalling the class could be not loaded
   * yet.
   */
  public SchemaClassImpl getLinkedClass(DatabaseSessionEmbedded session) {
    acquireSchemaReadLock();
    try {
      if (linkedClass == null && linkedClassName != null) {
        linkedClass = owner.owner.getClass(linkedClassName);
      }
      return linkedClass;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public static void checkSupportLinkedClass(PropertyTypeInternal type) {
    if (type != PropertyTypeInternal.LINK
        && type != PropertyTypeInternal.LINKSET
        && type != PropertyTypeInternal.LINKLIST
        && type != PropertyTypeInternal.LINKMAP
        && type != PropertyTypeInternal.EMBEDDED
        && type != PropertyTypeInternal.EMBEDDEDSET
        && type != PropertyTypeInternal.EMBEDDEDLIST
        && type != PropertyTypeInternal.EMBEDDEDMAP
        && type != PropertyTypeInternal.LINKBAG) {
      throw new SchemaException("Linked class is not supported for type: " + type);
    }
  }

  public PropertyTypeInternal getLinkedType() {
    acquireSchemaReadLock();
    try {
      return linkedType;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public static void checkLinkTypeSupport(PropertyTypeInternal type) {
    if (type != PropertyTypeInternal.EMBEDDEDSET && type != PropertyTypeInternal.EMBEDDEDLIST
        && type != PropertyTypeInternal.EMBEDDEDMAP) {
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
    return "\"" + QUOTATION_PATTERN.matcher(DOUBLE_SLASH_PATTERN.matcher(s).replaceAll("\\\\\\\\"))
        .replaceAll("\\\\\"") + "\"";
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

  @Nullable
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

  @Nullable
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

  public void removeCustom(DatabaseSessionEmbedded session, final String iName) {
    setCustom(session, iName, null);
  }

  public abstract void setCustom(DatabaseSessionEmbedded session, final String iName,
      final String iValue);

  public Set<String> getCustomKeys() {
    acquireSchemaReadLock();
    try {
      if (customFields != null) {
        return customFields.keySet();
      }

      return new HashSet<>();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Object get(DatabaseSessionEmbedded db, final ATTRIBUTES attribute) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    return switch (attribute) {
      case LINKEDCLASS -> getLinkedClass(db);
      case LINKEDTYPE -> getLinkedType();
      case MIN -> getMin();
      case MANDATORY -> isMandatory();
      case READONLY -> isReadonly();
      case MAX -> getMax();
      case DEFAULT -> getDefaultValue();
      case NAME -> getName();
      case NOTNULL -> isNotNull();
      case REGEXP -> getRegexp();
      case TYPE -> getType();
      case COLLATE -> getCollate();
      case DESCRIPTION -> getDescription();
      default -> throw new IllegalArgumentException("Cannot find attribute '" + attribute + "'");
    };
  }

  public void set(DatabaseSessionEmbedded session, final ATTRIBUTES attribute,
      final Object iValue) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = iValue != null ? iValue.toString() : null;

    switch (attribute) {
      case LINKEDCLASS ->
          setLinkedClass(session,
              session.getSharedContext().getSchema().getClass(stringValue));
      case LINKEDTYPE -> {
        if (stringValue == null) {
          setLinkedType(session, null);
        } else {
          setLinkedType(session, PropertyTypeInternal.valueOf(stringValue));
        }
      }
      case MIN -> setMin(session, stringValue);
      case MANDATORY -> setMandatory(session, Boolean.parseBoolean(stringValue));
      case READONLY -> setReadonly(session, Boolean.parseBoolean(stringValue));
      case MAX -> setMax(session, stringValue);
      case DEFAULT -> setDefaultValue(session, stringValue);
      case NAME -> setName(session, stringValue);
      case NOTNULL -> setNotNull(session, Boolean.parseBoolean(stringValue));
      case REGEXP -> setRegexp(session, stringValue);
      case TYPE ->
          setType(session,
              PropertyTypeInternal.valueOf(stringValue.toUpperCase(Locale.ENGLISH)));
      case COLLATE -> setCollate(session, stringValue);
      case CUSTOM -> {
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
      }
      case DESCRIPTION -> setDescription(session, stringValue);
    }
  }

  public abstract void setLinkedClass(DatabaseSessionEmbedded session, SchemaClassImpl oClass);

  public abstract void setLinkedType(DatabaseSessionEmbedded session, PropertyTypeInternal type);

  public abstract void setMin(DatabaseSessionEmbedded session, String min);

  public abstract void setMandatory(DatabaseSessionEmbedded session, boolean mandatory);

  public abstract void setReadonly(DatabaseSessionEmbedded session, boolean iReadonly);

  public abstract void setMax(DatabaseSessionEmbedded session, String max);

  public abstract void setDefaultValue(DatabaseSessionEmbedded session, String defaultValue);

  public abstract void setName(DatabaseSessionEmbedded session, String iName);

  public abstract void setNotNull(DatabaseSessionEmbedded session, boolean iNotNull);

  public abstract void setRegexp(DatabaseSessionEmbedded session, String regexp);

  public abstract void setType(DatabaseSessionEmbedded session, final PropertyTypeInternal iType);

  public abstract void setDescription(DatabaseSessionEmbedded session, String iDescription);

  public abstract void setCollate(DatabaseSessionEmbedded session, String iCollateName);

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

  public Collate getCollate() {
    acquireSchemaReadLock();
    try {
      return collate;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void setCollate(DatabaseSessionEmbedded session, final Collate collate) {
    setCollate(session, collate.getName());
  }

  public String getDescription() {
    acquireSchemaReadLock();
    try {
      return description;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void fromStream(EntityImpl entity) {
    String name = entity.getProperty("name");
    PropertyTypeInternal type = null;
    if (entity.getProperty("type") != null) {
      type = PropertyTypeInternal.getById(((Integer) entity.getProperty("type")).byteValue());
    }
    Integer globalId = entity.getProperty("globalId");
    if (globalId != null) {
      globalRef = owner.owner.getGlobalPropertyById(globalId);
    } else {
      if (type == null) {
        throw new UnsupportedOperationException("Type is not defined for property " + name);
      }
      globalRef = owner.owner.findOrCreateGlobalProperty(name, type);
    }

    if (entity.hasProperty("mandatory")) {
      mandatory = entity.getProperty("mandatory");
    } else {
      mandatory = false;
    }
    if (entity.hasProperty("readonly")) {
      readonly = entity.getProperty("readonly");
    } else {
      readonly = false;
    }
    if (entity.hasProperty("notNull")) {
      notNull = entity.getProperty("notNull");
    } else {
      notNull = false;
    }
    if (entity.hasProperty("defaultValue")) {
      defaultValue = entity.getProperty("defaultValue");
    } else {
      defaultValue = null;
    }
    if (entity.hasProperty("collate")) {
      collate = SQLEngine.getCollate(entity.getProperty("collate"));
    }

    if (entity.hasProperty("min")) {
      min = entity.getProperty("min");
    } else {
      min = null;
    }
    if (entity.hasProperty("max")) {
      max = entity.getProperty("max");
    } else {
      max = null;
    }
    if (entity.hasProperty("regexp")) {
      regexp = entity.getProperty("regexp");
    } else {
      regexp = null;
    }
    if (entity.hasProperty("linkedClass")) {
      linkedClassName = entity.getProperty("linkedClass");
    } else {
      linkedClassName = null;
    }
    if (entity.getProperty("linkedType") != null) {
      linkedType =
          PropertyTypeInternal.getById(((Integer) entity.getProperty("linkedType")).byteValue());
    } else {
      linkedType =
          null;
    }
    if (entity.hasProperty("customFields")) {
      customFields = entity.getProperty("customFields");
    } else {
      customFields = null;
    }
    if (entity.hasProperty("description")) {
      description = entity.getProperty("description");
    } else {
      description = null;
    }
  }

  public Collection<String> getAllIndexes(DatabaseSessionEmbedded session) {
    return getAllIndexesInternal(session).stream().map(Index::getName).toList();
  }

  public Collection<Index> getAllIndexesInternal(DatabaseSessionEmbedded session) {
    acquireSchemaReadLock();
    try {
      final Set<Index> indexes = new HashSet<>();
      owner.getIndexesInternal(session, indexes);

      final List<Index> indexList = new LinkedList<>();
      for (final var index : indexes) {
        final var indexDefinition = index.getDefinition();
        if (indexDefinition.getProperties().contains(globalRef.getName())) {
          indexList.add(index);
        }
      }

      return indexList;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Entity toStream(DatabaseSessionEmbedded session) {
    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", getName());
    entity.setProperty("type",
        PropertyTypeInternal.convertFromPublicType(getType()).getId());
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
          linkedClass != null ? linkedClass.getName() : linkedClassName);
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

  public void acquireSchemaReadLock() {
    owner.acquireSchemaReadLock();
  }

  public void releaseSchemaReadLock() {
    owner.releaseSchemaReadLock();
  }

  public void acquireSchemaWriteLock(DatabaseSessionEmbedded session) {
    owner.acquireSchemaWriteLock(session);
  }

  public void releaseSchemaWriteLock(DatabaseSessionEmbedded session) {
    owner.releaseSchemaWriteLock(session);
  }

  public static void checkEmbedded(DatabaseSessionEmbedded session) {

  }

  protected void checkForDateFormat(DatabaseSessionEmbedded session, final String iDateAsString) {
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

  public abstract void clearCustom(DatabaseSessionEmbedded session);

  public Integer getId() {
    return globalRef.getId();
  }
}
