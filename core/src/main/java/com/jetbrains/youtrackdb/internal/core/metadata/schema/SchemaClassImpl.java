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

import static com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.EDGE_CLASS_NAME;
import static com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.VERTEX_CLASS_NAME;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.CommonConst;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.RoundRobinCollectionSelectionStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.query.Result;
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
      final DatabaseSessionEmbedded db, final int[] iCollectionIds, String className) {
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


  public CollectionSelectionStrategy getCollectionSelection() {
    acquireSchemaReadLock();
    try {
      return collectionSelection;
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
        return Collections.unmodifiableMap(customFields);
      }
      return null;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void removeCustom(DatabaseSessionEmbedded session, final String name) {
    setCustom(session, name, null);
  }

  public abstract void setCustom(DatabaseSessionEmbedded session, final String name,
      final String value);

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


  public List<SchemaClassImpl> getSuperClasses() {
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

  public List<String> getSuperClassesNames() {
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

  public void setSuperClassesByNames(DatabaseSessionEmbedded session, List<String> classNames) {
    if (classNames == null) {
      classNames = Collections.EMPTY_LIST;
    }

    final List<SchemaClassImpl> classes = new ArrayList<>(classNames.size());
    for (var className : classNames) {
      classes.add(owner.getClass(decodeClassName(className)));
    }

    setSuperClasses(session, classes);
  }

  public abstract void setSuperClasses(DatabaseSessionEmbedded session,
      List<SchemaClassImpl> classes);

  protected abstract void setSuperClassesInternal(
      DatabaseSessionEmbedded session,
      final List<SchemaClassImpl> classes,
      boolean validateIndexes);


  public String getDescription() {
    acquireSchemaReadLock();
    try {
      return description;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public String getStreamableName() {
    acquireSchemaReadLock();
    try {
      return name;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Collection<SchemaPropertyImpl> declaredProperties() {
    acquireSchemaReadLock();
    try {
      return Collections.unmodifiableCollection(properties.values());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Map<String, SchemaPropertyImpl> propertiesMap(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA,
        Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      final Map<String, SchemaPropertyImpl> props = new HashMap<>(20);
      propertiesMap(props);
      return props;
    } finally {
      releaseSchemaReadLock();
    }
  }

  private void propertiesMap(Map<String, SchemaPropertyImpl> propertiesMap) {
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

  public Collection<SchemaPropertyImpl> properties() {
    acquireSchemaReadLock();
    try {
      final Collection<SchemaPropertyImpl> props = new ArrayList<>();
      properties(props);
      return props;
    } finally {
      releaseSchemaReadLock();
    }
  }

  private void properties(Collection<SchemaPropertyImpl> properties) {
    properties.addAll(this.properties.values());
    for (var superClass : superClasses) {
      superClass.properties(properties);
    }
  }

  public SchemaPropertyImpl getProperty(String propertyName) {
    return getPropertyInternal(propertyName);
  }

  public SchemaPropertyImpl getPropertyInternal(String propertyName) {
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

  public SchemaPropertyImpl getDeclaredPropertyInternal(String propertyName) {
    acquireSchemaReadLock();
    try {
      return properties.get(propertyName);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaPropertyImpl createProperty(DatabaseSessionEmbedded session,
      final String iPropertyName,
      final PropertyTypeInternal iType) {
    return addProperty(session, iPropertyName, iType, null, null,
        false);
  }


  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName, final PropertyTypeInternal iType,
      final SchemaClassImpl iLinkedClass) {
    return addProperty(session, iPropertyName, iType, null,
        iLinkedClass,
        false);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName,
      final PropertyTypeInternal iType,
      final SchemaClassImpl iLinkedClass,
      final boolean unsafe) {
    return addProperty(session, iPropertyName, iType, null,
        iLinkedClass,
        unsafe);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName, final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType) {
    return addProperty(session, iPropertyName, iType, iLinkedType, null,
        false);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName,
      final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType,
      final boolean unsafe) {
    return addProperty(session, iPropertyName, iType, iLinkedType, null,
        unsafe);
  }

  public SchemaPropertyImpl createProperty(DatabaseSessionEmbedded session,
      final String iPropertyName,
      final PropertyType iType) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType));
  }


  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName, final PropertyType iType,
      final SchemaClassImpl iLinkedClass) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType), iLinkedClass);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName,
      final PropertyType iType,
      final SchemaClassImpl iLinkedClass,
      final boolean unsafe) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType), iLinkedClass,
        unsafe);
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName, final PropertyType iType,
      final PropertyType iLinkedType) {
    return createProperty(session, iPropertyName,
        PropertyTypeInternal.convertFromPublicType(iType),
        PropertyTypeInternal.convertFromPublicType(iLinkedType));
  }

  public SchemaPropertyImpl createProperty(
      DatabaseSessionEmbedded session, final String iPropertyName,
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

  public abstract void addSuperClass(DatabaseSessionEmbedded session, SchemaClassImpl superClass);

  public abstract void removeSuperClass(DatabaseSessionEmbedded session,
      SchemaClassImpl superClass);

  public void fromStream(EntityImpl entity) {
    subclasses = null;
    superClasses.clear();

    name = entity.getProperty("name");
    if (entity.hasProperty("description")) {
      description = entity.getProperty("description");
    } else {
      description = null;
    }
    defaultCollectionId = entity.getProperty("defaultCollectionId");
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

    final var cc = entity.getProperty("collectionIds");
    if (cc instanceof Collection<?>) {
      final Collection<Integer> coll = entity.getProperty("collectionIds");
      collectionIds = new int[coll.size()];
      var i = 0;
      for (final var item : coll) {
        collectionIds[i++] = item;
      }
    } else {
      collectionIds = (int[]) cc;
    }
    Arrays.sort(collectionIds);

    if (collectionIds.length == 1 && collectionIds[0] == -1) {
      setPolymorphicCollectionIds(CommonConst.EMPTY_INT_ARRAY);
    } else {
      setPolymorphicCollectionIds(collectionIds);
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
          prop.fromStream(p);
        } else {
          prop = createPropertyInstance();
          prop.fromStream(p);
        }

        newProperties.put(prop.getName(), prop);
      }
    }

    properties.clear();
    properties.putAll(newProperties);
    customFields = entity.getProperty("customFields");
  }

  protected abstract SchemaPropertyImpl createPropertyInstance();

  public Entity toStream(DatabaseSessionEmbedded session) {
    var entity = session.newEmbeddedEntity();
    entity.setProperty("name", name);
    entity.setProperty("description", description);
    entity.setProperty("defaultCollectionId", defaultCollectionId);
    entity.newEmbeddedList("collectionIds", collectionIds);
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
      entity.setProperty("superClass", superClasses.getFirst().getName(),
          PropertyType.STRING);
      List<String> superClassesNames = session.newEmbeddedList(superClasses.size());
      for (var superClass : superClasses) {
        superClassesNames.add(superClass.getName());
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

  private void setPolymorphicCollectionIds(final int[] iCollectionIds) {
    var set = new IntRBTreeSet(iCollectionIds);
    polymorphicCollectionIds = set.toIntArray();
  }

  public void renameProperty(final String iOldName, final String iNewName) {
    var p = properties.remove(iOldName);
    if (p != null) {
      properties.put(iNewName, p);
    }
  }

  public Collection<SchemaClassImpl> getSubclasses() {
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

  public Collection<SchemaClassImpl> getAllSubclasses() {
    acquireSchemaReadLock();
    try {
      final Set<SchemaClassImpl> set = new HashSet<>();
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
  public Collection<SchemaClassImpl> getBaseClasses() {
    return getSubclasses();
  }

  @Deprecated
  public Collection<SchemaClassImpl> getAllBaseClasses() {
    return getAllSubclasses();
  }

  private void getAllSuperClasses(Set<SchemaClassImpl> set) {
    set.addAll(superClasses);
    for (var superClass : superClasses) {
      superClass.getAllSuperClasses(set);
    }
  }

  public abstract void removeBaseClassInternal(DatabaseSessionEmbedded session,
      final SchemaClassImpl baseClass);

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
    if (!(obj instanceof SchemaClassImpl other)) {
      return false;
    }

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

  public long count(DatabaseSessionEmbedded session) {
    return count(session, true);
  }

  public long count(DatabaseSessionEmbedded session, final boolean isPolymorphic) {
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
  public void truncate(DatabaseSessionEmbedded session) {
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
  public boolean isSubClassOf(final SchemaClassImpl clazz) {
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
  public boolean isSuperClassOf(final SchemaClassImpl clazz) {
    return clazz != null && clazz.isSubClassOf(this);
  }

  public boolean isSuperClassOf(final String className) {
    var clazz = owner.getClass(className);
    if (clazz == null) {
      return false;
    }

    return clazz.isSuperClassOf(this);
  }


  public Object get(DatabaseSessionEmbedded db, final ATTRIBUTES iAttribute) {
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

  public void set(DatabaseSessionEmbedded session, final ATTRIBUTES attribute,
      final Object iValue) {
    if (attribute == null) {
      throw new IllegalArgumentException("attribute is null");
    }

    final var stringValue = iValue != null ? iValue.toString() : null;
    final var isNull = stringValue == null || stringValue.equalsIgnoreCase("NULL");

    switch (attribute) {
      case NAME -> setName(session, decodeClassName(stringValue));
      case SUPERCLASSES ->
          setSuperClassesByNames(session
              , stringValue != null ? Arrays.asList(PATTERN.split(stringValue)) : null);
      case STRICT_MODE -> setStrictMode(session, Boolean.parseBoolean(stringValue));
      case ABSTRACT -> setAbstract(session, Boolean.parseBoolean(stringValue));
      case CUSTOM -> {
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
      }
      case DESCRIPTION -> setDescription(session, stringValue);
    }
  }

  public abstract void clearCustom(DatabaseSessionEmbedded session);

  public abstract void setDescription(DatabaseSessionEmbedded session,
      String iDescription);

  public abstract void setName(DatabaseSessionEmbedded session, String iName);

  public abstract void setStrictMode(DatabaseSessionEmbedded session, boolean iMode);

  public abstract void setAbstract(DatabaseSessionEmbedded session, boolean iAbstract);


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

  public boolean areIndexed(DatabaseSessionEmbedded session, final String... fields) {
    return areIndexed(session, Arrays.asList(fields));
  }

  public boolean areIndexed(DatabaseSessionEmbedded session, final Collection<String> fields) {
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

  public Set<String> getInvolvedIndexes(DatabaseSessionEmbedded session, final String... fields) {
    return getInvolvedIndexes(session, Arrays.asList(fields));
  }

  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionEmbedded session, String... fields) {
    return getInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  public Set<String> getInvolvedIndexes(DatabaseSessionEmbedded session,
      final Collection<String> fields) {
    return getInvolvedIndexesInternal(session, fields).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getInvolvedIndexesInternal(DatabaseSessionEmbedded session,
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

  public Set<String> getClassInvolvedIndexes(DatabaseSessionEmbedded session,
      final Collection<String> fields) {
    return getClassInvolvedIndexesInternal(session, fields).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      Collection<String> fields) {
    final var indexManager = session.getSharedContext().getIndexManager();

    acquireSchemaReadLock();
    try {
      return indexManager.getClassInvolvedIndexes(session, name, fields);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<String> getClassInvolvedIndexes(DatabaseSessionEmbedded session,
      final String... fields) {
    return getClassInvolvedIndexes(session, Arrays.asList(fields));
  }

  public Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      String... fields) {
    return getClassInvolvedIndexesInternal(session, Arrays.asList(fields));
  }

  public Index getClassIndex(DatabaseSessionEmbedded session, final String name) {
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

  public Set<String> getClassIndexes(DatabaseSessionEmbedded session) {
    return getClassInvolvedIndexesInternal(session).stream().map(Index::getName)
        .collect(Collectors.toSet());
  }

  public Set<Index> getClassIndexesInternal(DatabaseSessionEmbedded session) {
    acquireSchemaReadLock();
    try {
      final var idxManager = session.getSharedContext().getIndexManager();
      return idxManager.getClassIndexes(session, name);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void getClassIndexes(DatabaseSessionEmbedded session, final Collection<Index> indexes) {
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


  public void getIndexesInternal(DatabaseSessionEmbedded session, final Collection<Index> indexes) {
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

  public Set<String> getIndexes(DatabaseSessionEmbedded session) {
    return getIndexesInternal(session).stream().map(Index::getName).collect(Collectors.toSet());
  }

  public Set<Index> getIndexesInternal(DatabaseSessionEmbedded session) {
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

  public void acquireSchemaWriteLock(DatabaseSessionEmbedded session) {
    owner.acquireSchemaWriteLock(session);
  }

  public void releaseSchemaWriteLock(DatabaseSessionEmbedded session) {
    releaseSchemaWriteLock(session, true);
  }

  public void releaseSchemaWriteLock(DatabaseSessionEmbedded session, final boolean iSave) {
    calculateHashCode();
    owner.releaseSchemaWriteLock(session, iSave);
  }

  public void checkEmbedded(DatabaseSessionEmbedded session) {
    owner.checkEmbedded(session);
  }

  public void fireDatabaseMigration(
      final DatabaseSessionEmbedded database, final String propertyName,
      final PropertyTypeInternal type) {

    var recordsToUpdate = database.computeInTx(transaction -> {
      try (var result =
          database.query(
              "select from "
                  + getEscapedName(name)
                  + " where "
                  + getEscapedName(propertyName)
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
      final DatabaseSessionEmbedded database,
      final String propertyName,
      final String newPropertyName,
      final PropertyTypeInternal type) {
    var ridsToMigrate = database.computeInTx(transaction -> {
      try (var result =
          database.query(
              "select from "
                  + getEscapedName(name)
                  + " where "
                  + getEscapedName(propertyName)
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
      final DatabaseSessionEmbedded session,
      final String propertyName,
      final PropertyTypeInternal type,
      SchemaClassImpl linkedClass) {
    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(name));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName));
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
        .append(getEscapedName(propertyName))
        .append(" is not null ");
    if (type.isMultiValue()) {
      builder
          .append(" and ")
          .append(getEscapedName(propertyName))
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
      DatabaseSessionEmbedded db, String propertyName, PropertyTypeInternal type,
      SchemaClassImpl linkedClass) {
    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(name));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName)).append(" is not null ");
    if (type.isMultiValue()) {
      builder.append(" and ").append(getEscapedName(propertyName)).append(".size() > 0");
    }

    db.executeInTx(tx -> {
      try (final var res = tx.query(builder.toString())) {
        while (res.hasNext()) {
          var item = res.next();
          switch (type) {
            case EMBEDDEDLIST, LINKLIST, EMBEDDEDSET, LINKSET -> {
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
            }
            case EMBEDDED, LINK -> {
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
            }
          }
        }
      }
    });
  }

  protected static boolean matchesType(DatabaseSessionEmbedded db, Object x,
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
        || linkedClass.getName().equalsIgnoreCase(((EntityImpl) x).getSchemaClassName());
  }

  protected static String getEscapedName(final String iName) {
    return "`" + iName + "`";
  }

  public SchemaShared getOwner() {
    return owner;
  }

  private void calculateHashCode() {
    var result = super.hashCode();
    result = 31 * result + (name != null ? name.hashCode() : 0);
    hashCode = result;
  }

  protected void renameCollection(DatabaseSessionEmbedded session, String oldName, String newName) {
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

  protected abstract SchemaPropertyImpl addProperty(
      DatabaseSessionEmbedded session, final String propertyName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassImpl linkedClass,
      final boolean unsafe);

  public abstract void dropProperty(DatabaseSessionEmbedded session, String iPropertyName);

  public Collection<SchemaClassImpl> getAllSuperClasses() {
    acquireSchemaReadLock();
    try {
      Set<SchemaClassImpl> ret = new HashSet<>();
      getAllSuperClasses(ret);
      return ret;
    } finally {
      releaseSchemaReadLock();
    }
  }

  protected void validatePropertyName(final String propertyName) {
  }

  protected abstract void addCollectionIdToIndexes(
      DatabaseSessionEmbedded session,
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
      DatabaseSessionEmbedded session,
      final SchemaClassImpl iBaseClass,
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

  protected void checkParametersConflict(DatabaseSessionEmbedded session,
      final SchemaClassImpl baseClass) {
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

  public static void checkParametersConflict(DatabaseSessionEmbedded db,
      List<SchemaClassImpl> classes) {
    final Map<String, SchemaPropertyImpl> comulative = new HashMap<>();
    final Map<String, SchemaPropertyImpl> properties = new HashMap<>();

    for (var superClass : classes) {
      if (superClass == null) {
        continue;
      }
      SchemaClassImpl impl;
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

  private void checkRecursion(DatabaseSessionEmbedded session, final SchemaClassImpl baseClass) {
    if (isSubClassOf(baseClass)) {
      throw new SchemaException(session.getDatabaseName(),
          "Cannot add base class '" + baseClass.getName() + "', because of recursion");
    }
  }

  protected void removePolymorphicCollectionIds(DatabaseSessionEmbedded session,
      final SchemaClassImpl iBaseClass) {
    for (final var collectionId : iBaseClass.polymorphicCollectionIds) {
      removePolymorphicCollectionId(session, collectionId);
    }
  }

  protected void removePolymorphicCollectionId(DatabaseSessionEmbedded session,
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

  private void removeCollectionFromIndexes(DatabaseSessionEmbedded session, final int iId) {
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
      DatabaseSessionEmbedded session,
      final SchemaClassImpl iBaseClass,
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
      DatabaseSessionEmbedded session,
      final SchemaClassImpl iBaseClass,
      boolean validateIndexes) {
    addPolymorphicCollectionIds(session, iBaseClass, validateIndexes);
    for (var superClass : superClasses) {
      superClass.addPolymorphicCollectionIdsWithInheritance(session, iBaseClass, validateIndexes);
    }
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
