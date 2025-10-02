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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaClassEntity;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Schema class
 */
public interface SchemaClass extends ImmutableSchemaClass {

  void setAbstract(boolean iAbstract);

  void setStrictMode(boolean iMode);

  void setParents(@Nonnull List<? extends SchemaClass> classes);

  void addParentClass(SchemaClass parentClass);

  void removeSuperClass(SchemaClass parentClass);

  SchemaClass setName(String iName);

  SchemaClass setDescription(String iDescription);

  SchemaProperty createProperty(String iPropertyName, PropertyTypeInternal iType);

  void dropProperty(String iPropertyName);


  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance and associated with database index.
   *
   * @param iName      Database index name
   * @param indexType  Index type.
   * @param properties Field names from which index will be created.
   */
  void createIndex(String iName, INDEX_TYPE indexType, String... properties);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance.
   *
   * @param iName      Database index name.
   * @param indexType  Index type.
   * @param metadata   Additional parameters which will be added in index configuration document as
   *                   "metadata" field.
   * @param properties Field names from which index will be created. @return Class index registered
   *                   inside of given class ans associated with database index.
   */
  void createIndex(
      String iName,
      INDEX_TYPE indexType,
      Map<String, Object> metadata,
      String... properties);


  SchemaClass setCustom(String iName, String iValue);

  void removeCustom(String iName);

  void clearCustom();

  SchemaProperty createProperty(
      final String iPropertyName,
      final PropertyTypeInternal iType,
      final PropertyTypeInternal iLinkedType);

  SchemaProperty createProperty(
      final String iPropertyName,
      final PropertyTypeInternal iType,
      final SchemaClass iLinkedClass);

  void truncate();

  @Override
  List<SchemaClass> getParents();

  @Override
  Collection<SchemaProperty> getDeclaredProperties();

  @Override
  Collection<SchemaProperty> getProperties();

  @Override
  Map<String, SchemaProperty> getPropertiesMap();

  @Override
  Collection<SchemaClass> getChildren();

  @Override
  Collection<SchemaClass> getAscendants();

  @Override
  SchemaProperty getProperty(String propertyName);

  @Override
  Collection<SchemaClass> getDescendants();

  SchemaClassEntity getImplementation();
}
