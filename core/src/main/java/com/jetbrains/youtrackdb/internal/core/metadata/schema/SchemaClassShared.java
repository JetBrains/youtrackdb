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

import static com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.EDGE_CLASS_NAME;
import static com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.VERTEX_CLASS_NAME;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Schema Class implementation.
 */
@SuppressWarnings("unchecked")
public final class SchemaClassShared {

  private static final Pattern PATTERN = Pattern.compile(",\\s*");
  @Nonnull
  private final SchemaShared owner;
  @Nonnull
  private final SchemaClassEntity schemaClassEntity;

  public SchemaClassShared(@Nonnull final SchemaShared owner,
      @Nonnull SchemaClassEntity schemaClassEntity) {
    this.owner = owner;
    this.schemaClassEntity = schemaClassEntity;
  }

  @Nonnull
  public SchemaClassEntity getSchemaClassEntity() {
    return schemaClassEntity;
  }

  public static int[] readableCollections(
      final DatabaseSessionInternal db, final int[] iCollectionIds, String className) {
    var listOfReadableIds = new IntArrayList();

    var all = true;
    for (var collectionId : iCollectionIds) {
      try {
        // This will exclude (filter out) any specific classes without explicit read permission.
        if (className != null) {
          db.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ, className);
        }

        final var collectionName = db.getCollectionNameById(collectionId);
        db.checkSecurity(Rule.ResourceGeneric.COLLECTION, Role.PERMISSION_READ, collectionName);
        listOfReadableIds.add(collectionId);
      } catch (SecurityAccessException ignore) {
        all = false;
        // if the collection is inaccessible it's simply not processed in the list.add
      }
    }

    // JUST RETURN INPUT ARRAY (FASTER)
    if (all) {
      return iCollectionIds;
    }

    final var readableCollectionIds = new int[listOfReadableIds.size()];
    var index = 0;
    for (var i = 0; i < listOfReadableIds.size(); i++) {
      readableCollectionIds[index++] = listOfReadableIds.getInt(i);
    }

