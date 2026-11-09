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
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

public class AbstractWriteCache {

  /** Validates supplied-id booking arguments before either cache takes its metadata lock. */
  protected static int validateSuppliedFileId(String fileName, long fileId,
      boolean reconstructAuxiliary, String storageName) {
    final int intId = extractFileId(fileId);
    if (intId <= 0) {
      throw new IllegalArgumentException("Internal file identifier must be positive");
    }
    if (reconstructAuxiliary) {
      validateAuxiliaryReconstruction(fileName, storageName);
    }
    return intId;
  }

  /** The caller holds its metadata lock while this rule evaluates facts in conflict order. */
  protected static void validateSuppliedFileIdConflicts(String fileName, int intId,
      @Nullable Integer registered, boolean liveRegistrationExists, @Nullable String negativeName,
      BooleanSupplier aliasExists, BooleanSupplier openFileExists, BooleanSupplier diskFileExists,
      boolean reconstructAuxiliary, String storageName) {
    final boolean nameRegisteredWithOtherState =
        registered != null && (!reconstructAuxiliary || registered != -intId);
    final boolean idLiveUnderAnyName = liveRegistrationExists;
    final boolean negativeIdHeldByOtherName =
        negativeName != null && !negativeName.equals(fileName);
    final BooleanSupplier idHeldByAlias = aliasExists;
    // Do not build a disk path when an earlier conflict already rejects the booking.
    final BooleanSupplier backingFileExists =
        () -> openFileExists.getAsBoolean() || diskFileExists.getAsBoolean();

    if (nameRegisteredWithOtherState || idLiveUnderAnyName || negativeIdHeldByOtherName
        || idHeldByAlias.getAsBoolean() || backingFileExists.getAsBoolean()) {
      throw new StorageException(storageName,
          "Conflicting registration or backing file for " + fileName + " and id " + intId);
    }
  }

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
