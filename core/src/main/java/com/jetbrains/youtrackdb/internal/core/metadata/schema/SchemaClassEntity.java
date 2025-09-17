package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassInToken;
import com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassOutToken;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassOutTokenInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema.YTDBSchemaClassPTokenInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
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


public class SchemaClassEntity extends EntityImpl {
  public static final String CUSTOM_PROPERTIES_PROPERTY_NAME = "customProperties";

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
    var backupWardLinkBag = getOppositeLinkBagPropertyName(
        YTDBSchemaClassInToken.superClass.name());

    LinkBag linkBag = getPropertyInternal(backupWardLinkBag);
    if (linkBag == null) {
      return false;
    }

    return !linkBag.isEmpty();
  }

  public String getName() {
    return getString(YTDBSchemaClassPTokenInternal.name.name());
  }

  public void setName(String name) {
    setString(YTDBSchemaClassPTokenInternal.name.name(), name);
  }

  public String getDescription() {
    return getString(YTDBSchemaClassPTokenInternal.description.name());
  }

  public void setDescription(String description) {
    setString(YTDBSchemaClassPTokenInternal.description.name(), description);
  }

  public List<Integer> getCollectionIds() {
    return getEmbeddedList(YTDBSchemaClassPTokenInternal.collectionIds.name());
  }

  public Set<Integer> getPolymorphicCollectionIds() {
    var superClasses = getSuperClasses();
    var polymorphicCollectionIds = new HashSet<Integer>();

    while (superClasses.hasNext()) {
      var superClass = superClasses.next();
      polymorphicCollectionIds.addAll(superClass.getCollectionIds());
    }

    var collectionIds = getCollectionIds();
    polymorphicCollectionIds.addAll(collectionIds);

    return polymorphicCollectionIds;
  }

  public Iterator<SchemaClassEntity> getSuperClasses() {
    var backupWardLinkBag = getOppositeLinkBagPropertyName(
        YTDBSchemaClassInToken.superClass.name());

    LinkBag linkBag = getPropertyInternal(backupWardLinkBag);
    if (linkBag == null) {
      return IteratorUtils.emptyIterator();
    }

    return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(linkBag.iterator(),
        session::load);
  }


  public Iterator<SchemaClassEntity> getAscendants() {
    return ascendantsInternal().iterator();
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
    var subclasses = getLinkSet(YTDBSchemaClassOutToken.superClass.name());
    if (subclasses == null) {
      return IteratorUtils.emptyIterator();
    }

    return org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.map(subclasses.iterator(),
        identifiable -> session.load(identifiable.getIdentity()));
  }

  public Iterator<SchemaClassEntity> getDescendants() {
    return descendantsInternal().iterator();
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
    var backupWardLinkBag = getOppositeLinkBagPropertyName(
        YTDBSchemaClassInToken.superClass.name());
    LinkBag linkBag = getPropertyInternal(backupWardLinkBag);
    if (linkBag == null) {
      return false;
    }

    var eRid = SchemaManager.getSchemaClassEntityRID(session, YTDBSchemaClass.EDGE_CLASS_NAME);
    return linkBag.contains(eRid);
  }

  public boolean isVertexType() {
    var backupWardLinkBag = getOppositeLinkBagPropertyName(
        YTDBSchemaClassInToken.superClass.name());
    LinkBag linkBag = getPropertyInternal(backupWardLinkBag);
    if (linkBag == null) {
      return false;
    }

    var vRid = SchemaManager.getSchemaClassEntityRID(session, YTDBSchemaClass.VERTEX_CLASS_NAME);
    return linkBag.contains(vRid);
  }

  public void addChildClass(SchemaClassEntity schemaClass) {
    var linkSet = getOrCreateLinkSet(YTDBSchemaClassOutToken.superClass.name());
    linkSet.add(schemaClass);
  }

  public void removeChildClass(SchemaClassEntity schemaClass) {
    var linkSet = getLinkSet(YTDBSchemaClassOutToken.superClass.name());
    if (linkSet != null) {
      linkSet.remove(schemaClass);
    }
  }

  public boolean isSubClassOf(String className) {
    var backupWardLinkBag = getOppositeLinkBagPropertyName(
        YTDBSchemaClassInToken.superClass.name());
    LinkBag linkBag = getPropertyInternal(backupWardLinkBag);
    if (linkBag == null) {
      return false;
    }

    var classRid = SchemaManager.getSchemaClassEntityRID(session, className);
    return linkBag.contains(classRid);
  }

  public boolean isSubClassOf(SchemaClassEntity schemaClass) {
    var backupWardLinkBag = getOppositeLinkBagPropertyName(
        YTDBSchemaClassInToken.superClass.name());
    LinkBag linkBag = getPropertyInternal(backupWardLinkBag);
    if (linkBag == null) {
      return false;
    }

    return linkBag.contains(schemaClass.getIdentity());
  }

  public boolean isSuperClassOf(String className) {
    var linkSet = getLinkSet(YTDBSchemaClassOutToken.superClass.name());
    if (linkSet == null) {
      return false;
    }

    var classRid = SchemaManager.getSchemaClassEntityRID(session, className);
    return linkSet.contains(classRid);
  }

  public boolean isSuperClassOf(SchemaClassEntity schemaClass) {
    var linkSet = getLinkSet(YTDBSchemaClassOutToken.superClass.name());
    if (linkSet == null) {
      return false;
    }

    return linkSet.contains(schemaClass);
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

  public Iterator<String> customPropertyNames() {
    var customProperties = this.<Map<String, String>>getEmbeddedMap(
        CUSTOM_PROPERTIES_PROPERTY_NAME);
    if (customProperties == null) {
      return IteratorUtils.emptyIterator();
    }

    return customProperties.keySet().iterator();
  }

  public boolean hasCollectionId(int collectionId) {
    return getCollectionIds().contains(collectionId);
  }

  public boolean hasPolymorphicCollectionId(int collectionId) {
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
    var declaredPropertiesLinks = getLinkSet(
        YTDBSchemaClassOutTokenInternal.declaredProperty.name());
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

  public Iterator<SchemaPropertyEntity> getSchemaProperties(String... name) {
    var processedProperties = new HashMap<String, SchemaPropertyEntity>();

    var properties = getDeclaredProperties(name);
    while (properties.hasNext()) {
      var property = properties.next();
      processedProperties.put(property.getName(), property);
    }

    if (name != null && name.length > 0 && processedProperties.size() == name.length) {
      return processedProperties.values().iterator();
    }

    var superClasses = getSuperClasses();
    while (superClasses.hasNext()) {
      var superClass = superClasses.next();

      var superProperties = superClass.getSchemaProperties(name);
      while (superProperties.hasNext()) {
        var superProperty = superProperties.next();

        if (!processedProperties.containsKey(superProperty.getName())) {
          processedProperties.put(superProperty.getName(), superProperty);
        }
      }

      if (name != null && name.length > 0 && processedProperties.size() == name.length) {
        return processedProperties.values().iterator();
      }
    }

    return processedProperties.values().iterator();
  }

  public boolean existsSchemaProperty(String name) {
    return getSchemaProperties(name).hasNext();
  }

  public void addSchemaProperty(SchemaPropertyEntity property) {
    var declaredPropertiesLinks = getOrCreateLinkSet(
        YTDBSchemaClassOutTokenInternal.declaredProperty.name());
    declaredPropertiesLinks.add(property);
  }

  public void removeSchemaProperty(SchemaPropertyEntity property) {
    var declaredPropertiesLinks = getLinkSet(
        YTDBSchemaClassOutTokenInternal.declaredProperty.name());
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