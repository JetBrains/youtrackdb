package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.decodeClassName;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;


public interface ImmutableSchemaClass {
  String EDGE_CLASS_NAME = "E";
  String VERTEX_CLASS_NAME = "V";

  enum ATTRIBUTES {
    NAME,
    SUPERCLASSES,
    STRICT_MODE,
    CUSTOM,
    ABSTRACT,
    DESCRIPTION
  }

  boolean isAbstract();

  boolean isStrictMode();

  boolean hasParentClasses();

  List<String> getParentClassesNames();

  List<? extends ImmutableSchemaClass> getParentClasses();

  String getName();

  String getDescription();

  Collection<? extends ImmutableSchemaProperty> getDeclaredProperties();

  Collection<? extends ImmutableSchemaProperty> getProperties();

  Map<String, ? extends ImmutableSchemaProperty> getPropertiesMap();

  ImmutableSchemaProperty getProperty(String propertyName);

  int[] getCollectionIds();

  int[] getPolymorphicCollectionIds();

  /**
   * @return all the subclasses (one level hierarchy only)
   */
  Collection<? extends ImmutableSchemaClass> getChildClasses();

  /**
   * @return all the subclass hierarchy
   */
  Collection<? extends ImmutableSchemaClass> getDescendantClasses();

  /**
   * @return all recursively collected super classes
   */
  Collection<? extends ImmutableSchemaClass> getAscendantClasses();

  /**
   * Tells if the current instance extends the passed schema class (iClass).
   *
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isParentOf(ImmutableSchemaClass)
   */
  boolean isChildOf(String iClassName);

  /**
   * Returns true if the current instance extends the passed schema class (iClass).
   *
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isParentOf(ImmutableSchemaClass)
   */
  boolean isChildOf(ImmutableSchemaClass iClass);

  /**
   * Returns true if the passed schema class (iClass) extends the current instance.
   *
   * @return Returns true if the passed schema class extends the current instance.
   * @see #isChildOf(ImmutableSchemaClass)
   */
  boolean isParentOf(ImmutableSchemaClass iClass);


  boolean existsProperty(String propertyName);

  /**
   * @return true if this class represents a subclass of an edge class (E)
   */
  boolean isEdgeType();

  /**
   * @return true if this class represents a subclass of a vertex class (V)
   */
  boolean isVertexType();

  String getCustom(String iName);

  Set<String> getCustomPopertiesNames();

  boolean hasCollectionId(int collectionId);

  boolean hasPolymorphicCollectionId(int collectionId);

  int getCollectionForNewInstance(final EntityImpl entity);

  Set<Index> getInvolvedIndexes(String... properties);

  Set<Index> getInvolvedIndexes(final Collection<String> properties);


  long count(DatabaseSessionInternal session);


  long count(DatabaseSessionInternal session, final boolean isPolymorphic);

  Set<Index> getClassInvolvedIndexes(String... properties);

  Set<Index> getClassInvolvedIndexes(final Collection<String> properties);

  Collection<Index> getClassIndexes();

  @Nullable
  Index getClassIndex(final String name);


  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p> If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param properties Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<String> getInvolvedIndexesNames(Collection<String> properties);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param properties Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getInvolvedIndexesNames(Collection)
   */
  Set<String> getInvolvedIndexesNames(String... properties);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>Indexes that related only to the given class will be returned.
   *
   * @param properties Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<String> getClassIndexNames(Collection<String> properties);

  /**
   * @param properties Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getClassIndexNames(Collection)
   */
  Set<String> getClassIndexNames(String... properties);

  /**
   * Indicates whether given fields are contained as first key fields in class indexes. Order of
   * fields does not matter. If there are indexes for the given set of fields in super class they
   * will be taken into account.
   *
   * @param properties Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   */
  boolean areIndexed(Collection<String> properties);

  /**
   * @param properties Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   * @see #areIndexed(Collection)
   */
  boolean areIndexed(String... properties);

  /**
   * @return All indexes for given class, not the inherited ones.
   */
  Set<String> getClassIndexNames();

  /**
   * @return All indexes for given class and its super classes.
   */
  Set<String> getIndexNames();

  /**
   * @return All indexes for given class and its super classes.
   */
  Set<Index> getIndexes();

  DatabaseSessionEmbedded getBoundToSession();

  boolean isUser();

  boolean isScheduler();

  boolean isRole();

  boolean isSecurityPolicy();

  boolean isFunction();

  boolean isSequence();

  default List<PropertyTypeInternal> extractFieldTypes(final String[] fieldNames) {
    final List<PropertyTypeInternal> types = new ArrayList<>(fieldNames.length);

    for (var fieldName : fieldNames) {
      if (!fieldName.equals("@rid")) {
        types.add(
            getProperty(
                decodeClassName(IndexDefinitionFactory.extractFieldName(fieldName))).getType());
      } else {
        types.add(PropertyTypeInternal.LINK);
      }
    }
    return types;
  }
}
