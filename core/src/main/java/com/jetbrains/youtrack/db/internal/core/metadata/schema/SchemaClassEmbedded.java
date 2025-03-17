package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.api.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.ScenarioThreadLocal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 *
 */
public class SchemaClassEmbedded extends SchemaClassImpl {

  protected SchemaClassEmbedded(SchemaShared iOwner, String iName) {
    super(iOwner, iName);
  }

  protected SchemaClassEmbedded(SchemaShared iOwner, String iName, int[] iClusterIds) {
    super(iOwner, iName, iClusterIds);
  }

  public SchemaProperty addProperty(
      DatabaseSessionInternal session, final String propertyName,
      final PropertyType type,
      final PropertyType linkedType,
      final SchemaClass linkedClass,
      final boolean unsafe) {
    if (type == null) {
      throw new SchemaException("Property type not defined.");
    }

    if (propertyName == null || propertyName.isEmpty()) {
      throw new SchemaException("Property name is null or empty");
    }

    validatePropertyName(propertyName);
    if (session.getTransaction().isActive()) {
      throw new SchemaException(
          "Cannot create property '" + propertyName + "' inside a transaction");
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    if (linkedType != null) {
      SchemaPropertyImpl.checkLinkTypeSupport(type);
    }

    if (linkedClass != null) {
      SchemaPropertyImpl.checkSupportLinkedClass(type);
    }

    acquireSchemaWriteLock(session);
    try {
      return addPropertyInternal(session, propertyName, type,
          linkedType, linkedClass, unsafe);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public SchemaClass setClusterSelection(DatabaseSession session, final String value) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      setClusterSelectionInternal(database, value);
      return this;
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  public void setClusterSelectionInternal(DatabaseSessionInternal session,
      final String clusterSelection) {
    // AVOID TO CHECK THIS IN LOCK TO AVOID RE-GENERATION OF IMMUTABLE SCHEMAS
    if (this.clusterSelection.getName().equals(clusterSelection))
    // NO CHANGES
    {
      return;
    }

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded();

      this.clusterSelection = owner.getClusterSelectionFactory().newInstance(clusterSelection);
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public SchemaClassImpl setCustom(DatabaseSession session, final String name, final String value) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      setCustomInternal(database, name, value);
      return this;
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  public void clearCustom(DatabaseSession session) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      clearCustomInternal(database);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  protected void clearCustomInternal(DatabaseSessionInternal session) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded();

      customFields = null;
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public SchemaClass setSuperClasses(DatabaseSession session,
      final List<? extends SchemaClass> classes) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    if (classes != null) {
      List<SchemaClass> toCheck = new ArrayList<SchemaClass>(classes);
      toCheck.add(this);
      checkParametersConflict(toCheck);
    }
    acquireSchemaWriteLock(database);
    try {
      setSuperClassesInternal(database, classes);
    } finally {
      releaseSchemaWriteLock(database);
    }
    return this;
  }

  public SchemaClass removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClass baseClass) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded();

      if (subclasses.isEmpty()) {
        return this;
      }

      if (subclasses.remove(baseClass)) {
        removePolymorphicClusterIds(session, (SchemaClassImpl) baseClass);
      }
      owner.markClassDirty(this);
      owner.markClassDirty(baseClass);

      return this;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public SchemaClass addSuperClass(DatabaseSession session, final SchemaClass superClass) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkParametersConflict(database, superClass);
    acquireSchemaWriteLock(database);
    try {
      addSuperClassInternal(database, superClass);
    } finally {
      releaseSchemaWriteLock(database);
    }
    return this;
  }

  protected void addSuperClassInternal(DatabaseSessionInternal database,
      final SchemaClass superClass) {
    acquireSchemaWriteLock(database);
    try {
      final SchemaClassImpl cls;
      cls = (SchemaClassImpl) superClass;

      if (cls != null) {

        // CHECK THE USER HAS UPDATE PRIVILEGE AGAINST EXTENDING CLASS
        final SecurityUser user = database.geCurrentUser();
        if (user != null) {
          user.allow(database, Rule.ResourceGeneric.CLASS, cls.getName(), Role.PERMISSION_UPDATE);
        }

        if (superClasses.contains(superClass)) {
          throw new SchemaException(
              "Class: '"
                  + this.getName()
                  + "' already has the class '"
                  + superClass.getName()
                  + "' as superclass");
        }

        cls.addBaseClass(database, this);
        superClasses.add(cls);
        owner.markClassDirty(this);
      }
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  @Override
  public void removeSuperClass(DatabaseSession session, SchemaClass superClass) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(database);
    try {
      removeSuperClassInternal(database, superClass);

    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  protected void removeSuperClassInternal(DatabaseSessionInternal session,
      final SchemaClass superClass) {
    acquireSchemaWriteLock(session);
    try {
      final SchemaClassImpl cls;
      cls = (SchemaClassImpl) superClass;

      if (superClasses.contains(cls)) {
        if (cls != null) {
          cls.removeBaseClassInternal(session, this);
        }

        superClasses.remove(superClass);
        owner.markClassDirty(this);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setSuperClassesInternal(DatabaseSessionInternal session,
      final List<? extends SchemaClass> classes) {
    List<SchemaClassImpl> newSuperClasses = new ArrayList<SchemaClassImpl>();
    SchemaClassImpl cls;
    for (SchemaClass superClass : classes) {
      cls = (SchemaClassImpl) superClass;
      if (newSuperClasses.contains(cls)) {
        throw new SchemaException("Duplicated superclass '" + cls.getName() + "'");
      }

      newSuperClasses.add(cls);
    }

    List<SchemaClassImpl> toAddList = new ArrayList<SchemaClassImpl>(newSuperClasses);
    toAddList.removeAll(superClasses);
    List<SchemaClassImpl> toRemoveList = new ArrayList<SchemaClassImpl>(superClasses);
    toRemoveList.removeAll(newSuperClasses);

    for (SchemaClassImpl toRemove : toRemoveList) {
      toRemove.removeBaseClassInternal(session, this);
    }
    for (SchemaClassImpl addTo : toAddList) {
      addTo.addBaseClass(session, this);
    }
    superClasses.clear();
    superClasses.addAll(newSuperClasses);
  }

  public SchemaClass setName(DatabaseSession session, final String name) {
    if (getName().equals(name)) {
      return this;
    }
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    final Character wrongCharacter = SchemaShared.checkClassNameIfValid(name);
    SchemaClass oClass = database.getMetadata().getSchema().getClass(name);
    if (oClass != null) {
      String error =
          String.format(
              "Cannot rename class %s to %s. A Class with name %s exists", this.name, name, name);
      throw new SchemaException(error);
    }
    //noinspection ConstantValue
    if (wrongCharacter != null) {
      throw new SchemaException(
          "Invalid class name found. Character '"
              + wrongCharacter
              + "' cannot be used in class name '"
              + name
              + "'");
    }
    acquireSchemaWriteLock(database);
    try {
      setNameInternal(database, name);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return this;
  }

  protected void setNameInternal(DatabaseSessionInternal database, final String name) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(database);
    try {
      checkEmbedded();
      final String oldName = this.name;
      owner.changeClassName(database, this.name, name, this);
      this.name = name;
      renameCluster(database, oldName, this.name);
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  public void setDefaultClusterId(DatabaseSession session, final int defaultClusterId) {
    var sessionInternal = (DatabaseSessionInternal) session;
    acquireSchemaWriteLock(sessionInternal);
    try {
      checkEmbedded();
      this.defaultClusterId = defaultClusterId;
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(sessionInternal);
    }
  }

  public SchemaClass setShortName(DatabaseSession session, String shortName) {
    if (shortName != null) {
      shortName = shortName.trim();
      if (shortName.isEmpty()) {
        shortName = null;
      }
    }
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      setShortNameInternal(database, shortName);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return this;
  }

  protected void setShortNameInternal(DatabaseSessionInternal database, final String iShortName) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      checkEmbedded();

      String oldName = null;

      if (this.shortName != null) {
        oldName = this.shortName;
      }

      owner.changeClassName(database, oldName, iShortName, this);

      this.shortName = iShortName;
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  protected SchemaPropertyImpl createPropertyInstance() {
    return new SchemaPropertyEmbedded(this);
  }

  public SchemaPropertyImpl addPropertyInternal(
      DatabaseSessionInternal session, final String name,
      final PropertyType type,
      final PropertyType linkedType,
      final SchemaClass linkedClass,
      final boolean unsafe) {
    if (name == null || name.isEmpty()) {
      throw new SchemaException("Found property name null");
    }

    if (!unsafe) {
      checkPersistentPropertyType(session, name, type, linkedClass);
    }

    final SchemaPropertyEmbedded prop;

    // This check are doubled because used by sql commands
    if (linkedType != null) {
      SchemaPropertyImpl.checkLinkTypeSupport(type);
    }

    if (linkedClass != null) {
      SchemaPropertyImpl.checkSupportLinkedClass(type);
    }

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded();

      if (properties.containsKey(name)) {
        throw new SchemaException(
            "Class '" + this.name + "' already has property '" + name + "'");
      }

      GlobalProperty global = owner.findOrCreateGlobalProperty(name, type);

      prop = createPropertyInstance(global);

      properties.put(name, prop);
      owner.markClassDirty(this);

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

  protected SchemaPropertyEmbedded createPropertyInstance(GlobalProperty global) {
    return new SchemaPropertyEmbedded(this, global);
  }

  /**
   * {@inheritDoc}
   */
  public SchemaClass truncateCluster(DatabaseSession session, String clusterName) {
    var database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.CLASS, Role.PERMISSION_DELETE, name);

    truncateClusterInternal(clusterName, database);

    return this;
  }

  public SchemaClass setStrictMode(DatabaseSession session, final boolean isStrict) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      setStrictModeInternal(database, isStrict);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return this;
  }

  protected void setStrictModeInternal(DatabaseSessionInternal session, final boolean iStrict) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      checkEmbedded();

      this.strictMode = iStrict;
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public SchemaClass setDescription(DatabaseSession session, String iDescription) {
    if (iDescription != null) {
      iDescription = iDescription.trim();
      if (iDescription.isEmpty()) {
        iDescription = null;
      }
    }
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      setDescriptionInternal(database, iDescription);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return this;
  }

  protected void setDescriptionInternal(DatabaseSessionInternal session,
      final String iDescription) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded();
      this.description = iDescription;
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  public SchemaClass addClusterId(DatabaseSession session, final int clusterId) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    if (isAbstract()) {
      throw new SchemaException("Impossible to associate a cluster to an abstract class class");
    }

    acquireSchemaWriteLock(database);
    try {
      addClusterIdInternal(database, clusterId);
    } finally {
      releaseSchemaWriteLock(database);
    }
    return this;
  }

  public SchemaClass removeClusterId(DatabaseSession session, final int clusterId) {
    return removeClusterId((DatabaseSessionInternal) session, clusterId, false);
  }

  public SchemaClass removeClusterId(DatabaseSessionInternal session, final int clusterId,
      boolean force) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    if (!force && clusterIds.length == 1 && clusterId == clusterIds[0]) {
      throw new DatabaseException(
          " Impossible to remove the last cluster of class '"
              + getName()
              + "' drop the class instead");
    }

    acquireSchemaWriteLock(session);
    try {
      removeClusterIdInternal(session, clusterId);
    } finally {
      releaseSchemaWriteLock(session);
    }

    return this;
  }

  protected void removeClusterIdInternal(
      DatabaseSessionInternal database, final int clusterToRemove) {
    acquireSchemaWriteLock(database);
    try {
      checkEmbedded();

      boolean found = false;
      for (int clusterId : clusterIds) {
        if (clusterId == clusterToRemove) {
          found = true;
          break;
        }
      }

      if (found) {
        final int[] newClusterIds = new int[clusterIds.length - 1];
        for (int i = 0, k = 0; i < clusterIds.length; ++i) {
          if (clusterIds[i] == clusterToRemove)
          // JUMP IT
          {
            continue;
          }

          newClusterIds[k] = clusterIds[i];
          k++;
        }
        clusterIds = newClusterIds;

        removePolymorphicClusterId(database, clusterToRemove);
      }

      if (defaultClusterId == clusterToRemove) {
        if (clusterIds.length >= 1) {
          defaultClusterId = clusterIds[0];
        } else {
          defaultClusterId = NOT_EXISTENT_CLUSTER_ID;
        }
      }

      ((SchemaEmbedded) owner).removeClusterForClass(database, clusterToRemove);
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }

  }

  public void dropProperty(DatabaseSession session, final String propertyName) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    if (database.getTransaction().isActive()) {
      throw new IllegalStateException("Cannot drop a property inside a transaction");
    }

    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    acquireSchemaWriteLock(database);
    try {
      if (!properties.containsKey(propertyName)) {
        throw new SchemaException(
            "Property '" + propertyName + "' not found in class " + name + "'");
      }

      ScenarioThreadLocal.executeAsDistributed(
          (Callable<SchemaProperty>)
              () -> {
                dropPropertyInternal(database, propertyName);
                return null;
              });

      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  protected void dropPropertyInternal(
      DatabaseSessionInternal database, final String iPropertyName) {
    if (database.getTransaction().isActive()) {
      throw new IllegalStateException("Cannot drop a property inside a transaction");
    }
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    acquireSchemaWriteLock(database);
    try {
      checkEmbedded();

      final SchemaProperty prop = properties.remove(iPropertyName);

      if (prop == null) {
        throw new SchemaException(
            "Property '" + iPropertyName + "' not found in class " + name + "'");
      }
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  @Override
  public SchemaClass addCluster(DatabaseSession session, final String clusterNameOrId) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    if (isAbstract()) {
      throw new SchemaException("Impossible to associate a cluster to an abstract class class");
    }

    acquireSchemaWriteLock(database);
    try {
      final int clusterId = owner.createClusterIfNeeded(database, clusterNameOrId);
      addClusterIdInternal(database, clusterId);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return this;
  }

  public SchemaClass setOverSize(DatabaseSession session, final float overSize) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(database);
    try {
      setOverSizeInternal(database, overSize);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return this;
  }

  protected void setOverSizeInternal(DatabaseSessionInternal database, final float overSize) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(database);
    try {
      checkEmbedded();

      this.overSize = overSize;
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  public SchemaClass setAbstract(DatabaseSession session, boolean isAbstract) {
    final DatabaseSessionInternal database = (DatabaseSessionInternal) session;
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      setAbstractInternal(database, isAbstract);
    } finally {
      releaseSchemaWriteLock(database);
    }

    return this;
  }

  protected void setCustomInternal(DatabaseSessionInternal session, final String name,
      final String value) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded();

      if (customFields == null) {
        customFields = new HashMap<String, String>();
      }
      if (value == null || "null".equalsIgnoreCase(value)) {
        customFields.remove(name);
      } else {
        customFields.put(name, value);
      }
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setAbstractInternal(DatabaseSessionInternal database, final boolean isAbstract) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      if (isAbstract) {
        // SWITCH TO ABSTRACT
        if (defaultClusterId != NOT_EXISTENT_CLUSTER_ID) {
          // CHECK
          if (count(database) > 0) {
            throw new IllegalStateException(
                "Cannot set the class as abstract because contains records.");
          }

          tryDropCluster(database, defaultClusterId);
          for (int clusterId : getClusterIds()) {
            tryDropCluster(database, clusterId);
            removePolymorphicClusterId(database, clusterId);
            ((SchemaEmbedded) owner).removeClusterForClass(database, clusterId);
          }

          setClusterIds(new int[]{NOT_EXISTENT_CLUSTER_ID});

          defaultClusterId = NOT_EXISTENT_CLUSTER_ID;
        }
      } else {
        if (!abstractClass) {
          return;
        }

        int clusterId = database.getClusterIdByName(name);
        if (clusterId == -1) {
          clusterId = database.addCluster(name);
        }

        this.defaultClusterId = clusterId;
        this.clusterIds[0] = this.defaultClusterId;
        this.polymorphicClusterIds = Arrays.copyOf(clusterIds, clusterIds.length);
        for (SchemaClass clazz : getAllSubclasses()) {
          if (clazz instanceof SchemaClassImpl) {
            addPolymorphicClusterIds(database, (SchemaClassImpl) clazz);
          } else {
            LogManager.instance()
                .warn(this, "Warning: cannot set polymorphic cluster IDs for class " + name);
          }
        }
      }

      this.abstractClass = isAbstract;
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  private void tryDropCluster(DatabaseSessionInternal session, final int clusterId) {
    if (name.toLowerCase(Locale.ENGLISH).equals(session.getClusterNameById(clusterId))) {
      // DROP THE DEFAULT CLUSTER CALLED WITH THE SAME NAME ONLY IF EMPTY
      if (session.countClusterElements(clusterId) == 0) {
        session.dropClusterInternal(clusterId);
      }
    }
  }

  protected void addClusterIdInternal(DatabaseSessionInternal database, final int clusterId) {
    acquireSchemaWriteLock(database);
    try {
      checkEmbedded();

      owner.checkClusterCanBeAdded(clusterId, this);

      for (int currId : clusterIds) {
        if (currId == clusterId)
        // ALREADY ADDED
        {
          return;
        }
      }

      clusterIds = ArrayUtils.copyOf(clusterIds, clusterIds.length + 1);
      clusterIds[clusterIds.length - 1] = clusterId;
      Arrays.sort(clusterIds);

      addPolymorphicClusterId(database, clusterId);

      if (defaultClusterId == NOT_EXISTENT_CLUSTER_ID) {
        defaultClusterId = clusterId;
      }

      ((SchemaEmbedded) owner).addClusterForClass(database, clusterId, this);
      owner.markClassDirty(this);
    } finally {
      releaseSchemaWriteLock(database);
    }
  }

  protected void addPolymorphicClusterId(DatabaseSessionInternal session, int clusterId) {
    if (Arrays.binarySearch(polymorphicClusterIds, clusterId) >= 0) {
      return;
    }

    polymorphicClusterIds = ArrayUtils.copyOf(polymorphicClusterIds,
        polymorphicClusterIds.length + 1);
    polymorphicClusterIds[polymorphicClusterIds.length - 1] = clusterId;
    Arrays.sort(polymorphicClusterIds);

    addClusterIdToIndexes(session, clusterId);

    for (SchemaClassImpl superClass : superClasses) {
      ((SchemaClassEmbedded) superClass).addPolymorphicClusterId(session, clusterId);
    }
  }

  protected void addClusterIdToIndexes(DatabaseSessionInternal session, int iId) {
    var clusterName = session.getClusterNameById(iId);
    final List<String> indexesToAdd = new ArrayList<String>();

    for (Index index : getIndexesInternal(session)) {
      indexesToAdd.add(index.getName());
    }

    final IndexManagerAbstract indexManager =
        session.getMetadata().getIndexManagerInternal();
    for (String indexName : indexesToAdd) {
      indexManager.addClusterToIndex(session, clusterName, indexName);
    }
  }
}
