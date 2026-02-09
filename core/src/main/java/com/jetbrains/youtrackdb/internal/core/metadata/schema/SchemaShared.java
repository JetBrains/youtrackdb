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

import com.jetbrains.youtrackdb.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableInteger;
import com.jetbrains.youtrackdb.internal.common.util.ArrayUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaNotCreatedException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.CollectionSelectionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.GlobalProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared schema class. It's shared by all the database instances that point to the same storage.
 */
public abstract class SchemaShared implements CloseableInStorage {

  public static final int CURRENT_VERSION_NUMBER = 4;
  public static final int VERSION_NUMBER_V4 = 4;
  // this is needed for guarantee the compatibility to 2.0-M1 and 2.0-M2 no changed associated with
  // it
  public static final int VERSION_NUMBER_V5 = 5;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  protected final Map<String, SchemaClassImpl> classes = new HashMap<>();
  protected final Int2ObjectOpenHashMap<SchemaClassImpl> collectionsToClasses = new Int2ObjectOpenHashMap<>();

  private final CollectionSelectionFactory collectionSelectionFactory = new CollectionSelectionFactory();

  private final ModifiableInteger modificationCounter = new ModifiableInteger();
  private final List<GlobalPropertyImpl> properties = new ArrayList<>();
  private final Map<String, GlobalPropertyImpl> propertiesByNameType = new HashMap<>();
  private IntOpenHashSet blobCollections = new IntOpenHashSet();
  private volatile int version = 0;
  private volatile RecordIdInternal identity;
  protected volatile ImmutableSchema snapshot;

  private final ReentrantLock snapshotLock = new ReentrantLock();

  protected static Set<String> internalClasses = new HashSet<>();

  static {
    internalClasses.add("ouser");
    internalClasses.add(Role.CLASS_NAME.toLowerCase(Locale.ROOT));
    internalClasses.add("osecuritypolicy");
    internalClasses.add("oidentity");
    internalClasses.add("ofunction");
    internalClasses.add("osequence");
    internalClasses.add("otrigger");
    internalClasses.add("oschedule");
    internalClasses.add("orids");
    internalClasses.add("o");
    internalClasses.add("v");
    internalClasses.add("e");
    internalClasses.add("le");
  }

  protected static final class CollectionIdsAreEmptyException extends Exception {

  }

  public SchemaShared() {
  }

  @Nullable
  public static Character checkClassNameIfValid(String name) throws SchemaException {
    if (name == null) {
      throw new IllegalArgumentException("Name is null");
    }

    name = name.trim();
    final var nameSize = name.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = name.charAt(i);
      if (c == ':')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  @SuppressWarnings("JavaExistingMethodCanBeUsed")
  @Nullable
  public static Character checkPropertyNameIfValid(String iName) {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    iName = iName.trim();

    final var nameSize = iName.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = iName.charAt(i);
      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '=')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  @Nullable
  public static Character checkIndexNameIfValid(String iName) {
    if (iName == null) {
      throw new IllegalArgumentException("Name is null");
    }

    iName = iName.trim();

    final var nameSize = iName.length();

    if (nameSize == 0) {
      throw new IllegalArgumentException("Name is empty");
    }

    for (var i = 0; i < nameSize; ++i) {
      final var c = iName.charAt(i);
      if (c == ':' || c == ',' || c == ';' || c == ' ' || c == '=')
      // INVALID CHARACTER
      {
        return c;
      }
    }

    return null;
  }

  public ImmutableSchema makeSnapshot(DatabaseSessionEmbedded session) {
    var snapshot = this.snapshot;

    if (snapshot == null) {
      acquireSchemaReadLock();
      try {
        snapshotLock.lock();
        try {
          if (this.snapshot == null) {
            this.snapshot = new ImmutableSchema(this, session);
          }

          return this.snapshot;
        } finally {
          snapshotLock.unlock();
        }
      } finally {
        releaseSchemaReadLock();
      }
    }

    return snapshot;
  }

  public void forceSnapshot() {
    if (snapshot == null) {
      return;
    }

    snapshotLock.lock();
    try {
      snapshot = null;
    } finally {
      snapshotLock.unlock();
    }
  }

  public CollectionSelectionFactory getCollectionSelectionFactory() {
    return collectionSelectionFactory;
  }

  public int countClasses(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      return classes.size();
    } finally {
      releaseSchemaReadLock();
    }
  }


