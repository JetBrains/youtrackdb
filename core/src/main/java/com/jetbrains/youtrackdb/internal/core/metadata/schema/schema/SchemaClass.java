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
package com.jetbrains.youtrackdb.internal.core.metadata.schema.schema;

import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema class
 */
public interface SchemaClass {

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

  SchemaClass setAbstract(boolean iAbstract);

  boolean isStrictMode();

  void setStrictMode(boolean iMode);

  boolean hasSuperClasses();

  List<String> getSuperClassesNames();

  List<SchemaClass> getSuperClasses();

  SchemaClass setSuperClasses(List<? extends SchemaClass> classes);

  SchemaClass addSuperClass(SchemaClass superClass);

  void removeSuperClass(SchemaClass superClass);

  String getName();

  SchemaClass setName(String iName);

  String getDescription();

  SchemaClass setDescription(String iDescription);

  Collection<SchemaProperty> getDeclaredProperties();

  Collection<SchemaProperty> getProperties();

  Map<String, SchemaProperty> getPropertiesMap();

  SchemaProperty getProperty(String iPropertyName);

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

  boolean existsProperty(String iPropertyName);

  int[] getCollectionIds();

  int[] getPolymorphicCollectionIds();

  /**
   * @return all the subclasses (one level hierarchy only)
   */
  Collection<SchemaClass> getSubclasses();

  /**
   * @return all the subclass hierarchy
   */
  Collection<SchemaClass> getAllSubclasses();

  /**
   * @return all recursively collected super classes
   */
  Collection<SchemaClass> getAllSuperClasses();

  /**
   * Tells if the current instance extends the passed schema class (iClass).
   *
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isSuperClassOf(SchemaClass)
   */
  boolean isSubClassOf(String iClassName);

  /**
   * Returns true if the current instance extends the passed schema class (iClass).
   *
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isSuperClassOf(SchemaClass)
   */
  boolean isSubClassOf(SchemaClass iClass);

  /**
   * Returns true if the passed schema class (iClass) extends the current instance.
   *
   * @return Returns true if the passed schema class extends the current instance.
   * @see #isSubClassOf(SchemaClass)
   */
  boolean isSuperClassOf(SchemaClass iClass);

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

  /**
   * @return true if this class represents a subclass of an edge class (E)
   */
  boolean isEdgeType();

  /**
   * @return true if this class represents a subclass of a vertex class (V)
   */
  boolean isVertexType();

  String getCustom(String iName);

  SchemaClass setCustom(String iName, String iValue);

  void removeCustom(String iName);

  void clearCustom();

  Set<String> getCustomKeys();

  boolean hasCollectionId(int collectionId);

  boolean hasPolymorphicCollectionId(int collectionId);
}
