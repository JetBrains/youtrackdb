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
package com.jetbrains.youtrackdb.internal.core.storage.cache;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;

public class AbstractWriteCache {

  /** Rejects reconstruction unless the strict logical name identifies rebuildable metadata. */
  protected static void validateAuxiliaryReconstruction(String fileName, String storageName) {
    final var identity = PageFileNames.parseLayout26(fileName);
    if (identity.isPresent()) {
      switch (identity.get().role()) {
        case FREE_SPACE_MAP, DIRTY_PAGE_BIT_SET, CONFIG_FREE_SPACE_MAP,
            CONFIG_DIRTY_PAGE_BIT_SET, HISTOGRAM -> {
          return;
        }
        default -> {
          // Primary data and link bags are not reconstructible cache metadata.
        }
      }
    }
    throw new StorageException(storageName,
        "Auxiliary reconstruction requires a strict layout 26 rebuildable role: " + fileName);
  }

  public static long composeFileId(int storageId, int fileId) {
    return (((long) storageId) << 32) | fileId;
  }

  public static int extractFileId(long fileId) {
    return (int) (fileId & 0xFFFFFFFFL);
  }

  public static int extractStorageId(long fileId) {
    return (int) (fileId >>> 32);
  }

  public static long checkFileIdCompatibility(int storageId, long fileId) {
    final var intId = extractFileId(fileId);
    return composeFileId(storageId, intId);
  }
}
