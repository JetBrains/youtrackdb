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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty.ATTRIBUTES;
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

  public String getName(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return globalRef.getName();
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public String getFullName(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return owner.getName(session) + "." + globalRef.getName();
    } finally {
      releaseSchemaReadLock(session);
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

  public PropertyType getType(DatabaseSessionInternal db) {
    acquireSchemaReadLock(db);
    try {
      return globalRef.getType();
    } finally {
      releaseSchemaReadLock(db);
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
  public String createIndex(DatabaseSessionInternal session, final INDEX_TYPE iType) {
    return createIndex(session, iType.toString());
  }

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @return the index name
   */
  public String createIndex(DatabaseSessionInternal session, final String iType) {
    acquireSchemaReadLock(session);
    try {
      var indexName = getFullName(session);
      owner.createIndex(session, indexName, iType, globalRef.getName());
      return indexName;
    } finally {
      releaseSchemaReadLock(session);
    }
  }


  public String createIndex(DatabaseSessionInternal session, INDEX_TYPE iType,
      Map<String, Object> metadata) {
    return createIndex(session, iType.name(), metadata);
  }

  public String createIndex(DatabaseSessionInternal session, String iType,
      Map<String, Object> metadata) {
    acquireSchemaReadLock(session);
    try {
      var indexName = getFullName(session);
      owner.createIndex(session,
          indexName, iType, null, metadata, new String[]{globalRef.getName()});
      return indexName;
    } finally {
      releaseSchemaReadLock(session);
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

    acquireSchemaReadLock(session);
    try {
      final var indexManager = session.getSharedContext().getIndexManager();

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
        session.getSharedContext().getIndexManager()
            .dropIndex(session, index.getName());
      }
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
  public boolean isIndexed(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return owner.areIndexed(session, globalRef.getName());
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public SchemaClassImpl getOwnerClass() {
    return owner;
  }

  /**
   * Returns the linked class in lazy mode because while unmarshalling the class could be not loaded
   * yet.
   */
  public SchemaClassImpl getLinkedClass(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return linkedClass;
    } finally {
      releaseSchemaReadLock(session);
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

  public PropertyTypeInternal getLinkedType(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return linkedType;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public static void checkLinkTypeSupport(PropertyTypeInternal type) {
    if (type != PropertyTypeInternal.EMBEDDEDSET && type != PropertyTypeInternal.EMBEDDEDLIST
        && type != PropertyTypeInternal.EMBEDDEDMAP) {
      throw new SchemaException("Linked type is not supported for type: " + type);
    }
  }

  public boolean isNotNull(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return notNull;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public boolean isMandatory(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return mandatory;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public boolean isReadonly(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return readonly;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public String getMin(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return min;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public String getMax(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return max;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  protected static Object quoteString(String s) {
    if (s == null) {
      return "null";
    }
    return "\"" + (QUOTATION_PATTERN.matcher(DOUBLE_SLASH_PATTERN.matcher(s).replaceAll("\\\\\\\\"))
        .replaceAll("\\\\\"")) + "\"";
  }

  public String getDefaultValue(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return defaultValue;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public String getRegexp(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return regexp;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  @Nullable
  public String getCustom(DatabaseSessionInternal db, final String iName) {
    acquireSchemaReadLock(db);
    try {
      if (customFields == null) {
        return null;
      }

      return customFields.get(iName);
    } finally {
      releaseSchemaReadLock(db);
    }
  }

  @Nullable
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

  public void removeCustom(DatabaseSessionInternal session, final String iName) {
    setCustom(session, iName, null);
  }

  public abstract void setCustom(DatabaseSessionInternal session, final String iName,
      final String iValue);

  public Set<String> getCustomKeys(DatabaseSessionInternal db) {
    acquireSchemaReadLock(db);
    try {
      if (customFields != null) {
        return customFields.keySet();
      }

      return new HashSet<>();
    } finally {
      releaseSchemaReadLock(db);
    }
  }

  public Object get(DatabaseSessionInternal session, final ATTRIBUTES attribute) {
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

  public void set(DatabaseSessionInternal session, final ATTRIBUTES attribute,
      final Object iValue) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = iValue != null ? iValue.toString() : null;

    switch (attribute) {
      case LINKEDCLASS:
        setLinkedClass(session,
            session.getSharedContext().getSchema().getClass(session, stringValue));
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

  public abstract void setLinkedClass(DatabaseSessionInternal session, SchemaClassImpl oClass);

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

  public abstract void setCollate(DatabaseSessionInternal session, String iCollateName);

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

  public Collate getCollate(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return collate;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void setCollate(DatabaseSessionInternal session, final Collate collate) {
    setCollate(session, collate.getName());
  }

  public String getDescription(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return description;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void fromStream(DatabaseSessionInternal session, EntityImpl entity) {
    acquireSchemaWriteLock(session);
    try {
      String name = entity.getProperty("name");
      PropertyTypeInternal type = null;
      if (entity.getProperty("type") != null) {
        type = PropertyTypeInternal.getById(((Integer) entity.getProperty("type")).byteValue());
      }
      Integer globalId = entity.getProperty("globalId");
      if (globalId != null) {
        globalRef = owner.owner.getGlobalPropertyById(session, globalId);
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
      final String linkedClassName;
      if (entity.hasProperty("linkedClass")) {
        linkedClassName = entity.getProperty("linkedClass");
      } else {
        linkedClassName = null;
      }
      // maybe I will find a better solution, but we need it to unfold recursion
      // class loads properties -> property loads same class which is not loaded yet
      if (linkedClassName != null && linkedClassName.equalsIgnoreCase(owner.getName(session))) {
        linkedClass = owner;
      } else {
        LazySchemaClass lazyClass = owner.owner.getLazyClass(linkedClassName);
        // we need to load class without inheritance to have a proper link on it
        // later inheritance info will be loaded if needed
        lazyClass.loadWithoutInheritanceIfNeeded(session);
        linkedClass = lazyClass.getDelegate();
      }
      if (entity.getProperty("linkedType") != null) {
        linkedType =
            PropertyTypeInternal.getById(((Integer) entity.getProperty("linkedType")).byteValue());
      } else {
        linkedType = null;
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
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public Collection<String> getAllIndexes(DatabaseSessionInternal session) {
    return getAllIndexesInternal(session).stream().map(Index::getName).toList();
  }

  public Collection<Index> getAllIndexesInternal(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      final Set<Index> indexes = new HashSet<>();
      owner.getIndexesInternal(session, indexes);

      final List<Index> indexList = new LinkedList<>();
      for (final var index : indexes) {
        final var indexDefinition = index.getDefinition();
        if (indexDefinition.getFields().contains(globalRef.getName())) {
          indexList.add(index);
        }
      }

      return indexList;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Entity toStream(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      Entity entity = session.newEmbeddedEntity();
      entity.setProperty("name", getName(session));
      entity.setProperty("type",
          PropertyTypeInternal.convertFromPublicType(getType(session)).getId());
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
      if (linkedClass != null) {
        entity.setProperty("linkedClass", linkedClass.getName(session));
      }

      if (customFields != null && customFields.isEmpty()) {
        var storedCustomFields = entity.getOrCreateEmbeddedMap("customFields");
        storedCustomFields.clear();
        storedCustomFields.putAll(customFields);
      } else {
        entity.removeProperty("customFields");
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
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void acquireSchemaReadLock(DatabaseSessionInternal session) {
    owner.acquireSchemaReadLock(session);
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
      acquireSchemaReadLock(session);
      try {
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
      } finally {
        releaseSchemaReadLock(session);
      }
    }
  }

  public abstract void clearCustom(DatabaseSessionInternal session);

  public Integer getId() {
    return globalRef.getId();
  }
}
