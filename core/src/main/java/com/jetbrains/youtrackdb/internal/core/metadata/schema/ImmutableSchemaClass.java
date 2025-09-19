package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassShared.decodeClassName;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


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

  enum INDEX_TYPE {
    UNIQUE,
    NOTUNIQUE,
    FULLTEXT,
    SPATIAL
  }

  boolean isAbstract();

  boolean isStrictMode();

  boolean hasSuperClasses();

  Iterator<String> getSuperClassesNames();

  Iterator<? extends ImmutableSchemaClass> getSuperClasses();

  String getName();

  String getDescription();

  Iterator<? extends ImmutableSchemaProperty> getDeclaredProperties();

  Iterator<? extends ImmutableSchemaProperty> getProperties();

  Map<String, ? extends ImmutableSchemaProperty> getPropertiesMap();

  ImmutableSchemaProperty getProperty(String propertyName);

  int[] getCollectionIds();

  int[] getPolymorphicCollectionIds();

  /**
   * @return all the subclasses (one level hierarchy only)
   */
  Iterator<? extends ImmutableSchemaClass> getSubclasses();

  /**
   * @return all the subclass hierarchy
   */
  Iterator<? extends ImmutableSchemaClass> getAllSubclasses();

  /**
   * @return all recursively collected super classes
   */
  Iterator<? extends ImmutableSchemaClass> getAllSuperClasses();

  /**
   * Tells if the current instance extends the passed schema class (iClass).
   *
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isSuperClassOf(ImmutableSchemaClass)
   */
  boolean isSubClassOf(String iClassName);

  /**
   * Returns true if the current instance extends the passed schema class (iClass).
   *
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isSuperClassOf(ImmutableSchemaClass)
   */
  boolean isSubClassOf(ImmutableSchemaClass iClass);

  /**
   * Returns true if the passed schema class (iClass) extends the current instance.
   *
   * @return Returns true if the passed schema class extends the current instance.
   * @see #isSubClassOf(ImmutableSchemaClass)
   */
  boolean isSuperClassOf(ImmutableSchemaClass iClass);


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

  Iterator<String> getCustomKeys();

  boolean hasCollectionId(int collectionId);

  boolean hasPolymorphicCollectionId(int collectionId);

  int getCollectionForNewInstance(final EntityImpl entity);

  Iterator<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session, String... fields);

  Iterator<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session,
      final Collection<String> fields);

  Iterator<Index> getIndexesInternal();

  void getIndexesInternal(DatabaseSessionInternal session, Collection<Index> indices);

  long count(DatabaseSessionInternal session);


  long count(DatabaseSessionInternal session, final boolean isPolymorphic);

  Iterator<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      String... fields);

  Iterator<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      final Collection<String> fields);

  Iterator<Index> getClassIndexesInternal();

  Index getClassIndex(DatabaseSessionInternal session, final String name);


  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Iterator<String> getInvolvedIndexes(DatabaseSessionInternal session, Collection<String> fields);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getInvolvedIndexes(DatabaseSessionInternal, Collection)
   */
  Iterator<String> getInvolvedIndexes(DatabaseSessionInternal session, String... fields);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>Indexes that related only to the given class will be returned.
   *
   * @param session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Iterator<String> getClassInvolvedIndexes(DatabaseSessionInternal session,
      Collection<String> fields);

  /**
   * @param session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getClassInvolvedIndexes(DatabaseSessionInternal, Collection)
   */
  Iterator<String> getClassInvolvedIndexes(DatabaseSessionInternal session, String... fields);

  /**
   * Indicates whether given fields are contained as first key fields in class indexes. Order of
   * fields does not matter. If there are indexes for the given set of fields in super class they
   * will be taken into account.
   *
   * @param session
   * @param fields  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   */
  boolean areIndexed(DatabaseSessionInternal session, Collection<String> fields);

  /**
   * @param session
   * @param fields  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   * @see #areIndexed(DatabaseSessionInternal, Collection)
   */
  boolean areIndexed(DatabaseSessionInternal session, String... fields);

  /**
   * @return All indexes for given class, not the inherited ones.
   */
  Iterator<String> getClassIndexes();

  /**
   * @return All indexes for given class and its super classes.
   */
  Iterator<String> getIndexes();

  SchemaClassShared getImplementation();

  DatabaseSessionEmbedded getBoundToSession();

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
