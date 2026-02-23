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

package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;

/**
 * Is used in together with {@link EntityFieldWalker} to visit all fields of current document.
 */
public interface EntityPropertiesVisitor {

  /**
   * Visits currently processed field.
   *
   * @param db
   * @param type       Filed type. May be null if absent in DB schema.
   * @param linkedType Linked type in case collection is processed. May be null if absent in DB
   *                   schema.
   * @param value      Field value.
   * @return New value of this field. If the same value is returned document content will not be
   * changed.
   */
  Object visitField(DatabaseSessionEmbedded db, PropertyTypeInternal type,
      PropertyTypeInternal linkedType, Object value);

  /**
   * Indicates whether we continue to visit document fields after current one or should stop fields
   * processing.
   *
   * @param type       Filed type. May be null if absent in DB schema.
   * @param linkedType Linked type in case collection is processed. May be null if absent in DB
   *                   schema.
   * @param value      Field value.
   * @param newValue   New value returned by
   *                   {@link #visitField(DatabaseSessionEmbedded, PropertyTypeInternal, PropertyTypeInternal,
   *                   Object)} method.
   * @return If false document processing will be stopped.
   */
  boolean goFurther(PropertyTypeInternal type, PropertyTypeInternal linkedType, Object value,
      Object newValue);

  /**
   * If currently processed value is collection or map of embedded documents or embedded document
   * itself then current method is called if it returns false then this collection will not be
   * visited.
   *
   * @param type       Filed type. May be null if absent in DB schema.
   * @param linkedType Linked type in case collection is processed. May be null if absent in DB
   *                   schema.
   * @param value      Field value.
   * @return If false currently processed collection of embedded documents will not be visited.
   */
  boolean goDeeper(PropertyTypeInternal type, PropertyTypeInternal linkedType, Object value);

  /**
   * Returns whether visited field values should be written back to the document.
   *
   * @return If false value returned by method
   * {@link #visitField(DatabaseSessionEmbedded, PropertyTypeInternal, PropertyTypeInternal, Object)} will not be
   * taken in account and field value will not be updated.
   */
  boolean updateMode();
}
