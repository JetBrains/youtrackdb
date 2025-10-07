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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Contains the description of a persistent class property.
 */
public interface SchemaProperty extends ImmutableSchemaProperty {

  void setName(String name);

  void setLinkedClass(SchemaClass schemaClass);

  void setLinkedType(@Nullable PropertyTypeInternal type);

  void setNotNull(boolean iNotNull);

  SchemaProperty setCollate(String iCollateName);

  void setCollate(Collate collate);

  void setMandatory(boolean mandatory);

  void setReadonly(boolean readonly);

  /**
   * @param min can be null
   * @see SchemaProperty#getMin()
   */
  void setMin(String min);


  /**
   * @param max can be null
   * @see SchemaProperty#getMax()
   */
  void setMax(String max);

  /**
   * @param defaultValue can be null
   * @see SchemaProperty#getDefaultValue()
   */
  void setDefaultValue(String defaultValue);

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
  String createIndex(final IndexType iType);

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
  String createIndex(IndexType iType, Map<String, Object> metadata);

  void setRegexp(String regexp);

  /**
   * Change the type. It checks for compatibility between the change of type.
   */
  void setType(final PropertyTypeInternal iType);

  void setCustomProperty(final String iName, final String iValue);

  void removeCustomProperty(final String iName);

  void clearCustomProperties();

  SchemaProperty setDescription(String iDescription);

  @Override
  SchemaClass getLinkedClass();

  @Override
  SchemaClass getOwnerClass();
}
