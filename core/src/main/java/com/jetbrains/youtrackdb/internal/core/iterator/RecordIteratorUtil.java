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
package com.jetbrains.youtrackdb.internal.core.iterator;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule;

public class RecordIteratorUtil {

  /// Check whether the current user is allowed to access the specified collection.
  public static void checkCollectionAccess(
      final DatabaseSessionInternal session,
      final int collectionId) {
    if (session.getStorage().isSystemCollection(collectionId)) {
      checkSystemCollectionAccess(session);
    }
  }

  /// Check whether the current user is allowed to access the specified collections.
  public static void checkCollectionsAccess(
      final DatabaseSessionInternal session,
      final int[] collectionIds
  ) {
    for (var collectionId : collectionIds) {
      if (session.getStorage().isSystemCollection(collectionId)) {
        checkSystemCollectionAccess(session);
        break;
      }
    }
  }

  private static void checkSystemCollectionAccess(final DatabaseSessionInternal session) {
    final var dbUser = session.getCurrentUser();
    if (dbUser != null) {
      dbUser.allow(session, Rule.ResourceGeneric.SYSTEM_COLLECTIONS, null, Role.PERMISSION_READ);
    }
  }
}