    return readableCollectionIds;
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
        return Collections.unmodifiableMap(customFields);
      }
      return null;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void removeCustom(DatabaseSessionInternal session, final String name) {
    setCustom(session, name, null);
  }

  public Set<String> getCustomKeys() {
    acquireSchemaReadLock();
    try {
      if (customFields != null) {
        return Collections.unmodifiableSet(customFields.keySet());
      }
      return new HashSet<>();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public boolean hasCollectionId(final int collectionId) {
    acquireSchemaReadLock();
    try {
      return Arrays.binarySearch(collectionIds, collectionId) >= 0;
    } finally {
      releaseSchemaReadLock();
    }

  }

  public boolean hasPolymorphicCollectionId(final int collectionId) {
    acquireSchemaReadLock();
    try {
      return Arrays.binarySearch(polymorphicCollectionIds, collectionId) >= 0;
    } finally {
      releaseSchemaReadLock();
    }

  }

  public String getName() {
    acquireSchemaReadLock();
    try {
      return name;
    } finally {
      releaseSchemaReadLock();
    }
  }


  public List<SchemaClassShared> getSuperClasses() {
    acquireSchemaReadLock();
    try {
      return Collections.unmodifiableList(superClasses);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public boolean hasSuperClasses() {
    acquireSchemaReadLock();
    try {
      return !superClasses.isEmpty();
    } finally {
      releaseSchemaReadLock();
    }
  }

  public List<String> getSuperClassesNames(DatabaseSessionInternal session) {
    acquireSchemaReadLock();
    try {
      List<String> superClassesNames = new ArrayList<>(superClasses.size());
      for (var superClass : superClasses) {
        superClassesNames.add(superClass.getName());
      }
      return superClassesNames;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void setSuperClassesByNames(DatabaseSessionInternal session, List<String> classNames) {
    if (classNames == null) {
      classNames = Collections.EMPTY_LIST;
    }

    final List<SchemaClassShared> classes = new ArrayList<>(classNames.size());
    for (var className : classNames) {
      classes.add(owner.getClass(decodeClassName(className)));
    }

    setSuperClasses(session, classes);
  }


  public long getSize(DatabaseSessionInternal session) {
    acquireSchemaReadLock();
    try {
      long size = 0;
      for (var collectionId : collectionIds) {
        size += session.getCollectionRecordSizeById(collectionId);
      }

      return size;
    } finally {
      releaseSchemaReadLock();
    }
  }


  public String getDescription() {
    acquireSchemaReadLock();
    try {
      return description;
    } finally {
      releaseSchemaReadLock();
    }
  }


  public Collection<SchemaPropertyShared> declaredProperties() {
    acquireSchemaReadLock();
    try {
      return Collections.unmodifiableCollection(properties.values());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Map<String, SchemaPropertyShared> propertiesMap(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA,
        Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      final Map<String, SchemaPropertyShared> props = new HashMap<>(20);
      propertiesMap(props);
      return props;
    } finally {
      releaseSchemaReadLock();
    }
  }

  private void propertiesMap(Map<String, SchemaPropertyShared> propertiesMap) {
    for (var p : properties.values()) {
      var propName = p.getName();
      if (!propertiesMap.containsKey(propName)) {
        propertiesMap.put(propName, p);
      }
    }
    for (var superClass : superClasses) {
      superClass.propertiesMap(propertiesMap);
    }
  }

  public Collection<SchemaPropertyShared> properties() {
    acquireSchemaReadLock();
    try {
      final Collection<SchemaPropertyShared> props = new ArrayList<>();
      properties(props);
      return props;
    } finally {
      releaseSchemaReadLock();
    }
  }

  private void properties(Collection<SchemaPropertyShared> properties) {
    properties.addAll(this.properties.values());
    for (var superClass : superClasses) {
      superClass.properties(properties);
    }
  }

  public void getIndexedProperties(DatabaseSessionInternal session,
      Collection<SchemaPropertyShared> indexedProperties) {
    for (var p : properties.values()) {
      if (areIndexed(session, p.getName())) {
        indexedProperties.add(p);
      }
    }
    for (var superClass : superClasses) {
      superClass.getIndexedProperties(session, indexedProperties);
    }
  }

  public Collection<SchemaPropertyShared> getIndexedProperties(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA,
        Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      Collection<SchemaPropertyShared> indexedProps = new HashSet<>();
      getIndexedProperties(session, indexedProps);
      return indexedProps;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaPropertyShared getProperty(String propertyName) {
    return getPropertyInternal(propertyName);
  }

  public SchemaPropertyShared getPropertyInternal(String propertyName) {
    acquireSchemaReadLock();
    try {
      var p = properties.get(propertyName);

      if (p != null) {
        return p;
      }

      for (var i = 0; i < superClasses.size() && p == null; i++) {
        p = superClasses.get(i).getPropertyInternal(propertyName);
      }

      return p;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaPropertyShared createProperty(DatabaseSessionInternal session,
      final String iPropertyName,
      final PropertyTypeInternal iType) {
    return addProperty(session, iPropertyName, iType, null, null,
        false);
  }


  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyTypeInternal iType,
      final SchemaClassShared iLinkedClass) {
    return addProperty(session, iPropertyName, iType, null,
        iLinkedClass,
        false);
  }

  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyTypeInternal iType,
      final SchemaClassShared iLinkedClass,
      final boolean unsafe) {
    return addProperty(session, iPropertyName, iType, null,
        iLinkedClass,
        unsafe);
  }

  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType) {
    return addProperty(session, iPropertyName, iType, iLinkedType, null,
        false);
  }

  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType,
      final boolean unsafe) {
    return addProperty(session, iPropertyName, iType, iLinkedType, null,
        unsafe);
  }

  public SchemaPropertyShared createProperty(DatabaseSessionInternal session,
      final String iPropertyName,
      final PropertyType iType) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType));
  }


  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyType iType,
      final SchemaClassShared iLinkedClass) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType), iLinkedClass);
  }

  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyType iType,
      final SchemaClassShared iLinkedClass,
      final boolean unsafe) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType), iLinkedClass,
        unsafe);
  }

  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyType iType,
      final PropertyType iLinkedType) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType),
        PropertyTypeInternal.convertFromPublicType(iLinkedType));
  }

  public SchemaPropertyShared createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyType iType,
      final PropertyType iLinkedType,
      final boolean unsafe) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType),
        PropertyTypeInternal.convertFromPublicType(iLinkedType), unsafe);
  }


  public boolean existsProperty(String propertyName) {
    acquireSchemaReadLock();
    try {
      var result = properties.containsKey(propertyName);
      if (result) {
        return true;
      }
      for (var superClass : superClasses) {
        result = superClass.existsProperty(propertyName);
        if (result) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock();
    }
  }


  public int[] getCollectionIds() {
    acquireSchemaReadLock();
    try {
      return collectionIds;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public int[] getPolymorphicCollectionIds() {
    acquireSchemaReadLock();
    try {
      return Arrays.copyOf(polymorphicCollectionIds, polymorphicCollectionIds.length);
    } finally {
      releaseSchemaReadLock();
    }
  }


  public void renameProperty(final String iOldName, final String iNewName) {
    var p = properties.remove(iOldName);
    if (p != null) {
      properties.put(iNewName, p);
    }
  }

  public Collection<SchemaClassShared> getSubclasses() {
    acquireSchemaReadLock();
    try {
      if (subclasses == null || subclasses.isEmpty()) {
        return Collections.emptyList();
      }

      return Collections.unmodifiableCollection(subclasses);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Collection<SchemaClassShared> getAllSubclasses() {
    acquireSchemaReadLock();
    try {
      final Set<SchemaClassShared> set = new HashSet<>();
      if (subclasses != null) {
        set.addAll(subclasses);

        for (var c : subclasses) {
          set.addAll(c.getAllSubclasses());
        }
      }
      return set;
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Deprecated
  public Collection<SchemaClassShared> getBaseClasses() {
    return getSubclasses();
  }

  @Deprecated
  public Collection<SchemaClassShared> getAllBaseClasses() {
    return getAllSubclasses();
  }

  private void getAllSuperClasses(Set<SchemaClassShared> set) {
    set.addAll(superClasses);
    for (var superClass : superClasses) {
      superClass.getAllSuperClasses(set);
    }
  }

  public abstract void removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClassShared baseClass);

  public boolean isAbstract() {
    acquireSchemaReadLock();
    try {
      return abstractClass;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public boolean isStrictMode() {
    acquireSchemaReadLock();
    try {
      return strictMode;
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    var other = (SchemaClassShared) obj;

    return Objects.equals(name, other.name);
  }

  @Override
  public int hashCode() {
    var name = this.name;
    if (name != null) {
      return name.hashCode();
    }
    return 0;
  }

  public long count(DatabaseSessionInternal session) {
    return count(session, true);
  }

  public long count(DatabaseSessionInternal session, final boolean isPolymorphic) {
    acquireSchemaReadLock();
    try {
      return session.countClass(getName(), isPolymorphic);
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * Truncates all the collections the class uses.
   */
  public void truncate(DatabaseSessionInternal session) {
    session.truncateClass(name, false);
  }

  /**
   * Check if the current instance extends specified schema class.
   *
   * @param iClassName of class that should be checked
   * @return Returns true if the current instance extends the passed schema class (iClass)
   */
  public boolean isSubClassOf(final String iClassName) {
    acquireSchemaReadLock();
    try {
      if (iClassName == null) {
        return false;
      }

      if (iClassName.equalsIgnoreCase(getName())) {
        return true;
      }
      for (var superClass : superClasses) {
        if (superClass.isSubClassOf(iClassName)) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * Check if the current instance extends specified schema class.
   *
   * @param clazz to check
   * @return true if the current instance extends the passed schema class (iClass)
   */
  public boolean isSubClassOf(final SchemaClassShared clazz) {
    acquireSchemaReadLock();
    try {
      if (clazz == null) {
        return false;
      }
      if (equals(clazz)) {
        return true;
      }
      for (var superClass : superClasses) {
        if (superClass.isSubClassOf(clazz)) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock();
    }
  }

  /**
   * Returns true if the passed schema class (iClass) extends the current instance.
   *
   * @param clazz to check
   * @return Returns true if the passed schema class extends the current instance
   */
  public boolean isSuperClassOf(final SchemaClassShared clazz) {
    return clazz != null && clazz.isSubClassOf(this);
  }

  public boolean isSuperClassOf(final String className) {
    var clazz = owner.getClass(className);
    if (clazz == null) {
      return false;
    }

    return clazz.isSuperClassOf(this);
  }


  public Object get(DatabaseSessionInternal db, final ATTRIBUTES iAttribute) {
    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    return switch (iAttribute) {
      case NAME -> getName();
      case SUPERCLASSES -> getSuperClasses();
      case STRICT_MODE -> isStrictMode();
      case ABSTRACT -> isAbstract();
      case CUSTOM -> getCustomInternal();
      case DESCRIPTION -> getDescription();
    };

  }

  public void set(DatabaseSessionInternal session, final ATTRIBUTES attribute,
      final Object iValue) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = iValue != null ? iValue.toString() : null;
    final var isNull = stringValue == null || stringValue.equalsIgnoreCase("NULL");

    switch (attribute) {
      case NAME:
        setName(session, decodeClassName(stringValue));
        break;
      case SUPERCLASSES:
        setSuperClassesByNames(session
            , stringValue != null ? Arrays.asList(PATTERN.split(stringValue)) : null);
        break;
      case STRICT_MODE:
        setStrictMode(session, Boolean.parseBoolean(stringValue));
        break;
      case ABSTRACT:
        setAbstract(session, Boolean.parseBoolean(stringValue));
        break;
      case CUSTOM:
        var indx = stringValue != null ? stringValue.indexOf('=') : -1;
        if (indx < 0) {
          if (isNull || "clear".equalsIgnoreCase(stringValue)) {
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

  public void createIndex(DatabaseSessionEmbedded session, final String iName,
      final INDEX_TYPE iType,
      final String... fields) {
    createIndex(session, iName, iType.name(), fields);
  }

  public void createIndex(DatabaseSessionEmbedded session, final String iName, final String iType,
      final String... fields) {
    createIndex(session, iName, iType, null, null, fields);
  }

  public void createIndex(
      DatabaseSessionEmbedded session, final String iName,
      final INDEX_TYPE iType,
      final ProgressListener iProgressListener,
      final String... fields) {
    createIndex(session, iName, iType.name(), iProgressListener, null, fields);
  }

  public void createIndex(
      DatabaseSessionEmbedded session, String iName,
      String iType,
      ProgressListener iProgressListener,
      Map<String, Object> metadata,
      String... fields) {
    createIndex(session, iName, iType, iProgressListener, metadata, null, fields);
  }

  public void createIndex(
      DatabaseSessionEmbedded session, final String name,
      String type,
      final ProgressListener progressListener,
      Map<String, Object> metadata,
      String algorithm,
      final String... fields) {
    if (type == null) {
      throw new IllegalArgumentException("Index type is null");
    }

    type = type.toUpperCase(Locale.ENGLISH);

    if (fields.length == 0) {
      throw new IndexException(session.getDatabaseName(),
          "List of fields to index cannot be empty.");
    }

    final var localName = this.name;

    for (final var fieldToIndex : fields) {
      final var fieldName =
          decodeClassName(IndexDefinitionFactory.extractFieldName(fieldToIndex));

      if (!fieldName.equals("@rid") && !existsProperty(fieldName)) {
        throw new IndexException(session.getDatabaseName(),
            "Index with name '"
                + name
                + "' cannot be created on class '"
                + localName
                + "' because the field '"
                + fieldName
                + "' is absent in class definition");
      }
    }

    final var oClass = new SchemaClassProxy(this, session);
    final var indexDefinition =
        IndexDefinitionFactory.createIndexDefinition(
            oClass, Arrays.asList(fields),
            oClass.extractFieldTypes(fields), null, type
        );

    final var localPolymorphicCollectionIds = polymorphicCollectionIds;
    session
        .getSharedContext()
        .getIndexManager()
        .createIndex(
            session,
            name,
            type,
            indexDefinition,
            localPolymorphicCollectionIds,
            progressListener,
            metadata,
            algorithm);
  }

  public boolean areIndexed(DatabaseSessionInternal session, final String... fields) {
    return areIndexed(session, Arrays.asList(fields));
  }

  public boolean areIndexed(DatabaseSessionInternal session, final Collection<String> fields) {
    final var indexManager =
        session.getSharedContext().getIndexManager();

    acquireSchemaReadLock();
    try {
      final var currentClassResult = indexManager.areIndexed(session, name, fields);

      if (currentClassResult) {
        return true;
      }
      for (var superClass : superClasses) {
        if (superClass.areIndexed(session, fields)) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<String> getInvolvedIndexes(DatabaseSessionInternal session, final String... fields) {
    return getInvolvedIndexes(session, Arrays.asList(fields));
  }

  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session, String... fields) {
    return getInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  public Set<String> getInvolvedIndexes(DatabaseSessionInternal session,
      final Collection<String> fields) {
    return getInvolvedIndexesInternal(session, fields).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session,
      Collection<String> fields) {
    acquireSchemaReadLock();
    try {
      final Set<Index> result = new HashSet<>(getClassInvolvedIndexesInternal(session, fields));

      for (var superClass : superClasses) {
        result.addAll(superClass.getInvolvedIndexesInternal(session, fields));
      }

      return result;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session,
      final Collection<String> fields) {
    return getClassInvolvedIndexesInternal(session, fields).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      Collection<String> fields) {
    final var indexManager = session.getSharedContext().getIndexManager();

    acquireSchemaReadLock();
    try {
      return indexManager.getClassInvolvedIndexes(session, name, fields);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session,
      final String... fields) {
    return getClassInvolvedIndexes(session, Arrays.asList(fields));
  }

  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      String... fields) {
    return getClassInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  public Index getClassIndex(DatabaseSessionInternal session, final String name) {
    acquireSchemaReadLock();
    try {
      return session
          .getSharedContext()
          .getIndexManager()
          .getClassIndex(session, this.name, name);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<String> getClassIndexes(DatabaseSessionInternal session) {
    return getClassInvolvedIndexesInternal(session).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getClassIndexesInternal(DatabaseSessionInternal session) {
    acquireSchemaReadLock();
    try {
      final var idxManager = session.getSharedContext().getIndexManager();
      return idxManager.getClassIndexes(session, name);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void getClassIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    acquireSchemaReadLock();
    try {
      final var idxManager = session.getSharedContext().getIndexManager();
      idxManager.getClassIndexes(session, name, indexes);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public boolean isEdgeType() {
    return isSubClassOf(EDGE_CLASS_NAME);
  }

  public boolean isVertexType() {
    return isSubClassOf(VERTEX_CLASS_NAME);
  }


  public void getIndexesInternal(DatabaseSessionInternal session, final Collection<Index> indexes) {
    acquireSchemaReadLock();
    try {
      getClassIndexes(session, indexes);
      for (var superClass : superClasses) {
        superClass.getIndexesInternal(session, indexes);
      }
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<String> getIndexes(DatabaseSessionInternal session) {
    return getIndexesInternal(session).stream().map(Index::getName).collect(Collectors.toSet());
  }

  public Set<Index> getIndexesInternal(DatabaseSessionInternal session) {
    final Set<Index> indexes = new HashSet<>();
    getIndexesInternal(session, indexes);

    return indexes;
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


  public void releaseSchemaWriteLock() {
    owner.releaseSchemaWriteLock();
  }


  public void fireDatabaseMigration(
      final DatabaseSessionInternal database, final String propertyName,
      final PropertyTypeInternal type) {
    final var strictSQL =
        database.getStorageInfo().getConfiguration().isStrictSql();

    var recordsToUpdate = database.computeInTx(transaction -> {
      try (var result =
          database.query(
              "select from "
                  + getEscapedName(name, strictSQL)
                  + " where "
                  + getEscapedName(propertyName, strictSQL)
                  + ".type() <> \""
                  + type.name()
                  + "\"")) {
        return result.toRidList();
      }
    });

    database.executeInTxBatches(recordsToUpdate, (s, rid) -> {
      var entity = (EntityImpl) s.loadEntity(rid);
      var value = entity.getPropertyInternal(propertyName);
      if (value == null) {
        return;
      }

      var valueType = PropertyTypeInternal.getTypeByValue(value);
      if (valueType != type) {
        entity.setPropertyInternal(propertyName, value, type);
      }
    });
  }

  public void firePropertyNameMigration(
      final DatabaseSessionInternal database,
      final String propertyName,
      final String newPropertyName,
      final PropertyTypeInternal type) {
    final var strictSQL =
        database.getStorageInfo().getConfiguration().isStrictSql();

    var ridsToMigrate = database.computeInTx(transaction -> {
      try (var result =
          database.query(
              "select from "
                  + getEscapedName(name, strictSQL)
                  + " where "
                  + getEscapedName(propertyName, strictSQL)
                  + " is not null ")) {
        return result.toRidList();
      }
    });

    database.executeInTxBatches(ridsToMigrate, (s, rid) -> {
      var entity = (EntityImpl) s.loadEntity(rid);
      entity.setPropertyInternal(newPropertyName, entity.getPropertyInternal(propertyName),
          type);
    });
  }

  public void checkPersistentPropertyType(
      final DatabaseSessionInternal session,
      final String propertyName,
      final PropertyTypeInternal type,
      SchemaClassShared linkedClass) {
    final var strictSQL = session.getStorageInfo().getConfiguration().isStrictSql();

    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(name, strictSQL));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName, strictSQL));
    builder.append(".type() not in [");

    final var cur = type.getCastable().iterator();
    while (cur.hasNext()) {
      builder.append('"').append(cur.next().name()).append('"');
      if (cur.hasNext()) {
        builder.append(",");
      }
    }
    builder
        .append("] and ")
        .append(getEscapedName(propertyName, strictSQL))
        .append(" is not null ");
    if (type.isMultiValue()) {
      builder
          .append(" and ")
          .append(getEscapedName(propertyName, strictSQL))
          .append(".size() <> 0 limit 1");
    }

    session.executeInTx(transaction -> {
      try (final var res = session.query(builder.toString())) {
        if (res.hasNext()) {
          throw new SchemaException(session.getDatabaseName(),
              "The database contains some schema-less data in the property '"
                  + name
                  + "."
                  + propertyName
                  + "' that is not compatible with the type "
                  + type
                  + ". Fix those records and change the schema again");
        }
      }
    });

    if (linkedClass != null) {
      checkAllLikedObjects(session, propertyName, type, linkedClass);
    }
  }

  protected void checkAllLikedObjects(
      DatabaseSessionInternal db, String propertyName, PropertyTypeInternal type,
      SchemaClassShared linkedClass) {
    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(name, true));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName, true)).append(" is not null ");
    if (type.isMultiValue()) {
      builder.append(" and ").append(getEscapedName(propertyName, true)).append(".size() > 0");
    }

    db.executeInTx(tx -> {
      try (final var res = tx.query(builder.toString())) {
        while (res.hasNext()) {
          var item = res.next();
          switch (type) {
            case EMBEDDEDLIST:
            case LINKLIST:
            case EMBEDDEDSET:
            case LINKSET:
              Collection<?> emb = item.getProperty(propertyName);
              emb.stream()
                  .filter(x -> !matchesType(db, x, linkedClass))
                  .findFirst()
                  .ifPresent(
                      x -> {
                        throw new SchemaException(db.getDatabaseName(),
                            "The database contains some schema-less data in the property '"
                                + name
                                + "."
                                + propertyName
                                + "' that is not compatible with the type "
                                + type
                                + " "
                                + linkedClass.getName()
                                + ". Fix those records and change the schema again. "
                                + x);
                      });
              break;
            case EMBEDDED:
            case LINK:
              var elem = item.getProperty(propertyName);
              if (!matchesType(db, elem, linkedClass)) {
                throw new SchemaException(db.getDatabaseName(),
                    "The database contains some schema-less data in the property '"
                        + name
                        + "."
                        + propertyName
                        + "' that is not compatible with the type "
                        + type
                        + " "
                        + linkedClass.getName()
                        + ". Fix those records and change the schema again!");
              }
              break;
          }
        }
      }
    });
  }

  protected static boolean matchesType(DatabaseSessionInternal db, Object x,
      SchemaClassShared linkedClass) {
    if (x instanceof Result) {
      x = ((Result) x).asEntity();
    }
    if (x instanceof RID) {
      try {
        var transaction = db.getActiveTransaction();
        x = transaction.load(((RID) x));
      } catch (RecordNotFoundException e) {
        return true;
      }
    }
    if (x == null) {
      return true;
    }
    if (!(x instanceof Entity)) {
      return false;
    }
    return !(x instanceof EntityImpl)
        || linkedClass.getName().equalsIgnoreCase(((EntityImpl) x).getSchemaClassName());
  }

  protected static String getEscapedName(final String iName, final boolean iStrictSQL) {
    if (iStrictSQL)
    // ESCAPE NAME
    {
      return "`" + iName + "`";
    }
    return iName;
  }

  public SchemaShared getOwner() {
    return owner;
  }

  private void calculateHashCode() {
    var result = super.hashCode();
    result = 31 * result + (name != null ? name.hashCode() : 0);
    hashCode = result;
  }

  protected void renameCollection(DatabaseSessionInternal session, String oldName, String newName) {
    oldName = oldName.toLowerCase(Locale.ENGLISH);
    newName = newName.toLowerCase(Locale.ENGLISH);

    if (session.getCollectionIdByName(newName) != -1) {
      return;
    }

    final var collectionId = session.getCollectionIdByName(oldName);
    if (collectionId == -1) {
      return;
    }

    if (!hasCollectionId(collectionId)) {
      return;
    }

    session.getStorage()
        .setCollectionAttribute(collectionId, StorageCollection.ATTRIBUTES.NAME, newName);
  }

  protected abstract SchemaPropertyShared addProperty(
      DatabaseSessionInternal session, final String propertyName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassShared linkedClass,
      final boolean unsafe);

  public abstract void dropProperty(DatabaseSessionInternal session, String iPropertyName);

  public Collection<SchemaClassShared> getAllSuperClasses() {
    acquireSchemaReadLock();
    try {
      Set<SchemaClassShared> ret = new HashSet<>();
      getAllSuperClasses(ret);
      return ret;
    } finally {
      releaseSchemaReadLock();
    }
  }

  protected void validatePropertyName(final String propertyName) {
  }

  protected abstract void addCollectionIdToIndexes(
      DatabaseSessionInternal session,
      int iId,
      boolean requireEmpty
  );

  /**
   * Adds a base class to the current one. It adds also the base class collection ids to the
   * polymorphic collection ids array.
   *
   * @param iBaseClass      The base class to add.
   * @param validateIndexes Require that collections are empty before adding them to indexes.
   */
  public void addBaseClass(
      DatabaseSessionInternal session,
      final SchemaClassShared iBaseClass,
      boolean validateIndexes) {
    checkRecursion(session, iBaseClass);

    if (subclasses == null) {
      subclasses = new ArrayList<>();
    }

    if (subclasses.contains(iBaseClass)) {
      return;
    }

    subclasses.add(iBaseClass);
    addPolymorphicCollectionIdsWithInheritance(session, iBaseClass, validateIndexes);
  }

  protected void checkParametersConflict(DatabaseSessionInternal session,
      final SchemaClassShared baseClass) {
    final var baseClassProperties = baseClass.properties();
    for (var property : baseClassProperties) {
      var thisProperty = getProperty(property.getName());
      if (thisProperty != null && !thisProperty.getType()
          .equals(property.getType())) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot add base class '"
                + baseClass.getName()
                + "', because of property conflict: '"
                + thisProperty
                + "' vs '"
                + property
                + "'");
      }
    }
  }

  public static void checkParametersConflict(DatabaseSessionInternal db,
      List<SchemaClassShared> classes) {
    final Map<String, SchemaPropertyShared> comulative = new HashMap<>();
    final Map<String, SchemaPropertyShared> properties = new HashMap<>();

    for (var superClass : classes) {
      if (superClass == null) {
        continue;
      }
      SchemaClassShared impl;
      impl = superClass;
      impl.propertiesMap(properties);
      for (var entry : properties.entrySet()) {
        if (comulative.containsKey(entry.getKey())) {
          final var property = entry.getKey();
          final var existingProperty = comulative.get(property);
          if (!existingProperty.getType().equals(entry.getValue().getType())) {
            throw new SchemaException(
                "Properties conflict detected: '"
                    + existingProperty
                    + "] vs ["
                    + entry.getValue()
                    + "]");
          }
        }
      }

      comulative.putAll(properties);
      properties.clear();
    }
  }

  private void checkRecursion(DatabaseSessionInternal session, final SchemaClassShared baseClass) {
    if (isSubClassOf(baseClass)) {
      throw new SchemaException(session.getDatabaseName(),
          "Cannot add base class '" + baseClass.getName() + "', because of recursion");
    }
  }

  protected void removePolymorphicCollectionIds(DatabaseSessionInternal session,
      final SchemaClassShared iBaseClass) {
    for (final var collectionId : iBaseClass.polymorphicCollectionIds) {
      removePolymorphicCollectionId(session, collectionId);
    }
  }

  protected void removePolymorphicCollectionId(DatabaseSessionInternal session,
      final int collectionId) {
    final var index = Arrays.binarySearch(polymorphicCollectionIds, collectionId);
    if (index < 0) {
      return;
    }

    if (index < polymorphicCollectionIds.length - 1) {
      System.arraycopy(
          polymorphicCollectionIds,
          index + 1,
          polymorphicCollectionIds,
          index,
          polymorphicCollectionIds.length - (index + 1));
    }

    polymorphicCollectionIds = Arrays.copyOf(polymorphicCollectionIds,
        polymorphicCollectionIds.length - 1);

    removeCollectionFromIndexes(session, collectionId);
    for (var superClass : superClasses) {
      superClass.removePolymorphicCollectionId(session, collectionId);
    }
  }

  private void removeCollectionFromIndexes(DatabaseSessionInternal session, final int iId) {
    if (session.getStorage() instanceof AbstractStorage) {
      final var collectionName = session.getCollectionNameById(iId);
      final List<String> indexesToRemove = new ArrayList<>();

      final Set<Index> indexes = new HashSet<>();
      getIndexesInternal(session, indexes);

      for (final var index : indexes) {
        indexesToRemove.add(index.getName());
      }

      final var indexManager =
          session.getSharedContext().getIndexManager();
      for (final var indexName : indexesToRemove) {
        indexManager.removeCollectionFromIndex(session, collectionName, indexName);
      }
    }
  }

  /**
   * Add different collection id to the "polymorphic collection ids" array.
   */
  protected void addPolymorphicCollectionIds(
      DatabaseSessionInternal session,
      final SchemaClassShared iBaseClass,
      boolean validateIndexes
  ) {
    var collections = new IntRBTreeSet(polymorphicCollectionIds);

    for (var collectionId : iBaseClass.polymorphicCollectionIds) {
      if (collections.add(collectionId)) {
        try {
          addCollectionIdToIndexes(session, collectionId, validateIndexes);
        } catch (RuntimeException e) {
          LogManager.instance()
              .warn(
                  this,
                  "Error adding collectionId '%d' to index of class '%s'",
                  e,
                  collectionId,
                  getName());
          collections.remove(collectionId);
        }
      }
    }

    polymorphicCollectionIds = collections.toIntArray();
  }

  private void addPolymorphicCollectionIdsWithInheritance(
      DatabaseSessionInternal session,
      final SchemaClassShared iBaseClass,
      boolean validateIndexes) {
    addPolymorphicCollectionIds(session, iBaseClass, validateIndexes);
    for (var superClass : superClasses) {
      superClass.addPolymorphicCollectionIdsWithInheritance(session, iBaseClass, validateIndexes);
    }
  }


  @Nullable
  public static String decodeClassName(String s) {
    if (s == null) {
      return null;
    }
    s = s.trim();
    if (!s.isEmpty() && s.charAt(0) == '`' && s.charAt(s.length() - 1) == '`') {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }


  @Override
  public SchemaPropertyShared addProperty(
      DatabaseSessionInternal session, final String propertyName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassShared linkedClass,
      final boolean unsafe) {
    if (type == null) {
      throw new SchemaException(session.getDatabaseName(), "Property type not defined.");
    }

    if (propertyName == null || propertyName.isEmpty()) {
      throw new SchemaException(session.getDatabaseName(), "Property name is null or empty");
    }

    validatePropertyName(propertyName);
    if (session.getTransactionInternal().isActive()) {
      throw new SchemaException(session.getDatabaseName(),
          "Cannot create property '" + propertyName + "' inside a transaction");
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    if (linkedType != null) {
      SchemaPropertyShared.checkLinkTypeSupport(type);
    }

    if (linkedClass != null) {
      SchemaPropertyShared.checkSupportLinkedClass(type);
    }

    acquireSchemaWriteLock();
    try {
      return addPropertyInternal(session, propertyName, type,
          linkedType, linkedClass, unsafe);

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
  public void removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClassShared baseClass) {
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      if (subclasses == null) {
        return;
      }

      if (subclasses.remove(baseClass)) {
        removePolymorphicCollectionIds(session, baseClass);
      }

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void addSuperClass(DatabaseSessionInternal session,
      final SchemaClassShared superClass) {

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkParametersConflict(session, superClass);
    addSuperClassInternal(session, superClass);
  }

  public void addSuperClassInternal(DatabaseSessionInternal session,
      final SchemaClassShared superClass) {

    acquireSchemaWriteLock();
    try {

      if (superClass.getName().equals(SchemaClassProxy.VERTEX_CLASS_NAME) ||
          superClass.getName().equals(SchemaClassProxy.EDGE_CLASS_NAME)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot add the class '"
                + superClass.getName()
                + "' as superclass of the class '"
                + this.getName()
                + "'. Addition of graph classes is not allowed");
      }

      // CHECK THE USER HAS UPDATE PRIVILEGE AGAINST EXTENDING CLASS
      final var user = session.getCurrentUser();
      if (user != null) {
        user.allow(session, Rule.ResourceGeneric.CLASS, superClass.getName(),
            Role.PERMISSION_UPDATE);
      }

      if (superClasses.contains(superClass)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class: '"
                + this.getName()
                + "' already has the class '"
                + superClass.getName()
                + "' as superclass");
      }

      superClass.addBaseClass(session, this, true);
      superClasses.add(superClass);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void removeSuperClass(DatabaseSessionInternal session, SchemaClassShared superClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      removeSuperClassInternal(session, superClass);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void removeSuperClassInternal(DatabaseSessionInternal session,
      final SchemaClassShared superClass) {
    acquireSchemaWriteLock();
    try {
      if (superClass.getName().equals(SchemaClassProxy.VERTEX_CLASS_NAME) ||
          superClass.getName().equals(SchemaClassProxy.EDGE_CLASS_NAME)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot remove the class '"
                + superClass.getName()
                + "' as superclass of the class '"
                + this.getName()
                + "'. Removal of graph classes is not allowed");
      }

      final SchemaClassShared cls;
      cls = superClass;

      if (superClasses.contains(cls)) {
        cls.removeBaseClassInternal(session, this);

        superClasses.remove(superClass);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setSuperClasses(DatabaseSessionInternal session,
      final List<SchemaClassShared> classes) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    if (classes != null) {
      List<SchemaClassShared> toCheck = new ArrayList<>(classes);
      toCheck.add(this);
      checkParametersConflict(session, toCheck);
    }
    acquireSchemaWriteLock();
    try {
      setSuperClassesInternal(session, classes, true);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  @Override
  protected void setSuperClassesInternal(DatabaseSessionInternal session,
      final List<SchemaClassShared> classes, boolean validateIndexes) {
    if (!name.equals(SchemaClass.EDGE_CLASS_NAME) && isEdgeType()) {
      if (!classes.contains(owner.getClass(SchemaClass.EDGE_CLASS_NAME))) {
        throw new IllegalArgumentException(
            "Edge class must have super class " + SchemaClass.EDGE_CLASS_NAME
                + ", its removal is not allowed.");
      }
    }
    if (!name.equals(SchemaClass.VERTEX_CLASS_NAME) && isVertexType()) {
      if (!classes.contains(owner.getClass(SchemaClass.VERTEX_CLASS_NAME))) {
        throw new IllegalArgumentException(
            "Vertex class must have super class " + SchemaClass.VERTEX_CLASS_NAME
                + ", its removal is not allowed.");
      }
    }

    List<SchemaClassShared> newSuperClasses = new ArrayList<>();
    SchemaClassShared cls;
    for (var superClass : classes) {
      cls = superClass;
      if (newSuperClasses.contains(cls)) {
        throw new SchemaException(session.getDatabaseName(),
            "Duplicated superclass '" + cls.getName() + "'");
      }

      newSuperClasses.add(cls);
    }

    List<SchemaClassShared> toAddList = new ArrayList<>(newSuperClasses);
    toAddList.removeAll(superClasses);
    List<SchemaClassShared> toRemoveList = new ArrayList<>(superClasses);
    toRemoveList.removeAll(newSuperClasses);

    for (var toRemove : toRemoveList) {
      toRemove.removeBaseClassInternal(session, this);
    }
    for (var addTo : toAddList) {
      addTo.addBaseClass(session, this, validateIndexes);
    }
    superClasses.clear();
    superClasses.addAll(newSuperClasses);
  }

  @Override
  public void setName(DatabaseSessionInternal session, final String name) {
    if (getName().equals(name)) {
      return;
    }
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(name);
    var oClass = session.getMetadata().getSlowMutableSchema().getClass(name);
    if (oClass != null) {
      var error =
          String.format(
              "Cannot rename class %s to %s. A Class with name %s exists", this.name, name, name);
      throw new SchemaException(session.getDatabaseName(), error);
    }
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(session.getDatabaseName(),
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + name
              + "'");
    }
    acquireSchemaWriteLock();
    try {
      setNameInternal(session, name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNameInternal(DatabaseSessionInternal session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      final var oldName = this.name;
      owner.changeClassName(session, this.name, name, this);
      this.name = name;
      renameCollection(session, oldName, this.name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  public SchemaPropertyShared addPropertyInternal(
      DatabaseSessionInternal session, final String name,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassShared linkedClass,
      final boolean unsafe) {
    if (name == null || name.isEmpty()) {
      throw new SchemaException(session.getDatabaseName(), "Found property name null");
    }

    if (!unsafe) {
      checkPersistentPropertyType(session, name, type, linkedClass);
    }

    final SchemaPropertyShared prop;

    // This check are doubled because used by sql commands
    if (linkedType != null) {
      SchemaPropertyShared.checkLinkTypeSupport(type);
    }

    if (linkedClass != null) {
      SchemaPropertyShared.checkSupportLinkedClass(type);
    }

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      if (properties.containsKey(name)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + this.name + "' already has property '" + name + "'");
      }

      var global = owner.findOrCreateGlobalProperty(name, type);

      prop = createPropertyInstance(global);

      properties.put(name, prop);

      if (linkedType != null) {
        prop.setLinkedTypeInternal(session, linkedType);
      } else if (linkedClass != null) {
        prop.setLinkedClassInternal(session, linkedClass);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }

    if (prop != null && !unsafe) {
      fireDatabaseMigration(session, name, type);
    }

    return prop;
  }


  @Override
  public void setStrictMode(DatabaseSessionInternal session, final boolean isStrict) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setStrictModeInternal(session, isStrict);
    } finally {
      releaseSchemaWriteLock(session);
    }

  }

  protected void setStrictModeInternal(DatabaseSessionInternal session, final boolean iStrict) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      this.strictMode = iStrict;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setDescription(DatabaseSessionInternal session, String iDescription) {
    if (iDescription != null) {
      iDescription = iDescription.trim();
      if (iDescription.isEmpty()) {
        iDescription = null;
      }
    }
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
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);
      this.description = iDescription;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void dropProperty(DatabaseSessionInternal session, final String propertyName) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot drop a property inside a transaction");
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    acquireSchemaWriteLock();
    try {
      if (!properties.containsKey(propertyName)) {
        throw new SchemaException(session.getDatabaseName(),
            "Property '" + propertyName + "' not found in class " + name + "'");
      }
      dropPropertyInternal(session, propertyName);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void dropPropertyInternal(
      DatabaseSessionInternal session, final String iPropertyName) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot drop a property inside a transaction");
    }
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      final var prop = properties.remove(iPropertyName);

      if (prop == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Property '" + iPropertyName + "' not found in class " + name + "'");
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public void setOverSize(DatabaseSessionInternal session, final float overSize) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      setOverSizeInternal(session, overSize);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setOverSizeInternal(DatabaseSessionInternal session, final float overSize) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      this.overSize = overSize;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void setAbstract(DatabaseSessionInternal session, boolean isAbstract) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      setAbstractInternal(session, isAbstract);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCustomInternal(DatabaseSessionInternal session, final String name,
      final String value) {
    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      if (customFields == null) {
        customFields = new HashMap<>();
      }
      if (value == null || "null".equalsIgnoreCase(value)) {
        customFields.remove(name);
      } else {
        customFields.put(name, value);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setAbstractInternal(DatabaseSessionInternal database, final boolean isAbstract) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock();
    try {
      if (isAbstract) {
        // SWITCH TO ABSTRACT
        if (defaultCollectionId != NOT_EXISTENT_COLLECTION_ID) {
          // CHECK
          if (count(database) > 0) {
            throw new IllegalStateException(
                "Cannot set the class as abstract because contains records.");
          }

          tryDropCollection(database, defaultCollectionId);
          for (var collectionId : getCollectionIds()) {
            tryDropCollection(database, collectionId);
            removePolymorphicCollectionId(database, collectionId);
            owner.removeCollectionForClass(database, collectionId);
          }

          setCollectionIds(new int[]{NOT_EXISTENT_COLLECTION_ID});

          defaultCollectionId = NOT_EXISTENT_COLLECTION_ID;
        }
      } else {
        if (!abstractClass) {
          return;
        }

        var collectionId = database.getCollectionIdByName(name);
        if (collectionId == -1) {
          collectionId = database.addCollection(name);
        }

        this.defaultCollectionId = collectionId;
        this.collectionIds[0] = this.defaultCollectionId;
        this.polymorphicCollectionIds = Arrays.copyOf(collectionIds, collectionIds.length);
        for (var clazz : getAllSubclasses()) {
          if (clazz instanceof SchemaClassShared) {
            addPolymorphicCollectionIds(database, clazz, true);
          } else {
            LogManager.instance()
                .warn(this, "Warning: cannot set polymorphic collection IDs for class " + name);
          }
        }
      }

      this.abstractClass = isAbstract;
    } finally {
      releaseSchemaWriteLock(database);
    }
  }


  @Override
  protected void addCollectionIdToIndexes(DatabaseSessionInternal session, int iId,
      boolean requireEmpty) {
    var collectionName = session.getCollectionNameById(iId);
    final List<String> indexesToAdd = new ArrayList<>();

    for (var index : getIndexesInternal(session)) {
      indexesToAdd.add(index.getName());
    }

    final var indexManager =
        session.getSharedContext().getIndexManager();
    for (var indexName : indexesToAdd) {
      indexManager.addCollectionToIndex(session, collectionName, indexName, requireEmpty);
    }
  }
}
