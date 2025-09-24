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

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.comparator.CaseInsentiveComparator;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.Collections;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProperty.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Contains the description of a persistent class property.
 */
public final class SchemaPropertyShared {
  private static final Pattern DOUBLE_SLASH_PATTERN = Pattern.compile("\\\\");
  private static final Pattern QUOTATION_PATTERN = Pattern.compile("\"");

  @Nonnull
  private final SchemaClassShared owner;
  @Nonnull
  private final SchemaPropertyEntity schemaPropertyEntity;

  public SchemaPropertyShared(@Nonnull final SchemaClassShared owner,
      @Nonnull final SchemaPropertyEntity schemaPropertyEntity) {
    this.owner = owner;
    this.schemaPropertyEntity = schemaPropertyEntity;
  }

  public String getName() {
    acquireSchemaReadLock();
    try {
      return globalRef.getName();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getFullName(DatabaseSessionInternal session) {
    acquireSchemaReadLock();
    try {
      return owner.getName() + "." + globalRef.getName();
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
  public void dropIndexes(DatabaseSessionInternal session) {
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
  public void dropIndexesInternal(DatabaseSessionInternal session) {
    dropIndexes(session);
  }

  @Deprecated
  public boolean isIndexed(DatabaseSessionInternal session) {
    acquireSchemaReadLock();
    try {
      return owner.areIndexed(session, globalRef.getName());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClassShared getOwnerClass() {
    return owner;
  }

  /**
   * Returns the linked class in lazy mode because while unmarshalling the class could be not loaded
   * yet.
   */
  public SchemaClassShared getLinkedClass(DatabaseSessionInternal session) {
    acquireSchemaReadLock();
    try {
      if (linkedClass == null && linkedClassName != null) {
        linkedClass = owner.owner.getClass(, linkedClassName);
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
    return "\"" + (QUOTATION_PATTERN.matcher(DOUBLE_SLASH_PATTERN.matcher(s).replaceAll("\\\\\\\\"))
        .replaceAll("\\\\\"")) + "\"";
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

  public void removeCustom(DatabaseSessionInternal session, final String iName) {
    setCustom(session, iName, null);
  }

  public abstract void setCustom(DatabaseSessionInternal session, final String iName,
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

  public Object get(DatabaseSessionInternal db, final ATTRIBUTES attribute) {
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
      case LINKEDCLASS:
        setLinkedClass(session,
            session.getSharedContext().getSchemaManager().getClass(stringValue));
        break;
      case LINKEDTYPE:
        if (stringValue == null) {
          setLinkedType(session, null);
        } else {
          setLinkedType(session, PropertyTypeInternal.valueOf(stringValue));
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
        setType(session, PropertyTypeInternal.valueOf(stringValue.toUpperCase(Locale.ENGLISH)));
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

  public abstract void setLinkedClass(DatabaseSessionInternal session, SchemaClassShared oClass);

  public abstract void setLinkedType(DatabaseSessionInternal session, PropertyTypeInternal type);

  public abstract void setMin(DatabaseSessionInternal session, String min);

  public abstract void setMandatory(DatabaseSessionInternal session, boolean mandatory);

  public abstract void setReadonly(DatabaseSessionInternal session, boolean iReadonly);

  public abstract void setMax(DatabaseSessionInternal session, String max);

  public abstract void setDefaultValue(DatabaseSessionInternal session, String defaultValue);

  public abstract void setName(DatabaseSessionInternal session, String iName);

  public abstract void setNotNull(DatabaseSessionInternal session, boolean iNotNull);

  public abstract void setRegexp(DatabaseSessionInternal session, String regexp);

  public abstract void setType(DatabaseSessionInternal session, final PropertyTypeInternal iType);

  public abstract void setDescription(DatabaseSessionInternal session, String iDescription);

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

  public Collection<String> getAllIndexes(DatabaseSessionInternal session) {
    return getAllIndexesInternal(session).stream().map(Index::getName).toList();
  }

  public Collection<Index> getAllIndexesInternal(DatabaseSessionInternal session) {
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

  public Entity toStream(DatabaseSessionInternal session) {
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

  public void acquireSchemaWriteLock() {
    owner.acquireSchemaWriteLock();
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal session) {
    owner.releaseSchemaWriteLock(session);
  }

  public static void checkEmbedded(DatabaseSessionInternal session) {

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

  public abstract void clearCustom(DatabaseSessionInternal session);

  public Integer getId() {
    return globalRef.getId();
  }

  @Override
  public void setType(DatabaseSessionInternal session, final PropertyTypeInternal type) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setTypeInternal(session, type);
    } finally {
      releaseSchemaWriteLock(session);
    }
    owner.fireDatabaseMigration(session, globalRef.getName(),
        PropertyTypeInternal.convertFromPublicType(globalRef.getType()));
  }

  /**
   * Change the type. It checks for compatibility between the change of type.
   */
  protected void setTypeInternal(DatabaseSessionInternal session,
      final PropertyTypeInternal iType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      if (iType == PropertyTypeInternal.convertFromPublicType(globalRef.getType()))
      // NO CHANGES
      {
        return;
      }

      if (!iType.getCastable()
          .contains(PropertyTypeInternal.convertFromPublicType(globalRef.getType()))) {
        throw new IllegalArgumentException(
            "Cannot change property type from " + globalRef.getType() + " to " + iType);
      }

      this.globalRef = SchemaManager.findOrCreateGlobalProperty(this.globalRef.getName(), iType);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setName(DatabaseSessionInternal session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setNameInternal(session, name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNameInternal(DatabaseSessionInternal session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    var oldName = this.globalRef.getName();
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      owner.renameProperty(oldName, name);
      this.globalRef = SchemaManager.findOrCreateGlobalProperty(name,
          PropertyTypeInternal.convertFromPublicType(this.globalRef.getType()));
    } finally {
      releaseSchemaWriteLock(session);
    }
    owner.firePropertyNameMigration(session, oldName, name,
        PropertyTypeInternal.convertFromPublicType(this.globalRef.getType()));
  }

  @Override
  public void setDescription(DatabaseSessionInternal session,
      final String iDescription) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setDescriptionInternal(session, iDescription);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setDescriptionInternal(DatabaseSessionInternal session,
      final String iDescription) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      this.description = iDescription;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setCollate(DatabaseSessionEmbedded session, String collate) {
    if (collate == null) {
      collate = DefaultCollate.NAME;
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setCollateInternal(session, collate);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCollateInternal(DatabaseSessionEmbedded session, String iCollate) {
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      final var oldCollate = this.collate;

      if (iCollate == null) {
        iCollate = DefaultCollate.NAME;
      }

      collate = SQLEngine.getCollate(iCollate);

      if ((this.collate != null && !this.collate.equals(oldCollate))
          || (this.collate == null && oldCollate != null)) {
        final var indexes = owner.getClassIndexesInternal(session);
        final List<Index> indexesToRecreate = new ArrayList<>();

        for (var index : indexes) {
          var definition = index.getDefinition();

          final var fields = definition.getProperties();
          if (fields.contains(getName())) {
            indexesToRecreate.add(index);
          }
        }

        if (!indexesToRecreate.isEmpty()) {
          LogManager.instance()
              .info(
                  this,
                  "Collate value was changed, following indexes will be rebuilt %s",
                  indexesToRecreate);

          final var indexManager = session.getSharedContext()
              .getIndexManager();
          for (var indexToRecreate : indexesToRecreate) {
            final var indexMetadata = session.computeInTxInternal(transaction ->
                indexToRecreate
                    .loadMetadata(transaction, indexToRecreate.getConfiguration(session)));

            final var fields = indexMetadata.getIndexDefinition().getProperties();
            final var fieldsToIndex = fields.toArray(new String[0]);

            indexManager.dropIndex(session, indexMetadata.getName());
            owner.createIndex(session,
                indexMetadata.getName(),
                indexMetadata.getType(),
                null,
                indexToRecreate.getMetadata(),
                indexMetadata.getAlgorithm(), fieldsToIndex);
          }
        }
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void clearCustom(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      clearCustomInternal(session);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void clearCustomInternal(DatabaseSessionInternal session) {
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      customFields = null;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setCustom(DatabaseSessionInternal session, final String name,
      final String value) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setCustomInternal(session, name, value);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCustomInternal(DatabaseSessionInternal session, final String iName,
      final String iValue) {
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      if (customFields == null) {
        customFields = new HashMap<>();
      }
      if (iValue == null || "null".equalsIgnoreCase(iValue)) {
        customFields.remove(iName);
      } else {
        customFields.put(iName, iValue);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setRegexp(DatabaseSessionInternal session, final String regexp) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setRegexpInternal(session, regexp);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setRegexpInternal(DatabaseSessionInternal session, final String regexp) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      this.regexp = regexp;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setLinkedClass(DatabaseSessionInternal session,
      final SchemaClassShared linkedClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkSupportLinkedClass(PropertyTypeInternal.convertFromPublicType(getType()));

    acquireSchemaWriteLock();
    try {
      setLinkedClassInternal(session, linkedClass);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setLinkedClassInternal(DatabaseSessionInternal session,
      final SchemaClassShared iLinkedClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      this.linkedClass = iLinkedClass;

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setLinkedType(DatabaseSessionInternal session,
      final PropertyTypeInternal linkedType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    checkLinkTypeSupport(PropertyTypeInternal.convertFromPublicType(getType()));

    acquireSchemaWriteLock();
    try {
      setLinkedTypeInternal(session, linkedType);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setLinkedTypeInternal(DatabaseSessionInternal session,
      final PropertyTypeInternal iLinkedType) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);
      this.linkedType = iLinkedType;

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setNotNull(DatabaseSessionInternal session, final boolean isNotNull) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setNotNullInternal(session, isNotNull);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNotNullInternal(DatabaseSessionInternal session, final boolean isNotNull) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      notNull = isNotNull;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setDefaultValue(DatabaseSessionInternal session,
      final String defaultValue) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setDefaultValueInternal(session, defaultValue);
    } catch (Exception e) {
      LogManager.instance().error(this, "Error on setting default value", e);
      throw e;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setDefaultValueInternal(DatabaseSessionInternal session,
      final String defaultValue) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      this.defaultValue = defaultValue;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setMax(DatabaseSessionInternal session, final String max) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkCorrectLimitValue(session, max);

    acquireSchemaWriteLock();
    try {
      setMaxInternal(session, max);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  private void checkCorrectLimitValue(DatabaseSessionInternal session, final String value) {
    if (value != null) {
      if (this.getType().equals(PropertyType.STRING)
          || this.getType().equals(PropertyType.LINKBAG)
          || this.getType().equals(PropertyType.BINARY)
          || this.getType().equals(PropertyType.EMBEDDEDLIST)
          || this.getType().equals(PropertyType.EMBEDDEDSET)
          || this.getType().equals(PropertyType.LINKLIST)
          || this.getType().equals(PropertyType.LINKSET)
          || this.getType().equals(PropertyType.LINKBAG)
          || this.getType().equals(PropertyType.EMBEDDEDMAP)
          || this.getType().equals(PropertyType.LINKMAP)) {
        PropertyTypeInternal.convert(session, value, Integer.class);
      } else if (this.getType().equals(PropertyType.DATE)
          || this.getType().equals(PropertyType.BYTE)
          || this.getType().equals(PropertyType.SHORT)
          || this.getType().equals(PropertyType.INTEGER)
          || this.getType().equals(PropertyType.LONG)
          || this.getType().equals(PropertyType.FLOAT)
          || this.getType().equals(PropertyType.DOUBLE)
          || this.getType().equals(PropertyType.DECIMAL)
          || this.getType().equals(PropertyType.DATETIME)) {
        PropertyTypeInternal.convert(session, value,
            PropertyTypeInternal.convertFromPublicType(this.getType()).getDefaultJavaType());
      }
    }
  }

  protected void setMaxInternal(DatabaseSessionInternal sesisson, final String max) {
    sesisson.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(sesisson);

      checkForDateFormat(sesisson, max);
      this.max = max;
    } finally {
      releaseSchemaWriteLock(sesisson);
    }
  }

  @Override
  public void setMin(DatabaseSessionInternal session, final String min) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkCorrectLimitValue(session, min);

    acquireSchemaWriteLock();
    try {
      setMinInternal(session, min);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setMinInternal(DatabaseSessionInternal session, final String min) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      checkForDateFormat(session, min);
      this.min = min;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setReadonly(DatabaseSessionInternal session, final boolean isReadonly) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setReadonlyInternal(session, isReadonly);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setReadonlyInternal(DatabaseSessionInternal session, final boolean isReadonly) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      this.readonly = isReadonly;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setMandatory(DatabaseSessionInternal session,
      final boolean isMandatory) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setMandatoryInternal(session, isMandatory);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setMandatoryInternal(DatabaseSessionInternal session,
      final boolean isMandatory) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);
      this.mandatory = isMandatory;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }
}
