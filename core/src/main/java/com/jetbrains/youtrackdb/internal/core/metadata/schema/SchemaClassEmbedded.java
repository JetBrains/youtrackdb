package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.ArrayUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
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
      DatabaseSessionEmbedded session, final String propertyName,
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
  public void setCustom(DatabaseSessionEmbedded session, final String name,
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
  public void clearCustom(DatabaseSessionEmbedded session) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      clearCustomInternal(session);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void clearCustomInternal(DatabaseSessionEmbedded session) {
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);

      customFields = null;
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void removeBaseClassInternal(DatabaseSessionEmbedded session,
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
  public void addSuperClass(DatabaseSessionEmbedded session,
      final SchemaClassImpl superClass) {

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    checkParametersConflict(session, superClass);
    addSuperClassInternal(session, superClass);
  }

  public void addSuperClassInternal(DatabaseSessionEmbedded session,
      final SchemaClassImpl superClass) {

    acquireSchemaWriteLock(session);
    try {

      if (superClass.getName().equals(SchemaClassProxy.VERTEX_CLASS_NAME) ||
          superClass.getName().equals(SchemaClassProxy.EDGE_CLASS_NAME)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot add the class '"
                + superClass.getName()
                + "' as superclass of the class '"
                + this.getName()
                + "'. Addition of graph classes is not allowed");
      }

      // CHECK THE USER HAS UPDATE PRIVILEGE AGAINST EXTENDING CLASS
      final var user = session.getCurrentUser();
      if (user != null) {
        user.allow(session, Rule.ResourceGeneric.CLASS, superClass.getName(),
            Role.PERMISSION_UPDATE);
      }

      if (superClasses.contains(superClass)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class: '"
                + this.getName()
                + "' already has the class '"
                + superClass.getName()
                + "' as superclass");
      }

      superClass.addBaseClass(session, this, true);
      superClasses.add(superClass);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  public void removeSuperClass(DatabaseSessionEmbedded session, SchemaClassImpl superClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      removeSuperClassInternal(session, superClass);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void removeSuperClassInternal(DatabaseSessionEmbedded session,
      final SchemaClassImpl superClass) {
    acquireSchemaWriteLock(session);
    try {
      if (superClass.getName().equals(SchemaClassProxy.VERTEX_CLASS_NAME) ||
          superClass.getName().equals(SchemaClassProxy.EDGE_CLASS_NAME)) {
        throw new SchemaException(session.getDatabaseName(),
            "Cannot remove the class '"
                + superClass.getName()
                + "' as superclass of the class '"
                + this.getName()
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
  public void setSuperClasses(DatabaseSessionEmbedded session,
      final List<SchemaClassImpl> classes) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    if (classes != null) {
      List<SchemaClassImpl> toCheck = new ArrayList<>(classes);
      toCheck.add(this);
      checkParametersConflict(session, toCheck);
    }
    acquireSchemaWriteLock(session);
    try {
      setSuperClassesInternal(session, classes, true);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  protected void setSuperClassesInternal(DatabaseSessionEmbedded session,
      final List<SchemaClassImpl> classes, boolean validateIndexes) {
    if (!name.equals(SchemaClass.EDGE_CLASS_NAME) && isEdgeType()) {
      if (!classes.contains(owner.getClass(SchemaClass.EDGE_CLASS_NAME))) {
        throw new IllegalArgumentException(
            "Edge class must have super class " + SchemaClass.EDGE_CLASS_NAME
                + ", its removal is not allowed.");
      }
    }
    if (!name.equals(SchemaClass.VERTEX_CLASS_NAME) && isVertexType()) {
      if (!classes.contains(owner.getClass(SchemaClass.VERTEX_CLASS_NAME))) {
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
            "Duplicated superclass '" + cls.getName() + "'");
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
      addTo.addBaseClass(session, this, validateIndexes);
    }
    superClasses.clear();
    superClasses.addAll(newSuperClasses);
  }

  @Override
  public void setName(DatabaseSessionEmbedded session, final String name) {
    if (getName().equals(name)) {
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

  protected void setNameInternal(DatabaseSessionEmbedded session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      checkEmbedded(session);
      final var oldName = this.name;
      owner.changeClassName(session, this.name, name, this);
      this.name = name;
      // A class rename is metadata-only: collection names are generated from a counter alone
      // (c_<counter>, no class-name component) and engine files are keyed by ie_<fileBaseId>
      // stems, so no storage file is touched. What DOES follow the rename is the index
      // re-association: the class's indexes must re-key from the old to the new class name
      // (classPropertyIndex + each definition's className) or they stop accelerating and stop
      // being maintained. On the transactional path the re-association is recorded on the
      // overlay and applied commit-only; on the legacy top-level path it applies eagerly, like
      // every other non-transactional DDL.
      if (owner.txLocal) {
        // The seeding guard mirrors the create and drop sites: copyForTx re-parses committed
        // classes through other paths and never through a rename, but the guard keeps the
        // recording uniform.
        if (!session.isSeedingTxSchemaState()) {
          final var txState = session.getTxSchemaState();
          if (txState == null) {
            throw new IllegalStateException(
                "a tx-local rename must run with a seeded tx-local schema state");
          }
          txState.ensureIndexOverlay().recordClassRenamed(oldName, name);
        }
      } else {
        session.getSharedContext().getIndexManager()
            .reassociateClassIndexesOnRename(session, oldName, name);
      }
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  @Override
  protected SchemaPropertyImpl createPropertyInstance() {
    return new SchemaPropertyEmbedded(this);
  }

  public SchemaPropertyImpl addPropertyInternal(
      DatabaseSessionEmbedded session, final String name,
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

  protected SchemaPropertyEmbedded createPropertyInstance(GlobalPropertyImpl global) {
    return new SchemaPropertyEmbedded(this, global);
  }

  @Override
  public void setStrictMode(DatabaseSessionEmbedded session, final boolean isStrict) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setStrictModeInternal(session, isStrict);
    } finally {
      releaseSchemaWriteLock(session);
    }

  }

  protected void setStrictModeInternal(DatabaseSessionEmbedded session, final boolean iStrict) {
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
  public void setDescription(DatabaseSessionEmbedded session, String iDescription) {
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

  protected void setDescriptionInternal(DatabaseSessionEmbedded session,
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
  public void dropProperty(DatabaseSessionEmbedded session, final String propertyName) {
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
      DatabaseSessionEmbedded session, final String iPropertyName) {
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

  public void setOverSize(DatabaseSessionEmbedded session, final float overSize) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock(session);
    try {
      setOverSizeInternal(session, overSize);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setOverSizeInternal(DatabaseSessionEmbedded session, final float overSize) {
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
  public void setAbstract(DatabaseSessionEmbedded session, boolean isAbstract) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(session);
    try {
      setAbstractInternal(session, isAbstract);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setCustomInternal(DatabaseSessionEmbedded session, final String name,
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

  protected void setAbstractInternal(DatabaseSessionEmbedded database, final boolean isAbstract) {
    database.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);

    acquireSchemaWriteLock(database);
    try {
      if (isAbstract) {
        // SWITCH TO ABSTRACT
        if (defaultCollectionId != NOT_EXISTENT_COLLECTION_ID) {
          // CHECK
          if (database.computeInTxInternal(tx -> count(database)) > 0) {
            throw new IllegalStateException(
                "Cannot set the class as abstract because contains records.");
          }

          tryDropCollection(database, defaultCollectionId);
          for (var collectionId : getCollectionIds()) {
            tryDropCollection(database, collectionId);
            removePolymorphicCollectionId(database, collectionId);
            ((SchemaEmbedded) owner).removeCollectionForClass(database, collectionId);
          }

          setCollectionIds(new int[] {NOT_EXISTENT_COLLECTION_ID});

          defaultCollectionId = NOT_EXISTENT_COLLECTION_ID;
        }
      } else {
        if (!abstractClass) {
          return;
        }

        // Inside a schema transaction the abstract->concrete switch must not allocate a real
        // storage collection eagerly: the self-committing addCollection would leave a stray
        // collection on disk if the transaction rolls back, the same metadata-first inversion the
        // tx-local create path applies. The class carries a provisional id (<= -2) the commit
        // resolves to a real id once it creates the real collection inside the commit's own atomic
        // operation. The seeding guard mirrors the create and rename sites: copyForTx -> fromStream
        // re-creates committed classes through other paths and never through setAbstract, but the
        // guard keeps the recording uniform. Outside a transaction (or while seeding) the legacy
        // eager allocation is kept: there is no user transaction to defer the create to.
        var collectionName = owner.nextCollectionName(database);
        final boolean provisional = owner.txLocal && !database.isSeedingTxSchemaState();
        final int collectionId;
        if (provisional) {
          var txState = database.getTxSchemaState();
          if (txState == null) {
            throw new IllegalStateException(
                "a tx-local abstract-to-concrete alter must run with a seeded tx-local schema state");
          }
          // Carry the generated name with the provisional id: the commit creates the real
          // collection under this name (the tx-local counter has advanced past it by commit time).
          collectionId = txState.allocateProvisionalCollectionId(collectionName);
          // Record the altered class so the commit writes its per-class record and reconciles the
          // provisional id to a real collection. The create path records the same way after a
          // tx-local createClass.
          txState.markClassChanged(name);
        } else {
          collectionId = database.addCollection(collectionName);
        }

        this.defaultCollectionId = collectionId;
        this.collectionIds[0] = this.defaultCollectionId;
        this.polymorphicCollectionIds = Arrays.copyOf(collectionIds, collectionIds.length);
        for (var clazz : getAllSubclasses()) {
          if (clazz instanceof SchemaClassImpl) {
            addPolymorphicCollectionIds(database, clazz, true);
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

  private void tryDropCollection(DatabaseSessionEmbedded session, final int collectionId) {
    var collectionName = session.getCollectionNameById(collectionId);
    if (name.toLowerCase(Locale.ENGLISH).equals(collectionName)) {
      // DROP THE DEFAULT COLLECTION CALLED WITH THE SAME NAME ONLY IF EMPTY
      // O(1) emptiness check via iterator instead of O(n) count
      if (session.computeInTxInternal(tx -> {
        try (var iter = session.browseCollection(collectionName)) {
          return !iter.hasNext();
        }
      })) {
        session.dropCollectionInternal(collectionId);
      }
    }
  }

  protected void addCollectionIdInternal(DatabaseSessionEmbedded session, final int collectionId) {
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

  protected void addPolymorphicCollectionId(DatabaseSessionEmbedded session, int collectionId) {
    if (Arrays.binarySearch(polymorphicCollectionIds, collectionId) >= 0) {
      return;
    }

    polymorphicCollectionIds = ArrayUtils.copyOf(polymorphicCollectionIds,
        polymorphicCollectionIds.length + 1);
    polymorphicCollectionIds[polymorphicCollectionIds.length - 1] = collectionId;
    Arrays.sort(polymorphicCollectionIds);

    addCollectionIdToIndexes(session, collectionId, true);

    for (var superClass : superClasses) {
      ((SchemaClassEmbedded) superClass).addPolymorphicCollectionId(session, collectionId);
    }
  }

  @Override
  protected void addCollectionIdToIndexes(DatabaseSessionEmbedded session, int iId,
      boolean requireEmpty) {
    // Provisional-aware resolution: a collection allocated in this same transaction carries a
    // provisional id (<= -2) that resolves to the carried c_<counter> name the commit
    // creates the real collection under (the commit's collection reconciliation runs before the
    // membership enroll). Recording the plain getCollectionNameById null instead would persist a
    // null placeholder into the committed index's collectionsToIndex. The shared resolver keeps
    // this add side, the remove side (removeCollectionFromIndexes), and the deferred index create
    // agreeing on the same name, so same-tx add/remove pairs cancel in the overlay.
    final var collectionName = SchemaShared.resolveCollectionNameById(session, iId);
    if (collectionName == null) {
      // The resolver is nullable by contract (an unknown committed id answers null). Folding a
      // null onward would persist a null placeholder into every index's covered set at commit —
      // the silent-corruption family the tx-schema hardening rejects loudly everywhere (mirrors
      // the resolveDeferredCollectionNames guard). Fail loudly naming the class and the id.
      throw new IllegalStateException(
          "collection id " + iId + " added to class '" + name
              + "' does not resolve to a collection name; refusing to fold an unresolved"
              + " collection into the class's index membership");
    }
    final List<String> indexesToAdd = new ArrayList<>();

    for (var index : getIndexesInternal(session)) {
      indexesToAdd.add(index.getName());
    }

    final var indexManager =
        session.getSharedContext().getIndexManager();
    for (var indexName : indexesToAdd) {
      indexManager.addCollectionToIndex(session, collectionName, indexName, requireEmpty);
    }
  }
}
