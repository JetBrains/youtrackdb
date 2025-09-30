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
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Presentation of index that is used information and contained in entity {@link SchemaClass} .
 *
 * <p>This object cannot be created directly, use {@link
 * SchemaClass} manipulation method instead.
 */
public interface IndexDefinition extends IndexCallback {
  /**
   * @return Names of fields which given index is used to calculate key value. Order of fields is
   * important.
   */
  List<String> getProperties();

  /**
   * @return Names of fields and their index modifiers (like "by value" for fields that hold <code>
   * Map</code> values) which given index is used to calculate key value. Order of fields is
   * important.
   */
  List<String> getFieldsToIndex();

  /**
   * @return Name of the class which this index belongs to.
   */
  String getClassName();

  /**
   * {@inheritDoc}
   */
  boolean equals(Object index);

  /**
   * {@inheritDoc}
   */
  int hashCode();

  /**
   * {@inheritDoc}
   */
  String toString();

  /**
   * Calculates key value by passed in parameters.
   *
   * <p>If it is impossible to calculate key value by given parameters <code>null</code> will be
   * returned.
   *
   * @param transaction Currently active database session.
   * @param params      Parameters from which index key will be calculated.
   * @return Key value or null if calculation is impossible.
   */
  @Nullable
  Object createValue(FrontendTransaction transaction, List<?> params);

  /**
   * Calculates key value by passed in parameters.
   *
   * <p>If it is impossible to calculate key value by given parameters <code>null</code> will be
   * returned.
   *
   * @param transaction Currently active database session.
   * @param params      Parameters from which index key will be calculated.
   * @return Key value or null if calculation is impossible.
   */
  @Nullable
  Object createValue(FrontendTransaction transaction, Object... params);

  /**
   * Returns amount of parameters that are used to calculate key value. It does not mean that all
   * parameters should be supplied. It only means that if you provide more parameters they will be
   * ignored and will not participate in index key calculation.
   *
   * @return Amount of that are used to calculate key value. Call result should be equals to
   * {@code getTypes().length}.
   */
  int getParamCount();

  /**
   * Return types of values from which index key consist. In case of index that is built on single
   * entity property value single array that contains property type will be returned. In case of
   * composite indexes result will contain several key types.
   *
   * @return Types of values from which index key consist.
   */
  PropertyTypeInternal[] getTypes();

  boolean isAutomatic();

  Collate getCollate();

  boolean isNullValuesIgnored();

  void setCollate(Collate collate);
}
