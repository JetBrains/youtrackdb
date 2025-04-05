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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ClassTrigger;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityShared;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserIml;
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
  private final ClusterSelectionStrategy clusterSelection;
  private final int[] clusterIds;
  private final int[] polymorphicClusterIds;
  private final Collection<String> baseClassesNames;
  private final List<String> superClassesNames;

  private final String shortName;
  private final Map<String, String> customFields;
  private final String description;

  private final ImmutableSchema schema;
  // do not do it volatile it is already SAFE TO USE IT in MT mode.
  private final List<SchemaImmutableClass> superClasses;
  // do not do it volatile it is already SAFE TO USE IT in MT mode.
  private Collection<SchemaImmutableClass> subclasses;
  private boolean restricted;
  private boolean isVertexType;
  private boolean isEdgeType;
  private boolean triggered;
  private boolean function;
  private boolean scheduler;
  private boolean sequence;
  private boolean ouser;
  private boolean orole;
  private boolean securityPolicy;
  private HashSet<Index> indexes;

  public SchemaImmutableClass(DatabaseSessionInternal session, final SchemaClassInternal oClass,
      final ImmutableSchema schema) {
    isAbstract = oClass.isAbstract();
    strictMode = oClass.isStrictMode();
    this.schema = schema;

    superClassesNames = oClass.getSuperClassesNames();
    superClasses = new ArrayList<>(superClassesNames.size());

    name = oClass.getName();
    streamAbleName = oClass.getStreamableName();
    clusterSelection = oClass.getClusterSelection();
    clusterIds = oClass.getClusterIds();
    polymorphicClusterIds = oClass.getPolymorphicClusterIds();

    baseClassesNames = new ArrayList<String>();
    for (SchemaClass baseClass : oClass.getSubclasses(session)) {
      baseClassesNames.add(baseClass.getName());
    }

    shortName = oClass.getShortName();

    properties = new HashMap<>();
    for (SchemaProperty p : oClass.declaredProperties()) {
      properties.put(p.getName(),
          new ImmutableSchemaProperty(session, (SchemaPropertyInternal) p, this));
    }

    Map<String, String> customFields = new HashMap<String, String>();
    for (String key : oClass.getCustomKeys()) {
      customFields.put(key, oClass.getCustom(key));
    }

    this.customFields = Collections.unmodifiableMap(customFields);
    this.description = oClass.getDescription();
  }

  public void init(DatabaseSession session) {
    if (!inited) {
      initSuperClasses((DatabaseSessionInternal) session);
      initBaseClasses();

      final Collection<SchemaProperty> allProperties = new ArrayList<SchemaProperty>();
      final Map<String, SchemaProperty> allPropsMap = new HashMap<String, SchemaProperty>(20);
      for (int i = superClasses.size() - 1; i >= 0; i--) {
        allProperties.addAll(superClasses.get(i).allProperties);
        allPropsMap.putAll(superClasses.get(i).allPropertiesMap);
      }
      allProperties.addAll(properties.values());
      for (SchemaProperty p : properties.values()) {
        final String propName = p.getName();

        if (!allPropsMap.containsKey(propName)) {
          allPropsMap.put(propName, p);
        }
      }

      this.allProperties = Collections.unmodifiableCollection(allProperties);
      this.allPropertiesMap = Collections.unmodifiableMap(allPropsMap);
      this.restricted = isSubClassOf(session, SecurityShared.RESTRICTED_CLASSNAME);
      this.isVertexType = isSubClassOf(session, SchemaClass.VERTEX_CLASS_NAME);
      this.isEdgeType = isSubClassOf(session, SchemaClass.EDGE_CLASS_NAME);
      this.triggered = isSubClassOf(session, ClassTrigger.CLASSNAME);
      this.function = isSubClassOf(session, FunctionLibraryImpl.CLASSNAME);
      this.scheduler = isSubClassOf(session, ScheduledEvent.CLASS_NAME);
      this.sequence = isSubClassOf(session, DBSequence.CLASS_NAME);
      this.ouser = isSubClassOf(session, SecurityUserIml.CLASS_NAME);
      this.orole = isSubClassOf(session, Role.CLASS_NAME);
      this.securityPolicy = SecurityPolicy.class.getSimpleName().equals(this.name);
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
  public SchemaClass setAbstract(DatabaseSession session, boolean iAbstract) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isStrictMode() {
    return strictMode;
  }

  @Override
  public SchemaClass setStrictMode(DatabaseSession session, boolean iMode) {
    throw new UnsupportedOperationException();
  }

  @Override
  @Deprecated
  public SchemaClass getSuperClass(DatabaseSession session) {
    initSuperClasses((DatabaseSessionInternal) session);

    return superClasses.isEmpty() ? null : superClasses.get(0);
  }

  @Override
  @Deprecated
  public SchemaClass setSuperClass(DatabaseSession session, SchemaClass iSuperClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SchemaClass> getSuperClasses(DatabaseSession session) {
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
  public SchemaClass setSuperClasses(DatabaseSession session, List<? extends SchemaClass> classes) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass addSuperClass(DatabaseSession session, SchemaClass superClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeSuperClass(DatabaseSession session, SchemaClass superClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public SchemaClass setName(DatabaseSession session, String iName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getStreamableName() {
    return streamAbleName;
  }

  @Override
  public Collection<SchemaProperty> declaredProperties() {
    return Collections.unmodifiableCollection(properties.values());
  }

  @Override
  public Collection<SchemaProperty> properties(DatabaseSession session) {
    return allProperties;
  }

  @Override
  public Map<String, SchemaProperty> propertiesMap(DatabaseSession session) {
    return allPropertiesMap;
  }

  public void getIndexedProperties(DatabaseSessionInternal session,
      Collection<SchemaProperty> indexedProperties) {
    for (SchemaProperty p : properties.values()) {
      if (areIndexed(session, p.getName())) {
        indexedProperties.add(p);
      }
    }
    initSuperClasses(session);
    for (SchemaImmutableClass superClass : superClasses) {
      superClass.getIndexedProperties(session, indexedProperties);
    }
  }

  @Override
  public Collection<SchemaProperty> getIndexedProperties(DatabaseSession session) {
    Collection<SchemaProperty> indexedProps = new HashSet<SchemaProperty>();
    getIndexedProperties((DatabaseSessionInternal) session, indexedProps);
    return indexedProps;
  }

  @Override
  public SchemaProperty getProperty(DatabaseSession session, String propertyName) {
    return getPropertyInternal(session, propertyName);
  }

  @Override
  public SchemaPropertyInternal getPropertyInternal(DatabaseSession session, String propertyName) {
    initSuperClasses((DatabaseSessionInternal) session);

    var p = properties.get(propertyName);
    if (p != null) {
      return p;
    }

    for (int i = 0; i < superClasses.size() && p == null; i++) {
      p = superClasses.get(i).getPropertyInternal(session, propertyName);
    }

    return p;
  }

  @Override
  public SchemaProperty createProperty(DatabaseSession session, String iPropertyName,
      PropertyType iType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaProperty createProperty(DatabaseSession session, String iPropertyName,
      PropertyType iType,
      SchemaClass iLinkedClass) {
    throw new UnsupportedOperationException();
  }


  @Override
  public SchemaProperty createProperty(DatabaseSession session, String iPropertyName,
      PropertyType iType,
      PropertyType iLinkedType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void dropProperty(DatabaseSession session, String iPropertyName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean existsProperty(DatabaseSession session, String propertyName) {
    boolean result = properties.containsKey(propertyName);
    if (result) {
      return true;
    }
    for (SchemaImmutableClass superClass : superClasses) {
      result = superClass.existsProperty(session, propertyName);
      if (result) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int getClusterForNewInstance(final EntityImpl entity) {
    return clusterSelection.getCluster(this, entity);
  }


  @Override
  public int[] getClusterIds() {
    return clusterIds;
  }

  @Override
  public SchemaClass addClusterId(DatabaseSession session, int iId) {
    throw new UnsupportedOperationException();
  }

  public ClusterSelectionStrategy getClusterSelection() {
    return clusterSelection;
  }


  @Override
  public SchemaClass setClusterSelection(DatabaseSession session, String iStrategyName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass setClusterSelection(DatabaseSession session,
      ClusterSelectionStrategy clusterSelection) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass addCluster(DatabaseSession session, String iClusterName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass truncateCluster(DatabaseSession session, String clusterName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaClass removeClusterId(DatabaseSession session, int iId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int[] getPolymorphicClusterIds() {
    return Arrays.copyOf(polymorphicClusterIds, polymorphicClusterIds.length);
  }

  public ImmutableSchema getSchema() {
    return schema;
  }

  @Override
  public Collection<SchemaClass> getSubclasses(DatabaseSession session) {
    initBaseClasses();

    ArrayList<SchemaClass> result = new ArrayList<SchemaClass>();
    for (SchemaClass c : subclasses) {
      result.add(c);
    }

    return result;
  }

  @Override
  public Collection<SchemaClass> getAllSubclasses(DatabaseSession session) {
    initBaseClasses();

    final Set<SchemaClass> set = new HashSet<SchemaClass>();
    set.addAll(getSubclasses(session));

    for (SchemaImmutableClass c : subclasses) {
      set.addAll(c.getAllSubclasses(session));
    }

    return set;
  }

  @Override
  public Collection<SchemaClass> getAllSuperClasses(DatabaseSession session) {
    Set<SchemaClass> ret = new HashSet<SchemaClass>();
    getAllSuperClasses(ret);
    return ret;
  }

  private void getAllSuperClasses(Set<SchemaClass> set) {
    set.addAll(superClasses);
    for (SchemaImmutableClass superClass : superClasses) {
      superClass.getAllSuperClasses(set);
    }
  }


  public long count(DatabaseSession session) {
    return count(session, true);
  }

  public long count(DatabaseSession session, boolean isPolymorphic) {
    return getDatabase().countClass(name, isPolymorphic);
  }

  public long countImpl(boolean isPolymorphic) {
    if (isPolymorphic) {
      return getDatabase()
          .countClusterElements(
              SchemaClassImpl.readableClusters(getDatabase(), polymorphicClusterIds, name));
    }

    return getDatabase()
        .countClusterElements(SchemaClassImpl.readableClusters(getDatabase(), clusterIds, name));
  }

  @Override
  public void truncate(DatabaseSession session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSubClassOf(DatabaseSession session, final String iClassName) {
    if (iClassName == null) {
      return false;
    }

    if (iClassName.equalsIgnoreCase(name) || iClassName.equalsIgnoreCase(shortName)) {
      return true;
    }

    final int s = superClasses.size();
    for (int i = 0; i < s; ++i) {
      if (superClasses.get(i).isSubClassOf(session, iClassName)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public boolean isSubClassOf(DatabaseSession session, final SchemaClass clazz) {
    if (clazz == null) {
      return false;
    }
    if (equals(clazz)) {
      return true;
    }

    final int s = superClasses.size();
    for (int i = 0; i < s; ++i) {
      if (superClasses.get(i).isSubClassOf(session, clazz)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isSuperClassOf(DatabaseSession session, SchemaClass clazz) {
    return clazz != null && clazz.isSubClassOf(session, this);
  }

  @Override
  public String getShortName() {
    return shortName;
  }

  @Override
  public SchemaClass setShortName(DatabaseSession session, String shortName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public SchemaClass setDescription(DatabaseSession session, String iDescription) {
    throw new UnsupportedOperationException();
  }


  public Object get(DatabaseSession session, ATTRIBUTES iAttribute) {
    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    switch (iAttribute) {
      case NAME:
        return name;
      case SHORTNAME:
        return shortName;
      case SUPERCLASS:
        return getSuperClass(session);
      case SUPERCLASSES:
        return getSuperClasses(session);
      case STRICT_MODE:
        return strictMode;
      case ABSTRACT:
        return isAbstract;
      case CLUSTER_SELECTION:
        return clusterSelection;
      case CUSTOM:
        return customFields;
      case DESCRIPTION:
        return description;
    }

    throw new IllegalArgumentException("Cannot find attribute '" + iAttribute + "'");
  }

  @Override
  public void createIndex(DatabaseSession session, String iName, INDEX_TYPE iType,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(DatabaseSession session, String iName, String iType,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(
      DatabaseSession session, String iName, INDEX_TYPE iType,
      ProgressListener iProgressListener,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSession session, Collection<String> fields) {
    initSuperClasses((DatabaseSessionInternal) session);

    final Set<String> result = new HashSet<>(getClassInvolvedIndexes(session, fields));

    for (SchemaImmutableClass superClass : superClasses) {
      result.addAll(superClass.getInvolvedIndexes(session, fields));
    }
    return result;
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSession session, Collection<String> fields) {
    initSuperClasses((DatabaseSessionInternal) session);

    final Set<Index> result = new HashSet<>(getClassInvolvedIndexesInternal(session, fields));
    for (SchemaImmutableClass superClass : superClasses) {
      result.addAll(superClass.getInvolvedIndexesInternal(session, fields));
    }

    return result;
  }

  @Override
  public Set<String> getInvolvedIndexes(DatabaseSession session, String... fields) {
    return getInvolvedIndexes(session, Arrays.asList(fields));
  }

  @Override
  public Set<Index> getInvolvedIndexesInternal(DatabaseSession session, String... fields) {
    return getInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSession session, Collection<String> fields) {
    return getClassInvolvedIndexesInternal(session, fields).stream().map(Index::getName)
        .collect(HashSet::new, HashSet::add, HashSet::addAll);
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSession session,
      Collection<String> fields) {
    final DatabaseSessionInternal database = getDatabase();
    final IndexManagerAbstract indexManager = database.getMetadata().getIndexManagerInternal();
    return indexManager.getClassInvolvedIndexes(database, name, fields);
  }

  @Override
  public Set<String> getClassInvolvedIndexes(DatabaseSession session, String... fields) {
    return getClassInvolvedIndexes(session, Arrays.asList(fields));
  }

  @Override
  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSession session, String... fields) {
    return getClassInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  @Override
  public boolean areIndexed(DatabaseSession session, Collection<String> fields) {
    final DatabaseSessionInternal database = getDatabase();
    final IndexManagerAbstract indexManager = database.getMetadata().getIndexManagerInternal();
    final boolean currentClassResult = indexManager.areIndexed(name, fields);

    initSuperClasses((DatabaseSessionInternal) session);

    if (currentClassResult) {
      return true;
    }
    for (SchemaImmutableClass superClass : superClasses) {
      if (superClass.areIndexed(session, fields)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean areIndexed(DatabaseSession session, String... fields) {
    return areIndexed(session, Arrays.asList(fields));
  }

  @Override
  public Set<String> getClassIndexes(DatabaseSession session) {
    return this.indexes.stream().map(Index::getName).collect(HashSet::new, HashSet::add,
        HashSet::addAll);
  }

  @Override
  public String getClusterSelectionStrategyName() {
    return clusterSelection.getName();
  }

  @Override
  public SchemaProperty createProperty(DatabaseSession session, String iPropertyName,
      PropertyType iType, PropertyType iLinkedType, boolean unsafe) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SchemaProperty createProperty(DatabaseSession session, String iPropertyName,
      PropertyType iType, SchemaClass iLinkedClass, boolean unsafe) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(DatabaseSession session, String iName, String iType,
      ProgressListener iProgressListener, Map<String, ?> metadata, String algorithm,
      String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createIndex(DatabaseSession session, String iName, String iType,
      ProgressListener iProgressListener, Map<String, ?> metadata, String... fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<Index> getClassIndexesInternal(DatabaseSession session) {
    return this.indexes;
  }

  @Override
  public Index getClassIndex(DatabaseSession session, String name) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    return database
        .getMetadata()
        .getIndexManagerInternal()
        .getClassIndex(database, this.name, name);
  }

  public void getClassIndexes(DatabaseSession session, final Collection<Index> indexes) {
    final DatabaseSessionInternal database = getDatabase();
    database.getMetadata().getIndexManagerInternal().getClassIndexes(database, name, indexes);
  }

  public void getRawClassIndexes(final Collection<Index> indexes) {
    getDatabase().getMetadata().getIndexManagerInternal().getClassRawIndexes(name, indexes);
  }

  @Override
  public void getIndexesInternal(DatabaseSession session, final Collection<Index> indexes) {
    initSuperClasses((DatabaseSessionInternal) session);

    getClassIndexes(session, indexes);
    for (SchemaClassInternal superClass : superClasses) {
      superClass.getIndexesInternal(session, indexes);
    }
  }

  public void getRawIndexes(DatabaseSession session, final Collection<Index> indexes) {
    initSuperClasses((DatabaseSessionInternal) session);

    getRawClassIndexes(indexes);
    for (SchemaImmutableClass superClass : superClasses) {
      superClass.getRawIndexes(session, indexes);
    }
  }

  @Override
  public Set<String> getIndexes(DatabaseSession session) {
    return this.indexes.stream().map(Index::getName).collect(HashSet::new, HashSet::add,
        HashSet::addAll);
  }

  @Override
  public Set<Index> getIndexesInternal(DatabaseSession session) {
    return this.indexes;
  }

  public Set<Index> getRawIndexes() {
    return indexes;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result;
    return result;
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (!SchemaClass.class.isAssignableFrom(obj.getClass())) {
      return false;
    }
    final SchemaClass other = (SchemaClass) obj;
    if (name == null) {
      return other.getName() == null;
    } else {
      return name.equals(other.getName());
    }
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
  public SchemaClass setCustom(DatabaseSession session, String iName, String iValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeCustom(DatabaseSession session, String iName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearCustom(DatabaseSession session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> getCustomKeys() {
    return Collections.unmodifiableSet(customFields.keySet());
  }

  @Override
  public boolean hasClusterId(final int clusterId) {
    return Arrays.binarySearch(clusterIds, clusterId) >= 0;
  }

  @Override
  public boolean hasPolymorphicClusterId(final int clusterId) {
    return Arrays.binarySearch(polymorphicClusterIds, clusterId) >= 0;
  }

  @Override
  public int compareTo(final SchemaClass other) {
    return name.compareTo(other.getName());
  }

  @Override
  public SchemaClass set(DatabaseSession session, ATTRIBUTES attribute, Object value) {
    throw new UnsupportedOperationException();
  }

  protected DatabaseSessionInternal getDatabase() {
    return DatabaseRecordThreadLocal.instance().get();
  }

  private Map<String, String> getCustomInternal() {
    return customFields;
  }

  private void initSuperClasses(DatabaseSessionInternal session) {
    if (superClassesNames != null && superClassesNames.size() != superClasses.size()) {
      superClasses.clear();
      for (String superClassName : superClassesNames) {
        SchemaImmutableClass superClass = (SchemaImmutableClass) schema.getClass(superClassName);
        superClass.init(session);
        superClasses.add(superClass);
      }
    }
  }

  private void initBaseClasses() {
    if (subclasses == null) {
      final List<SchemaImmutableClass> result = new ArrayList<SchemaImmutableClass>(
          baseClassesNames.size());
      for (String clsName : baseClassesNames) {
        result.add((SchemaImmutableClass) schema.getClass(clsName));
      }

      subclasses = result;
    }
  }

  public boolean isRestricted() {
    return restricted;
  }

  @Override
  public boolean isEdgeType(
      DatabaseSession unused) { // todo update interfaces to have immutable one without session?
    return isEdgeType;
  }

  @Override
  public boolean isVertexType(DatabaseSession unused) {
    return isVertexType;
  }

  public boolean isTriggered() {
    return triggered;
  }

  public boolean isFunction() {
    return function;
  }

  public boolean isScheduler() {
    return scheduler;
  }

  public boolean isOuser() {
    return ouser;
  }

  public boolean isOrole() {
    return orole;
  }

  public boolean isSequence() {
    return sequence;
  }
}
