package com.jetbrains.youtrackdb.internal.core.metadata.schema.entities;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.common.collection.YTDBIteratorUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.StorageComponentId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.ArrayUtils;


public class SchemaClassEntity extends EntityImpl implements SchemaEntity {

  public interface PropertyNames {

    String NAME = "name";
    String DESCRIPTION = "description";
    String CUSTOM_PROPERTIES = "customProperties";
    String PARENT_CLASSES = "parentClasses";
    String DECLARED_PROPERTIES = "declaredProperties";
    String ABSTRACT_CLASS = "abstractClass";
    String STRICT_MODE = "strictMode";
    String COLLECTION_IDS = "collectionIds";
  }

  public SchemaClassEntity(@Nonnull RecordIdInternal recordId,
      @Nonnull DatabaseSessionEmbedded session) {
    super(recordId, session);
  }

  public boolean isAbstractClass() {
    var isAbstract = getBoolean(PropertyNames.ABSTRACT_CLASS);
    return Boolean.TRUE.equals(isAbstract);
  }

  public void setAbstractClass(boolean value) {
    setBoolean(PropertyNames.ABSTRACT_CLASS, value);
  }

  public boolean isStrictMode() {
    var setStrictMode = getBoolean(PropertyNames.STRICT_MODE);
    return Boolean.TRUE.equals(setStrictMode);
  }

  public void setStrictMode(boolean value) {
    setBoolean(PropertyNames.STRICT_MODE, value);
  }

  public boolean hasParentClasses() {
    var parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    if (parentClasses == null) {
      return false;
    }

    return !parentClasses.isEmpty();
  }

  public boolean hasSubClasses() {
    LinkBag subclasses = getPropertyInternal(
        getOppositeLinkBagPropertyName(PropertyNames.PARENT_CLASSES));
    if (subclasses == null) {
      return false;
    }

    return !subclasses.isEmpty();
  }

  public String getName() {
    return getString(PropertyNames.NAME);
  }

  public void setName(String name) {
    SchemaManager.checkClassNameIfValid(name);
    setString(PropertyNames.NAME, name);
  }

  public String getDescription() {
    return getString(PropertyNames.DESCRIPTION);
  }

  public void setDescription(String description) {
    setString(PropertyNames.DESCRIPTION, description);
  }

  public void setCollectionIds(@Nonnull List<StorageComponentId> collectionIds) {
    newEmbeddedList(PropertyNames.COLLECTION_IDS, collectionIds);
  }

  public List<StorageComponentId> getCollectionIds() {
    List<StorageComponentId> result = getEmbeddedList(PropertyNames.COLLECTION_IDS);
    if (result == null) {
      return Collections.emptyList();
    }
    return result;
  }

  @Nonnull
  public int[] getPrimitiveCollectionIds() {
    List<StorageComponentId> idsList = getEmbeddedList(PropertyNames.COLLECTION_IDS);
    if (idsList == null) {
      return ArrayUtils.EMPTY_INT_ARRAY;
    }

    var ids = new int[idsList.size()];

    for (var i = 0; i < idsList.size(); i++) {
      ids[i] = idsList.get(i).getId();
    }

    return ids;
  }

  public void clearCollectionIds() {
    removeProperty(PropertyNames.COLLECTION_IDS);
  }

  public Set<StorageComponentId> getPolymorphicCollectionIds() {
    var parentClasses = getParentClasses();
    var polymorphicCollectionIds = new HashSet<StorageComponentId>();

    while (parentClasses.hasNext()) {
      var parentClass = parentClasses.next();
      polymorphicCollectionIds.addAll(parentClass.getCollectionIds());
    }

    var collectionIds = getCollectionIds();
    polymorphicCollectionIds.addAll(collectionIds);

    return polymorphicCollectionIds;
  }

  @Nonnull
  public int[] getPrimitivePolymorphicCollectionIds() {
    var polymorphicCollectionIds = getPolymorphicCollectionIds();
    var ids = new int[polymorphicCollectionIds.size()];

    var i = 0;
    for (var polymorphicCollectionId : polymorphicCollectionIds) {
      ids[i] = polymorphicCollectionId.getId();
      i++;
    }

    return ids;
  }

  public Iterator<SchemaClassEntity> getParentClasses() {
    var parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);

    if (parentClasses == null) {
      return IteratorUtils.emptyIterator();
    }

