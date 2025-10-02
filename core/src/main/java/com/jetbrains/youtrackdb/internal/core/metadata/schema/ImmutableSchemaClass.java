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

  List<? extends ImmutableSchemaClass> getParents();

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
  Collection<? extends ImmutableSchemaClass> getChildren();

  /**
   * @return all the subclass hierarchy
   */
  Collection<? extends ImmutableSchemaClass> getDescendants();

  /**
   * @return all recursively collected super classes
   */
  Collection<? extends ImmutableSchemaClass> getAscendants();

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

  Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session, String... properties);

  Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session,
      final Collection<String> properties);


  void getIndexes(DatabaseSessionEmbedded session, Collection<Index> indices);

  long count(DatabaseSessionInternal session);


  long count(DatabaseSessionInternal session, final boolean isPolymorphic);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      String... properties);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      final Collection<String> properties);

  Collection<Index> getClassIndexesInternal();

  @Nullable
  Index getClassIndex(DatabaseSessionInternal session, final String name);


  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param session
   * @param properties  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<String> getInvolvedIndexes(DatabaseSessionInternal session, Collection<String> properties);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param session
   * @param properties  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getInvolvedIndexes(DatabaseSessionInternal, Collection)
   */
  Set<String> getInvolvedIndexes(DatabaseSessionInternal session, String... properties);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>Indexes that related only to the given class will be returned.
   *
   * @param properties  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session,
      Collection<String> properties);

  /**
   * @param session
   * @param properties  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getClassInvolvedIndexes(DatabaseSessionInternal, Collection)
   */
  Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session, String... properties);

  /**
   * Indicates whether given fields are contained as first key fields in class indexes. Order of
   * fields does not matter. If there are indexes for the given set of fields in super class they
   * will be taken into account.
   *
   * @param session
   * @param properties  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   */
  boolean areIndexed(DatabaseSessionInternal session, Collection<String> properties);

  /**
   * @param session
   * @param properties  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   * @see #areIndexed(DatabaseSessionInternal, Collection)
   */
  boolean areIndexed(DatabaseSessionInternal session, String... properties);

  /**
   * @return All indexes for given class, not the inherited ones.
   */
  Set<String> getClassIndexes();

  /**
   * @return All indexes for given class and its super classes.
   */
  Set<String> getIndexes();

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
            PropertyTypeInternal.convertFromPublicType(getProperty(
                decodeClassName(IndexDefinitionFactory.extractFieldName(fieldName))).getType()));
      } else {
        types.add(PropertyTypeInternal.LINK);
      }
    }
    return types;
  }
}
