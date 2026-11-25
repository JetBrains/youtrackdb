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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;

/**
 * Factory for creating paginated storage collection instances.
 *
 * @since 10/8/13
 */
public final class StorageCollectionFactory {

  /** Builds an ordinary layout 28 family with its owner and lock domain fixed before publication. */
  public static PaginatedCollectionV2 createCollection(
      String name, AbstractStorage storage, int componentId) {
    return new PaginatedCollectionV2(name, PageFileRole.RECORD_DATA.layout27Extension(),
        PageFileRole.POSITION_MAP.layout27Extension(),
        PageFileRole.FREE_SPACE_MAP.layout27Extension(),
        PageFileRole.DIRTY_PAGE_BIT_SET.layout27Extension(), storage,
        new PageFileIdentity(PageFileRole.RECORD_DATA, componentId));
  }

  public static PaginatedCollection createCollection(
      final String name,
      final int configurationVersion,
      final int binaryVersion,
      final AbstractStorage storage) {
    return createCollection(name, configurationVersion, binaryVersion, storage, null);
  }

  /** Preserves version dispatch while selecting construction-time ownership when present. */
  public static PaginatedCollection createCollection(
      final String name,
      final int configurationVersion,
      final int binaryVersion,
      final AbstractStorage storage,
      final Integer componentId) {
    if (configurationVersion >= 0 && configurationVersion < 6) {
      throw new StorageException(storage.getName(),
          "You use deprecated version of storage collection, this version is not supported in current"
              + " implementation. Please do export/import or recreate database.");
    }

    return switch (binaryVersion) {
      case 0 -> throw new IllegalStateException(
          "Version 0 of collection is not supported with given configuration");
      case 1 -> throw new IllegalStateException(
          "Version 1 of collection is not supported with given configuration");
      case 2 -> throw new IllegalStateException(
          "Version 2 of collection is not supported with given configuration");
      case 3 -> componentId == null ? new PaginatedCollectionV2(name, storage)
          : createCollection(name, storage, componentId);
      default ->
          throw new IllegalStateException("Invalid binary version of collection " + binaryVersion);
    };
  }

  public static PaginatedCollection createCollection(
      final String name,
      final int binaryVersion,
      final AbstractStorage storage,
      final String dataExtension,
      final String cpmExtension,
      final String fsmExtension,
      final String dpbExtension) {
    if (binaryVersion != 3) {
      throw new IllegalStateException(
          "Binary version " + binaryVersion
              + " of collection is not supported, only version 3 is supported");
    }
    return new PaginatedCollectionV2(
        name, dataExtension, cpmExtension, fsmExtension, dpbExtension, storage);
  }
}
