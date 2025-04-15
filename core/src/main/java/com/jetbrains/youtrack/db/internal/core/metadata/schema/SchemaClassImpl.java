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
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerEmbedded;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.collectionselection.RoundRobinCollectionSelectionStrategy;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.StorageCollection;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Schema Class implementation.
 */
@SuppressWarnings("unchecked")
public abstract class SchemaClassImpl {

  protected static final int NOT_EXISTENT_COLLECTION_ID = -1;
  private static final Pattern PATTERN = Pattern.compile(",\\s*");
  protected final SchemaShared owner;
  protected final Map<String, SchemaPropertyImpl> properties = new HashMap<>();
  protected int defaultCollectionId = NOT_EXISTENT_COLLECTION_ID;
  protected String name;
  protected String description;
  protected int[] collectionIds;
  protected List<SchemaClassImpl> superClasses = new ArrayList<>();
  protected int[] polymorphicCollectionIds;
  protected List<SchemaClassImpl> subclasses;
  protected float overSize = 0f;
  protected boolean strictMode = false; // @SINCE v1.0rc8
  protected boolean abstractClass = false; // @SINCE v1.2.0
  protected Map<String, String> customFields;
  protected final CollectionSelectionStrategy collectionSelection = new RoundRobinCollectionSelectionStrategy();
  protected volatile int hashCode;

  protected SchemaClassImpl(final SchemaShared iOwner, final String iName,
      final int[] iCollectionIds) {
    this(iOwner, iName);
    setCollectionIds(iCollectionIds);
    defaultCollectionId = iCollectionIds[0];
    if (defaultCollectionId == NOT_EXISTENT_COLLECTION_ID) {
      abstractClass = true;
    }

    if (abstractClass) {
      setPolymorphicCollectionIds(CommonConst.EMPTY_INT_ARRAY);
    } else {
      setPolymorphicCollectionIds(iCollectionIds);
    }
  }

