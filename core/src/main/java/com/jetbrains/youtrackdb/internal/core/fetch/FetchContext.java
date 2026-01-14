/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.fetch;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.FetchException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

/**
 *
 */
public interface FetchContext {

  void onBeforeFetch(final EntityImpl iRootRecord) throws FetchException;

  void onAfterFetch(DatabaseSessionInternal db, final EntityImpl iRootRecord) throws FetchException;

  void onBeforeArray(
      DatabaseSessionInternal db, final EntityImpl iRootRecord,
      final String iFieldName,
      final Object iUserObject,
      final Identifiable[] iArray)
      throws FetchException;

  void onAfterArray(DatabaseSessionInternal db, final EntityImpl iRootRecord,
      final String iFieldName,
      final Object iUserObject)
      throws FetchException;

  void onBeforeCollection(
      DatabaseSessionInternal db, final EntityImpl iRootRecord,
      final String iFieldName,
      final Object iUserObject,
      final Iterable<?> iterable)
      throws FetchException;

  void onAfterCollection(
      DatabaseSessionInternal db, final EntityImpl iRootRecord, final String iFieldName,
      final Object iUserObject)
      throws FetchException;

  void onBeforeMap(DatabaseSessionInternal db, final EntityImpl iRootRecord,
      final String iFieldName,
      final Object iUserObject)
      throws FetchException;

  void onAfterMap(DatabaseSessionInternal db, final EntityImpl iRootRecord, final String iFieldName,
      final Object iUserObject)
      throws FetchException;

  void onBeforeDocument(
      DatabaseSessionInternal db, final EntityImpl iRecord,
      final EntityImpl entity,
      final String iFieldName,
      final Object iUserObject)
      throws FetchException;

  void onAfterDocument(
      DatabaseSessionInternal db, final EntityImpl iRootRecord,
      final EntityImpl entity,
      final String iFieldName,
      final Object iUserObject)
      throws FetchException;

  void onBeforeStandardField(
      final Object iFieldValue, final String iFieldName, final Object iUserObject,
      PropertyTypeInternal fieldType);

  void onAfterStandardField(
      final Object iFieldValue, final String iFieldName, final Object iUserObject,
      PropertyTypeInternal fieldType);

  boolean fetchEmbeddedDocuments();
}
