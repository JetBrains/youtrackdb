package com.jetbrains.youtrackdb.api.gremlin.embedded.domain;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexBy;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
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
public interface YTDBSchemaClass extends YTDBDomainVertex {

  String EDGE_CLASS_NAME = "E";
  String VERTEX_CLASS_NAME = "V";

  String LABEL = "$schemaClass";

  @Override
  default String label() {
    return LABEL;
  }

  boolean abstractClass();

  void abstractClass(boolean value);

  boolean strictMode();

  void strictMode(boolean mode);

  boolean hasParentClasses();

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
  Iterator<YTDBSchemaClass> parentClasses();

  @Nonnull
  Iterator<YTDBSchemaClass> childClasses();

  @Nonnull
  Iterator<YTDBSchemaClass> descendants();

  @Nonnull
  Iterator<YTDBSchemaClass> ascendants();

  void addParentClass(@Nonnull YTDBSchemaClass parentClass);

  void removeParentClass(@Nonnull YTDBSchemaClass parentClass);

  void addChildClass(@Nonnull YTDBSchemaClass childClass);

  void removeChildClass(@Nonnull YTDBSchemaClass childClass);

  boolean isChildOf(@Nonnull String className);

  boolean isChildOf(@Nonnull YTDBSchemaClass classInstance);

  boolean isParentOf(@Nonnull String className);

  boolean isParentOf(@Nonnull YTDBSchemaClass classInstance);

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
  Iterator<YTDBSchemaProperty> declaredProperty(String... name);

  @Nonnull
  Iterator<YTDBSchemaProperty> schemaProperty(String... name);

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

  YTDBSchemaIndex createIndex(@Nonnull String indexName, IndexType indexType,
      String... propertyNames);

  YTDBSchemaIndex createIndex(@Nonnull String indexName, IndexType indexType,
      String[] propertyNames, IndexBy[] indexBy);

  Iterator<YTDBSchemaIndex> indexes(String... indexName);
}
