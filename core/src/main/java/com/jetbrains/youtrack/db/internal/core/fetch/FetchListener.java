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
package com.jetbrains.youtrack.db.internal.core.fetch;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.FetchException;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

/**
 * Listener interface used while fetching records.
 */
public interface FetchListener {

  /**
   * Returns true if the listener fetches he fields.
   */
  boolean requireFieldProcessing();

  /**
   * Fetch the linked field.
   *
   * @param iRoot
   * @param iFieldName
   * @param iLinked
   * @return null if the fetching must stop, otherwise the current field value
   */
  Object fetchLinked(
      final EntityImpl iRoot,
      final Object iUserObject,
      final String iFieldName,
      final EntityImpl iLinked,
      final FetchContext iContext)
      throws FetchException;

  void parseLinked(
      DatabaseSessionInternal db, final EntityImpl iRootRecord,
      final Identifiable iLinked,
      final Object iUserObject,
      final String iFieldName,
      final FetchContext iContext)
      throws FetchException;

  void parseLinkedCollectionValue(
      DatabaseSessionInternal db, final EntityImpl iRootRecord,
      final Identifiable iLinked,
      final Object iUserObject,
      final String iFieldName,
      final FetchContext iContext)
      throws FetchException;

  Object fetchLinkedMapEntry(
      final EntityImpl iRoot,
      final Object iUserObject,
      final String iFieldName,
      final String iKey,
      final EntityImpl iLinked,
      final FetchContext iContext)
      throws FetchException;

  Object fetchLinkedCollectionValue(
      final EntityImpl iRoot,
      final Object iUserObject,
      final String iFieldName,
      final EntityImpl iLinked,
      final FetchContext iContext)
      throws FetchException;

  void processStandardField(
      DatabaseSessionInternal db, final EntityImpl iRecord,
      final Object iFieldValue,
      final String iFieldName,
      final FetchContext iContext,
      final Object iUserObject,
      String iFormat,
      PropertyTypeInternal filedType)
      throws FetchException;

  void skipStandardField(
      final EntityImpl iRecord,
      final String iFieldName,
      final FetchContext iContext,
      final Object iUserObject,
      String iFormat)
      throws FetchException;
}