    return YTDBIteratorUtils.unmodifiableIterator(YTDBIteratorUtils.map(parentClasses.iterator(),
        identifiable -> {
          if (identifiable instanceof SchemaClassEntity schemaClassEntity) {
            return schemaClassEntity;
          }
          return session.load(identifiable.getIdentity());
        }
    ));
  }


  public Set<SchemaClassEntity> getAscendants() {
    return ascendantsInternal();
  }

  private Set<SchemaClassEntity> ascendantsInternal() {
    var parents = new HashSet<SchemaClassEntity>();

    var parentClasses = getParentClasses();
    while (parentClasses.hasNext()) {
      var parentClass = parentClasses.next();
      parents.add(parentClass);

      parents.addAll(parentClass.ascendantsInternal());
    }

    return parents;
  }

  public Iterator<SchemaClassEntity> getChildClasses() {
    LinkBag subclasses = getPropertyInternal(
        getOppositeLinkBagPropertyName(PropertyNames.PARENT_CLASSES));
    if (subclasses == null) {
      return IteratorUtils.emptyIterator();
    }

    return YTDBIteratorUtils.unmodifiableIterator(YTDBIteratorUtils.map(subclasses.iterator(),
        session::load));
  }

  public Set<SchemaClassEntity> getDescendants() {
    return descendantsInternal();
  }

  private Set<SchemaClassEntity> descendantsInternal() {
    var children = new HashSet<SchemaClassEntity>();
    var subclasses = getChildClasses();

    while (subclasses.hasNext()) {
      var subclass = subclasses.next();
      children.add(subclass);

      children.addAll(subclass.descendantsInternal());
    }

    return children;
  }

  public boolean isEdgeType() {
    var parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    if (parentClasses == null) {
      return false;
    }

    var eRid = SchemaManager.getClassLink(session, YTDBSchemaClass.EDGE_CLASS_NAME);
    return parentClasses.contains(eRid);
  }

  public boolean isVertexType() {
    var parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    if (parentClasses == null) {
      return false;
    }

    var vRid = SchemaManager.getClassLink(session, YTDBSchemaClass.VERTEX_CLASS_NAME);
    return parentClasses.contains(vRid);
  }

  public void addChildClass(@Nonnull SchemaClassEntity schemaClass) {
    schemaClass.addParentClass(this);
  }

  public void removeChildClass(@Nonnull SchemaClassEntity schemaClass) {
    schemaClass.removeParentClass(this);
  }

  public void clearParentClasses() {
    var linkSet = getOrCreateLinkSet(PropertyNames.PARENT_CLASSES);
    linkSet.clear();
  }

  public void addParentClass(@Nonnull SchemaClassEntity parentClass) {
    if (parentClass.isChildOf(this)) {
      throw new DatabaseException(session,
          "Recursion detected. Class : " + parentClass.getName() + " is already a child of class "
              + getName());
    }

    var linkSet = getOrCreateLinkSet(PropertyNames.PARENT_CLASSES);
    linkSet.add(parentClass);
  }

  public void removeParentClass(SchemaClassEntity parentClass) {
    var linkSet = getLinkSet(PropertyNames.PARENT_CLASSES);
    if (linkSet != null) {
      linkSet.remove(parentClass);
    }
  }

  public boolean isChildOf(String className) {
    var parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    if (parentClasses == null) {
      return false;
    }

    var classRid = SchemaManager.getClassLink(session, className);
    if (classRid == null) {
      return false;
    }

    if (parentClasses.contains(classRid)) {
      return true;
    }

    for (var parentClassIdentifiable : parentClasses) {
      SchemaClassEntity parentClassEntity;

      if (parentClassIdentifiable instanceof SchemaClassEntity parentClass) {
        parentClassEntity = parentClass;
      } else {
        parentClassEntity = session.load(parentClassIdentifiable.getIdentity());
      }

      if (parentClassEntity.isChildOf(className)) {
        return true;
      }
    }

    return false;
  }

  public boolean isChildOf(SchemaClassEntity schemaClass) {
    var parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    if (parentClasses == null) {
      return false;
    }

    if (parentClasses.contains(schemaClass)) {
      return true;
    }

    for (var parentClassIdentifiable : parentClasses) {
      SchemaClassEntity parentClassEntity;

      if (parentClassIdentifiable instanceof SchemaClassEntity parentClass) {
        parentClassEntity = parentClass;
      } else {
        parentClassEntity = session.load(parentClassIdentifiable.getIdentity());
      }

      if (parentClassEntity.isChildOf(schemaClass)) {
        return true;
      }
    }

    return false;
  }

  public boolean isParentOf(String className) {
    var childClass = SchemaManager.getClass(session, className);
    if (childClass == null) {
      throw new DatabaseException("Class " + className + " not found");
    }

    return childClass.isChildOf(this);
  }

  public boolean isParentOf(SchemaClassEntity schemaClass) {
    return schemaClass.isChildOf(this);
  }

  @Nullable
  public String getCustomProperty(String name) {
    var customProperties = this.<String>getEmbeddedMap(PropertyNames.CUSTOM_PROPERTIES);

    if (customProperties == null) {
      return null;
    }

    return customProperties.get(name);
  }

  public void setCustomProperty(String name, String value) {
    var customProperties = this.<String>getOrCreateEmbeddedMap(PropertyNames.CUSTOM_PROPERTIES);
    customProperties.put(name, value);
  }

  public void removeCustomProperty(String name) {
    var customProperties = this.<String>getEmbeddedMap(PropertyNames.CUSTOM_PROPERTIES);

    if (customProperties == null) {
      return;
    }

    customProperties.remove(name);
  }

  public void clearCustomProperties() {
    removeProperty(PropertyNames.CUSTOM_PROPERTIES);
  }

  public Set<String> getCustomPropertiesNames() {
    var customProperties = this.<Map<String, String>>getEmbeddedMap(
        PropertyNames.CUSTOM_PROPERTIES);
    if (customProperties == null) {
      return Collections.emptySet();
    }

    return customProperties.keySet();
  }

  public boolean hasCollectionId(StorageComponentId collectionId) {
    return getCollectionIds().contains(collectionId);
  }

  public boolean hasCollectionId(int collectionId) {
    var collectionIds = getCollectionIds();
    for (var collection : collectionIds) {
      if (collection.getId() == collectionId) {
        return true;
      }
    }

    return false;
  }

  public boolean hasPolymorphicCollectionId(StorageComponentId collectionId) {
    var collectionIds = getCollectionIds();
    if (collectionIds.contains(collectionId)) {
      return true;
    }

    var parentClasses = getParentClasses();
    while (parentClasses.hasNext()) {
      var parentClass = parentClasses.next();
      if (parentClass.hasPolymorphicCollectionId(collectionId)) {
        return true;
      }
    }

    return false;
  }

  public boolean hasPolymorphicCollectionId(int collectionId) {
    var collectionIds = getCollectionIds();
    for (var collection : collectionIds) {
      if (collection.getId() == collectionId) {
        return true;
      }
    }

    var parentClasses = getParentClasses();
    while (parentClasses.hasNext()) {
      var parentClass = parentClasses.next();

      if (parentClass.hasPolymorphicCollectionId(collectionId)) {
        return true;
      }
    }

    return false;
  }


  public Iterator<SchemaPropertyEntity> getDeclaredProperties(String... name) {
    var declaredPropertiesLinks = getLinkSet(PropertyNames.DECLARED_PROPERTIES);

    if (declaredPropertiesLinks == null) {
      return IteratorUtils.emptyIterator();
    }

    Iterator<SchemaPropertyEntity> properties = YTDBIteratorUtils.map(
        declaredPropertiesLinks.iterator(),
        identifiable -> session.load(identifiable.getIdentity()));

    if (name == null || name.length == 0) {
      return properties;
    }

    return YTDBIteratorUtils.unmodifiableIterator(YTDBIteratorUtils.filter(properties,
        property -> ArrayUtils.contains(name, property.getName())));
  }

  @Nullable
  public SchemaPropertyEntity getDeclaredProperty(String name) {
    var declaredProperties = getLinkSet(PropertyNames.DECLARED_PROPERTIES);

    if (declaredProperties == null) {
      return null;
    }

    for (var declaredPropertyLink : declaredProperties) {
      SchemaPropertyEntity declaredProperty;

      if (declaredPropertyLink instanceof SchemaPropertyEntity declaredPropertyEntity) {
        declaredProperty = declaredPropertyEntity;
      } else {
        declaredProperty = session.load(declaredPropertyLink.getIdentity());
      }

      if (declaredProperty.getName().equals(name)) {
        return declaredProperty;
      }
    }

    return null;
  }

  public Collection<SchemaPropertyEntity> getSchemaProperties(String... name) {
    var processedProperties = new HashMap<String, SchemaPropertyEntity>();

    var properties = getDeclaredProperties(name);
    while (properties.hasNext()) {
      var property = properties.next();
      processedProperties.put(property.getName(), property);
    }

    if (name != null && name.length > 0 && processedProperties.size() == name.length) {
      return processedProperties.values();
    }

    Set<Identifiable> parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    Set<Identifiable> nextParentClasses = new HashSet<>();

    while (!parentClasses.isEmpty()) {
      for (var parentClassLink : parentClasses) {
        SchemaClassEntity parentClass;
        if (parentClassLink instanceof SchemaClassEntity parentClassEntity) {
          parentClass = parentClassEntity;
        } else {
          parentClass = session.load(parentClassLink.getIdentity());
        }

        var parentProperties = parentClass.getDeclaredProperties(name);
        while (parentProperties.hasNext()) {
          var parentProperty = parentProperties.next();
          if (!processedProperties.containsKey(parentProperty.getName())) {
            processedProperties.put(parentProperty.getName(), parentProperty);
          }
        }

        if (name != null && name.length > 0 && processedProperties.size() == name.length) {
          return processedProperties.values();
        }

        var parentParentClasses = parentClass.getLinkSet(PropertyNames.PARENT_CLASSES);
        nextParentClasses.addAll(parentParentClasses);
      }

      parentClasses = nextParentClasses;
      nextParentClasses = new HashSet<>();
    }

    return processedProperties.values();
  }

  @Nullable
  public SchemaPropertyEntity getSchemaProperty(String name) {
    var property = getDeclaredProperty(name);
    if (property != null) {
      return property;
    }

    Set<Identifiable> parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    Set<Identifiable> nextParentClasses = new HashSet<>();

    while (!parentClasses.isEmpty()) {
      for (var parentClassLink : parentClasses) {
        SchemaClassEntity parentClass;
        if (parentClassLink instanceof SchemaClassEntity parentClassEntity) {
          parentClass = parentClassEntity;
        } else {
          parentClass = session.load(parentClassLink.getIdentity());
        }

        property = parentClass.getDeclaredProperty(name);
        if (property != null) {
          return property;
        }

        var parentParentClasses = parentClass.getLinkSet(PropertyNames.PARENT_CLASSES);
        nextParentClasses.addAll(parentParentClasses);
      }

      parentClasses = nextParentClasses;
      nextParentClasses = new HashSet<>();
    }

    return null;
  }

  public boolean existsSchemaProperty(String name) {
    var declaredProperties = getLinkSet(PropertyNames.DECLARED_PROPERTIES);
    if (declaredProperties == null) {
      return false;
    }

    for (var declaredPropertyLink : declaredProperties) {
      SchemaPropertyEntity declaredProperty;
      if (declaredPropertyLink instanceof SchemaPropertyEntity declaredPropertyEntity) {
        declaredProperty = declaredPropertyEntity;
      } else {
        declaredProperty = session.load(declaredPropertyLink.getIdentity());
      }

      if (declaredProperty.getName().equals(name)) {
        return true;
      }
    }

    var parentClasses = getLinkSet(PropertyNames.PARENT_CLASSES);
    if (parentClasses == null) {
      return false;
    }

    for (var parentClassLink : parentClasses) {
      SchemaClassEntity parentClass;
      if (parentClassLink instanceof SchemaClassEntity parentClassEntity) {
        parentClass = parentClassEntity;
      } else {
        parentClass = session.load(parentClassLink.getIdentity());
      }

      if (parentClass.existsSchemaProperty(name)) {
        return true;
      }
    }

    return false;
  }

  public void addSchemaProperty(SchemaPropertyEntity property) {
    var declaredPropertiesLinks = getOrCreateLinkSet(PropertyNames.DECLARED_PROPERTIES);
    declaredPropertiesLinks.add(property);
  }

  public void removeSchemaProperty(SchemaPropertyEntity property) {
    var declaredPropertiesLinks = getLinkSet(PropertyNames.DECLARED_PROPERTIES);

    if (declaredPropertiesLinks != null) {
      declaredPropertiesLinks.remove(property);
    }
  }

  public void removeSchemaProperty(String name) {
    var declaredProperties = getDeclaredProperties(name);

    if (declaredProperties.hasNext()) {
      var declaredProperty = declaredProperties.next();
      removeSchemaProperty(declaredProperty);
    }
  }

  public Iterator<SchemaIndexEntity> getIndexes() {
    var indexesPropertyName = getOppositeLinkBagPropertyName(SchemaIndexEntity.PROPERTIES_TO_INDEX);

    LinkBag indexes = getPropertyInternal(indexesPropertyName);
    if (indexes == null) {
      return Collections.emptyIterator();
    }

    return YTDBIteratorUtils.unmodifiableIterator(YTDBIteratorUtils.map(indexes.iterator(),
        session::load));
  }

  public boolean isNameChangedInCallback() {
    var property = properties.get(PropertyNames.NAME);
    return property != null && property.isChanged();
  }

  public boolean isAbstractChangedInCallback() {
    var property = properties.get(PropertyNames.ABSTRACT_CLASS);
    return property != null && property.isChanged();
  }

  public boolean isParentClassesChangedInCallback() {
    var property = properties.get(PropertyNames.PARENT_CLASSES);
    return property != null && property.isChanged();
  }
}