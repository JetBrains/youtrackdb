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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;


import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Contains the description of a persistent class property.
 */
public interface SchemaProperty extends ImmutableSchemaProperty {
  SchemaProperty setName(String iName);

  void set(ATTRIBUTES attribute, Object iValue);

  SchemaProperty setLinkedClass(SchemaClass oClass);

  SchemaProperty setLinkedType(@Nonnull PropertyType type);

  SchemaProperty setNotNull(boolean iNotNull);

  SchemaProperty setCollate(String iCollateName);

  SchemaProperty setCollate(Collate collate);

  SchemaProperty setMandatory(boolean mandatory);

  SchemaProperty setReadonly(boolean iReadonly);

  /**
   * @param min can be null
   * @return this property
   * @see SchemaProperty#getMin()
   */
  SchemaProperty setMin(String min);


  /**
   * @param max can be null
   * @return this property
   * @see SchemaProperty#getMax()
   */
  SchemaProperty setMax(String max);

  /**
   * @param defaultValue can be null
   * @return this property
   * @see SchemaProperty#getDefaultValue()
   */
  SchemaProperty setDefaultValue(String defaultValue);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param iType One of types supported.
   *              <ul>
   *                <li>UNIQUE: Doesn't allow duplicates
   *                <li>NOTUNIQUE: Allow duplicates
   *              </ul>
   */
  String createIndex(final INDEX_TYPE iType);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param iType
   * @return
   */
  String createIndex(final String iType);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param iType    One of types supported.
   *                 <ul>
   *                   <li>UNIQUE: Doesn't allow duplicates
   *                   <li>NOTUNIQUE: Allow duplicates
   *                   <li>FULLTEXT: Indexes single word for full text search
   *                 </ul>
   * @param metadata the index metadata
   * @return
   */
  String createIndex(String iType, Map<String, Object> metadata);

  /**
   * Creates an index on this property. Indexes speed up queries but slow down insert and update
   * operations. For massive inserts we suggest to remove the index, make the massive insert and
   * recreate it.
   *
   * @param iType    One of types supported.
   *                 <ul>
   *                   <li>UNIQUE: Doesn't allow duplicates
   *                   <li>NOTUNIQUE: Allow duplicates
   *                   <li>FULLTEXT: Indexes single word for full text search
   *                 </ul>
   * @param metadata the index metadata
   * @return Index name
   */
  String createIndex(INDEX_TYPE iType, Map<String, Object> metadata);

  SchemaProperty setRegexp(String regexp);

  /**
   * Change the type. It checks for compatibility between the change of type.
   */
  SchemaProperty setType(final PropertyType iType);

  SchemaProperty setCustom(final String iName, final String iValue);

  void removeCustom(final String iName);

  void clearCustom();

  SchemaProperty setDescription(String iDescription);

  @Override
  SchemaClass getLinkedClass();

  @Override
  SchemaClass getOwnerClass();
}
