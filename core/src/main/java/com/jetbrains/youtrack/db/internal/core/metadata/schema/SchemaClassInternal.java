package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl.decodeClassName;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionFactory;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface SchemaClassInternal extends SchemaClass {

  CollectionSelectionStrategy getCollectionSelection();

  int getCollectionForNewInstance(final EntityImpl entity);

  Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session, String... fields);

  Set<Index> getInvolvedIndexesInternal(DatabaseSessionInternal session,
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

  void getIndexesInternal(DatabaseSessionInternal session, Collection<Index> indices);

  long count(DatabaseSessionInternal session);

  void truncate();

  long count(DatabaseSessionInternal session, final boolean isPolymorphic);

  SchemaPropertyInternal getPropertyInternal(String propertyName);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session, String... fields);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSessionInternal session,
      final Collection<String> fields);

  Set<Index> getClassIndexesInternal();

  Index getClassIndex(DatabaseSessionInternal session, final String name);

  SchemaClass set(final ATTRIBUTES attribute, final Object value);


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
  Set<String> getInvolvedIndexes(DatabaseSessionInternal session, Collection<String> fields);

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
  Set<String> getInvolvedIndexes(DatabaseSessionInternal session, String... fields);

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
  Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session, Collection<String> fields);

  /**
   * @param session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getClassInvolvedIndexes(DatabaseSessionInternal, Collection)
   */
  Set<String> getClassInvolvedIndexes(DatabaseSessionInternal session, String... fields);

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
  Set<String> getClassIndexes();

  /**
   * @return All indexes for given class and its super classes.
   */
  Set<String> getIndexes();

  SchemaClassImpl getImplementation();


  DatabaseSession getBoundToSession();

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
