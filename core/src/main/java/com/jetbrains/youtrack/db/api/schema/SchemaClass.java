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
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema class
 */
public interface SchemaClass extends Comparable<SchemaClass> {

  String EDGE_CLASS_NAME = "E";
  String VERTEX_CLASS_NAME = "V";

  enum ATTRIBUTES {
    NAME,
    SHORTNAME,
    SUPERCLASS,
    SUPERCLASSES,
    STRICT_MODE,
    ADD_CLUSTER,
    REMOVE_CLUSTER,
    CUSTOM,
    ABSTRACT,
    CLUSTER_SELECTION,
    DESCRIPTION
  }

  enum INDEX_TYPE {
    UNIQUE(true),
    NOTUNIQUE(true),
    FULLTEXT(true),
    /**
     * @deprecated can be used only as manual index and manual indexes are deprecated and will be
     * removed
     */
    @Deprecated
    DICTIONARY(false),
    PROXY(true),
    UNIQUE_HASH_INDEX(true),
    NOTUNIQUE_HASH_INDEX(true),
    DICTIONARY_HASH_INDEX(false),
    SPATIAL(true);

    private final boolean automaticIndexable;

    INDEX_TYPE(boolean iValue) {
      automaticIndexable = iValue;
    }

    boolean isAutomaticIndexable() {
      return automaticIndexable;
    }
  }

  boolean isAbstract();

  SchemaClass setAbstract(DatabaseSession session, boolean iAbstract);

  boolean isStrictMode();

  SchemaClass setStrictMode(DatabaseSession session, boolean iMode);

  @Deprecated
  SchemaClass getSuperClass(DatabaseSession session);

  @Deprecated
  SchemaClass setSuperClass(DatabaseSession session, SchemaClass iSuperClass);

  boolean hasSuperClasses();

  List<String> getSuperClassesNames();

  List<SchemaClass> getSuperClasses(DatabaseSession session);

  SchemaClass setSuperClasses(DatabaseSession session, List<? extends SchemaClass> classes);

  SchemaClass addSuperClass(DatabaseSession session, SchemaClass superClass);

  void removeSuperClass(DatabaseSession session, SchemaClass superClass);

  String getName();

  SchemaClass setName(DatabaseSession session, String iName);

  String getDescription();

  SchemaClass setDescription(DatabaseSession session, String iDescription);

  String getStreamableName();

  Collection<SchemaProperty> declaredProperties();

  Collection<SchemaProperty> properties(DatabaseSession session);

  Map<String, SchemaProperty> propertiesMap(DatabaseSession session);

  Collection<SchemaProperty> getIndexedProperties(DatabaseSession session);

  SchemaProperty getProperty(DatabaseSession session, String iPropertyName);

  SchemaProperty createProperty(DatabaseSession session, String iPropertyName, PropertyType iType);

  /**
   * Create a property in the class with the specified options.
   *
   * @param session
   * @param iPropertyName the name of the property.
   * @param iType         the type of the property.
   * @param iLinkedClass  in case of property of type
   *                      LINK,LINKLIST,LINKSET,LINKMAP,EMBEDDED,EMBEDDEDLIST,EMBEDDEDSET,EMBEDDEDMAP
   *                      can be specified a linked class in all the other cases should be null
   * @return the created property.
   */
  SchemaProperty createProperty(DatabaseSession session, String iPropertyName, PropertyType iType,
      SchemaClass iLinkedClass);

  /**
   * Create a property in the class with the specified options.
   *
   * @param session
   * @param iPropertyName the name of the property.
   * @param iType         the type of the property.
   * @param iLinkedType   in case of property of type EMBEDDEDLIST,EMBEDDEDSET,EMBEDDEDMAP can be
   *                      specified a linked type in all the other cases should be null
   * @return the created property.
   */
  SchemaProperty createProperty(DatabaseSession session, String iPropertyName, PropertyType iType,
      PropertyType iLinkedType);

  void dropProperty(DatabaseSession session, String iPropertyName);

  boolean existsProperty(DatabaseSession session, String iPropertyName);

  int[] getClusterIds();

  SchemaClass addClusterId(DatabaseSession session, int iId);

  SchemaClass setClusterSelection(DatabaseSession session, String iStrategyName);

  String getClusterSelectionStrategyName();

  SchemaClass addCluster(DatabaseSession session, String iClusterName);

  SchemaClass removeClusterId(DatabaseSession session, int iId);

  int[] getPolymorphicClusterIds();

  /**
   * @return all the subclasses (one level hierarchy only)
   */
  Collection<SchemaClass> getSubclasses(DatabaseSession session);

