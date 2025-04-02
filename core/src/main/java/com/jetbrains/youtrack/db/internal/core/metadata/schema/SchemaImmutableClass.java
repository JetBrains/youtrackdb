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
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.schedule.ScheduledEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SchemaImmutableClass implements SchemaClassInternal {

  /**
   * use SchemaClass.EDGE_CLASS_NAME instead
   */
  @Deprecated
  public static final String EDGE_CLASS_NAME = SchemaClass.EDGE_CLASS_NAME;

  /**
   * use SchemaClass.EDGE_CLASS_NAME instead
   */
  @Deprecated
  public static final String VERTEX_CLASS_NAME = SchemaClass.VERTEX_CLASS_NAME;

  private boolean inited = false;
  private final boolean isAbstract;
  private final boolean strictMode;
  private final String name;
  private final String streamAbleName;
  private final Map<String, SchemaPropertyInternal> properties;
  private Map<String, SchemaProperty> allPropertiesMap;
  private Collection<SchemaProperty> allProperties;
  private final CollectionSelectionStrategy collectionSelection;
  private final int[] collectionIds;
  private final int[] polymorphicCollectionIds;
  private final Collection<String> baseClassesNames;
  private final List<String> superClassesNames;

  private final Map<String, String> customFields;
  private final String description;

  private final ImmutableSchema schema;
  // do not do it volatile it is already SAFE TO USE IT in MT mode.
  private final List<SchemaImmutableClass> superClasses;
  // do not do it volatile it is already SAFE TO USE IT in MT mode.
  private Collection<SchemaImmutableClass> subclasses;
  private boolean isVertexType;
  private boolean isEdgeType;
  private boolean function;
  private boolean scheduler;
  private boolean sequence;
  private boolean user;
  private boolean role;
  private boolean securityPolicy;
  private HashSet<Index> indexes;

  @Nonnull
  private final SchemaClassImpl original;


  public SchemaImmutableClass(@Nonnull DatabaseSessionInternal session,
      @Nonnull final SchemaClassImpl oClass,
      final ImmutableSchema schema) {

    isAbstract = oClass.isAbstract(session);
    strictMode = oClass.isStrictMode(session);
    this.schema = schema;

    superClassesNames = oClass.getSuperClassesNames(session);
    superClasses = new ArrayList<>(superClassesNames.size());

    name = oClass.getName(session);
    streamAbleName = oClass.getStreamableName(session);
    collectionSelection = oClass.getCollectionSelection(session);
    collectionIds = oClass.getCollectionIds(session);
    polymorphicCollectionIds = oClass.getPolymorphicCollectionIds(session);

    baseClassesNames = new ArrayList<>();
    for (var baseClass : oClass.getSubclasses(session)) {
      baseClassesNames.add(baseClass.getName(session));
    }

    properties = new HashMap<>();
    for (var p : oClass.declaredProperties(session)) {
      properties.put(p.getName(session),
          new ImmutableSchemaProperty(session, p, this));
    }

    Map<String, String> customFields = new HashMap<>();
    for (var key : oClass.getCustomKeys(session)) {
      customFields.put(key, oClass.getCustom(session, key));
    }

    this.customFields = Collections.unmodifiableMap(customFields);
    this.description = oClass.getDescription(session);

    this.original = oClass;
  }

  public void init(DatabaseSessionInternal session) {
    if (!inited) {
      initSuperClasses(session);

      final Collection<SchemaProperty> allProperties = new ArrayList<>();
      final Map<String, SchemaProperty> allPropsMap = new HashMap<>(20);
      for (var i = superClasses.size() - 1; i >= 0; i--) {
        allProperties.addAll(superClasses.get(i).allProperties);
        allPropsMap.putAll(superClasses.get(i).allPropertiesMap);
      }
      allProperties.addAll(properties.values());
      for (SchemaProperty p : properties.values()) {
        final var propName = p.getName();

        if (!allPropsMap.containsKey(propName)) {
          allPropsMap.put(propName, p);
        }
      }

      this.allProperties = Collections.unmodifiableCollection(allProperties);
      this.allPropertiesMap = Collections.unmodifiableMap(allPropsMap);
      this.isVertexType = isSubClassOf(SchemaClass.VERTEX_CLASS_NAME);
      this.isEdgeType = isSubClassOf(SchemaClass.EDGE_CLASS_NAME);
      this.function = isSubClassOf(FunctionLibraryImpl.CLASSNAME);
      this.scheduler = isSubClassOf(ScheduledEvent.CLASS_NAME);
      this.sequence = isSubClassOf(DBSequence.CLASS_NAME);
      this.user = isSubClassOf(SecurityUserImpl.CLASS_NAME);
      this.role = isSubClassOf(Role.CLASS_NAME);
      this.securityPolicy = isSubClassOf(SecurityPolicy.CLASS_NAME);
      this.indexes = new HashSet<>();
      getRawIndexes(session, indexes);
    }

    inited = true;
  }

  public boolean isSecurityPolicy() {
    return securityPolicy;
  }

  @Override
  public boolean isAbstract() {
    return isAbstract;
  }

  @Override
  public SchemaClass setAbstract(boolean iAbstract) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isStrictMode() {
    return strictMode;
  }

  @Override
  public void setStrictMode(boolean iMode) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SchemaClass> getSuperClasses() {
    return Collections.unmodifiableList(superClasses);
  }

  @Override
  public boolean hasSuperClasses() {
    return !superClasses.isEmpty();
  }

  @Override
  public List<String> getSuperClassesNames() {
    return superClassesNames;
  }

  @Override
  public SchemaClass setSuperClasses(List<? extends SchemaClass> classes) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass addSuperClass(SchemaClass superClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeSuperClass(SchemaClass superClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public SchemaClass setName(String iName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getStreamableName() {
    return streamAbleName;
  }

  @Override
  public Collection<SchemaProperty> getDeclaredProperties() {
    return Collections.unmodifiableCollection(properties.values());
  }

  @Override
  public Collection<SchemaProperty> getProperties() {
    return allProperties;
  }

  @Override
  public Map<String, SchemaProperty> getPropertiesMap() {
    return allPropertiesMap;
  }

  @Override
  public SchemaProperty getProperty(String propertyName) {
    return getPropertyInternal(propertyName);
  }

  @Override
  public SchemaPropertyInternal getPropertyInternal(String propertyName) {
    var p = properties.get(propertyName);

    if (p != null) {
      return p;
    }

    for (var i = 0; i < superClasses.size() && p == null; i++) {
      p = superClasses.get(i).getPropertyInternal(propertyName);
    }

    return p;
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType,
      SchemaClass iLinkedClass) {
    throw new UnsupportedOperationException();
  }


  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyType iType,
      PropertyType iLinkedType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropProperty(String iPropertyName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean existsProperty(String propertyName) {
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
  }

  @Override
  public int getCollectionForNewInstance(final EntityImpl entity) {
    return collectionSelection.getCollection(entity.getBoundedToSession(), this, entity);
  }


  @Override
  public int[] getCollectionIds() {
    return collectionIds;
  }


  public CollectionSelectionStrategy getCollectionSelection() {
    return collectionSelection;
  }

  @Override
  public int[] getPolymorphicCollectionIds() {
    return Arrays.copyOf(polymorphicCollectionIds, polymorphicCollectionIds.length);
  }

  public ImmutableSchema getSchema() {
    return schema;
  }

  @Override
  public Collection<SchemaClass> getSubclasses() {
    initBaseClasses();
    return new ArrayList<>(subclasses);
  }

  @Override
  public Collection<SchemaClass> getAllSubclasses() {
    initBaseClasses();

    final Set<SchemaClass> set = new HashSet<>(getSubclasses());

    for (var c : subclasses) {
      set.addAll(c.getAllSubclasses());
    }

    return set;
  }

  @Override
  public Collection<SchemaClass> getAllSuperClasses() {
    Set<SchemaClass> ret = new HashSet<>();
    getAllSuperClasses(ret);
    return ret;
  }

  private void getAllSuperClasses(Set<SchemaClass> set) {
    set.addAll(superClasses);
    for (var superClass : superClasses) {
      superClass.getAllSuperClasses(set);
    }
  }


  public long count(DatabaseSessionInternal session) {
    return count(session, true);
  }

  public long count(DatabaseSessionInternal session, boolean isPolymorphic) {
    assert session.assertIfNotActive();
    return session.countClass(name, isPolymorphic);
  }

  public long countImpl(boolean isPolymorphic, DatabaseSessionInternal session) {
    assert session.assertIfNotActive();

    if (isPolymorphic) {
      return session
          .countCollectionElements(
              SchemaClassImpl.readableCollections(session, polymorphicCollectionIds, name));
    }

    return session
        .countCollectionElements(SchemaClassImpl.readableCollections(session, collectionIds, name));
  }

  @Override
  public void truncate() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSubClassOf(final String iClassName) {
    if (iClassName == null) {
      return false;
    }

    if (iClassName.equalsIgnoreCase(name)) {
      return true;
    }

    for (var superClass : superClasses) {
      if (superClass.isSubClassOf(iClassName)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public boolean isSubClassOf(final SchemaClass clazz) {
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
  }

  @Override
  public boolean isSuperClassOf(SchemaClass clazz) {
    return clazz != null && clazz.isSubClassOf(this);
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public SchemaClass setDescription(String iDescription) {
    throw new UnsupportedOperationException();
  }


  public Object get(ATTRIBUTES iAttribute) {
    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    return switch (iAttribute) {
      case NAME -> name;
      case SUPERCLASSES -> getSuperClasses();
      case STRICT_MODE -> strictMode;
      case ABSTRACT -> isAbstract;
      case CUSTOM -> customFields;
      case DESCRIPTION -> description;
    };

  }

  @Override
  public void createIndex(String iName, INDEX_TYPE iType,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(String iName, String iType,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(
      String iName, INDEX_TYPE iType,
      ProgressListener iProgressListener,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSessionInternal session,
      Collection<String> fields) {
    initSuperClasses(session);

    final Set<String> result = new HashSet<>(getClassInvolvedIndexes(session, fields));

    for (var superClass : superClasses) {
      result.addAll(superClass.getInvolvedIndexes(session, fields));
    }
    return result;
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session,
      Collection<String> fields) {
    initSuperClasses(session);

    final Set<Index> result = new HashSet<>(getClassInvolvedIndexesInternal(session, fields));
    for (var superClass : superClasses) {
      result.addAll(superClass.getInvolvedIndexesInternal(session, fields));
    }

    return result;
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSessionInternal session, String... fields) {
    return getInvolvedIndexes(session, Arrays.asList(fields));
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session, String... fields) {
    return getInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session,
      Collection<String> fields) {
    return getClassInvolvedIndexesInternal(session, fields).stream().map(Index::getName)
        .collect(HashSet::new, HashSet::add, HashSet::addAll);
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      Collection<String> fields) {
    final var indexManager = session.getMetadata().getIndexManagerInternal();
    return indexManager.getClassInvolvedIndexes(session, name, fields);
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session, String... fields) {
    assert session.assertIfNotActive();
    return getClassInvolvedIndexes(session, Arrays.asList(fields));
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      String... fields) {
    assert session.assertIfNotActive();
    return getClassInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  @Override
  public boolean areIndexed(DatabaseSessionInternal session, Collection<String> fields) {
    assert session.assertIfNotActive();
    final var indexManager = session.getMetadata().getIndexManagerInternal();
    final var currentClassResult = indexManager.areIndexed(session, name, fields);

    initSuperClasses(session);

    if (currentClassResult) {
      return true;
    }
    for (var superClass : superClasses) {
      if (superClass.areIndexed(session, fields)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean areIndexed(DatabaseSessionInternal session, String... fields) {
    assert session.assertIfNotActive();
    return areIndexed(session, Arrays.asList(fields));
  }

  @Override
  public Set<String> getClassIndexes() {
    return this.indexes.stream().map(Index::getName).collect(HashSet::new, HashSet::add,
        HashSet::addAll);
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyTypeInternal iType, PropertyTypeInternal iLinkedType, boolean unsafe) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaProperty createProperty(String iPropertyName,
      PropertyTypeInternal iType, SchemaClass iLinkedClass, boolean unsafe) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(String iName, String iType,
      ProgressListener iProgressListener, Map<String, Object> metadata, String algorithm,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(String iName, String iType,
      ProgressListener iProgressListener, Map<String, Object> metadata, String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<Index> getClassIndexesInternal() {
    return this.indexes;
  }

  @Override
  public Index getClassIndex(DatabaseSessionInternal session, String name) {
    assert session.assertIfNotActive();
    return session
        .getMetadata()
        .getIndexManagerInternal()
        .getClassIndex(session, this.name, name);
  }

  public void getClassIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    assert session.assertIfNotActive();
    session.getMetadata().getIndexManagerInternal()
        .getClassIndexes(session, name, indexes);
  }

  public void getRawClassIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    assert session.assertIfNotActive();
    session.getMetadata().getIndexManagerInternal()
        .getClassRawIndexes(session, name, indexes);
  }

  @Override
  public void getIndexesInternal(DatabaseSessionInternal session, final Collection<Index> indexes) {
    initSuperClasses(session);

    getClassIndexes(session, indexes);
    for (SchemaClassInternal superClass : superClasses) {
      superClass.getIndexesInternal(session, indexes);
    }
  }

  public void getRawIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    initSuperClasses(session);

    getRawClassIndexes(session, indexes);
    for (var superClass : superClasses) {
      superClass.getRawIndexes(session, indexes);
    }
  }

  @Override
  public Set<String> getIndexes() {
    return this.indexes.stream().map(Index::getName).collect(HashSet::new, HashSet::add,
        HashSet::addAll);
  }

  @Override
  public Set<Index> getIndexesInternal() {
    return this.indexes;
  }

  public Set<Index> getRawIndexes() {
    return indexes;
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public String getCustom(final String iName) {
    return customFields.get(iName);
  }

  @Override
  public SchemaClass setCustom(String iName, String iValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeCustom(String iName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearCustom() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> getCustomKeys() {
    return Collections.unmodifiableSet(customFields.keySet());
  }

  @Override
  public boolean hasCollectionId(int collectionId) {
    return Arrays.binarySearch(collectionIds, collectionId) >= 0;
  }

  @Override
  public boolean hasPolymorphicCollectionId(final int collectionId) {
    return Arrays.binarySearch(polymorphicCollectionIds, collectionId) >= 0;
  }


  @Override
  public SchemaClass set(ATTRIBUTES attribute, Object value) {
    throw new UnsupportedOperationException();
  }

  private void initSuperClasses(DatabaseSessionInternal session) {
    if (superClassesNames != null && superClassesNames.size() != superClasses.size()) {
      superClasses.clear();
      for (var superClassName : superClassesNames) {
        var superClass = (SchemaImmutableClass) schema.getClass(superClassName);
        superClass.init(session);
        superClasses.add(superClass);
      }
    }
  }

  private void initBaseClasses() {
    if (subclasses == null) {
      final List<SchemaImmutableClass> result = new ArrayList<>(
          baseClassesNames.size());
      for (var clsName : baseClassesNames) {
        result.add((SchemaImmutableClass) schema.getClass(clsName));
      }

      subclasses = result;
    }
  }

  public boolean isEdgeType() {
    return isEdgeType;
  }

  public boolean isVertexType() {
    return isVertexType;
  }

  public boolean isFunction() {
    return function;
  }

  public boolean isScheduler() {
    return scheduler;
  }

  public boolean isUser() {
    return user;
  }

  public boolean isRole() {
    return role;
  }

  public boolean isSequence() {
    return sequence;
  }

  @Override
  public SchemaClassImpl getImplementation() {
    return original;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }

    if (obj instanceof SchemaClassInternal schemaClass) {
      return schemaClass.getBoundToSession() == null && name.equals(schemaClass.getName());
    }

    return false;
  }

  @Nullable
  @Override
  public DatabaseSession getBoundToSession() {
    return null;
  }
}