  /**
   * Constructor used in unmarshalling.
   */
  protected SchemaClassImpl(final SchemaShared iOwner, final String iName) {
    name = iName;
    owner = iOwner;
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


  public CollectionSelectionStrategy getCollectionSelection(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return collectionSelection;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  @Nullable
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

  @Nullable
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

  public boolean hasCollectionId(DatabaseSessionInternal session, final int collectionId) {
    acquireSchemaReadLock(session);
    try {
      return Arrays.binarySearch(collectionIds, collectionId) >= 0;
    } finally {
      releaseSchemaReadLock(session);
    }

  }

  public boolean hasPolymorphicCollectionId(DatabaseSessionInternal session,
      final int collectionId) {
    acquireSchemaReadLock(session);
    try {
      return Arrays.binarySearch(polymorphicCollectionIds, collectionId) >= 0;
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
      List<SchemaClass> result = new ArrayList<>(superClasses.size());
      for (LazySchemaClass superClass : superClasses.values()) {
        superClass.loadIfNeeded((DatabaseSessionInternal) db);
        result.add(superClass.getDelegate());
      }
      return result;
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
  protected void setLazySuperClassesInternal(DatabaseSessionInternal session,
      List<LazySchemaClass> lazyClasses) {
    Map<String, LazySchemaClass> newSuperClasses = new HashMap<>();
    for (LazySchemaClass superClass : lazyClasses) {
      String className = superClass.getName(session);
      if (newSuperClasses.containsKey(className)) {
        throw new SchemaException("Duplicated superclass '" + className + "'");
      }
      newSuperClasses.put(className, superClass);
    }

    Map<String, LazySchemaClass> classesToAdd = new HashMap<>();
    for (Map.Entry<String, LazySchemaClass> potentialSuperClass : newSuperClasses.entrySet()) {
      if (!superClasses.containsKey(potentialSuperClass.getKey())) {
        classesToAdd.put(potentialSuperClass.getKey(), potentialSuperClass.getValue());
      }
    }

    Map<String, LazySchemaClass> classesToRemove = new HashMap<>();
    for (Map.Entry<String, LazySchemaClass> potentialSuperClass : superClasses.entrySet()) {
      if (!newSuperClasses.containsKey(potentialSuperClass.getKey())) {
        classesToRemove.put(potentialSuperClass.getKey(), potentialSuperClass.getValue());
      }
    }

    for (LazySchemaClass toRemove : classesToRemove.values()) {
      toRemove.loadWithoutInheritanceIfNeeded(session);
      ((SchemaClassImpl) toRemove.getDelegate()).removeBaseClassInternal(session, this);
    }
    for (LazySchemaClass addTo : classesToAdd.values()) {
      addTo.loadWithoutInheritanceIfNeeded(session);
      ((SchemaClassImpl) addTo.getDelegate()).addBaseClass(session, this);
    }
    superClasses.clear();
    superClasses.putAll(newSuperClasses);
  }

  protected void setSuperClassesInternal(DatabaseSessionInternal session,
      final List<? extends SchemaClass> classes) {
    // todo this is a bad temporary decision, we already have all classes, converting them to lazy classes to load again smells.
    // I think it's possible to completely move to lazy classes and remove this method
    List<LazySchemaClass> lazyClasses = new ArrayList<>(classes.size());
    for (SchemaClass superClass : classes) {
      lazyClasses.add(owner.getLazyClass(superClass.getName()));
    }
    setLazySuperClassesInternal(session, lazyClasses);
  }

  public long getSize(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      long size = 0;
      for (var collectionId : collectionIds) {
        size += session.getCollectionRecordSizeById(collectionId);
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
      final Map<String, SchemaProperty> props = new HashMap<String, SchemaProperty>(20);
      propertiesMap((DatabaseSessionInternal) session, props);
      return props;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  private void propertiesMap(DatabaseSessionInternal db,
      Map<String, SchemaProperty> propertiesMap) {
    for (SchemaProperty p : properties.values()) {
      String propName = p.getName();
      if (!propertiesMap.containsKey(propName)) {
        propertiesMap.put(propName, p);
      }
    }
    for (LazySchemaClass superClass : superClasses.values()) {
      superClass.loadIfNeeded(db);
      ((SchemaClassImpl) superClass.getDelegate()).propertiesMap(db, propertiesMap);
    }
  }

  public Collection<SchemaPropertyImpl> properties(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA,
        Role.PERMISSION_READ);

    acquireSchemaReadLock(session);
    try {
      return new ArrayList<>(propertiesInternal((DatabaseSessionInternal) session));
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  private List<SchemaProperty> propertiesInternal(DatabaseSessionInternal session) {
    List<SchemaProperty> resultProperties = new ArrayList<>(
        this.properties.values()
    );
    for (LazySchemaClass superClass : superClasses.values()) {
      superClass.loadIfNeeded(session);
      resultProperties.addAll((superClass.getDelegate()).properties(session));
    }
    return resultProperties;
  }

  public void getIndexedProperties(DatabaseSessionInternal session,
      Collection<SchemaPropertyImpl> indexedProperties) {
    for (var p : properties.values()) {
      if (areIndexed(session, p.getName(session))) {
        indexedProperties.add(p);
      }
    }
    for (LazySchemaClass superClass : superClasses.values()) {
      superClass.loadIfNeeded(session);
      ((SchemaClassImpl) superClass.getDelegate()).getIndexedProperties(session, indexedProperties);
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

  @Override
  public SchemaProperty getProperty(DatabaseSession session, String propertyName) {
    return getPropertyInternal(session, propertyName);
  }

  @Override
  public SchemaPropertyInternal getPropertyInternal(DatabaseSession session,
      String propertyName) {
    acquireSchemaReadLock();
    try {
      var p = properties.get(propertyName);

      if (p != null) {
        return p;
      }

      for (LazySchemaClass schemaClass : superClasses.values()) {
        schemaClass.loadIfNeeded((DatabaseSessionInternal) session);
        p = schemaClass.getDelegate().getPropertyInternal(session, propertyName);
        if (p != null) {
          return p;
        }
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

  @Override
  public boolean existsProperty(DatabaseSession session, String propertyName) {
    acquireSchemaReadLock();

  public boolean existsProperty(DatabaseSessionInternal session, String propertyName) {
    acquireSchemaReadLock(session);
    try {
      var result = properties.containsKey(propertyName);
      if (result) {
        return true;
      }
      for (LazySchemaClass superClass : superClasses.values()) {
        superClass.loadIfNeeded((DatabaseSessionInternal) session);
        result = superClass.getDelegate().existsProperty(session, propertyName);
        if (result) {
          return true;
        }
      }
      return false;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void fromStream(DatabaseSessionInternal db, EntityImpl entity) {
    fromStream(db, entity, true);
  }

  public void fromStream(DatabaseSessionInternal db, EntityImpl entity,
      boolean loadInheritanceTree) {
    acquireSchemaWriteLock(db);
    try {
      identity = entity.getIdentity();
      subclasses.clear();
      superClasses.clear();

      name = entity.field("name");
      if (entity.containsField("shortName")) {
        shortName = entity.field("shortName");
      } else {
        shortName = null;
      }
      if (entity.containsField("description")) {
        description = entity.field("description");
      } else {
        description = null;
      }
      defaultClusterId = entity.field("defaultClusterId");
      if (entity.containsField("strictMode")) {
        strictMode = entity.field("strictMode");
      } else {
        strictMode = false;
      }

      if (entity.containsField("abstract")) {
        abstractClass = entity.field("abstract");
      } else {
        abstractClass = false;
      }

      if (entity.field("overSize") != null) {
        overSize = entity.field("overSize");
      } else {
        overSize = 0f;
      }

      final Object cc = entity.field("clusterIds");
      if (cc instanceof Collection<?>) {
        final Collection<Integer> coll = entity.field("clusterIds");
        clusterIds = new int[coll.size()];
        int i = 0;
        for (final Integer item : coll) {
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

      final Map<String, SchemaPropertyInternal> newProperties = new HashMap<>();
      final Collection<EntityImpl> storedProperties = entity.field("properties");

      if (storedProperties != null) {
        for (Identifiable id : storedProperties) {
          EntityImpl p = id.getRecord();
          String name = p.field("name");
          // To lower case ?
          if (properties.containsKey(name)) {
            prop = (SchemaPropertyImpl) properties.get(name);
            prop.fromStream(db, p);
          } else {
            prop = createPropertyInstance();
            prop.fromStream(db, p);
          }

          newProperties.put(prop.getName(), prop);
        }
      }

      properties.clear();
      properties.putAll(newProperties);
      Map<String, String> persistedCustomFields = entity.field("customFields",
          PropertyType.EMBEDDEDMAP);

      if (persistedCustomFields != null) {
        // customFields should be wrapped to hashmap to avoid issue with modification out of the bound of transaction
        customFields = new HashMap<>(persistedCustomFields);
      }
      clusterSelection =
          owner.getClusterSelectionFactory().getStrategy(entity.field("clusterSelection"));

      if (loadInheritanceTree) {
        // we don't load tree for some classes to prevent infinite recursion
        // from these classes we only need their names and cluster ids to be loaded
        loadInheritanceTree(db, entity);
      }
    } finally {
      releaseSchemaWriteLock(db);
    }
  }

  public void loadInheritanceTree(DatabaseSessionInternal db, EntityImpl entity) {
    Collection<String> superClassNames = entity.field("superClasses");
    String legacySuperClassName = entity.field("superClass");
    if (superClassNames == null) {
      superClassNames = new ArrayList<>();
    }

    if (legacySuperClassName != null && !superClassNames.contains(legacySuperClassName)) {
      superClassNames.add(legacySuperClassName);
    }

    if (!superClassNames.isEmpty()) {
      // HAS A SUPER CLASS or CLASSES
      List<LazySchemaClass> superClassesToInit = new ArrayList<>(superClassNames.size());
      for (String superClassName : superClassNames) {

        LazySchemaClass lazySuperClass = owner.getLazyClass(
            SchemaShared.normalizeClassName(superClassName));

        if (lazySuperClass == null) {
          throw new ConfigurationException(
              "Super class '"
                  + superClassName
                  + "' was declared in class '"
                  + this.getName()
                  + "' but was not found in schema. Remove the dependency or create the class"
                  + " to continue.");
        }
        superClassesToInit.add(lazySuperClass);
      }
      setLazySuperClassesInternal(db, superClassesToInit);
    }
    Collection<String> storedSubclassesNames = entity.field("subClasses");
    if (storedSubclassesNames != null && !storedSubclassesNames.isEmpty()) {
      subclasses.clear();
      for (String storedSubclassName : storedSubclassesNames) {
        LazySchemaClass subclass = owner.getLazyClass(
            SchemaShared.normalizeClassName(storedSubclassName));
        subclasses.put(storedSubclassName, subclass);
        subclass.loadWithoutInheritanceIfNeeded(db);
        addPolymorphicClusterIdsWithInheritance(db, (SchemaClassImpl) subclass.getDelegate());
      }
    }
  }

  protected abstract SchemaPropertyImpl createPropertyInstance();

  public EntityImpl toStream(DatabaseSessionInternal db) {
    acquireSchemaWriteLock(db);
    try {
      final EntityImpl entity;
      // null identity means entity is new
      if (identity != null && identity.isValid()) {
        entity = db.load(identity);
      } else {
        entity = new EntityImpl(db);
        // I don't like the solution, there should be a better way to make only one copy of identity present in the system
        identity = entity.getIdentity();
      }
      entity.field("name", name);
      entity.field("shortName", shortName);
      entity.field("description", description);
      entity.field("defaultClusterId", defaultClusterId);
      entity.field("clusterIds", clusterIds);
      entity.field("clusterSelection", clusterSelection.getName());
      entity.field("overSize", overSize);
      entity.field("strictMode", strictMode);
      entity.field("abstract", abstractClass);

      final Set<EntityImpl> props = new LinkedHashSet<EntityImpl>();
      for (final SchemaProperty p : properties.values()) {
        props.add(((SchemaPropertyImpl) p).toStream());
      }
      entity.field("properties", props, PropertyType.EMBEDDEDSET);

      if (superClasses.isEmpty()) {
        // Single super class is deprecated!
        entity.field("superClass", null, PropertyType.STRING);
        entity.field("superClasses", null, PropertyType.EMBEDDEDLIST);
      } else {
        // Single super class is deprecated!
        entity.field("superClass", superClasses.firstEntry().getKey(), PropertyType.STRING);
        List<String> superClassesNames = new ArrayList<>(superClasses.keySet());
        entity.field("superClasses", superClassesNames, PropertyType.EMBEDDEDLIST);
      }

      if (!subclasses.isEmpty()) {
        List<String> subClassesNames = new ArrayList<>(subclasses.keySet());
        entity.field("subClasses", subClassesNames, PropertyType.EMBEDDEDSET);
      } else {
        entity.field("subClasses", null, PropertyType.EMBEDDEDSET);
      }

      entity.field(
          "customFields",
          customFields != null && customFields.size() > 0 ? customFields : null,
          PropertyType.EMBEDDEDMAP);

      return entity;
    } finally {
      releaseSchemaWriteLock(db);
    }
  }


  public int[] getCollectionIds(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return collectionIds;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public int[] getPolymorphicCollectionIds(DatabaseSessionInternal session) {
    acquireSchemaReadLock(session);
    try {
      return Arrays.copyOf(polymorphicCollectionIds, polymorphicCollectionIds.length);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  private void setPolymorphicCollectionIds(final int[] iCollectionIds) {
    var set = new IntRBTreeSet(iCollectionIds);
    polymorphicCollectionIds = set.toIntArray();
  }

  public void renameProperty(
      DatabaseSessionInternal db,
      final String iOldName,
      final String iNewName
  ) {
    var p = properties.remove(iOldName);
    if (p != null) {
      properties.put(iNewName, p);
    }
  }

  protected static void truncateCollectionInternal(
      final String collectionName, final DatabaseSessionInternal database) {
    database.truncateCollection(collectionName);
  }

  public Collection<SchemaClass> getSubclasses(DatabaseSession session) {
    acquireSchemaReadLock();
    try {
      if (subclasses.isEmpty()) {
        return Collections.emptyList();
      }

      List<SchemaClass> result = new ArrayList<>(subclasses.size());
      for (LazySchemaClass lazySchemaClass : subclasses.values()) {
        lazySchemaClass.loadIfNeeded((DatabaseSessionInternal) session);
        result.add(lazySchemaClass.getDelegate());
      }
      return result;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public Collection<SchemaClass> getAllSubclasses(DatabaseSession session) {
    acquireSchemaReadLock();
    try {
      final Set<SchemaClass> set = new HashSet<>(subclasses.size());

      for (LazySchemaClass c : subclasses.values()) {
        c.loadIfNeeded((DatabaseSessionInternal) session);
        set.add(c.getDelegate());
        set.addAll(c.getDelegate().getAllSubclasses(session));
      }
      return set;
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  @Deprecated
  public Collection<SchemaClass> getBaseClasses(DatabaseSession session) {
    return getSubclasses(session);
  }

  @Deprecated
  public Collection<SchemaClass> getAllBaseClasses(DatabaseSession session) {
    return getAllSubclasses(session);
  }

  @Override
  public Collection<SchemaClass> getAllSuperClasses(DatabaseSession session) {
    Set<SchemaClass> ret = new HashSet<>();
    ret.addAll(getAllSuperClassesInternal((DatabaseSessionInternal) session));
    return ret;
  }

  private Set<SchemaClass> getAllSuperClassesInternal(DatabaseSessionInternal session) {
    Set<SchemaClass> ret = new HashSet<>(superClasses.size());
    for (LazySchemaClass superClass : superClasses.values()) {
      superClass.loadIfNeeded(session);
      ret.add(superClass.getDelegate());
      ret.addAll(superClass.getDelegate().getAllSuperClasses(session));
    }
    return ret;
  }

  public abstract SchemaClass removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClass baseClass);

  public float getOverSize(DatabaseSession session) {
    acquireSchemaReadLock();
    try {
      if (overSize > 0)
      // CUSTOM OVERSIZE SET
      {
        return overSize;
      }

      // NO OVERSIZE by default
      float maxOverSize = 0;
      float thisOverSize;
      for (LazySchemaClass superClass : superClasses.values()) {
        superClass.loadIfNeeded((DatabaseSessionInternal) session);
        thisOverSize = ((SchemaClassImpl) superClass.getDelegate()).getOverSize(session);
        if (thisOverSize > maxOverSize) {
          maxOverSize = thisOverSize;
        }
      }
      return maxOverSize;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public float getClassOverSize() {
    acquireSchemaReadLock();
    try {
      return overSize;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public boolean isAbstract() {
    acquireSchemaReadLock();
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
  public boolean isSubClassOf(DatabaseSession session, final String iClassName) {
    acquireSchemaReadLock();
    try {
      if (iClassName == null) {
        return false;
      }

      if (iClassName.equalsIgnoreCase(getName(session))) {
        return true;
      }
      for (LazySchemaClass superClass : superClasses.values()) {
        superClass.loadIfNeeded((DatabaseSessionInternal) session);
        if (superClass.getDelegate().isSubClassOf(session, iClassName)) {
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
  public boolean isSubClassOf(DatabaseSession session, final SchemaClass clazz) {
    acquireSchemaReadLock();
    try {
      if (clazz == null) {
        return false;
      }
      if (equals(clazz)) {
        return true;
      }
      for (LazySchemaClass superClass : superClasses.values()) {
        superClass.loadIfNeeded((DatabaseSessionInternal) session);
        if (superClass.getDelegate().isSubClassOf(session, clazz)) {
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
  public boolean isSuperClassOf(DatabaseSession session, final SchemaClass clazz) {
    return clazz != null && clazz.isSubClassOf(session, this);
  }

  public Object get(DatabaseSession session, final ATTRIBUTES iAttribute) {
    if (iAttribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    switch (iAttribute) {
      case NAME:
        return getName();
      case SHORTNAME:
        return getShortName();
      case SUPERCLASS:
        return getSuperClass(session);
      case SUPERCLASSES:
        return getSuperClasses(session);
      case STRICT_MODE:
        return isStrictMode();
      case ABSTRACT:
        return isAbstract();
      case CLUSTER_SELECTION:
        return getClusterSelection();
      case CUSTOM:
        return getCustomInternal();
      case DESCRIPTION:
        return getDescription();
    }

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

    acquireSchemaReadLock(session);
    try {
      final var currentClassResult = indexManager.areIndexed(session, name, fields);

      if (currentClassResult) {
        return true;
      }
      for (var superClass : superClasses) {
        if (superClass.areIndexed(session, fields)) {
      for (LazySchemaClass superClass : superClasses.values()) {
        superClass.loadIfNeeded(sessionInternal);
        if (superClass.getDelegate().areIndexed(sessionInternal, fields)) {
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

      for (LazySchemaClass superClass : superClasses.values()) {
        superClass.loadIfNeeded((DatabaseSessionInternal) session);
        result.addAll(superClass.getDelegate().getInvolvedIndexesInternal(session, fields));
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
    final var indexManager = session.getSharedContext().getIndexManager();

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
          .getSharedContext()
          .getIndexManager()
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
      final var idxManager = session.getSharedContext().getIndexManager();
      return idxManager.getClassIndexes(session, name);
    } finally {
      releaseSchemaReadLock(session);
    }
  }

  public void getClassIndexes(DatabaseSessionInternal session, final Collection<Index> indexes) {
    acquireSchemaReadLock(session);
    try {
      final var idxManager = session.getSharedContext().getIndexManager();
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
      for (var superClass : superClasses.values()) {
        superClass.loadIfNeeded((DatabaseSessionInternal) session);
        superClass.getDelegate().getIndexesInternal(session, indexes);
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

    if (!hasCollectionId(session, collectionId)) {
      return;
    }

    if (!session.isRemote()) {
      session.getStorage()
          .setCollectionAttribute(collectionId, StorageCollection.ATTRIBUTES.NAME, newName);
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

  protected abstract void addCollectionIdToIndexes(DatabaseSessionInternal session, int iId);

  /**
   * Adds a base class to the current one. It adds also the base class collection ids to the
   * polymorphic collection ids array.
   *
   * @param iBaseClass The base class to add.
   */
  public void addBaseClass(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
    checkRecursion(session, iBaseClass);

    String baseClassName = iBaseClass.getName();
    if (subclasses.containsKey(baseClassName)) {
      return this;
    }

    LazySchemaClass lazyClass = owner.getLazyClass(baseClassName);
    subclasses.put(baseClassName, lazyClass);
    owner.markClassDirty(this);
    owner.markClassDirty(iBaseClass);
    addPolymorphicClusterIdsWithInheritance(session, iBaseClass);
    return this;
  }

  protected void checkParametersConflict(DatabaseSessionInternal session,
      final SchemaClass baseClass) {
    final Collection<SchemaProperty> baseClassProperties = baseClass.properties(session);
    for (SchemaProperty property : baseClassProperties) {
      SchemaProperty thisProperty = getProperty(session, property.getName());
      if (thisProperty != null && !thisProperty.getType().equals(property.getType())) {
        throw new SchemaException(
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

  protected void removePolymorphicCollectionIds(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
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

    removeClusterFromIndexes(session, clusterId);
    for (LazySchemaClass superClass : superClasses.values()) {
      superClass.loadIfNeeded(session);
      ((SchemaClassImpl) superClass.getDelegate()).removePolymorphicClusterId(session, clusterId);
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
          (IndexManagerEmbedded) session.getSharedContext().getIndexManager();
      for (final var indexName : indexesToRemove) {
        indexManager.removeCollectionFromIndex(session, collectionName, indexName);
      }
    }
  }

  /**
   * Add different collection id to the "polymorphic collection ids" array.
   */
  protected void addPolymorphicCollectionIds(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
    var collections = new IntRBTreeSet(polymorphicCollectionIds);

    for (var collectionId : iBaseClass.polymorphicCollectionIds) {
      if (collections.add(collectionId)) {
        try {
          addCollectionIdToIndexes(session, collectionId);
        } catch (RuntimeException e) {
          LogManager.instance()
              .warn(
                  this,
                  "Error adding collectionId '%d' to index of class '%s'",
                  e,
                  collectionId,
                  getName(session));
          collections.remove(collectionId);
        }
      }
    }

    polymorphicCollectionIds = collections.toIntArray();
  }

  private void addPolymorphicCollectionIdsWithInheritance(DatabaseSessionInternal session,
      final SchemaClassImpl iBaseClass) {
    addPolymorphicClusterIds(session, iBaseClass);
    for (LazySchemaClass superClass : superClasses.values()) {
      superClass.loadIfNeeded(session);
      ((SchemaClassImpl) superClass.getDelegate()).addPolymorphicClusterIdsWithInheritance(session,
          iBaseClass);
    }
  }

  public List<PropertyType> extractFieldTypes(DatabaseSession session, final String[] fieldNames) {
    final List<PropertyType> types = new ArrayList<PropertyType>(fieldNames.length);

    for (String fieldName : fieldNames) {
      if (!fieldName.equals("@rid")) {
        types.add(
            getProperty(session,
                decodeClassName(IndexDefinitionFactory.extractFieldName(fieldName)))
                .getType());
      } else {
        types.add(PropertyType.LINK);
      }
    }
    return types;
  }

  protected void setCollectionIds(final int[] iCollectionIds) {
    collectionIds = iCollectionIds;
    Arrays.sort(collectionIds);
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
}
