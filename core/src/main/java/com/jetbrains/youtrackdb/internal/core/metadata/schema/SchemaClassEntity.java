package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.CollectionId;
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

  public static final String CUSTOM_PROPERTIES_PROPERTY_NAME = "customProperties";
  public static final String SUPER_CLASSES_PROPERTY_NAME = "superClasses";
  public static final String DECLARED_PROPERTIES_NAME = "declaredProperties";

  public SchemaClassEntity(@Nonnull RecordIdInternal recordId,
      @Nonnull DatabaseSessionEmbedded session) {
    super(recordId, session);
  }

  public boolean isAbstractClass() {
    return getBoolean(YTDBSchemaClassPTokenInternal.abstractClass.name());
  }

  public void setAbstractClass(boolean value) {
    setBoolean(YTDBSchemaClassPTokenInternal.abstractClass.name(), value);
  }

  public boolean isStrictMode() {
    return getBoolean(YTDBSchemaClassPTokenInternal.strictMode.name());
  }

  public void setStrictMode(boolean value) {
    setBoolean(YTDBSchemaClassPTokenInternal.strictMode.name(), value);
  }

  public boolean hasSuperClasses() {
    var superClasses = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);
    if (superClasses == null) {
      return false;
    }

    return !superClasses.isEmpty();
  }

  public boolean hasSubClasses() {
    LinkBag subclasses = getPropertyInternal(
        getOppositeLinkBagPropertyName(SUPER_CLASSES_PROPERTY_NAME));
    if (subclasses == null) {
      return false;
    }

    return !subclasses.isEmpty();
  }

  public String getName() {
    return getString(YTDBSchemaClassPTokenInternal.name.name());
  }

  public void setName(String name) {
    SchemaManager.checkClassNameIfValid(name);
    setString(YTDBSchemaClassPTokenInternal.name.name(), name);
  }

  public String getDescription() {
    return getString(YTDBSchemaClassPTokenInternal.description.name());
  }

  public void setDescription(String description) {
    setString(YTDBSchemaClassPTokenInternal.description.name(), description);
  }

  public void setCollectionIds(@Nonnull List<CollectionId> collectionIds) {
    newEmbeddedList(YTDBSchemaClassPTokenInternal.collectionIds.name(), collectionIds);
  }

  public List<CollectionId> getCollectionIds() {
    return getEmbeddedList(YTDBSchemaClassPTokenInternal.collectionIds.name());
  }

  public void clearCollectionIds() {
    removeProperty(YTDBSchemaClassPTokenInternal.collectionIds.name());
  }

  public Set<CollectionId> getPolymorphicCollectionIds() {
    var superClasses = getSuperClasses();
    var polymorphicCollectionIds = new HashSet<CollectionId>();

    while (superClasses.hasNext()) {
      var superClass = superClasses.next();
      polymorphicCollectionIds.addAll(superClass.getCollectionIds());
    }

    var collectionIds = getCollectionIds();
    polymorphicCollectionIds.addAll(collectionIds);

    return polymorphicCollectionIds;
  }

  public Iterator<SchemaClassEntity> getSuperClasses() {
    var superClasses = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);

    if (superClasses == null) {
      return IteratorUtils.emptyIterator();
    }

    return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(superClasses.iterator(),
        identifiable -> {
          if (identifiable instanceof SchemaClassEntity schemaClassEntity) {
            return schemaClassEntity;
          }
          return session.load(identifiable.getIdentity());
        }
    );
  }


  public Set<SchemaClassEntity> getAscendants() {
    return ascendantsInternal();
  }

  private Set<SchemaClassEntity> ascendantsInternal() {
    var parents = new HashSet<SchemaClassEntity>();

    var superClasses = getSuperClasses();
    while (superClasses.hasNext()) {
      var superClass = superClasses.next();
      parents.add(superClass);

      parents.addAll(superClass.ascendantsInternal());
    }

    return parents;
  }

  public Iterator<SchemaClassEntity> getSubClasses() {
    LinkBag subclasses = getPropertyInternal(
        getOppositeLinkBagPropertyName(SUPER_CLASSES_PROPERTY_NAME));
    if (subclasses == null) {
      return IteratorUtils.emptyIterator();
    }

    return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(subclasses.iterator(),
        session::load);
  }

  public Set<SchemaClassEntity> getDescendants() {
    return descendantsInternal();
  }

  private Set<SchemaClassEntity> descendantsInternal() {
    var children = new HashSet<SchemaClassEntity>();
    var subclasses = getSubClasses();

    while (subclasses.hasNext()) {
      var subclass = subclasses.next();
      children.add(subclass);

      children.addAll(subclass.descendantsInternal());
    }

    return children;
  }

  public boolean isEdgeType() {
    var superClasses = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);
    if (superClasses == null) {
      return false;
    }

    var eRid = SchemaManager.getClassLink(session, YTDBSchemaClass.EDGE_CLASS_NAME);
    return superClasses.contains(eRid);
  }

  public boolean isVertexType() {
    var superClasses = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);
    if (superClasses == null) {
      return false;
    }

    var vRid = SchemaManager.getClassLink(session, YTDBSchemaClass.VERTEX_CLASS_NAME);
    return superClasses.contains(vRid);
  }

  public void addChildClass(@Nonnull SchemaClassEntity schemaClass) {
    schemaClass.addSuperClass(this);
  }

  public void removeChildClass(@Nonnull SchemaClassEntity schemaClass) {
    schemaClass.removeSuperClass(this);
  }

  public void addSuperClass(@Nonnull SchemaClassEntity schemaClass) {
    var linkSet = getOrCreateLinkSet(SUPER_CLASSES_PROPERTY_NAME);
    linkSet.add(schemaClass);
  }

  public void removeSuperClass(SchemaClassEntity schemaClass) {
    var linkSet = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);

    if (linkSet != null) {
      linkSet.remove(schemaClass);
    }
  }

  public boolean isSubClassOf(String className) {
    var superClasses = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);
    if (superClasses == null) {
      return false;
    }

    var classRid = SchemaManager.getClassLink(session, className);
    if (classRid == null) {
      return false;
    }

    if (superClasses.contains(classRid)) {
      return true;
    }

    for (var superClassIdentifiable : superClasses) {
      SchemaClassEntity superClassEntity;

      if (superClassIdentifiable instanceof SchemaClassEntity superClass) {
        superClassEntity = superClass;
      } else {
        superClassEntity = session.load(superClassIdentifiable.getIdentity());
      }

      if (superClassEntity.isSubClassOf(className)) {
        return true;
      }
    }

    return false;
  }

  public boolean isSubClassOf(SchemaClassEntity schemaClass) {
    var superClasses = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);
    if (superClasses == null) {
      return false;
    }

    if (superClasses.contains(schemaClass)) {
      return true;
    }

    for (var superClassIdentifiable : superClasses) {
      SchemaClassEntity superClassEntity;

      if (superClassIdentifiable instanceof SchemaClassEntity superClass) {
        superClassEntity = superClass;
      } else {
        superClassEntity = session.load(superClassIdentifiable.getIdentity());
      }

      if (superClassEntity.isSubClassOf(schemaClass)) {
        return true;
      }
    }

    return false;
  }

  public boolean isSuperClassOf(String className) {
    var childClass = SchemaManager.getClass(session, className);
    if (childClass == null) {
      throw new DatabaseException("Class " + className + " not found");
    }

    return childClass.isSubClassOf(this);
  }

  public boolean isSuperClassOf(SchemaClassEntity schemaClass) {
    return schemaClass.isSubClassOf(this);
  }

  @Nullable
  public String getCustomProperty(String name) {
    var customProperties = this.<String>getEmbeddedMap(CUSTOM_PROPERTIES_PROPERTY_NAME);

    if (customProperties == null) {
      return null;
    }

    return customProperties.get(name);
  }

  public void setCustomProperty(String name, String value) {
    var customProperties = this.<String>getOrCreateEmbeddedMap(CUSTOM_PROPERTIES_PROPERTY_NAME);
    customProperties.put(name, value);
  }

  public void removeCustomProperty(String name) {
    var customProperties = this.<String>getEmbeddedMap(CUSTOM_PROPERTIES_PROPERTY_NAME);

    if (customProperties == null) {
      return;
    }

    customProperties.remove(name);
  }

  public void clearCustomProperties() {
    removeProperty(CUSTOM_PROPERTIES_PROPERTY_NAME);
  }

  public Set<String> customPropertyNames() {
    var customProperties = this.<Map<String, String>>getEmbeddedMap(
        CUSTOM_PROPERTIES_PROPERTY_NAME);
    if (customProperties == null) {
      return Collections.emptySet();
    }

    return customProperties.keySet();
  }

  public boolean hasCollectionId(CollectionId collectionId) {
    return getCollectionIds().contains(collectionId);
  }

  public boolean hasPolymorphicCollectionId(CollectionId collectionId) {
    var collectionIds = getCollectionIds();
    if (collectionIds.contains(collectionId)) {
      return true;
    }

    var superClasses = getSuperClasses();
    while (superClasses.hasNext()) {
      var superClass = superClasses.next();
      if (superClass.hasPolymorphicCollectionId(collectionId)) {
        return true;
      }
    }

    return false;
  }

  public Iterator<SchemaPropertyEntity> getDeclaredProperties(String... name) {
    var declaredPropertiesLinks = getLinkSet(DECLARED_PROPERTIES_NAME);

    if (declaredPropertiesLinks == null) {
      return IteratorUtils.emptyIterator();
    }

    Iterator<SchemaPropertyEntity> properties = org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(
        declaredPropertiesLinks.iterator(),
        identifiable -> session.load(identifiable.getIdentity()));

    if (name == null || name.length == 0) {
      return properties;
    }

    return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.filter(properties,
        property -> ArrayUtils.contains(name, property.getName()));
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

    var superClasses = getSuperClasses();
    while (superClasses.hasNext()) {
      var superClass = superClasses.next();

      var superProperties = superClass.getSchemaProperties(name);
      for (var superProperty : superProperties) {
        if (!processedProperties.containsKey(superProperty.getName())) {
          processedProperties.put(superProperty.getName(), superProperty);
        }
      }

      if (name != null && name.length > 0 && processedProperties.size() == name.length) {
        return processedProperties.values();
      }
    }

    return processedProperties.values();
  }

  public boolean existsSchemaProperty(String name) {
    var declaredProperties = getLinkSet(DECLARED_PROPERTIES_NAME);
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

    var superClasses = getLinkSet(SUPER_CLASSES_PROPERTY_NAME);
    if (superClasses == null) {
      return false;
    }

    for (var superClassLink : superClasses) {
      SchemaClassEntity superClass;
      if (superClassLink instanceof SchemaClassEntity superClassEntity) {
        superClass = superClassEntity;
      } else {
        superClass = session.load(superClassLink.getIdentity());
      }

      if (superClass.existsSchemaProperty(name)) {
        return true;
      }
    }

    return false;
  }

  public void addSchemaProperty(SchemaPropertyEntity property) {
    var declaredPropertiesLinks = getOrCreateLinkSet(DECLARED_PROPERTIES_NAME);
    declaredPropertiesLinks.add(property);
  }

  public void removeSchemaProperty(SchemaPropertyEntity property) {
    var declaredPropertiesLinks = getLinkSet(DECLARED_PROPERTIES_NAME);

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

}