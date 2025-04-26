package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.ArrayUtils;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class SchemaClassEmbedded extends SchemaClassImpl {

  protected SchemaClassEmbedded(SchemaShared iOwner, String iName) {
    super(iOwner, iName);
  }

  protected SchemaClassEmbedded(SchemaShared iOwner, String iName, int[] iCollectionIds) {
    super(iOwner, iName, iCollectionIds);
  }

  @Override
  public SchemaPropertyImpl addProperty(
      DatabaseSessionInternal session, final String propertyName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassImpl linkedClass,
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
  public void setCustom(DatabaseSessionInternal session, final String name,
      final String value) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setCustomInternal(session, name, value);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void clearCustom(DatabaseSessionInternal session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      clearCustomInternal(session);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void clearCustomInternal(DatabaseSessionInternal session) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      customFields = null;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClassImpl baseClass) {
    acquireSchemaWriteLock(session);
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
      final SchemaClassImpl superClass) {

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkParametersConflict(session, superClass);
    addSuperClassInternal(session, superClass);
  }

  public void addSuperClassInternal(DatabaseSessionInternal session,
      final SchemaClassImpl superClass) {

    acquireSchemaWriteLock(session);
    try {

      if (superClass.getName(session).equals(SchemaClassProxy.VERTEX_CLASS_NAME) ||
          superClass.getName(session).equals(SchemaClassProxy.EDGE_CLASS_NAME)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot add the class '"
                + superClass.getName(session)
                + "' as superclass of the class '"
                + this.getName(session)
                + "'. Addition of graph classes is not allowed");
      }

      // CHECK THE USER HAS UPDATE PRIVILEGE AGAINST EXTENDING CLASS
      final var user = session.getCurrentUser();
      if (user != null) {
        user.allow(session, Rule.ResourceGeneric.CLASS, superClass.getName(session),
            Role.PERMISSION_UPDATE);
      }

      if (superClasses.contains(superClass)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class: '"
                + this.getName(session)
                + "' already has the class '"
                + superClass.getName(session)
                + "' as superclass");
      }

      superClass.addBaseClass(session, this);
      superClasses.add(superClass);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void removeSuperClass(DatabaseSessionInternal session, SchemaClassImpl superClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      removeSuperClassInternal(session, superClass);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void removeSuperClassInternal(DatabaseSessionInternal session,
      final SchemaClassImpl superClass) {
    acquireSchemaWriteLock(session);
    try {
      if (superClass.getName(session).equals(SchemaClassProxy.VERTEX_CLASS_NAME) ||
          superClass.getName(session).equals(SchemaClassProxy.EDGE_CLASS_NAME)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot remove the class '"
                + superClass.getName(session)
                + "' as superclass of the class '"
                + this.getName(session)
                + "'. Removal of graph classes is not allowed");
      }

      final SchemaClassImpl cls;
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
      final List<SchemaClassImpl> classes) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    if (classes != null) {
      List<SchemaClassImpl> toCheck = new ArrayList<>(classes);
      toCheck.add(this);
      checkParametersConflict(session, toCheck);
    }
    acquireSchemaWriteLock(session);
    try {
      setSuperClassesInternal(session, classes);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  @Override
  protected void setSuperClassesInternal(DatabaseSessionInternal session,
      final List<SchemaClassImpl> classes) {
    if (!name.equals(SchemaClass.EDGE_CLASS_NAME) && isEdgeType(session)) {
      if (!classes.contains(owner.getClass(session, SchemaClass.EDGE_CLASS_NAME))) {
        throw new IllegalArgumentException(
            "Edge class must have super class " + SchemaClass.EDGE_CLASS_NAME
                + ", its removal is not allowed.");
      }
    }
    if (!name.equals(SchemaClass.VERTEX_CLASS_NAME) && isVertexType(session)) {
      if (!classes.contains(owner.getClass(session, SchemaClass.VERTEX_CLASS_NAME))) {
        throw new IllegalArgumentException(
            "Vertex class must have super class " + SchemaClass.VERTEX_CLASS_NAME
                + ", its removal is not allowed.");
      }
    }

    List<SchemaClassImpl> newSuperClasses = new ArrayList<>();
    SchemaClassImpl cls;
    for (var superClass : classes) {
      cls = superClass;
      if (newSuperClasses.contains(cls)) {
        throw new SchemaException(session.getDatabaseName(),
            "Duplicated superclass '" + cls.getName(session) + "'");
      }

      newSuperClasses.add(cls);
    }

    List<SchemaClassImpl> toAddList = new ArrayList<>(newSuperClasses);
    toAddList.removeAll(superClasses);
    List<SchemaClassImpl> toRemoveList = new ArrayList<>(superClasses);
    toRemoveList.removeAll(newSuperClasses);

    for (var toRemove : toRemoveList) {
      toRemove.removeBaseClassInternal(session, this);
    }
    for (var addTo : toAddList) {
      addTo.addBaseClass(session, this);
    }
    superClasses.clear();
    superClasses.addAll(newSuperClasses);
  }

  @Override
  public void setName(DatabaseSessionInternal session, final String name) {
    if (getName(session).equals(name)) {
      return;
    }
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    final var wrongCharacter = SchemaShared.checkClassNameIfValid(name);
    var oClass = session.getMetadata().getSchema().getClass(name);
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
    acquireSchemaWriteLock(session);
    try {
      setNameInternal(session, name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNameInternal(DatabaseSessionInternal session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);
      final var oldName = this.name;
      owner.changeClassName(session, this.name, name, this);
      this.name = name;
      renameCollection(session, oldName, this.name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  protected SchemaPropertyImpl createPropertyInstance() {
    return new SchemaPropertyEmbedded(this);
  }

  public SchemaPropertyImpl addPropertyInternal(
      DatabaseSessionInternal session, final String name,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassImpl linkedClass,
      final boolean unsafe) {
    if (name == null || name.isEmpty()) {
      throw new SchemaException(session.getDatabaseName(), "Found property name null");
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

  protected SchemaPropertyEmbedded createPropertyInstance(GlobalProperty global) {
    return new SchemaPropertyEmbedded(this, global);
  }


  @Override
  public void setStrictMode(DatabaseSessionInternal session, final boolean isStrict) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setStrictModeInternal(session, isStrict);
    } finally {
      releaseSchemaWriteLock(session);
    }

  }

  protected void setStrictModeInternal(DatabaseSessionInternal session, final boolean iStrict) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
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

    acquireSchemaWriteLock(session);
    try {
      setDescriptionInternal(session, iDescription);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setDescriptionInternal(DatabaseSessionInternal session,
      final String iDescription) {
    acquireSchemaWriteLock(session);
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

    acquireSchemaWriteLock(session);
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

    acquireSchemaWriteLock(session);
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
    acquireSchemaWriteLock(session);
    try {
      setOverSizeInternal(session, overSize);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setOverSizeInternal(DatabaseSessionInternal session, final float overSize) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
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

    acquireSchemaWriteLock(session);
    try {
      setAbstractInternal(session, isAbstract);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCustomInternal(DatabaseSessionInternal session, final String name,
      final String value) {
    acquireSchemaWriteLock(session);
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

    acquireSchemaWriteLock(database);
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
          for (var collectionId : getCollectionIds(database)) {
            tryDropCollection(database, collectionId);
            removePolymorphicCollectionId(database, collectionId);
            ((SchemaEmbedded) owner).removeCollectionForClass(database, collectionId);
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
        for (var clazz : getAllSubclasses(database)) {
          if (clazz instanceof SchemaClassImpl) {
            addPolymorphicCollectionIds(database, clazz);
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

  private void tryDropCollection(DatabaseSessionInternal session, final int collectionId) {
    if (name.toLowerCase(Locale.ENGLISH).equals(session.getCollectionNameById(collectionId))) {
      // DROP THE DEFAULT COLLECTION CALLED WITH THE SAME NAME ONLY IF EMPTY
      if (session.countCollectionElements(collectionId) == 0) {
        session.dropCollectionInternal(collectionId);
      }
    }
  }

  protected void addCollectionIdInternal(DatabaseSessionInternal session, final int collectionId) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      owner.checkCollectionCanBeAdded(session, collectionId, this);

      for (var currId : collectionIds) {
        if (currId == collectionId)
        // ALREADY ADDED
        {
          return;
        }
      }

      collectionIds = ArrayUtils.copyOf(collectionIds, collectionIds.length + 1);
      collectionIds[collectionIds.length - 1] = collectionId;
      Arrays.sort(collectionIds);

      addPolymorphicCollectionId(session, collectionId);

      if (defaultCollectionId == NOT_EXISTENT_COLLECTION_ID) {
        defaultCollectionId = collectionId;
      }

      ((SchemaEmbedded) owner).addCollectionForClass(session, collectionId, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void addPolymorphicCollectionId(DatabaseSessionInternal session, int collectionId) {
    if (Arrays.binarySearch(polymorphicCollectionIds, collectionId) >= 0) {
      return;
    }

    polymorphicCollectionIds = ArrayUtils.copyOf(polymorphicCollectionIds,
        polymorphicCollectionIds.length + 1);
    polymorphicCollectionIds[polymorphicCollectionIds.length - 1] = collectionId;
    Arrays.sort(polymorphicCollectionIds);

    addCollectionIdToIndexes(session, collectionId);

    for (var superClass : superClasses) {
      ((SchemaClassEmbedded) superClass).addPolymorphicCollectionId(session, collectionId);
    }
  }

  @Override
  protected void addCollectionIdToIndexes(DatabaseSessionInternal session, int iId) {
    var collectionName = session.getCollectionNameById(iId);
    final List<String> indexesToAdd = new ArrayList<>();

    for (var index : getIndexesInternal(session)) {
      indexesToAdd.add(index.getName());
    }

    final var indexManager =
        session.getSharedContext().getIndexManager();
    for (var indexName : indexesToAdd) {
      indexManager.addCollectionToIndex(session, collectionName, indexName);
    }
  }
}
