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

import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.schema.IndexDefinition;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface Schema extends ImmutableSchema {
  @Nonnull
  SchemaClass createClass(String iClassName);

  @Nonnull
  SchemaClass createClass(@Nonnull String iClassName, @Nonnull SchemaClass iSuperClass);

  SchemaClass createClass(String iClassName, SchemaClass... superClasses);

  SchemaClass createAbstractClass(String iClassName);

  SchemaClass createAbstractClass(String iClassName, SchemaClass iSuperClass);

  SchemaClass createAbstractClass(String iClassName, SchemaClass... superClasses);

  /**
   * creates a new vertex class (a class that extends V)
   *
   * @param className the class name
   * @return The object representing the class in the schema
   * @throws SchemaException if the class already exists or if V class is not defined (Eg. if it was
   *                         deleted from the schema)
   */
  default SchemaClass createVertexClass(String className) throws SchemaException {
    return createClass(className, getClass(SchemaClass.VERTEX_CLASS_NAME));
  }

  /**
   * Creates a non-abstract new edge class (a class that extends E)
   *
   * @param className the class name
   * @return The object representing the class in the schema
   * @throws SchemaException if the class already exists or if E class is not defined (Eg. if it was
   *                         deleted from the schema)
   */
  default SchemaClass createEdgeClass(String className) {
    var edgeClass = createClass(className, getClass(SchemaClass.EDGE_CLASS_NAME));

    edgeClass.createProperty(Edge.DIRECTION_IN, PropertyType.LINK);
    edgeClass.createProperty(Edge.DIRECTION_OUT, PropertyType.LINK);

    return edgeClass;
  }

  /**
   * Creates a new edge class for lightweight edge (an abstract class that extends E)
   *
   * @param className the class name
   * @return The object representing the class in the schema
   * @throws SchemaException if the class already exists or if E class is not defined (Eg. if it was
   *                         deleted from the schema)
   */
  default SchemaClass createLightweightEdgeClass(String className) {
    return createAbstractClass(className, getClass(SchemaClass.EDGE_CLASS_NAME));
  }

  void dropClass(String iClassName);

  SchemaClass getOrCreateClass(String iClassName);

  SchemaClass getOrCreateClass(String iClassName, SchemaClass iSuperClass);

  SchemaClass getOrCreateClass(String iClassName, SchemaClass... superClasses);

  GlobalProperty createGlobalProperty(String name, PropertyType type, Integer id);


  @Nonnull
  SchemaClass createClass(@Nonnull String className, int collections,
      @Nonnull SchemaClass... superClasses);

  SchemaClass createClass(String iClassName, SchemaClass iSuperClass, int[] iCollectionIds);

  SchemaClass createClass(String className, int[] collectionIds, SchemaClass... superClasses);

  @Nullable
  @Override
  SchemaClass getClass(Class<?> iClass);

  @Override
  SchemaClass getClass(String iClassName);

  @Override
  Iterator<SchemaClass> getClasses();

  @Nullable
  @Override
  SchemaClass getClassByCollectionId(int collectionId);
}
