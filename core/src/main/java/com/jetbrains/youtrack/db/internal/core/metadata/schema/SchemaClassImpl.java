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

import static com.jetbrains.youtrack.db.api.schema.SchemaClass.EDGE_CLASS_NAME;
import static com.jetbrains.youtrack.db.api.schema.SchemaClass.VERTEX_CLASS_NAME;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.ATTRIBUTES;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection.RoundRobinClusterSelectionStrategy;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
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

/**
 * Schema Class implementation.
 */
@SuppressWarnings("unchecked")
public abstract class SchemaClassImpl {

  protected static final int NOT_EXISTENT_CLUSTER_ID = -1;
  private static final Pattern PATTERN = Pattern.compile(",\\s*");
  protected final SchemaShared owner;
  protected final Map<String, SchemaPropertyImpl> properties = new HashMap<>();
  protected int defaultClusterId = NOT_EXISTENT_CLUSTER_ID;
  protected String name;
  protected String description;
  protected int[] clusterIds;
  protected List<SchemaClassImpl> superClasses = new ArrayList<>();
  protected int[] polymorphicClusterIds;
  protected List<SchemaClassImpl> subclasses;
  protected float overSize = 0f;
  protected boolean strictMode = false; // @SINCE v1.0rc8
  protected boolean abstractClass = false; // @SINCE v1.2.0
  protected Map<String, String> customFields;
  protected final ClusterSelectionStrategy clusterSelection = new RoundRobinClusterSelectionStrategy();
  protected volatile int hashCode;

  private static final Set<String> RESERVED = new HashSet<>();

  static {
    // reserved.add("select");
    RESERVED.add("traverse");
    RESERVED.add("insert");
    RESERVED.add("update");
    RESERVED.add("delete");
    RESERVED.add("from");
    RESERVED.add("where");
    RESERVED.add("skip");
    RESERVED.add("limit");
    RESERVED.add("timeout");
  }

  protected SchemaClassImpl(final SchemaShared iOwner, final String iName,
      final int[] iClusterIds) {
    this(iOwner, iName);
    setClusterIds(iClusterIds);
    defaultClusterId = iClusterIds[0];
    if (defaultClusterId == NOT_EXISTENT_CLUSTER_ID) {
      abstractClass = true;
    }

    if (abstractClass) {
      setPolymorphicClusterIds(CommonConst.EMPTY_INT_ARRAY);
    } else {
      setPolymorphicClusterIds(iClusterIds);
    }
  }

  /**
   * Constructor used in unmarshalling.
   */
  protected SchemaClassImpl(final SchemaShared iOwner, final String iName) {
    name = iName;
    owner = iOwner;
  }

  public static int[] readableClusters(
      final DatabaseSessionInternal db, final int[] iClusterIds, String className) {
    var listOfReadableIds = new IntArrayList();

    var all = true;
    for (var clusterId : iClusterIds) {
      try {
        // This will exclude (filter out) any specific classes without explicit read permission.
        if (className != null) {
          db.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_READ, className);
        }

        final var clusterName = db.getClusterNameById(clusterId);
        db.checkSecurity(Rule.ResourceGeneric.CLUSTER, Role.PERMISSION_READ, clusterName);
        listOfReadableIds.add(clusterId);
      } catch (SecurityAccessException ignore) {
        all = false;
        // if the cluster is inaccessible it's simply not processed in the list.add
      }
    }

    // JUST RETURN INPUT ARRAY (FASTER)
    if (all) {
      return iClusterIds;
    }

    final var readableClusterIds = new int[listOfReadableIds.size()];
    var index = 0;
    for (var i = 0; i < listOfReadableIds.size(); i++) {
      readableClusterIds[index++] = listOfReadableIds.getInt(i);
    }

