/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.api.schema;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Contains the description of a persistent class property.
 */
public interface SchemaProperty extends Comparable<SchemaProperty> {
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

  SchemaProperty setName(DatabaseSession session, String iName);

  void set(DatabaseSession session, ATTRIBUTES attribute, Object iValue);

  PropertyType getType();

  /**
   * Returns the linked class in lazy mode because while unmarshalling the class could be not loaded
   * yet.
   *
   * @return
   */
  SchemaClass getLinkedClass(DatabaseSession session);

  SchemaProperty setLinkedClass(DatabaseSession session, SchemaClass oClass);

  PropertyType getLinkedType();

  SchemaProperty setLinkedType(DatabaseSession session, PropertyType type);

  boolean isNotNull();

  SchemaProperty setNotNull(DatabaseSession session, boolean iNotNull);

  Collate getCollate();

  SchemaProperty setCollate(DatabaseSession session, String iCollateName);

  SchemaProperty setCollate(DatabaseSession session, Collate collate);

  boolean isMandatory();

  SchemaProperty setMandatory(DatabaseSession session, boolean mandatory);

  boolean isReadonly();

  SchemaProperty setReadonly(DatabaseSession session, boolean iReadonly);

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
   * @param session
   * @param min     can be null
   * @return this property
   * @see SchemaProperty#getMin()
   */
  SchemaProperty setMin(DatabaseSession session, String min);

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
   * @param session
   * @param max     can be null
   * @return this property
   * @see SchemaProperty#getMax()
   */
  SchemaProperty setMax(DatabaseSession session, String max);

  /**
   * Default value for the property; can be function
   *
   * @return String, can be null
   */
  String getDefaultValue();

  /**
   * @param session
   * @param defaultValue can be null
   * @return this property
   * @see SchemaProperty#getDefaultValue()
   */
  SchemaProperty setDefaultValue(DatabaseSession session, String defaultValue);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param session
   * @param iType   One of types supported.
   *                <ul>
   *                  <li>UNIQUE: Doesn't allow duplicates
   *                  <li>NOTUNIQUE: Allow duplicates
   *                  <li>FULLTEXT: Indexes single word for full text search
   *                </ul>
   */
  String createIndex(DatabaseSession session, final INDEX_TYPE iType);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param session
   * @param iType
   * @return
   */
  String createIndex(DatabaseSession session, final String iType);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param session
   * @param iType    One of types supported.
   *                 <ul>
   *                   <li>UNIQUE: Doesn't allow duplicates
   *                   <li>NOTUNIQUE: Allow duplicates
   *                   <li>FULLTEXT: Indexes single word for full text search
   *                 </ul>
   * @param metadata the index metadata
   * @return
   */
  String createIndex(DatabaseSession session, String iType, Map<String, ?> metadata);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param session
   * @param iType    One of types supported.
   *                 <ul>
   *                   <li>UNIQUE: Doesn't allow duplicates
   *                   <li>NOTUNIQUE: Allow duplicates
   *                   <li>FULLTEXT: Indexes single word for full text search
   *                 </ul>
   * @param metadata the index metadata
   * @return Index name
   */
  String createIndex(DatabaseSession session, INDEX_TYPE iType, Map<String, ?> metadata);

  /**
   * @return All indexes in which this property participates.
   */
  Collection<String> getAllIndexes(DatabaseSession session);

  String getRegexp();

  SchemaProperty setRegexp(DatabaseSession session, String regexp);

  /**
   * Change the type. It checks for compatibility between the change of type.
   */
  SchemaProperty setType(DatabaseSession session, final PropertyType iType);

  String getCustom(final String iName);

  SchemaProperty setCustom(DatabaseSession session, final String iName, final String iValue);

  void removeCustom(DatabaseSession session, final String iName);

  void clearCustom(DatabaseSession session);

  Set<String> getCustomKeys();

  SchemaClass getOwnerClass();

  Object get(DatabaseSession session, ATTRIBUTES iAttribute);

  Integer getId();

  String getDescription();

  SchemaProperty setDescription(DatabaseSession session, String iDescription);
}
