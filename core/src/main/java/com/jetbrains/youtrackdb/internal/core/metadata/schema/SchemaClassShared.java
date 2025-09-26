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

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * Schema Class implementation.
 */
@SuppressWarnings("unchecked")
public final class SchemaClassShared {
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
      SchemaClassShared linkedClass) {
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
      SchemaClassShared linkedClass) {
    final var builder = new StringBuilder(256);
    builder.append("select from ");
    builder.append(getEscapedName(name, true));
    builder.append(" where ");
    builder.append(getEscapedName(propertyName, true)).append(" is not null ");
    if (type.isMultiValue()) {
      builder.append(" and ").append(getEscapedName(propertyName, true)).append(".size() > 0");
    }

    db.executeInTx(tx -> {
      try (final var res = tx.query(builder.toString())) {
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
                                + linkedClass.getName()
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
                        + linkedClass.getName()
                        + ". Fix those records and change the schema again!");
              }
              break;
          }
        }
      }
    });
  }

  protected static boolean matchesType(DatabaseSessionInternal db, Object x,
      SchemaClassShared linkedClass) {
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

  protected static String getEscapedName(final String iName, final boolean iStrictSQL) {
    if (iStrictSQL)
    // ESCAPE NAME
    {
      return "`" + iName + "`";
    }
    return iName;
  }

  public SchemaManager getOwner() {
    return owner;
  }


  public SchemaPropertyShared addProperty(
      DatabaseSessionInternal session, final String propertyName,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassShared linkedClass,
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
      SchemaPropertyShared.checkLinkTypeSupport(type);
    }

    if (linkedClass != null) {
      SchemaPropertyShared.checkSupportLinkedClass(type);
    }

    try {
      return addPropertyInternal(session, propertyName, type,
          linkedType, linkedClass, unsafe);

    } finally {
    }
  }

  @Override
  public void removeSuperClass(DatabaseSessionInternal session, SchemaClassShared superClass) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      removeSuperClassInternal(session, superClass);

    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void removeSuperClassInternal(DatabaseSessionInternal session,
      final SchemaClassShared superClass) {
    acquireSchemaWriteLock();
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

      final SchemaClassShared cls;
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
      final List<SchemaClassShared> classes) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    if (classes != null) {
      List<SchemaClassShared> toCheck = new ArrayList<>(classes);
      toCheck.add(this);
      checkParametersConflict(session, toCheck);
    }
    acquireSchemaWriteLock();
    try {
      setSuperClassesInternal(session, classes, true);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  @Override
  protected void setSuperClassesInternal(DatabaseSessionInternal session,
      final List<SchemaClassShared> classes, boolean validateIndexes) {
    if (!name.equals(SchemaClass.EDGE_CLASS_NAME) && isEdgeType()) {
      if (!classes.contains(owner.getClass(, , SchemaClass.EDGE_CLASS_NAME))) {
        throw new IllegalArgumentException(
            "Edge class must have super class " + SchemaClass.EDGE_CLASS_NAME
                + ", its removal is not allowed.");
      }
    }
    if (!name.equals(SchemaClass.VERTEX_CLASS_NAME) && isVertexType()) {
      if (!classes.contains(owner.getClass(, , SchemaClass.VERTEX_CLASS_NAME))) {
        throw new IllegalArgumentException(
            "Vertex class must have super class " + SchemaClass.VERTEX_CLASS_NAME
                + ", its removal is not allowed.");
      }
    }

    List<SchemaClassShared> newSuperClasses = new ArrayList<>();
    SchemaClassShared cls;
    for (var superClass : classes) {
      cls = superClass;
      if (newSuperClasses.contains(cls)) {
        throw new SchemaException(session.getDatabaseName(),
            "Duplicated superclass '" + cls.getName() + "'");
      }

      newSuperClasses.add(cls);
    }

    List<SchemaClassShared> toAddList = new ArrayList<>(newSuperClasses);
    toAddList.removeAll(superClasses);
    List<SchemaClassShared> toRemoveList = new ArrayList<>(superClasses);
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
  public void setName(DatabaseSessionInternal session, final String name) {
    if (getName().equals(name)) {
      return;
    }
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    var oClass = session.getMetadata().getSlowMutableSchema().getClass(name);
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
    acquireSchemaWriteLock();
    try {
      setNameInternal(session, name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }

  protected void setNameInternal(DatabaseSessionInternal session, final String name) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    acquireSchemaWriteLock();
    try {
      final var oldName = this.name;
      owner.changeClassName(session, this.name, name, this);
      this.name = name;
      renameCollection(session, oldName, this.name);
    } finally {
      releaseSchemaWriteLock(session);
    }
  }


  public SchemaPropertyShared addPropertyInternal(
      DatabaseSessionInternal session, final String name,
      final PropertyTypeInternal type,
      final PropertyTypeInternal linkedType,
      final SchemaClassShared linkedClass,
      final boolean unsafe) {
    if (name == null || name.isEmpty()) {
      throw new SchemaException(session.getDatabaseName(), "Found property name null");
    }

    if (!unsafe) {
      checkPersistentPropertyType(session, name, type, linkedClass);
    }

    final SchemaPropertyShared prop;

    // This check are doubled because used by sql commands
    if (linkedType != null) {
      SchemaPropertyShared.checkLinkTypeSupport(type);
    }

    if (linkedClass != null) {
      SchemaPropertyShared.checkSupportLinkedClass(type);
    }

    acquireSchemaWriteLock();
    try {
      checkEmbedded(session);

      if (properties.containsKey(name)) {
        throw new SchemaException(session.getDatabaseName(),
            "Class '" + this.name + "' already has property '" + name + "'");
      }

      var global = SchemaManager.findOrCreateGlobalProperty(name, type);

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



  @Override
  public void dropProperty(DatabaseSessionInternal session, final String propertyName) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot drop a property inside a transaction");
    }

    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);

    if (!properties.containsKey(propertyName)) {
      throw new SchemaException(session.getDatabaseName(),
          "Property '" + propertyName + "' not found in class " + name + "'");
    }
    dropPropertyInternal(session, propertyName);
  }

  protected void dropPropertyInternal(
      DatabaseSessionInternal session, final String iPropertyName) {
    if (session.getTransactionInternal().isActive()) {
      throw new IllegalStateException("Cannot drop a property inside a transaction");
    }
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_DELETE);
    try {
      final var prop = properties.remove(iPropertyName);

      if (prop == null) {
        throw new SchemaException(session.getDatabaseName(),
            "Property '" + iPropertyName + "' not found in class " + name + "'");
      }
    } finally {
    }
  }

  public void setOverSize(DatabaseSessionInternal session, final float overSize) {
    session.checkSecurity(Rule.ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
    try {
      setOverSizeInternal(session, overSize);
    } finally {
    }
  }



  protected void setCustomInternal(DatabaseSessionInternal session, final String name,
      final String value) {
    acquireSchemaWriteLock();
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

    acquireSchemaWriteLock();
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
          for (var collectionId : getCollectionIds()) {
            tryDropCollection(database, collectionId);
            removePolymorphicCollectionId(database, collectionId);
            owner.removeCollectionForClass(database, collectionId);
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
        for (var clazz : getAllSubclasses()) {
          if (clazz instanceof SchemaClassShared) {
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


  @Override
  protected void addCollectionIdToIndexes(DatabaseSessionInternal session, int iId,
      boolean requireEmpty) {
    var collectionName = session.getCollectionNameById(iId);
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