    return readableClusterIds;
  }


  public ClusterSelectionStrategy getClusterSelection(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return clusterSelection;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public String getCustom(DatabaseSessionInternal session, final String iName) {
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
        return Collections.unmodifiableMap(customFields);
      }
      return null;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void removeCustom(DatabaseSessionInternal session, final String name) {
    setCustom(session, name, null);
  }

  public abstract void setCustom(DatabaseSessionInternal session, final String name,
      final String value);

  public Set<String> getCustomKeys(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      if (customFields != null) {
        return Collections.unmodifiableSet(customFields.keySet());
      }
      return new HashSet<>();
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public boolean hasClusterId(DatabaseSessionInternal session, final int clusterId) {
    acquireSchemaReadLock(session);
    try {
      return Arrays.binarySearch(clusterIds, clusterId) >= 0;
    } finally {
      releaseSchemaReadLock(session);
    }

  }

  public boolean hasPolymorphicClusterId(DatabaseSessionInternal session, final int clusterId) {
    acquireSchemaReadLock(session);
    try {
      return Arrays.binarySearch(polymorphicClusterIds, clusterId) >= 0;
    } finally {
      releaseSchemaReadLock(session);
    }

  }

  public String getName(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return name;
    } finally {
      releaseSchemaReadLock(session);
    }
  }


  public List<SchemaClassImpl> getSuperClasses(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return Collections.unmodifiableList(superClasses);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public boolean hasSuperClasses(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return !superClasses.isEmpty();
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public List<String> getSuperClassesNames(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      List<String> superClassesNames = new ArrayList<>(superClasses.size());
      for (var superClass : superClasses) {
        superClassesNames.add(superClass.getName(session));
      }
      return superClassesNames;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void setSuperClassesByNames(DatabaseSessionInternal session, List<String> classNames) {
    if (classNames == null) {
      classNames = Collections.EMPTY_LIST;
    }

    final List<SchemaClassImpl> classes = new ArrayList<>(classNames.size());
    for (var className : classNames) {
      classes.add(owner.getClass(session, decodeClassName(className)));
    }

    setSuperClasses(session, classes);
  }

  public abstract void setSuperClasses(DatabaseSessionInternal session,
      List<SchemaClassImpl> classes);

  protected abstract void setSuperClassesInternal(DatabaseSessionInternal session,
      final List<SchemaClassImpl> classes);

  public long getSize(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      long size = 0;
      for (var clusterId : clusterIds) {
        size += session.getClusterRecordSizeById(clusterId);
      }

      return size;
    } finally {
      releaseSchemaReadLock(session);
    }
  }


  public String getDescription(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return description;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public String getStreamableName(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return name;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Collection<SchemaPropertyImpl> declaredProperties(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return Collections.unmodifiableCollection(properties.values());
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Map<String, SchemaPropertyImpl> propertiesMap(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA,
        Role.PERMISSION_READ);

    acquireSchemaReadLock(session);
    try {
      final Map<String, SchemaPropertyImpl> props = new HashMap<>(20);
      propertiesMap(session, props);
      return props;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  private void propertiesMap(DatabaseSessionInternal db,
      Map<String, SchemaPropertyImpl> propertiesMap) {
    for (var p : properties.values()) {
      var propName = p.getName(db);
      if (!propertiesMap.containsKey(propName)) {
        propertiesMap.put(propName, p);
      }
    }
    for (var superClass : superClasses) {
      superClass.propertiesMap(db, propertiesMap);
    }
  }

  public Collection<SchemaPropertyImpl> properties(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA,
        Role.PERMISSION_READ);

    acquireSchemaReadLock(session);
    try {
      final Collection<SchemaPropertyImpl> props = new ArrayList<>();
      properties(props);
      return props;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  private void properties(Collection<SchemaPropertyImpl> properties) {
    properties.addAll(this.properties.values());
    for (var superClass : superClasses) {
      superClass.properties(properties);
    }
  }

  public void getIndexedProperties(DatabaseSessionInternal session,
      Collection<SchemaPropertyImpl> indexedProperties) {
    for (var p : properties.values()) {
      if (areIndexed(session, p.getName(session))) {
        indexedProperties.add(p);
      }
    }
    for (var superClass : superClasses) {
      superClass.getIndexedProperties(session, indexedProperties);
    }
  }

  public Collection<SchemaPropertyImpl> getIndexedProperties(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA,
        Role.PERMISSION_READ);

    acquireSchemaReadLock(session);
    try {
      Collection<SchemaPropertyImpl> indexedProps = new HashSet<>();
      getIndexedProperties(session, indexedProps);
      return indexedProps;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public SchemaPropertyImpl getProperty(DatabaseSessionInternal session, String propertyName) {
    return getPropertyInternal(session, propertyName);
  }

  public SchemaPropertyImpl getPropertyInternal(DatabaseSessionInternal session,
      String propertyName) {
    acquireSchemaReadLock(session);
    try {
      var p = properties.get(propertyName);

      if (p != null) {
        return p;
      }

      for (var i = 0; i < superClasses.size() && p == null; i++) {
        p = superClasses.get(i).getPropertyInternal(session, propertyName);
      }

      return p;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public SchemaPropertyImpl createProperty(DatabaseSessionInternal session,
      final String iPropertyName,
      final PropertyTypeInternal iType) {
    return addProperty(session, iPropertyName, iType, null, null,
        false);
  }


  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyTypeInternal iType,
      final SchemaClassImpl iLinkedClass) {
    return addProperty(session, iPropertyName, iType, null,
        iLinkedClass,
        false);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyTypeInternal iType,
      final SchemaClassImpl iLinkedClass,
      final boolean unsafe) {
    return addProperty(session, iPropertyName, iType, null,
        iLinkedClass,
        unsafe);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType) {
    return addProperty(session, iPropertyName, iType, iLinkedType, null,
        false);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType,
      final boolean unsafe) {
    return addProperty(session, iPropertyName, iType, iLinkedType, null,
        unsafe);
  }

  public SchemaPropertyImpl createProperty(DatabaseSessionInternal session,
      final String iPropertyName,
      final PropertyType iType) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType));
  }


  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyType iType,
      final SchemaClassImpl iLinkedClass) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType), iLinkedClass);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyType iType,
      final SchemaClassImpl iLinkedClass,
      final boolean unsafe) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType), iLinkedClass,
        unsafe);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName, final PropertyType iType,
      final PropertyType iLinkedType) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType),
        PropertyTypeInternal.convertFromPublicType(iLinkedType));
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionInternal session, final String iPropertyName,
      final PropertyType iType,
      final PropertyType iLinkedType,
      final boolean unsafe) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType),
        PropertyTypeInternal.convertFromPublicType(iLinkedType), unsafe);
  }


  public boolean existsProperty(DatabaseSessionInternal session, String propertyName) {
    acquireSchemaReadLock(session);
    try {
      var result = properties.containsKey(propertyName);
      if (result) {
        return true;
      }
      for (var superClass : superClasses) {
        result = superClass.existsProperty(session, propertyName);
        if (result) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public abstract void addSuperClass(DatabaseSessionInternal session, SchemaClassImpl superClass);

  public abstract void removeSuperClass(DatabaseSessionInternal session,
      SchemaClassImpl superClass);

  public void fromStream(DatabaseSessionInternal session, EntityImpl entity) {
    subclasses = null;
    superClasses.clear();

    name = entity.getProperty("name");
    if (entity.hasProperty("description")) {
      description = entity.getProperty("description");
    } else {
      description = null;
    }
    defaultClusterId = entity.getProperty("defaultClusterId");
    if (entity.hasProperty("strictMode")) {
      strictMode = entity.getProperty("strictMode");
    } else {
      strictMode = false;
    }

    if (entity.hasProperty("abstract")) {
      abstractClass = entity.getProperty("abstract");
    } else {
      abstractClass = false;
    }

    if (entity.getProperty("overSize") != null) {
      overSize = entity.getProperty("overSize");
    } else {
      overSize = 0f;
    }

    final var cc = entity.getProperty("clusterIds");
    if (cc instanceof Collection<?>) {
      final Collection<Integer> coll = entity.getProperty("clusterIds");
      clusterIds = new int[coll.size()];
      var i = 0;
      for (final var item : coll) {
        clusterIds[i++] = item;
      }
    } else {
      clusterIds = (int[]) cc;
    }
    Arrays.sort(clusterIds);

    if (clusterIds.length == 1 && clusterIds[0] == -1) {
      setPolymorphicClusterIds(CommonConst.EMPTY_INT_ARRAY);
    } else {
      setPolymorphicClusterIds(clusterIds);
    }

    // READ PROPERTIES
    SchemaPropertyImpl prop;

    final Map<String, SchemaPropertyImpl> newProperties = new HashMap<>();
    final Collection<EntityImpl> storedProperties = entity.getProperty("properties");

    if (storedProperties != null) {
      for (var p : storedProperties) {
        String name = p.getProperty("name");
        // To lower case ?
        if (properties.containsKey(name)) {
          prop = properties.get(name);
          prop.fromStream(session, p);
        } else {
          prop = createPropertyInstance();
          prop.fromStream(session, p);
        }

        newProperties.put(prop.getName(session), prop);
      }
    }

    properties.clear();
    properties.putAll(newProperties);
    customFields = entity.getProperty("customFields");
  }

  protected abstract SchemaPropertyImpl createPropertyInstance();

  public Entity toStream(DatabaseSessionInternal session) {
    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", name);
    entity.setProperty("description", description);
    entity.setProperty("defaultClusterId", defaultClusterId);
    entity.newEmbeddedList("clusterIds", clusterIds);
    entity.setProperty("overSize", overSize);
    entity.setProperty("strictMode", strictMode);
    entity.setProperty("abstract", abstractClass);

    var props = session.newEmbeddedSet(properties.size());
    for (final var p : properties.values()) {
      props.add(p.toStream(session));
    }
    entity.setProperty("properties", props);

    if (superClasses.isEmpty()) {
      // Single super class is deprecated!
      entity.setProperty("superClass", null, PropertyType.STRING);
      entity.setProperty("superClasses", null, PropertyType.EMBEDDEDLIST);
    } else {
      // Single super class is deprecated!
      entity.setProperty("superClass", superClasses.getFirst().getName(session),
          PropertyType.STRING);
      List<String> superClassesNames = session.newEmbeddedList(superClasses.size());
      for (var superClass : superClasses) {
        superClassesNames.add(superClass.getName(session));
      }
      entity.setProperty("superClasses", superClassesNames, PropertyType.EMBEDDEDLIST);
    }

    entity.setProperty(
        "customFields",
        customFields != null && !customFields.isEmpty() ? session.newEmbeddedMap(customFields)
            : null,
        PropertyType.EMBEDDEDMAP);

    return entity;
  }


  public int[] getClusterIds(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return clusterIds;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public int[] getPolymorphicClusterIds(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return Arrays.copyOf(polymorphicClusterIds, polymorphicClusterIds.length);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  private void setPolymorphicClusterIds(final int[] iClusterIds) {
    var set = new IntRBTreeSet(iClusterIds);
    polymorphicClusterIds = set.toIntArray();
  }

  public void renameProperty(final String iOldName, final String iNewName) {
    var p = properties.remove(iOldName);
    if (p != null) {
      properties.put(iNewName, p);
    }
  }

  protected static void truncateClusterInternal(
      final String clusterName, final DatabaseSessionInternal database) {
    database.truncateCluster(clusterName);
  }

  public Collection<SchemaClassImpl> getSubclasses(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      if (subclasses == null || subclasses.isEmpty()) {
        return Collections.emptyList();
      }

      return Collections.unmodifiableCollection(subclasses);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Collection<SchemaClassImpl> getAllSubclasses(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      final Set<SchemaClassImpl> set = new HashSet<>();
      if (subclasses != null) {
        set.addAll(subclasses);

        for (var c : subclasses) {
          set.addAll(c.getAllSubclasses(session));
        }
      }
      return set;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  @Deprecated
  public Collection<SchemaClassImpl> getBaseClasses(DatabaseSessionInternal db) {
    return getSubclasses(db);
  }

  @Deprecated
  public Collection<SchemaClassImpl> getAllBaseClasses(DatabaseSessionInternal db) {
    return getAllSubclasses(db);
  }

  private void getAllSuperClasses(Set<SchemaClassImpl> set) {
    set.addAll(superClasses);
    for (var superClass : superClasses) {
      superClass.getAllSuperClasses(set);
    }
  }

  public abstract void removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClassImpl baseClass);

  public boolean isAbstract(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return abstractClass;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public boolean isStrictMode(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return strictMode;
    } finally {
      releaseSchemaReadLock(session);
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
    var other = (SchemaClassImpl) obj;

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
    acquireSchemaReadLock(session);
    try {
      return session.countClass(getName(session), isPolymorphic);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  /**
   * Truncates all the clusters the class uses.
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
  public boolean isSubClassOf(DatabaseSessionInternal session, final String iClassName) {
    acquireSchemaReadLock(session);
    try {
      if (iClassName == null) {
        return false;
      }

      if (iClassName.equalsIgnoreCase(getName(session))) {
        return true;
      }
      for (var superClass : superClasses) {
        if (superClass.isSubClassOf(session, iClassName)) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  /**
   * Check if the current instance extends specified schema class.
   *
   * @param clazz to check
   * @return true if the current instance extends the passed schema class (iClass)
   */
  public boolean isSubClassOf(DatabaseSessionInternal session, final SchemaClassImpl clazz) {
    acquireSchemaReadLock(session);
    try {
      if (clazz == null) {
        return false;
      }
      if (equals(clazz)) {
        return true;
      }
      for (var superClass : superClasses) {
        if (superClass.isSubClassOf(session, clazz)) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  /**
   * Returns true if the passed schema class (iClass) extends the current instance.
   *
   * @param clazz to check
   * @return Returns true if the passed schema class extends the current instance
   */
  public boolean isSuperClassOf(DatabaseSessionInternal session, final SchemaClassImpl clazz) {
    return clazz != null && clazz.isSubClassOf(session, this);
  }

  public Object get(DatabaseSessionInternal db, final ATTRIBUTES iAttribute) {
    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    return switch (iAttribute) {
      case NAME -> getName(db);
      case SUPERCLASSES -> getSuperClasses(db);
      case STRICT_MODE -> isStrictMode(db);
      case ABSTRACT -> isAbstract(db);
      case CUSTOM -> getCustomInternal(db);
      case DESCRIPTION -> getDescription(db);
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

  public abstract void clearCustom(DatabaseSessionInternal session);

  public abstract void setDescription(DatabaseSessionInternal session,
      String iDescription);

  public abstract void setName(DatabaseSessionInternal session, String iName);

  public abstract void setStrictMode(DatabaseSessionInternal session, boolean iMode);

  public abstract void setAbstract(DatabaseSessionInternal session, boolean iAbstract);


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

  public void createIndex(DatabaseSessionInternal session, final String iName,
      final INDEX_TYPE iType,
      final String... fields) {
    createIndex(session, iName, iType.name(), fields);
  }

  public void createIndex(DatabaseSessionInternal session, final String iName, final String iType,
      final String... fields) {
    createIndex(session, iName, iType, null, null, fields);
  }

  public void createIndex(
      DatabaseSessionInternal session, final String iName,
      final INDEX_TYPE iType,
      final ProgressListener iProgressListener,
      final String... fields) {
    createIndex(session, iName, iType.name(), iProgressListener, null, fields);
  }

  public void createIndex(
      DatabaseSessionInternal session, String iName,
      String iType,
      ProgressListener iProgressListener,
      Map<String, Object> metadata,
      String... fields) {
    createIndex(session, iName, iType, iProgressListener, metadata, null, fields);
  }

  public void createIndex(
      DatabaseSessionInternal session, final String name,
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

      if (!fieldName.equals("@rid") && !existsProperty(session, fieldName)) {
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

    final var localPolymorphicClusterIds = polymorphicClusterIds;
    session
        .getMetadata()
        .getIndexManagerInternal()
        .createIndex(
            session,
            name,
            type,
            indexDefinition,
            localPolymorphicClusterIds,
            progressListener,
            metadata,
            algorithm);
  }

  public boolean areIndexed(DatabaseSessionInternal session, final String... fields) {
    return areIndexed(session, Arrays.asList(fields));
  }

  public boolean areIndexed(DatabaseSessionInternal session, final Collection<String> fields) {
    final var indexManager =
        session.getMetadata().getIndexManagerInternal();

    acquireSchemaReadLock(session);
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
      releaseSchemaReadLock(session);
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
    acquireSchemaReadLock(session);
    try {
      final Set<Index> result = new HashSet<>(getClassInvolvedIndexesInternal(session, fields));

      for (var superClass : superClasses) {
        result.addAll(superClass.getInvolvedIndexesInternal(session, fields));
      }

      return result;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session,
      final Collection<String> fields) {
    return getClassInvolvedIndexesInternal(session, fields).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      Collection<String> fields) {
    final var indexManager = session.getMetadata().getIndexManagerInternal();

    acquireSchemaReadLock(session);
    try {
      return indexManager.getClassInvolvedIndexes(session, name, fields);
    } finally {
      releaseSchemaReadLock(session);
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
    acquireSchemaReadLock(session);
    try {
      return session
          .getMetadata()
          .getIndexManagerInternal()
          .getClassIndex(session, this.name, name);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Set<String> getClassIndexes(DatabaseSessionInternal session) {
    return getClassInvolvedIndexesInternal(session).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getClassIndexesInternal(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      final var idxManager = session.getMetadata().getIndexManagerInternal();
      if (idxManager == null) {
        return new HashSet<>();
      }

      return idxManager.getClassIndexes(session, name);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void getClassIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    acquireSchemaReadLock(session);
    try {
      final var idxManager = session.getMetadata().getIndexManagerInternal();
      if (idxManager == null) {
        return;
      }
      idxManager.getClassIndexes(session, name, indexes);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public boolean isEdgeType(DatabaseSessionInternal session) {
    return isSubClassOf(session, EDGE_CLASS_NAME);
  }

  public boolean isVertexType(DatabaseSessionInternal session) {
    return isSubClassOf(session, VERTEX_CLASS_NAME);
  }


  public void getIndexesInternal(DatabaseSessionInternal session, final Collection<Index> indexes) {
    acquireSchemaReadLock(session);
    try {
      getClassIndexes(session, indexes);
      for (var superClass : superClasses) {
        superClass.getIndexesInternal(session, indexes);
      }
    } finally {
      releaseSchemaReadLock(session);
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
    releaseSchemaWriteLock(session, true);
  }

  public void releaseSchemaWriteLock(DatabaseSessionInternal session, final boolean iSave) {
    calculateHashCode();
    owner.releaseSchemaWriteLock(session, iSave);
  }

  public void checkEmbedded(DatabaseSessionInternal session) {
    owner.checkEmbedded(session);
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
      SchemaClassImpl linkedClass) {
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
      SchemaClassImpl linkedClass) {
    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(name, true));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName, true)).append(" is not null ");
    if (type.isMultiValue()) {
      builder.append(" and ").append(getEscapedName(propertyName, true)).append(".size() > 0");
    }

    try (final var res = db.query(builder.toString())) {
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
                              + linkedClass.getName(db)
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
                      + linkedClass.getName(db)
                      + ". Fix those records and change the schema again!");
            }
            break;
        }
      }
    }
  }

  protected static boolean matchesType(DatabaseSessionInternal db, Object x,
      SchemaClassImpl linkedClass) {
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
        || linkedClass.getName(db).equalsIgnoreCase(((EntityImpl) x).getSchemaClassName());
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

  protected void renameCluster(DatabaseSessionInternal session, String oldName, String newName) {
    oldName = oldName.toLowerCase(Locale.ENGLISH);
    newName = newName.toLowerCase(Locale.ENGLISH);

    if (session.getClusterIdByName(newName) != -1) {
      return;
    }

    final var clusterId = session.getClusterIdByName(oldName);
    if (clusterId == -1) {
      return;
    }

    if (!hasClusterId(session, clusterId)) {
      return;
    }

    session.execute("alter cluster `" + oldName + "` NAME \"" + newName + "\"").close();
  }

  protected void onlyAddPolymorphicClusterId(int clusterId) {
    if (Arrays.binarySearch(polymorphicClusterIds, clusterId) >= 0) {
      return;
    }

    polymorphicClusterIds = ArrayUtils.copyOf(polymorphicClusterIds,
        polymorphicClusterIds.length + 1);
    polymorphicClusterIds[polymorphicClusterIds.length - 1] = clusterId;
    Arrays.sort(polymorphicClusterIds);

    for (var superClass : superClasses) {
      superClass.onlyAddPolymorphicClusterId(clusterId);
    }
  }

  protected abstract SchemaPropertyImpl addProperty(
      DatabaseSessionInternal session, final String propertyName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassImpl linkedClass,
      final boolean unsafe);

  public abstract void dropProperty(DatabaseSessionInternal session, String iPropertyName);

  public Collection<SchemaClassImpl> getAllSuperClasses(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      Set<SchemaClassImpl> ret = new HashSet<>();
      getAllSuperClasses(ret);
      return ret;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  protected void validatePropertyName(final String propertyName) {
  }

  protected abstract void addClusterIdToIndexes(DatabaseSessionInternal session, int iId);

  /**
   * Adds a base class to the current one. It adds also the base class cluster ids to the
   * polymorphic cluster ids array.
   *
   * @param iBaseClass The base class to add.
   */
  public void addBaseClass(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
    checkRecursion(session, iBaseClass);

    if (subclasses == null) {
      subclasses = new ArrayList<>();
    }

    if (subclasses.contains(iBaseClass)) {
      return;
    }

    subclasses.add(iBaseClass);
    addPolymorphicClusterIdsWithInheritance(session, iBaseClass);
  }

  protected void checkParametersConflict(DatabaseSessionInternal session,
      final SchemaClassImpl baseClass) {
    final var baseClassProperties = baseClass.properties(session);
    for (var property : baseClassProperties) {
      var thisProperty = getProperty(session, property.getName(session));
      if (thisProperty != null && !thisProperty.getType(session)
          .equals(property.getType(session))) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot add base class '"
                + baseClass.getName(session)
                + "', because of property conflict: '"
                + thisProperty
                + "' vs '"
                + property
                + "'");
      }
    }
  }

  public static void checkParametersConflict(DatabaseSessionInternal db,
      List<SchemaClassImpl> classes) {
    final Map<String, SchemaPropertyImpl> comulative = new HashMap<>();
    final Map<String, SchemaPropertyImpl> properties = new HashMap<>();

    for (var superClass : classes) {
      if (superClass == null) {
        continue;
      }
      SchemaClassImpl impl;
      impl = superClass;
      impl.propertiesMap(db, properties);
      for (var entry : properties.entrySet()) {
        if (comulative.containsKey(entry.getKey())) {
          final var property = entry.getKey();
          final var existingProperty = comulative.get(property);
          if (!existingProperty.getType(db).equals(entry.getValue().getType(db))) {
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

  private void checkRecursion(DatabaseSessionInternal session, final SchemaClassImpl baseClass) {
    if (isSubClassOf(session, baseClass)) {
      throw new SchemaException(session.getDatabaseName(),
          "Cannot add base class '" + baseClass.getName(session) + "', because of recursion");
    }
  }

  protected void removePolymorphicClusterIds(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
    for (final var clusterId : iBaseClass.polymorphicClusterIds) {
      removePolymorphicClusterId(session, clusterId);
    }
  }

  protected void removePolymorphicClusterId(DatabaseSessionInternal session,
      final int clusterId) {
    final var index = Arrays.binarySearch(polymorphicClusterIds, clusterId);
    if (index < 0) {
      return;
    }

    if (index < polymorphicClusterIds.length - 1) {
      System.arraycopy(
          polymorphicClusterIds,
          index + 1,
          polymorphicClusterIds,
          index,
          polymorphicClusterIds.length - (index + 1));
    }

    polymorphicClusterIds = Arrays.copyOf(polymorphicClusterIds, polymorphicClusterIds.length - 1);

    removeClusterFromIndexes(session, clusterId);
    for (var superClass : superClasses) {
      superClass.removePolymorphicClusterId(session, clusterId);
    }
  }

  private void removeClusterFromIndexes(DatabaseSessionInternal session, final int iId) {
    if (session.getStorage() instanceof AbstractPaginatedStorage) {
      final var clusterName = session.getClusterNameById(iId);
      final List<String> indexesToRemove = new ArrayList<>();

      final Set<Index> indexes = new HashSet<>();
      getIndexesInternal(session, indexes);

      for (final var index : indexes) {
        indexesToRemove.add(index.getName());
      }

      final var indexManager =
          session.getMetadata().getIndexManagerInternal();
      for (final var indexName : indexesToRemove) {
        indexManager.removeClusterFromIndex(session, clusterName, indexName);
      }
    }
  }

  /**
   * Add different cluster id to the "polymorphic cluster ids" array.
   */
  protected void addPolymorphicClusterIds(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
    var clusters = new IntRBTreeSet(polymorphicClusterIds);

    for (var clusterId : iBaseClass.polymorphicClusterIds) {
      if (clusters.add(clusterId)) {
        try {
          addClusterIdToIndexes(session, clusterId);
        } catch (RuntimeException e) {
          LogManager.instance()
              .warn(
                  this,
                  "Error adding clusterId '%d' to index of class '%s'",
                  e,
                  clusterId,
                  getName(session));
          clusters.remove(clusterId);
        }
      }
    }

    polymorphicClusterIds = clusters.toIntArray();
  }

  private void addPolymorphicClusterIdsWithInheritance(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
    addPolymorphicClusterIds(session, iBaseClass);
    for (var superClass : superClasses) {
      superClass.addPolymorphicClusterIdsWithInheritance(session, iBaseClass);
    }
  }

  protected void setClusterIds(final int[] iClusterIds) {
    clusterIds = iClusterIds;
    Arrays.sort(clusterIds);
  }

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
}