  public SchemaClassImpl createClass(DatabaseSessionEmbedded sesion, final String className) {
    return createClass(sesion, className, null, (int[]) null);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session, final String iClassName, final SchemaClassImpl iSuperClass) {
    return createClass(session, iClassName, iSuperClass, null);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session, String iClassName, SchemaClassImpl... superClasses) {
    return createClass(session, iClassName, null, superClasses);
  }

  public SchemaClassImpl getOrCreateClass(DatabaseSessionEmbedded session,
      final String iClassName) {
    return getOrCreateClass(session, iClassName, (SchemaClassImpl) null);
  }

  public SchemaClassImpl getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName, final SchemaClassImpl superClass) {
    return getOrCreateClass(
        session, iClassName,
        superClass == null ? new SchemaClassImpl[0] : new SchemaClassImpl[]{superClass});
  }

  public abstract SchemaClassImpl getOrCreateClass(
      DatabaseSessionEmbedded session, final String iClassName,
      final SchemaClassImpl... superClasses);

  public SchemaClassImpl createAbstractClass(DatabaseSessionEmbedded session,
      final String className) {
    return createClass(session, className, null, new int[]{-1});
  }

  public SchemaClassImpl createAbstractClass(
      DatabaseSessionEmbedded session, final String className, final SchemaClassImpl superClass) {
    return createClass(session, className, superClass, new int[]{-1});
  }

  public SchemaClassImpl createAbstractClass(
      DatabaseSessionEmbedded session, String iClassName, SchemaClassImpl... superClasses) {
    return createClass(session, iClassName, new int[]{-1}, superClasses);
  }

  public SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      final SchemaClassImpl superClass,
      int[] collectionIds) {
    return createClass(session, className, collectionIds, superClass);
  }

  public abstract SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int[] collectionIds,
      SchemaClassImpl... superClasses);

  public abstract SchemaClassImpl createClass(
      DatabaseSessionEmbedded session,
      final String className,
      int collections,
      SchemaClassImpl... superClasses);


  public abstract void checkEmbedded(DatabaseSessionEmbedded session);

  void checkCollectionCanBeAdded(DatabaseSessionEmbedded session, int collectionId,
      SchemaClassImpl cls) {
    acquireSchemaReadLock();
    try {
      if (collectionId < 0) {
        return;
      }

      if (blobCollections.contains(collectionId)) {
        throw new SchemaException(session.getDatabaseName(),
            "Collection with id " + collectionId + " already belongs to Blob");
      }

      final var existingCls = collectionsToClasses.get(collectionId);

      if (existingCls != null && (cls == null || !cls.equals(existingCls))) {
        throw new SchemaException(session.getDatabaseName(),
            "Collection with id "
                + collectionId
                + " already belongs to the class '"
                + collectionsToClasses.get(collectionId)
                + "'");
      }
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaClassImpl getClassByCollectionId(int collectionId) {
    acquireSchemaReadLock();
    try {
      return collectionsToClasses.get(collectionId);
    } finally {
      releaseSchemaReadLock();
    }
  }

  public abstract void dropClass(DatabaseSessionEmbedded session, final String className);

  /**
   * Reloads the schema inside a storage's shared lock.
   */
  public void reload(DatabaseSessionEmbedded session) {
    lock.writeLock().lock();
    try {
      session.executeInTx(
          transaction -> {
            identity = RecordIdInternal.fromString(
                session.getStorage().getSchemaRecordId(), false);

            EntityImpl entity = session.load(identity);
            fromStream(session, entity);
            forceSnapshot();
          });
    } finally {
      lock.writeLock().unlock();
    }
  }

  public boolean existsClass(final String iClassName) {
    if (iClassName == null) {
      return false;
    }

    acquireSchemaReadLock();
    try {
      return classes.containsKey(iClassName.toLowerCase(Locale.ENGLISH));
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Nullable
  public SchemaClassImpl getClass(final Class<?> iClass) {
    if (iClass == null) {
      return null;
    }

    return getClass(iClass.getSimpleName());
  }

  @Nullable
  public SchemaClassImpl getClass(final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    acquireSchemaReadLock();
    try {
      return classes.get(iClassName.toLowerCase(Locale.ENGLISH));
    } finally {
      releaseSchemaReadLock();
    }
  }

  public void acquireSchemaReadLock() {
    lock.readLock().lock();
  }

  public void releaseSchemaReadLock() {
    lock.readLock().unlock();
  }

  public void acquireSchemaWriteLock(DatabaseSessionEmbedded session) {
    lock.writeLock().lock();
    modificationCounter.increment();
  }

  public void releaseSchemaWriteLock(DatabaseSessionEmbedded session) {
    releaseSchemaWriteLock(session, true);
  }

  public void releaseSchemaWriteLock(DatabaseSessionEmbedded session, final boolean iSave) {
    int count;
    try {
      if (modificationCounter.intValue() == 1) {
        // if it is embedded storage modification of schema is done by internal methods otherwise it
        // is done by
        // by sql commands and we need to reload local replica

        if (iSave) {
          if (session.getStorage() instanceof AbstractStorage) {
            saveInternal(session);
          } else {
            reload(session);
          }
        } else {
          snapshot = null;
        }
        //noinspection NonAtomicOperationOnVolatileField
        version++;
      }
    } finally {
      modificationCounter.decrement();
      count = modificationCounter.intValue();
      lock.writeLock().unlock();
    }

    assert count >= 0;
  }

  void changeClassName(
      DatabaseSessionEmbedded session,
      final String oldName,
      final String newName,
      final SchemaClassImpl cls) {

    if (oldName != null && oldName.equalsIgnoreCase(newName)) {
      throw new IllegalArgumentException(
          "Class '" + oldName + "' cannot be renamed with the same name");
    }

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      if (newName != null
          && (classes.containsKey(newName.toLowerCase(Locale.ENGLISH)))) {
        throw new IllegalArgumentException("Class '" + newName + "' is already present in schema");
      }

      if (oldName != null) {
        classes.remove(oldName.toLowerCase(Locale.ENGLISH));
      }
      if (newName != null) {
        classes.put(newName.toLowerCase(Locale.ENGLISH), cls);
      }

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  /**
   * Binds EntityImpl to POJO.
   */
  public void fromStream(DatabaseSessionEmbedded session, EntityImpl entity) {
    lock.writeLock().lock();
    modificationCounter.increment();
    try {
      // READ CURRENT SCHEMA VERSION
      final Integer schemaVersion = entity.getProperty("schemaVersion");
      if (schemaVersion == null) {
        LogManager.instance()
            .error(
                this,
                "Database's schema is empty! Recreating the system classes and allow the opening of"
                    + " the database but double check the integrity of the database",
                null);
        return;
      } else if (schemaVersion != CURRENT_VERSION_NUMBER && VERSION_NUMBER_V5 != schemaVersion) {
        // VERSION_NUMBER_V5 is needed for guarantee the compatibility to 2.0-M1 and 2.0-M2 no
        // changed associated with it
        // HANDLE SCHEMA UPGRADE
        throw new ConfigurationException(
            session.getDatabaseName(),
            "Database schema is different. Please export your old database with the previous"
                + " version of YouTrackDB and reimport it using the current one.");
      }

      properties.clear();
      propertiesByNameType.clear();
      List<EntityImpl> globalProperties = entity.getProperty("globalProperties");
      var hasGlobalProperties = false;
      if (globalProperties != null) {
        hasGlobalProperties = true;
        for (var oDocument : globalProperties) {
          var prop = new GlobalPropertyImpl();
          prop.fromEntity(oDocument);
          ensurePropertiesSize(prop.getId());
          properties.set(prop.getId(), prop);
          propertiesByNameType.put(prop.getName() + "|" + prop.getType().name(), prop);
        }
      }
      // REGISTER ALL THE CLASSES
      collectionsToClasses.clear();

      final Map<String, SchemaClassImpl> newClasses = new HashMap<>();

      Collection<EntityImpl> storedClasses = entity.getProperty("classes");
      for (var c : storedClasses) {
        String name = c.getProperty("name");

        SchemaClassImpl cls;
        if (classes.containsKey(name.toLowerCase(Locale.ENGLISH))) {
          cls = classes.get(name.toLowerCase(Locale.ENGLISH));
          cls.fromStream(c);
        } else {
          cls = createClassInstance(name);
          cls.fromStream(c);
        }

        newClasses.put(cls.getName().toLowerCase(Locale.ENGLISH), cls);
        addCollectionClassMap(cls);
      }

      classes.clear();
      classes.putAll(newClasses);

      // REBUILD THE INHERITANCE TREE
      Collection<String> superClassNames;
      String legacySuperClassName;
      List<SchemaClassImpl> superClasses;
      SchemaClassImpl superClass;

      for (var c : storedClasses) {
        superClassNames = c.getProperty("superClasses");
        legacySuperClassName = c.getProperty("superClass");
        if (superClassNames == null) {
          superClassNames = new ArrayList<>();
        }
        if (legacySuperClassName != null && !superClassNames.contains(legacySuperClassName)) {
          superClassNames.add(legacySuperClassName);
        }

        if (!superClassNames.isEmpty()) {
          // HAS A SUPER CLASS or CLASSES
          var cls =
              classes.get(((String) c.getProperty("name")).toLowerCase(Locale.ENGLISH));
          superClasses = new ArrayList<>(superClassNames.size());
          for (var superClassName : superClassNames) {

            superClass = classes.get(superClassName.toLowerCase(Locale.ENGLISH));

            if (superClass == null) {
              throw new ConfigurationException(
                  session.getDatabaseName(), "Super class '"
                  + superClassName
                  + "' was declared in class '"
                  + cls.getName()
                  + "' but was not found in schema. Remove the dependency or create the class"
                  + " to continue.");
            }
            superClasses.add(superClass);
          }
          cls.setSuperClassesInternal(session, superClasses, false);
        }
      }

      // VIEWS

      if (entity.hasProperty("blobCollections")) {
        blobCollections = new IntOpenHashSet(entity.getEmbeddedSet("blobCollections"));
      }

      if (!hasGlobalProperties) {
        if (session.getStorage() instanceof AbstractStorage) {
          saveInternal(session);
        }
      }

    } finally {
      //noinspection NonAtomicOperationOnVolatileField
      version++;
      modificationCounter.decrement();
      lock.writeLock().unlock();
    }
  }

  protected abstract SchemaClassImpl createClassInstance(String name);

  /**
   * Binds POJO to EntityImpl.
   */
  public EntityImpl toStream(@Nonnull DatabaseSessionEmbedded session) {
    lock.readLock().lock();
    try {
      EntityImpl entity = session.load(identity);
      entity.setProperty("schemaVersion", CURRENT_VERSION_NUMBER);

      // This steps is needed because in classes there are duplicate due to aliases
      Set<SchemaClassImpl> realClases = new HashSet<>(classes.values());

      Set<Entity> classesEntities = session.newEmbeddedSet();
      for (var c : realClases) {
        classesEntities.add(c.toStream(session));
      }
      entity.setProperty("classes", classesEntities, PropertyType.EMBEDDEDSET);

      List<Entity> globalProperties = session.newEmbeddedList();
      for (var globalProperty : properties) {
        if (globalProperty != null) {
          globalProperties.add(globalProperty.toEntity(session));
        }
      }
      entity.setProperty("globalProperties", globalProperties, PropertyType.EMBEDDEDLIST);
      Object propertyValue = session.newEmbeddedSet(blobCollections);
      entity.setProperty("blobCollections", propertyValue, PropertyType.EMBEDDEDSET);
      return entity;
    } finally {
      lock.readLock().unlock();
    }
  }

  public Collection<SchemaClassImpl> getClasses(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
    acquireSchemaReadLock();
    try {
      return new HashSet<>(classes.values());
    } finally {
      releaseSchemaReadLock();
    }
  }

  public Set<SchemaClassImpl> getClassesRelyOnCollection(
      DatabaseSessionEmbedded session, final String collectionName) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_READ);

    acquireSchemaReadLock();
    try {
      final var collectionId = session.getCollectionIdByName(collectionName);
      final Set<SchemaClassImpl> result = new HashSet<>();
      for (var c : classes.values()) {
        if (ArrayUtils.contains(c.getPolymorphicCollectionIds(), collectionId)) {
          result.add(c);
        }
      }

      return result;
    } finally {
      releaseSchemaReadLock();
    }
  }

  public SchemaShared load(DatabaseSessionEmbedded session) {

    lock.writeLock().lock();
    try {
      identity = RecordIdInternal.fromString(
          session.getStorage().getSchemaRecordId(), false);
      if (!identity.isValidPosition()) {
        throw new SchemaNotCreatedException(session.getDatabaseName(),
            "Schema is not created and cannot be loaded");
      }
      session.executeInTx(
          transaction -> {
            EntityImpl entity = session.load(identity);
            fromStream(session, entity);
          });
      return this;
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void create(final DatabaseSessionEmbedded session) {
    lock.writeLock().lock();
    try {
      var entity = session.computeInTx(transaction -> session.newInternalInstance());

      this.identity = entity.getIdentity();
      session.getStorage().setSchemaRecordId(entity.getIdentity().toString());
      snapshot = null;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void close() {
  }

  @Deprecated
  public int getVersion() {
    return version;
  }

  public RecordIdInternal getIdentity() {
    acquireSchemaReadLock();
    try {
      return identity;
    } finally {
      releaseSchemaReadLock();
    }
  }

  @Nullable
  public GlobalPropertyImpl getGlobalPropertyById(int id) {
    acquireSchemaReadLock();
    try {
      if (id >= properties.size()) {
        return null;
      }
      return properties.get(id);
    } finally {
      releaseSchemaReadLock();
    }

  }

  public GlobalProperty createGlobalProperty(
      DatabaseSessionEmbedded session, final String name, final PropertyTypeInternal type,
      final Integer id) {

    acquireSchemaWriteLock(session);
    try {
      GlobalPropertyImpl global;
      if (id < properties.size() && (global = properties.get(id)) != null) {
        if (!global.getName().equals(name)
            || PropertyTypeInternal.convertFromPublicType(global.getType()) != type) {
          throw new SchemaException("A property with id " + id + " already exist ");
        }
        return global;
      }

      global = new GlobalPropertyImpl(name, type, id);
      ensurePropertiesSize(id);
      properties.set(id, global);
      propertiesByNameType.put(global.getName() + "|" + global.getType().name(), global);
      return global;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public List<GlobalProperty> getGlobalProperties() {
    acquireSchemaReadLock();
    try {
      return Collections.unmodifiableList(properties);
    } finally {
      releaseSchemaReadLock();
    }
  }

  protected GlobalPropertyImpl findOrCreateGlobalProperty(final String name,
      final PropertyTypeInternal type) {
    var global = propertiesByNameType.get(name + "|" + type.name());
    if (global == null) {
      var id = properties.size();
      global = new GlobalPropertyImpl(name, type, id);
      properties.add(id, global);
      propertiesByNameType.put(global.getName() + "|" + global.getType().name(), global);
    }
    return global;
  }

  private void saveInternal(DatabaseSessionEmbedded session) {

    var tx = session.getTransactionInternal();
    if (tx.isActive()) {
      throw new SchemaException(session.getDatabaseName(),
          "Cannot change the schema while a transaction is active. Schema changes are not"
              + " transactional");
    }

    session.executeInTx(transaction -> toStream(session));

    forceSnapshot();
  }

  protected void addCollectionClassMap(final SchemaClassImpl cls) {
    for (var collectionId : cls.getCollectionIds()) {
      if (collectionId < 0) {
        continue;
      }

      collectionsToClasses.put(collectionId, cls);
    }
  }

  private void ensurePropertiesSize(int size) {
    while (properties.size() <= size) {
      properties.add(null);
    }
  }

  public int addBlobCollection(DatabaseSessionEmbedded session, int collectionId) {
    acquireSchemaWriteLock(session);
    try {
      checkCollectionCanBeAdded(session, collectionId, null);
      blobCollections.add(collectionId);
    } finally {
      releaseSchemaWriteLock(session);
    }
    return collectionId;
  }

  public void removeBlobCollection(DatabaseSessionEmbedded session, String collectionName) {
    acquireSchemaWriteLock(session);
    try {
      var collectionId = getCollectionId(session, collectionName);
      blobCollections.remove(collectionId);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected static int getCollectionId(DatabaseSessionEmbedded session, final String stringValue) {
    int clId;
    try {
      clId = Integer.parseInt(stringValue);
    } catch (NumberFormatException ignore) {
      clId = session.getCollectionIdByName(stringValue);
    }
    return clId;
  }

  public IntSet getBlobCollections() {
    acquireSchemaReadLock();
    try {
      return IntSets.unmodifiable(blobCollections);
    } finally {
      releaseSchemaReadLock();
    }

  }
}