  /**
   * @return all the subclass hierarchy
   */
  Collection<SchemaClass> getAllSubclasses(DatabaseSession session);

  /**
   * @return all recursively collected super classes
   */
  Collection<SchemaClass> getAllSuperClasses(DatabaseSession session);

  /**
   * Tells if the current instance extends the passed schema class (iClass).
   *
   * @param iClassName
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isSuperClassOf(SchemaClass)
   */
  boolean isSubClassOf(DatabaseSession session, String iClassName);

  /**
   * Returns true if the current instance extends the passed schema class (iClass).
   *
   * @param iClass
   * @return true if the current instance extends the passed schema class (iClass).
   * @see #isSuperClassOf(SchemaClass)
   */
  boolean isSubClassOf(DatabaseSession session, SchemaClass iClass);

  /**
   * Returns true if the passed schema class (iClass) extends the current instance.
   *
   * @param iClass
   * @return Returns true if the passed schema class extends the current instance.
   * @see #isSubClassOf(SchemaClass)
   */
  boolean isSuperClassOf(DatabaseSession databaseSession, SchemaClass iClass);

  String getShortName();

  SchemaClass setShortName(DatabaseSession session, String shortName);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance and associated with database index.
   *
   * @param session
   * @param iName   Database index name
   * @param iType   Index type.
   * @param fields  Field names from which index will be created.
   */
  void createIndex(DatabaseSession session, String iName, INDEX_TYPE iType, String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance and associated with database index.
   *
   * @param session
   * @param iName   Database index name
   * @param iType   Index type.
   * @param fields  Field names from which index will be created.
   */
  void createIndex(DatabaseSession session, String iName, String iType, String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance.
   *
   * @param session
   * @param iName             Database index name.
   * @param iType             Index type.
   * @param iProgressListener Progress listener.
   * @param fields            Field names from which index will be created.
   */
  void createIndex(
      DatabaseSession session, String iName, INDEX_TYPE iType,
      ProgressListener iProgressListener,
      String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance.
   *
   * @param session
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
      DatabaseSession session, String iName,
      String iType,
      ProgressListener iProgressListener,
      Map<String, ?> metadata,
      String algorithm,
      String... fields);

  /**
   * Creates database index that is based on passed in field names. Given index will be added into
   * class instance.
   *
   * @param session
   * @param iName             Database index name.
   * @param iType             Index type.
   * @param iProgressListener Progress listener.
   * @param metadata          Additional parameters which will be added in index configuration
   *                          document as "metadata" field.
   * @param fields            Field names from which index will be created. @return Class index
   *                          registered inside of given class ans associated with database index.
   */
  void createIndex(
      DatabaseSession session, String iName,
      String iType,
      ProgressListener iProgressListener,
      Map<String, ?> metadata,
      String... fields);

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
  Set<String> getInvolvedIndexes(DatabaseSession session, Collection<String> fields);

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
   * @see #getInvolvedIndexes(DatabaseSession, Collection)
   */
  Set<String> getInvolvedIndexes(DatabaseSession session, String... fields);

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
  Set<String> getClassInvolvedIndexes(DatabaseSession session, Collection<String> fields);

  /**
   * @param session
   * @param fields  Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   * @see #getClassInvolvedIndexes(DatabaseSession, Collection)
   */
  Set<String> getClassInvolvedIndexes(DatabaseSession session, String... fields);

  /**
   * Indicates whether given fields are contained as first key fields in class indexes. Order of
   * fields does not matter. If there are indexes for the given set of fields in super class they
   * will be taken into account.
   *
   * @param session
   * @param fields  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   */
  boolean areIndexed(DatabaseSession session, Collection<String> fields);

  /**
   * @param session
   * @param fields  Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   * @see #areIndexed(DatabaseSession, Collection)
   */
  boolean areIndexed(DatabaseSession session, String... fields);

  /**
   * @return All indexes for given class, not the inherited ones.
   */
  Set<String> getClassIndexes(DatabaseSession session);

  /**
   * @return All indexes for given class and its super classes.
   */
  Set<String> getIndexes(DatabaseSession session);

  /**
   * @return true if this class represents a subclass of an edge class (E)
   */
  boolean isEdgeType(DatabaseSession session);

  /**
   * @return true if this class represents a subclass of a vertex class (V)
   */
  boolean isVertexType(DatabaseSession session);

  String getCustom(String iName);

  SchemaClass setCustom(DatabaseSession session, String iName, String iValue);

  void removeCustom(DatabaseSession session, String iName);

  void clearCustom(DatabaseSession session);

  Set<String> getCustomKeys();

  boolean hasClusterId(int clusterId);

  boolean hasPolymorphicClusterId(int clusterId);
}
