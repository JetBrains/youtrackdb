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

import com.jetbrains.youtrackdb.internal.common.util.ArrayUtils;
import com.jetbrains.youtrackdb.internal.common.util.MultiKey;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.RoundRobinCollectionSelectionStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaClassEntity;
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

  private final boolean isAbstract;
  private final boolean strictMode;
  private final String name;
  private final HashMap<String, SchemaPropertySnapshot> properties;
  private HashMap<String, SchemaPropertySnapshot> allPropertiesMap;
  private ArrayList<SchemaPropertySnapshot> allProperties;
  private final int[] collectionIds;
  private final int[] polymorphicCollectionIds;

  private final HashMap<String, String> customFields;
  private final String description;

  private final SchemaSnapshot schema;

  private final ArrayList<String> parentClassesNames = new ArrayList<>();
  private final ArrayList<SchemaClassSnapshot> parentClasses = new ArrayList<>();

  private final ArrayList<String> childClassesNames;
  private ArrayList<SchemaClassSnapshot> childClasses;


  private boolean isVertexType;
  private boolean isEdgeType;
  private boolean function;
  private boolean scheduler;
  private boolean sequence;
  private boolean user;
  private boolean role;
  private boolean securityPolicy;

  private final HashMap<String, Index> indexes;
  private final HashMap<MultiKey, Set<Index>> indexesByProperties;

  private boolean initialized;

  public SchemaClassSnapshot(@Nonnull DatabaseSessionEmbedded session,
      @Nonnull final SchemaClassEntity classEntity, final SchemaSnapshot schema,
      List<Index> indices) {

    isAbstract = classEntity.isAbstractClass();
    strictMode = classEntity.isStrictMode();
    this.schema = schema;

    var entityParentClasses = classEntity.getParentClasses();
    while (entityParentClasses.hasNext()) {
      var parent = entityParentClasses.next();
      this.parentClassesNames.add(parent.getName());
    }

    name = classEntity.getName();
    var classCollectionIds = classEntity.getCollectionIds();
    collectionIds = new int[classCollectionIds.size()];
    for (var i = 0; i < classCollectionIds.size(); i++) {
      collectionIds[i] = classCollectionIds.get(i).getId();
    }

    var classPolymorphicCollectionIds = classEntity.getPolymorphicCollectionIds();
    polymorphicCollectionIds = new int[classPolymorphicCollectionIds.size()];

    {
      var i = 0;
      for (var collectionId : classPolymorphicCollectionIds) {
        polymorphicCollectionIds[i] = collectionId.getId();
        i++;
      }
    }

    var classChildClasses = classEntity.getChildClasses();
    var childClassesNames = new ArrayList<String>();

    while (classChildClasses.hasNext()) {
      var childClass = classChildClasses.next();
      childClassesNames.add(childClass.getName());
    }

    this.childClassesNames = childClassesNames;

    var indexes = new HashMap<String, Index>();
    var indexesByProperties = new HashMap<MultiKey, Set<Index>>();

    for (var index : indices) {
      indexes.put(index.getName(), index);
      var indexDefinition = index.getDefinition();
      final var paramCount = indexDefinition.getParamCount();

      for (var i = 1; i <= paramCount; i++) {
        final var fields = indexDefinition.getProperties().subList(0, i);
        final var multiKey = new MultiKey(fields);
        var indexSet = indexesByProperties.get(multiKey);

        if (indexSet == null) {
          indexSet = new HashSet<>();
        } else {
          indexSet = new HashSet<>(indexSet);
        }

        indexSet.add(index);
        indexesByProperties.put(multiKey, indexSet);
      }
    }

    this.indexes = indexes;
    this.indexesByProperties = indexesByProperties;

    properties = new HashMap<>();
    var declaredProperties = classEntity.getDeclaredProperties();
    while (declaredProperties.hasNext()) {
      var declaredProperty = declaredProperties.next();
      properties.put(declaredProperty.getName(),
          new SchemaPropertySnapshot(session, declaredProperty, this));
    }

    var customPropertyNames = classEntity.getCustomPropertiesNames();
    var customProperties = new HashMap<String, String>();
    for (var key : customPropertyNames) {
      customProperties.put(key, classEntity.getCustomProperty(key));
    }

    this.customFields = customProperties;
    this.description = classEntity.getDescription();


  }

  public void init() {
    if (!initialized) {
      initParentClasses();
      addChildClasses();

      var allProperties = new ArrayList<SchemaPropertySnapshot>();
      var allPropsMap = new HashMap<String, SchemaPropertySnapshot>();

      for (var i = parentClasses.size() - 1; i >= 0; i--) {
        allProperties.addAll(parentClasses.get(i).allProperties);
        allPropsMap.putAll(parentClasses.get(i).allPropertiesMap);
      }

      allProperties.addAll(properties.values());
      for (var p : properties.values()) {
        final var propName = p.getName();

        if (!allPropsMap.containsKey(propName)) {
          allPropsMap.put(propName, p);
        }
      }

      this.allProperties = allProperties;
      this.allPropertiesMap = allPropsMap;
      this.isVertexType = isChildOf(SchemaClass.VERTEX_CLASS_NAME);
      this.isEdgeType = isChildOf(SchemaClass.EDGE_CLASS_NAME);
      this.function = isChildOf(FunctionLibraryImpl.CLASSNAME);
      this.scheduler = isChildOf(ScheduledEvent.CLASS_NAME);
      this.sequence = isChildOf(DBSequence.CLASS_NAME);
      this.user = isChildOf(SecurityUserImpl.CLASS_NAME);
      this.role = isChildOf(Role.CLASS_NAME);
      this.securityPolicy = isChildOf(SecurityPolicy.CLASS_NAME);
    }

    initialized = true;
  }

  private void initParentClasses() {
    if (parentClassesNames.size() != parentClasses.size()) {
      parentClasses.clear();

      for (var superClassName : parentClassesNames) {
        var superClass = schema.getClass(superClassName);
        superClass.init();
        parentClasses.add(superClass);
      }
    }
  }

  private void addChildClasses() {
    var result = new ArrayList<SchemaClassSnapshot>(childClassesNames.size());

    for (var clsName : childClassesNames) {
      result.add(schema.getClass(clsName));
    }

    childClasses = result;
  }

  @Override
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
  public List<SchemaClassSnapshot> getParentClasses() {
    return parentClasses;
  }

  @Override
  public boolean hasParentClasses() {
    return !parentClasses.isEmpty();
  }

  @Override
  public List<String> getParentClassesNames() {
    return parentClassesNames;
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

    for (var i = 0; i < parentClasses.size() && p == null; i++) {
      p = parentClasses.get(i).getProperty(propertyName);
    }

    return p;
  }

  @Override
  public boolean existsProperty(String propertyName) {
    var result = properties.containsKey(propertyName);
    if (result) {
      return true;
    }
    for (var superClass : parentClasses) {
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
  public Collection<SchemaClassSnapshot> getChildClasses() {
    return childClasses;
  }

  @Override
  public Collection<SchemaClassSnapshot> getDescendantClasses() {
    var set = new HashSet<>(getChildClasses());

    for (var c : childClasses) {
      set.addAll(c.getDescendantClasses());
    }

    return set;
  }

  @Override
  public Collection<ImmutableSchemaClass> getAscendantClasses() {
    var ret = new HashSet<ImmutableSchemaClass>();
    getAllSuperClasses(ret);
    return ret;
  }

  private void getAllSuperClasses(HashSet<ImmutableSchemaClass> set) {
    set.addAll(parentClasses);
    for (var superClass : parentClasses) {
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
  public boolean isChildOf(final String iClassName) {
    if (iClassName == null) {
      return false;
    }

    if (iClassName.equalsIgnoreCase(name)) {
      return true;
    }

    for (var superClass : parentClasses) {
      if (superClass.isChildOf(iClassName)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public boolean isChildOf(final ImmutableSchemaClass clazz) {
    if (clazz == null) {
      return false;
    }
    if (equals(clazz)) {
      return true;
    }

    for (var superClass : parentClasses) {
      if (superClass.isChildOf(clazz)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isParentOf(ImmutableSchemaClass clazz) {
    return clazz != null && clazz.isChildOf(this);
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
      case SUPERCLASSES -> getParentClasses();
      case STRICT_MODE -> strictMode;
      case ABSTRACT -> isAbstract;
      case CUSTOM -> customFields;
      case DESCRIPTION -> description;
    };
  }

  @Override
  public Set<String> getInvolvedIndexesNames(Collection<String> properties) {
    final Set<String> result = new HashSet<>(getClassIndexes(properties));

    for (var superClass : parentClasses) {
      result.addAll(superClass.getInvolvedIndexesNames(properties));
    }
    return result;
  }

  @Override
  public Set<Index> getInvolvedIndexes(Collection<String> properties) {
    final Set<Index> result = new HashSet<>(getClassInvolvedIndexes(properties));
    for (var superClass : parentClasses) {
      result.addAll(superClass.getInvolvedIndexes(properties));
    }

    return result;
  }

  @Override
  public Set<String> getInvolvedIndexesNames(String... properties) {
    return getInvolvedIndexesNames(Arrays.asList(properties));
  }

  @Override
  public Set<Index> getInvolvedIndexes(String... properties) {
    return getInvolvedIndexes(Arrays.asList(properties));
  }

  @Override
  public Set<String> getClassIndexes(Collection<String> properties) {
    return getClassInvolvedIndexes(properties).stream().map(Index::getName)
        .collect(HashSet::new, HashSet::add, HashSet::addAll);
  }

  @Override
  public Set<Index> getClassInvolvedIndexes(Collection<String> properties) {
    final var multiKey = new MultiKey(properties);

    final var rawResult = indexesByProperties.get(multiKey);
    if (rawResult == null) {
      return Collections.emptySet();
    }

    var result = new HashSet<Index>(rawResult.size());
    for (final var index : rawResult) {
      if (properties.size() == index.getDefinition().getProperties().size()
          || !index.getDefinition().isNullValuesIgnored()) {
        result.add(index);
      }
    }

    return result;
  }

  @Override
  public Set<String> getClassIndexes(String... properties) {
    return getClassIndexes(Arrays.asList(properties));
  }

  @Override
  public Set<Index> getClassInvolvedIndexes(String... properties) {

    return getClassInvolvedIndexes(Arrays.asList(properties));
  }

  @Override
  public boolean areIndexed(Collection<String> properties) {
    var multiKey = new MultiKey(properties);

    if (indexesByProperties.containsKey(multiKey)) {
      return true;
    }

    for (var superClass : parentClasses) {
      if (superClass.areIndexed(properties)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean areIndexed(String... properties) {
    return areIndexed(Arrays.asList(properties));
  }

  @Override
  public Set<String> getClassIndexes() {
    return this.indexes.keySet();
  }

  @Override
  public Collection<Index> getClassIndexesInternal() {
    return this.indexes.values();
  }

  @Override
  public Index getClassIndex(DatabaseSessionInternal session, String name) {
    assert session.assertIfNotActive();
    return indexes.get(name);
  }


  @Override
  public Set<String> getIndexNames() {
    var result = new HashSet<>(this.indexes.keySet());

    for (var superClass : parentClasses) {
      result.addAll(superClass.getIndexNames());
    }

    return result;
  }

  @Override
  public Set<Index> getIndexes() {
    var result = new HashSet<>(this.indexes.values());

    for (var superClass : parentClasses) {
      result.addAll(superClass.getIndexes());
    }

    return result;
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
  public Set<String> getCustomPopertiesNames() {
    return customFields.keySet();
  }

  @Override
  public boolean hasCollectionId(int collectionId) {
    return ArrayUtils.contains(collectionIds, collectionId);
  }

  @Override
  public boolean hasPolymorphicCollectionId(final int collectionId) {
    return ArrayUtils.contains(polymorphicCollectionIds, collectionId);
  }


  @Override
  public boolean isEdgeType() {
    return isEdgeType;
  }

  @Override
  public boolean isVertexType() {
    return isVertexType;
  }

  @Override
  public boolean isFunction() {
    return function;
  }

  @Override
  public boolean isScheduler() {
    return scheduler;
  }

  @Override
  public boolean isUser() {
    return user;
  }

  @Override
  public boolean isRole() {
    return role;
  }

  @Override
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
