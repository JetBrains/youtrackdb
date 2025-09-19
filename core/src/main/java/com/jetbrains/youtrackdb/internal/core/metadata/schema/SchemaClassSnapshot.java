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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.RoundRobinCollectionSelectionStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.schedule.ScheduledEvent;
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

public class SchemaClassSnapshot implements ImmutableSchemaClass {

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
  private final Map<String, SchemaPropertySnapshot> properties;
  private Map<String, SchemaPropertySnapshot> allPropertiesMap;
  private Collection<SchemaPropertySnapshot> allProperties;
  private final int[] collectionIds;
  private final int[] polymorphicCollectionIds;
  private final Collection<String> baseClassesNames;
  private final List<String> superClassesNames;

  private final Map<String, String> customFields;
  private final String description;

  private final SchemaSnapshot schema;
  // do not do it volatile it is already SAFE TO USE IT in MT mode.
  private final List<SchemaClassSnapshot> superClasses;
  // do not do it volatile it is already SAFE TO USE IT in MT mode.
  private Collection<SchemaClassSnapshot> subclasses;
  private boolean isVertexType;
  private boolean isEdgeType;
  private boolean function;
  private boolean scheduler;
  private boolean sequence;
  private boolean user;
  private boolean role;
  private boolean securityPolicy;
  private HashSet<Index> indexes;

  public SchemaClassSnapshot(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final SchemaClassShared oClass, final SchemaSnapshot schema) {

    isAbstract = oClass.isAbstract();
    strictMode = oClass.isStrictMode();
    this.schema = schema;

    superClassesNames = oClass.getSuperClassesNames(session);
    superClasses = new ArrayList<>(superClassesNames.size());

    name = oClass.getName();
    collectionIds = oClass.getCollectionIds();
    polymorphicCollectionIds = oClass.getPolymorphicCollectionIds();

    baseClassesNames = new ArrayList<>();
    for (var baseClass : oClass.getSubclasses()) {
      baseClassesNames.add(baseClass.getName());
    }

    properties = new HashMap<>();
    for (var p : oClass.declaredProperties()) {
      properties.put(p.getName(), new SchemaPropertySnapshot(session, p, this));
    }

    Map<String, String> customFields = new HashMap<>();
    for (var key : oClass.getCustomKeys()) {
      customFields.put(key, oClass.getCustom(key));
    }

    this.customFields = Collections.unmodifiableMap(customFields);
    this.description = oClass.getDescription();
  }

