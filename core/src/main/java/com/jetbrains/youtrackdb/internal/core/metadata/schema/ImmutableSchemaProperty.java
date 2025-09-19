package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.Collection;
import java.util.Set;

public interface ImmutableSchemaProperty {

  enum ATTRIBUTES {
    LINKEDTYPE,
    LINKEDCLASS,
    MIN,
    MAX,
    MANDATORY,
    NAME,
    NOTNULL,
    REGEXP,
    TYPE,
    CUSTOM,
    READONLY,
    COLLATE,
    DEFAULT,
    DESCRIPTION
  }

  String getName();

  /**
   * Returns the full name as <class>.<property>
   */
  String getFullName();


  PropertyType getType();

  /**
   * Returns the linked class in lazy mode because while unmarshalling the class could be not loaded
   * yet.
   *
   * @return
   */
  ImmutableSchemaClass getLinkedClass();

  PropertyType getLinkedType();


  boolean isNotNull();

  Collate getCollate();

  boolean isMandatory();


  boolean isReadonly();


  /**
   * Min behavior depends on the Property PropertyType.
   *
   * <p>
   *
   * <ul>
   *   <li>String : minimum length
   *   <li>Number : minimum value
   *   <li>date and time : minimum time in millisecond, date must be written in the storage date
   *       format
   *   <li>binary : minimum size of the byte array
   *   <li>List,Set,Collection : minimum size of the collection
   * </ul>
   *
   * @return String, can be null
   */
  String getMin();

  /**
   * Max behavior depends on the Property PropertyType.
   *
   * <p>
   *
   * <ul>
   *   <li>String : maximum length
   *   <li>Number : maximum value
   *   <li>date and time : maximum time in millisecond, date must be written in the storage date
   *       format
   *   <li>binary : maximum size of the byte array
   *   <li>List,Set,Collection : maximum size of the collection
   * </ul>
   *
   * @return String, can be null
   */
  String getMax();


  /**
   * Default value for the property; can be function
   *
   * @return String, can be null
   */
  String getDefaultValue();

  String getRegexp();

  Set<String> getCustomKeys();

  ImmutableSchemaClass getOwnerClass();

  Object get(ATTRIBUTES iAttribute);

  Integer getId();

  String getDescription();

  String getCustom(final String iName);

  /**
   * @return All indexes in which this property participates.
   */
  Collection<String> getAllIndexes();

  DatabaseSession getBoundToSession();

  PropertyTypeInternal getTypeInternal();
}
