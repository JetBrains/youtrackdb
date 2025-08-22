package com.jetbrains.youtrack.db.api.gremlin.embedded.schema;

import com.jetbrains.youtrack.db.api.gremlin.embedded.YTDBDomainObject;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectInToken;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import com.jetbrains.youtrack.db.api.gremlin.tokens.schema.YTDBSchemaClassInToken;
import com.jetbrains.youtrack.db.api.gremlin.tokens.schema.YTDBSchemaClassOutToken;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/// Representation of database class in database schema.
///
/// YouTrackDB schema reflects standard OOP concepts such as inheritance and polymorphism.
/// YouTrackDB schema has notion of parent and child classes. Classes itself can be abstract.
///
/// Each class can have multiple parent classes and multiple child classes. Class inherits all
/// properties of parent classes.
///
/// Each vertex should be associated with schema class and 'label' in TinkerPop and 'class' in
/// YouTrackDB are two equivalent terms that can be used interchangeably.
///
/// Each class can store associated 'custom' properties that have meaning for concrete database
/// user.
///
/// Schema class is represented as special type of [org.apache.tinkerpop.gremlin.structure.Vertex]
/// that can be accessed only using special traversal step and can not be manipulated by standard
/// TinkerPop steps.
public interface YTDBSchemaClass extends YTDBDomainObject {

  String EDGE_CLASS_NAME = "E";
  String VERTEX_CLASS_NAME = "V";

  String LABEL = "$schemaClass";

  @SuppressWarnings("rawtypes")
  @Override
  default YTDBDomainObjectObjectOutToken[] outTokens() {
    return YTDBSchemaClassOutToken.values();
  }

  @SuppressWarnings("rawtypes")
  @Override
  default YTDBDomainObjectInToken[] inTokens() {
    return YTDBSchemaClassInToken.values();
  }

  @Override
  default YTDBDomainObjectInToken<YTDBDomainObject> inToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBDomainObjectInToken) YTDBSchemaClassInToken.valueOf(label);
  }

  @Override
  default YTDBDomainObjectObjectOutToken<YTDBDomainObject> outToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBDomainObjectObjectOutToken) YTDBSchemaClassOutToken.valueOf(label);
  }

  @Override
  default String label() {
    return LABEL;
  }

  boolean abstractClass();

  boolean strictMode();

  void strictMode(boolean mode);

  boolean hasSuperClasses();

  @Nonnull
  String name();

  void name(@Nonnull String name);

  @Nonnull
  String description();

  void description(@Nonnull String description);

  @Nonnull
  int[] collectionIds();

  @Nonnull
  int[] polymorphicCollectionIds();

  boolean isEdgeType();

  boolean isVertexType();

  @Nonnull
  Iterator<YTDBSchemaClass> superClasses();

  @Nonnull
  Iterator<YTDBSchemaClass> subClasses();

  @Nonnull
  Iterator<YTDBSchemaClass> descendants();

  @Nonnull
  Iterator<YTDBSchemaClass> parents();

  void addSuperClass(@Nonnull YTDBSchemaClass superClass);

  void removeSuperClass(@Nonnull YTDBSchemaClass superClass);

  boolean isSubClassOf(@Nonnull String className);

  boolean isSubClassOf(@Nonnull YTDBSchemaClass classInstance);

  boolean isSuperClassOf(@Nonnull String className);

  boolean isSuperClassOf(@Nonnull YTDBSchemaClass classInstance);

  @Nullable
  String customProperty(@Nonnull String propertyName);

  void customProperty(@Nonnull String propertyName, @Nullable String propertyValue);

  void removeCustomProperty(@Nonnull String propertyName);

  void clearCustomProperties();

  @Nonnull
  Iterator<String> customPropertyNames();

  boolean hasCollectionId(int collectionId);

  boolean hasPolymorphicCollectionId(int collectionId);

  @Nonnull
  Iterator<YTDBSchemaProperty> declaredProperty(@Nonnull String... name);

  @Nonnull
  Iterator<YTDBSchemaProperty> schemaProperty(@Nonnull String... name);

  @Nonnull
  YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType);

  @Nonnull
  YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull YTDBSchemaClass linkedClass);

  @Nonnull
  YTDBSchemaProperty createSchemaProperty(@Nonnull String propertyName,
      @Nonnull PropertyType propertyType,
      @Nonnull PropertyType linkedType);

  void dropSchemaProperty(@Nonnull String propertyName);

  boolean existsSchemaProperty(@Nonnull String propertyName);
}
