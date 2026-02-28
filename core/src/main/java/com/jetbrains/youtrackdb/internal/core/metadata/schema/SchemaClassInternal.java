package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassImpl.decodeClassName;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaProperty;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface SchemaClassInternal extends SchemaClass {

  CollectionSelectionStrategy getCollectionSelection();

  int getCollectionForNewInstance(final EntityImpl entity);

  Set<Index> getInvolvedIndexesInternal(DatabaseSessionEmbedded session, String... fields);

  Set<Index> getInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      final Collection<String> fields);

  SchemaProperty createProperty(
      final String iPropertyName,
      final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType,
      final boolean unsafe);

  SchemaProperty createProperty(
      final String iPropertyName,
      final PropertyTypeInternal iType,
      final SchemaClass iLinkedClass,
      final boolean unsafe);

  Set<Index> getIndexesInternal();

  String getStreamableName();

  void getIndexesInternal(DatabaseSessionEmbedded session, Collection<Index> indices);

  long count(DatabaseSessionEmbedded session);

  void truncate();

  long count(DatabaseSessionEmbedded session, final boolean isPolymorphic);

  long approximateCount(DatabaseSessionEmbedded session);

  long approximateCount(DatabaseSessionEmbedded session, boolean isPolymorphic);

  SchemaPropertyInternal getPropertyInternal(String propertyName);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionEmbedded session, String... fields);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionEmbedded session,
      final Collection<String> fields);

  Set<Index> getClassIndexesInternal();

  Index getClassIndex(DatabaseSessionEmbedded session, final String name);

  SchemaClass set(final ATTRIBUTES attribute, final Object value);


  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param session the active database session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<String> getInvolvedIndexes(DatabaseSessionEmbedded session, Collection<String> fields);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param session the active database session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getInvolvedIndexes(DatabaseSessionEmbedded, Collection)
   */
  Set<String> getInvolvedIndexes(DatabaseSessionEmbedded session, String... fields);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>Indexes that related only to the given class will be returned.
   *
   * @param session the active database session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<String> getClassInvolvedIndexes(DatabaseSessionEmbedded session, Collection<String> fields);

  /**
   * Returns indexes that contain the given fields as their first keys.
   *
   * @param session the active database session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getClassInvolvedIndexes(DatabaseSessionEmbedded, Collection)
   */
  Set<String> getClassInvolvedIndexes(DatabaseSessionEmbedded session, String... fields);

  /**
   * Indicates whether given fields are contained as first key fields in class indexes. Order of
   * fields does not matter. If there are indexes for the given set of fields in super class they
   * will be taken into account.
   *
   * @param session the active database session
   * @param fields  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   */
  boolean areIndexed(DatabaseSessionEmbedded session, Collection<String> fields);

  /**
   * Checks whether the given fields are indexed as first key fields in class indexes.
   *
   * @param session the active database session
   * @param fields  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   * @see #areIndexed(DatabaseSessionEmbedded, Collection)
   */
  boolean areIndexed(DatabaseSessionEmbedded session, String... fields);

  /**
   * Returns all indexes defined directly on this class, not the inherited ones.
   *
   * @return All indexes for given class, not the inherited ones.
   */
  Set<String> getClassIndexes();

  /**
   * Returns all indexes for this class and its super classes.
   *
   * @return All indexes for given class and its super classes.
   */
  Set<String> getIndexes();

  SchemaClassImpl getImplementation();


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
