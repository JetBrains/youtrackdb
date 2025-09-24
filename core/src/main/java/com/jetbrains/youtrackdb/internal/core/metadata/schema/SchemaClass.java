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

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Schema class
 */
public interface SchemaClass extends ImmutableSchemaClass {

  SchemaClass setAbstract(boolean iAbstract);

  void setStrictMode(boolean iMode);

  SchemaClass setSuperClasses(List<? extends SchemaClass> classes);

  SchemaClass addSuperClass(SchemaClass superClass);

  void removeSuperClass(SchemaClass superClass);

  SchemaClass setName(String iName);

  SchemaClass setDescription(String iDescription);

  SchemaProperty createProperty(String iPropertyName, PropertyType iType);

  /**
   * Create a property in the class with the specified options.
   *
   * @param iPropertyName the name of the property.
   * @param iType         the type of the property.
   * @param iLinkedClass  in case of property of type
   *                      LINK,LINKLIST,LINKSET,LINKMAP,EMBEDDED,EMBEDDEDLIST,EMBEDDEDSET,EMBEDDEDMAP
   *                      can be specified a linked class in all the other cases should be null
   * @return the created property.
   */
  SchemaProperty createProperty(String iPropertyName, PropertyType iType,
      SchemaClass iLinkedClass);

  /**
   * Create a property in the class with the specified options.
   *
   * @param iPropertyName the name of the property.
   * @param iType         the type of the property.
   * @param iLinkedType   in case of property of type EMBEDDEDLIST,EMBEDDEDSET,EMBEDDEDMAP can be
   *                      specified a linked type in all the other cases should be null
   * @return the created property.
   */
  SchemaProperty createProperty(String iPropertyName, PropertyType iType,
      PropertyType iLinkedType);

  void dropProperty(String iPropertyName);


  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance and associated with database index.
   *
   * @param iName  Database index name
   * @param iType  Index type.
   * @param fields Field names from which index will be created.
   */
  void createIndex(String iName, INDEX_TYPE iType, String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance and associated with database index.
   *
   * @param iName  Database index name
   * @param iType  Index type.
   * @param fields Field names from which index will be created.
   */
  void createIndex(String iName, String iType, String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance.
   *
   * @param iName             Database index name.
   * @param iType             Index type.
   * @param iProgressListener Progress listener.
   * @param fields            Field names from which index will be created.
   */
  void createIndex(
      String iName, INDEX_TYPE iType,
      ProgressListener iProgressListener,
      String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance.
   *
   * @param iName             Database index name.
   * @param iType             Index type.
   * @param iProgressListener Progress listener.
   * @param metadata          Additional parameters which will be added in index configuration
   *                          document as "metadata" field.
   * @param algorithm         Algorithm to use for indexing.
   * @param fields            Field names from which index will be created. @return Class index
   *                          registered inside of given class ans associated with database index.
   */
  void createIndex(
      String iName,
      String iType,
      ProgressListener iProgressListener,
      Map<String, Object> metadata,
      String algorithm,
      String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance.
   *
   * @param iName             Database index name.
   * @param iType             Index type.
   * @param iProgressListener Progress listener.
   * @param metadata          Additional parameters which will be added in index configuration
   *                          document as "metadata" field.
   * @param fields            Field names from which index will be created. @return Class index
   *                          registered inside of given class ans associated with database index.
   */
  void createIndex(
      String iName,
      String iType,
      ProgressListener iProgressListener,
      Map<String, Object> metadata,
      String... fields);


  SchemaClass setCustom(String iName, String iValue);

  void removeCustom(String iName);

  void clearCustom();

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

  void truncate();


  SchemaClass set(final ATTRIBUTES attribute, final Object value);

  @Override
  List<SchemaClass> getSuperClasses();

  @Override
  Collection<SchemaProperty> getDeclaredProperties();

  @Override
  Collection<SchemaProperty> getProperties();

  @Override
  Map<String, SchemaProperty> getPropertiesMap();

  @Override
  Collection<SchemaClass> getSubclasses();

  @Override
  Collection<SchemaClass> getAllSuperClasses();

  @Override
  SchemaProperty getProperty(String propertyName);

  @Override
  Collection<SchemaClass> getAllSubclasses();

  SchemaClassEntity getImplementation();
}
