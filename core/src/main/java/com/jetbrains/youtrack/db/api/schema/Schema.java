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

import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface Schema {

  int countClasses();

  @Nonnull
  SchemaClass createClass(String iClassName);

  @Nonnull
  SchemaClass createClass(@Nonnull String iClassName, @Nonnull SchemaClass iSuperClass);

  @Nonnull
  SchemaClass createClass(@Nonnull String className, int clusters,
      @Nonnull SchemaClass... superClasses);

  SchemaClass createClass(String iClassName, SchemaClass... superClasses);

  SchemaClass createClass(String iClassName, SchemaClass iSuperClass, int[] iClusterIds);

  SchemaClass createClass(String className, int[] clusterIds, SchemaClass... superClasses);

  SchemaClass createAbstractClass(String iClassName);

  SchemaClass createAbstractClass(String iClassName, SchemaClass iSuperClass);

  SchemaClass createAbstractClass(String iClassName, SchemaClass... superClasses);

  void dropClass(String iClassName);

  boolean existsClass(String iClassName);

  @Nullable
  SchemaClass getClass(Class<?> iClass);

  /**
   * Returns the SchemaClass instance by class name.
   *
   * <p>If the class is not configured and the database has an entity manager with the requested
   * class as registered, then creates a schema class for it at the fly.
   *
   * <p>If the database nor the entity manager have not registered class with specified name,
   * returns null.
   *
   * @param iClassName Name of the class to retrieve
   * @return class instance or null if class with given name is not configured.
   */
  SchemaClass getClass(String iClassName);

  SchemaClass getOrCreateClass(String iClassName);

  SchemaClass getOrCreateClass(String iClassName, SchemaClass iSuperClass);

  SchemaClass getOrCreateClass(String iClassName, SchemaClass... superClasses);

  Collection<SchemaClass> getClasses();

  Collection<String> getIndexes();

  boolean indexExists(String indexName);

  @Nonnull
  IndexDefinition getIndexDefinition(String indexName);

  int getVersion();

  @Nullable
  SchemaClass getClassByClusterId(int clusterId);

  GlobalProperty getGlobalPropertyById(int id);

  List<GlobalProperty> getGlobalProperties();

  GlobalProperty createGlobalProperty(String name, PropertyType type, Integer id);
}
