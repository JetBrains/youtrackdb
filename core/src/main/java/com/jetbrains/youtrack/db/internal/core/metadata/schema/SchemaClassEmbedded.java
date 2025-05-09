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
import java.util.Map;

public class SchemaClassEmbedded extends SchemaClassImpl {

  protected SchemaClassEmbedded(SchemaShared iOwner, String iName) {
    super(iOwner, iName);
  }

  protected SchemaClassEmbedded(SchemaShared iOwner, String iName, int[] iCollectionIds) {
    super(iOwner, iName, iCollectionIds);
  }

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
      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void removeSubClassInternal(DatabaseSessionInternal session,
      final SchemaClassImpl baseClass) {
    removeBaseClassInternal(session, baseClass);
  }

  @Override
  @Deprecated // please use removeSubClassInternal instead
  public void removeBaseClassInternal(DatabaseSessionInternal session,
      final SchemaClassImpl subClass) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      if (subclasses.isEmpty()) {
        return;
      }

      var removedClass = subclasses.remove(subClass.getName(session));
      if (removedClass != null) {
        removePolymorphicCollectionIds(session, subClass);
      }
      owner.markClassDirty(session, this);
      owner.markClassDirty(session, subClass);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

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

      var superClassName = superClass.getName(session);
      if (superClassName.equals(SchemaClassProxy.VERTEX_CLASS_NAME) ||
          superClassName.equals(SchemaClassProxy.EDGE_CLASS_NAME)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot add the class '"
                + superClassName
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

      if (superClasses.containsKey(superClassName)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class: '"
                + this.getName(session)
                + "' already has the class '"
                + superClassName
                + "' as superclass");
      }

      superClass.addSubClass(session, this);
      superClasses.put(superClassName, owner.getLazyClass(superClassName));

      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

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

      if (superClasses.containsKey(cls.getName(session))) {
        cls.removeBaseClassInternal(session, this);

        superClasses.remove(superClass.getName(session));
        owner.markClassDirty(session, this);
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

    Map<String, LazySchemaClass> newSuperClasses = new HashMap<>();
    for (var superClass : classes) {
      var className = superClass.getName(session);
      if (newSuperClasses.containsKey(className)) {
        throw new SchemaException(session.getDatabaseName(),
            "Duplicated superclass '" + className + "'");
      }

      newSuperClasses.put(className, owner.getLazyClass(className));
    }

    Map<String, LazySchemaClass> classesToAdd = new HashMap<>();
    for (var potentialSuperClass : newSuperClasses.entrySet()) {
      if (!superClasses.containsKey(potentialSuperClass.getKey())) {
        classesToAdd.put(potentialSuperClass.getKey(), potentialSuperClass.getValue());
      }
    }

    Map<String, LazySchemaClass> classesToRemove = new HashMap<>();
    for (var potentialSuperClass : superClasses.entrySet()) {
      if (!newSuperClasses.containsKey(potentialSuperClass.getKey())) {
        classesToRemove.put(potentialSuperClass.getKey(), potentialSuperClass.getValue());
      }
    }

    for (var toRemove : classesToRemove.values()) {
      toRemove.loadIfNeeded(session);
      toRemove.getDelegate().removeBaseClassInternal(session, this);
    }
    for (var toAdd : classesToAdd.values()) {
      toAdd.loadIfNeeded(session);
      toAdd.getDelegate().addSubClass(session, this);
    }
    superClasses.clear();
    superClasses.putAll(newSuperClasses);
  }

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
      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

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
      owner.markClassDirty(session, this);

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
      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

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
      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

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
      owner.markClassDirty(session, this);
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
      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

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
      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setAbstractInternal(DatabaseSessionInternal session, final boolean isAbstract) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      if (isAbstract) {
        // SWITCH TO ABSTRACT
        if (defaultCollectionId != NOT_EXISTENT_COLLECTION_ID) {
          // CHECK
          if (count(session) > 0) {
            throw new IllegalStateException(
                "Cannot set the class as abstract because contains records.");
          }

          tryDropCollection(session, defaultCollectionId);
          for (var collectionId : getCollectionIds(session)) {
            tryDropCollection(session, collectionId);
            removePolymorphicCollectionId(session, collectionId);
            ((SchemaEmbedded) owner).removeCollectionForClass(session, collectionId);
          }

          setCollectionIds(new int[]{NOT_EXISTENT_COLLECTION_ID});

          defaultCollectionId = NOT_EXISTENT_COLLECTION_ID;
        }
      } else {
        if (!abstractClass) {
          return;
        }

        var collectionId = session.getCollectionIdByName(name);
        if (collectionId == -1) {
          collectionId = session.addCollection(name);
        }

        this.defaultCollectionId = collectionId;
        this.collectionIds[0] = this.defaultCollectionId;
        this.polymorphicCollectionIds = Arrays.copyOf(collectionIds, collectionIds.length);
        for (var clazz : getAllSubclasses(session)) {
          if (clazz instanceof SchemaClassImpl) {
            addPolymorphicCollectionIds(session, clazz);
          } else {
            LogManager.instance()
                .warn(this, "Warning: cannot set polymorphic collection IDs for class " + name);
          }
        }
      }

      this.abstractClass = isAbstract;
      owner.markClassDirty(session, this);
    } finally {
      releaseSchemaWriteLock(session);
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
      owner.markClassDirty(session, this);
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

    for (var superClass : superClasses.values()) {
      superClass.loadIfNeeded(session);
      ((SchemaClassEmbedded) superClass.getDelegate()).addPolymorphicCollectionId(session,
          collectionId);
    }
  }

  protected void addCollectionIdToIndexes(DatabaseSessionInternal session, int iId) {
    var collectionName = session.getCollectionNameById(iId);
    final List<String> indexesToAdd = new ArrayList<>();

    for (var index : getIndexesInternal(session)) {
      indexesToAdd.add(index.getName());
    }

    final var indexManager =
        ((DatabaseSessionEmbedded) session).getSharedContext().getIndexManager();
    for (var indexName : indexesToAdd) {
      indexManager.addCollectionToIndex(session, collectionName, indexName);
    }
  }
}