  public void init(DatabaseSessionInternal session) {
    if (!inited) {
      initSuperClasses(session);

      final Collection<SchemaPropertySnapshot> allProperties = new ArrayList<>();
      final Map<String, SchemaPropertySnapshot> allPropsMap = new HashMap<>(20);
      for (var i = superClasses.size() - 1; i >= 0; i--) {
        allProperties.addAll(superClasses.get(i).allProperties);
        allPropsMap.putAll(superClasses.get(i).allPropertiesMap);
      }
      allProperties.addAll(properties.values());
      for (var p : properties.values()) {
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
  public boolean isStrictMode() {
    return strictMode;
  }

  @Override
  public List<SchemaClassSnapshot> getSuperClasses() {
    return superClasses;
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
  public String getName() {
    return name;
  }

  @Override
  public Collection<SchemaPropertySnapshot> getDeclaredProperties() {
    return properties.values();
  }

  @Override
  public Collection<SchemaPropertySnapshot> getProperties() {
    return allProperties;
  }

  @Override
  public Map<String, SchemaPropertySnapshot> getPropertiesMap() {
    return allPropertiesMap;
  }

  @Override
  public SchemaPropertySnapshot getProperty(String propertyName) {
    var p = properties.get(propertyName);

    if (p != null) {
      return p;
    }

    for (var i = 0; i < superClasses.size() && p == null; i++) {
      p = superClasses.get(i).getProperty(propertyName);
    }

    return p;
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
    return RoundRobinCollectionSelectionStrategy.INSTANCE.getCollection(
        entity.getBoundedToSession(), this, entity);
  }


  @Override
  public int[] getCollectionIds() {
    return collectionIds;
  }

  @Override
  public int[] getPolymorphicCollectionIds() {
    return polymorphicCollectionIds;
  }

  public SchemaSnapshot getSchema() {
    return schema;
  }

  @Override
  public Collection<SchemaClassSnapshot> getSubclasses() {
    initBaseClasses();
    return subclasses;
  }

  @Override
  public Collection<SchemaClassSnapshot> getAllSubclasses() {
    initBaseClasses();

    var set = new HashSet<>(getSubclasses());

    for (var c : subclasses) {
      set.addAll(c.getAllSubclasses());
    }

    return set;
  }

  @Override
  public Collection<ImmutableSchemaClass> getAllSuperClasses() {
    var ret = new HashSet<ImmutableSchemaClass>();
    getAllSuperClasses(ret);
    return ret;
  }

  private void getAllSuperClasses(HashSet<ImmutableSchemaClass> set) {
    set.addAll(superClasses);
    for (var superClass : superClasses) {
      superClass.getAllSuperClasses(set);
    }
  }


  @Override
  public long count(DatabaseSessionInternal session) {
    return count(session, true);
  }

  @Override
  public long count(DatabaseSessionInternal session, boolean isPolymorphic) {
    assert session.assertIfNotActive();
    return session.countClass(name, isPolymorphic);
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
  public boolean isSubClassOf(final ImmutableSchemaClass clazz) {
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
  public boolean isSuperClassOf(ImmutableSchemaClass clazz) {
    return clazz != null && clazz.isSubClassOf(this);
  }

  @Override
  public String getDescription() {
    return description;
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
    final var indexManager = session.getSharedContext().getIndexManager();
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
    final var indexManager = session.getSharedContext().getIndexManager();
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
  public Set<Index> getClassIndexesInternal() {
    return this.indexes;
  }

  @Override
  public Index getClassIndex(DatabaseSessionInternal session, String name) {
    assert session.assertIfNotActive();
    return session
        .getSharedContext()
        .getIndexManager()
        .getClassIndex(session, this.name, name);
  }

  public void getClassIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    assert session.assertIfNotActive();
    session.getSharedContext().getIndexManager()
        .getClassIndexes(session, name, indexes);
  }

  public void getRawClassIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    assert session.assertIfNotActive();
    session.getSharedContext().getIndexManager()
        .getClassRawIndexes(session, name, indexes);
  }

  @Override
  public void getIndexesInternal(DatabaseSessionInternal session, final Collection<Index> indexes) {
    initSuperClasses(session);

    getClassIndexes(session, indexes);
    for (var superClass : superClasses) {
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


  private void initSuperClasses(DatabaseSessionInternal session) {
    if (superClassesNames != null && superClassesNames.size() != superClasses.size()) {
      superClasses.clear();
      for (var superClassName : superClassesNames) {
        var superClass = schema.getClass(superClassName);
        superClass.init(session);
        superClasses.add(superClass);
      }
    }
  }

  private void initBaseClasses() {
    if (subclasses == null) {
      final List<SchemaClassSnapshot> result = new ArrayList<>(
          baseClassesNames.size());
      for (var clsName : baseClassesNames) {
        result.add(schema.getClass(clsName));
      }

      subclasses = result;
    }
  }

  @Override
  public boolean isEdgeType() {
    return isEdgeType;
  }

  @Override
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
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }

    if (obj instanceof SchemaClass schemaClass) {
      return schemaClass.getBoundToSession() == null && name.equals(schemaClass.getName());
    }

    return false;
  }

  @Nullable
  @Override
  public DatabaseSessionEmbedded getBoundToSession() {
    return null;
  }
}
