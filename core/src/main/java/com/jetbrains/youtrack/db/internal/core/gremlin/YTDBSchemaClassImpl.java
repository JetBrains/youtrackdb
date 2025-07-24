package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBDomainObject;
import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectPToken;
import com.jetbrains.youtrack.db.api.gremlin.tokens.schema.YTDBSchemaClassPToken;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule.ResourceGeneric;
import java.util.ArrayList;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

public class YTDBSchemaClassImpl implements YTDBSchemaClass {

  private SchemaClassImpl schemaClass;
  private String name;
  private RecordId recordId;

  private final @Nonnull YTDBGraphInternal graph;

  public YTDBSchemaClassImpl(@Nonnull SchemaClassImpl schemaClass,
      @Nonnull YTDBGraphInternal graph) {
    this.schemaClass = schemaClass;

    this.name = schemaClass.getName();
    this.graph = graph;
  }

  @Override
  public boolean abstractClass() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.isAbstract();
    });
  }

  @Override
  public void abstractClass(boolean abstractClass) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);
      schemaClass.setAbstract(session, abstractClass);
    });
  }

  @Override
  public boolean strictMode() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.isStrictMode();
    });
  }

  @Override
  public void strictMode(boolean mode) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);
      schemaClass.setStrictMode(session, mode);
    });
  }

  @Override
  public boolean hasSuperClasses() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.hasSuperClasses();
    });
  }

  @Nonnull
  @Override
  public String name() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.getName();
    });
  }

  @Override
  public void name(@Nonnull String name) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);
      schemaClass.setName(session, name);
    });
    this.name = name;
  }

  @Override
  public @Nonnull String description() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.getDescription();
    });
  }

  @Override
  public void description(@Nonnull String description) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);
      schemaClass.setDescription(session, description);
    });
  }

  @Override
  public @Nonnull int[] collectionIds() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.getCollectionIds();
    });
  }

  @Override
  public @Nonnull int[] polymorphicCollectionIds() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.getPolymorphicCollectionIds();
    });
  }

  @Override
  public boolean isEdgeType() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.isEdgeType();
    });
  }

  @Override
  public boolean isVertexType() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.isVertexType();
    });
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> superClasses() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return mapToDomainClassIterator(schemaClass.getSuperClasses().iterator());
    });
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> subClasses() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return mapToDomainClassIterator(schemaClass.getSubclasses().iterator());
    });
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> descendants() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return mapToDomainClassIterator(schemaClass.getAllSubclasses().iterator());
    });
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaClass> parents() {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return mapToDomainClassIterator(schemaClass.getAllSuperClasses().iterator());
    });
  }

  @Override
  public void addSuperClass(@Nonnull YTDBSchemaClass superClass) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);

      var superSchemaClassImpl = (YTDBSchemaClassImpl) superClass;
      schemaClass.addSuperClass(session, superSchemaClassImpl.schemaClass);
    });
  }

  @Override
  public void removeSuperClass(@Nonnull YTDBSchemaClass superClass) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);

      var schemaClassImpl = (YTDBSchemaClassImpl) superClass;
      schemaClass.removeSuperClass(session, schemaClassImpl.schemaClass);
    });
  }

  @Override
  public boolean isSubClassOf(@Nonnull String className) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.isSubClassOf(className);
    });
  }

  @Override
  public boolean isSubClassOf(@Nonnull YTDBSchemaClass classInstance) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      var schemaClassImpl = (YTDBSchemaClassImpl) classInstance;
      return schemaClass.isSubClassOf(schemaClassImpl.schemaClass);
    });
  }

  @Override
  public boolean isSuperClassOf(@Nonnull String className) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return schemaClass.isSuperClassOf(className);
    });
  }

  @Override
  public boolean isSuperClassOf(@Nonnull YTDBSchemaClass classInstance) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      var schemaClassImpl = (YTDBSchemaClassImpl) classInstance;
      return schemaClass.isSuperClassOf(schemaClassImpl.schemaClass);
    });
  }

  @Override
  public String customProperty(@Nonnull String propertyName) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return schemaClass.getCustom(propertyName);
    });
  }

  @Override
  public void customProperty(@Nonnull String propertyName, @Nullable String propertyValue) {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);

      schemaClass.setCustom(session, propertyName, propertyValue);
    });
  }

  @Override
  public void removeCustomProperty(@Nonnull String propertyName) {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);

      schemaClass.removeCustom(session, propertyName);
    });
  }

  @Override
  public void clearCustomProperties() {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);

      schemaClass.clearCustom(session);
    });
  }

  @Nonnull
  @Override
  public Iterator<String> customPropertyNames() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return schemaClass.getCustomKeys().iterator();
    });
  }

  @Override
  public boolean hasCollectionId(int collectionId) {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return schemaClass.hasCollectionId(collectionId);
    });
  }

  @Override
  public boolean hasPolymorphicCollectionId(int collectionId) {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      return schemaClass.hasPolymorphicCollectionId(collectionId);
    });
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaProperty> declaredProperty(@Nonnull String... name) {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      if (name.length == 0) {
        return mapToDomainPropertyIterator(schemaClass.declaredProperties().iterator());
      }

      var filteredProperties = new ArrayList<YTDBSchemaProperty>(name.length);
      for (var property : name) {
        filteredProperties.add(
            new YTDBSchemaPropertyImpl(schemaClass.getDeclaredPropertyInternal(property), graph));
      }

      return filteredProperties.iterator();
    });
  }

  @Override
  public @Nonnull Iterator<YTDBSchemaProperty> schemaProperty(@Nonnull String... name) {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      if (name.length == 0) {
        return mapToDomainPropertyIterator(schemaClass.properties().iterator());
      }

      var filteredProperties = new ArrayList<YTDBSchemaProperty>(name.length);
      for (var property : name) {
        filteredProperties.add(
            new YTDBSchemaPropertyImpl(schemaClass.getPropertyInternal(property), graph));
      }

      return filteredProperties.iterator();
    });
  }

  @Override
  public @Nonnull YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);

      return new YTDBSchemaPropertyImpl(
          schemaClass.createProperty(session, propertyName, propertyType),
          graph);
    });
  }

  @Override
  public @Nonnull YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull YTDBSchemaClass linkedClass) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);

      var linkedSchemaClassImpl = (YTDBSchemaClassImpl) linkedClass;
      return new YTDBSchemaPropertyImpl(
          schemaClass.createProperty(session, propertyName, propertyType,
              linkedSchemaClassImpl.schemaClass), graph);
    });
  }

  @Override
  public @Nonnull YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull PropertyType linkedType) {
    checkIfDeleted();

    return graph.computeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);
      return new YTDBSchemaPropertyImpl(
          schemaClass.createProperty(session, propertyName, propertyType,
              linkedType), graph);
    });
  }

  @Override
  public void dropSchemaProperty(@Nonnull String propertyName) {
    checkIfDeleted();
    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);
      schemaClass.dropProperty(session, propertyName);
    });
  }

  @Override
  public boolean existsSchemaProperty(@Nonnull String propertyName) {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      return schemaClass.existsProperty(propertyName);
    });
  }

  @SuppressWarnings("rawtypes")
  @Override
  public YTDBDomainObjectPToken[] pTokens() {
    checkIfDeleted();
    return graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);

      var basicPTokens = YTDBSchemaClassPToken.values();

      var customPropertyNames = schemaClass.getCustomKeys();
      if (customPropertyNames.isEmpty()) {
        return basicPTokens;
      }

      var pTokens = new YTDBDomainObjectPToken[basicPTokens.length + customPropertyNames.size()];
      System.arraycopy(basicPTokens, 0, pTokens, 0, basicPTokens.length);

      var i = 0;
      for (var customPropertyName : customPropertyNames) {
        pTokens[basicPTokens.length + i] = new YTDBCustomSchemaClassPropertyPToken(
            customPropertyName);
        i++;
      }

      return pTokens;
    });
  }

  @Override
  public YTDBDomainObjectPToken<YTDBDomainObject> pToken(String name) {
    checkIfDeleted();

    //noinspection unchecked,rawtypes
    return (YTDBDomainObjectPToken) graph.computeSchemaCode(session -> {
      checkSchemaReadPermissions(session);
      try {
        return YTDBSchemaClassPToken.valueOf(name);
      } catch (IllegalArgumentException e) {
        return new YTDBCustomSchemaClassPropertyPToken(name);
      }
    });
  }


  @Override
  public RID id() {
    checkIfDeleted();

    if (recordId == null) {
      recordId = RecordId.tempRecordId();
    }

    return recordId;
  }

  @Override
  public YTDBGraph graph() {
    return graph;
  }

  @Override
  public void remove() {
    checkIfDeleted();

    graph.executeSchemaCode(session -> {
      checkSchemaUpdatePermissions(session);
      var schema = session.getSharedContext().getSchema();
      schema.dropClass(session, schemaClass.getName());
      schemaClass = null;
    });
  }

  @Nonnull
  public SchemaClassImpl getSchemaClass() {
    checkIfDeleted();

    return schemaClass;
  }

  public static void checkSchemaReadPermissions(DatabaseSessionEmbedded session) {
    session.checkSecurity(ResourceGeneric.SCHEMA, Role.PERMISSION_READ);
  }

  public static void checkSchemaUpdatePermissions(DatabaseSessionEmbedded session) {
    session.checkSecurity(ResourceGeneric.SCHEMA, Role.PERMISSION_UPDATE);
  }

  private void checkIfDeleted() {
    if (schemaClass == null) {
      throw schemaClassWasDeleted();
    }
  }

  private IllegalStateException schemaClassWasDeleted() {
    return new IllegalStateException("Schema class " + name + " has been deleted.");
  }

  private Iterator<YTDBSchemaClass> mapToDomainClassIterator(
      Iterator<SchemaClassImpl> schemaClassIterator) {
    return IteratorUtils.map(schemaClassIterator,
        schemaClass -> new YTDBSchemaClassImpl(schemaClass, graph));
  }

  private Iterator<YTDBSchemaProperty> mapToDomainPropertyIterator(
      Iterator<SchemaPropertyImpl> schemaPropertyIterator) {
    return IteratorUtils.map(schemaPropertyIterator,
        schemaProperty -> new YTDBSchemaPropertyImpl(schemaProperty,
            graph));
  }
}
