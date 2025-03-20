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
package com.jetbrains.youtrack.db.api.record;


import javax.annotation.Nonnull;

/**
 * Hook abstract class that calls separate methods for each hook defined.
 *
 * @see RecordHook
 */
public abstract class RecordHookAbstract implements RecordHook {
  /**
   * It's called just after the iRecord is created.
   *
   * @param iRecord The iRecord just created
   */
  public void onRecordCreate(final DBRecord iRecord) {
  }


  /**
   * It's called just after the iRecord is read.
   *
   * @param iRecord The iRecord just read
   */
  public void onRecordRead(final DBRecord iRecord) {
  }

  /**
   * It's called just after the iRecord is updated.
   *
   * @param iRecord The iRecord just updated
   */
  public void onRecordUpdate(final DBRecord iRecord) {
  }


  /**
   * It's called just after the iRecord is deleted.
   *
   * @param iRecord The iRecord just deleted
   */
  public void onRecordDelete(final DBRecord iRecord) {
  }

  public void onTrigger(@Nonnull final TYPE iType,
      @Nonnull final DBRecord record) {
    switch (iType) {
      case READ:
        onRecordRead(record);
        break;

      case UPDATE:
        onRecordUpdate(record);
        break;

      case DELETE:
        onRecordDelete(record);
        break;
    }
  }
}
