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
   * It's called just after the iRecord is read.
   *
   * @param record The iRecord just read
   */
  public void onRecordRead(final DBRecord record) {
  }

  /**
   * It's called just after the iRecord is created.
   *
   * @param record The iRecord just created
   */
  public void onBeforeRecordCreate(final DBRecord record) {
  }

  public void onAfterRecordCreate(final DBRecord record) {
  }

  /**
   * It's called just after the iRecord is updated.
   *
   * @param iRecord The iRecord just updated
   */
  public void onBeforeRecordUpdate(final DBRecord iRecord) {
  }

  public void onAfterRecordUpdate(final DBRecord iRecord) {
  }

  /**
   * It's called just after the iRecord is deleted.
   *
   * @param iRecord The iRecord just deleted
   */
  public void onBeforeRecordDelete(final DBRecord iRecord) {
  }

  public void onAfterRecordDelete(final DBRecord iRecord) {
  }

  @Override
  public void onTrigger(@Nonnull final TYPE iType,
      @Nonnull final DBRecord record) {
    switch (iType) {
      case READ:
        onRecordRead(record);
        break;

      case BEFORE_CREATE:
        onBeforeRecordCreate(record);
        break;

      case AFTER_CREATE:
        onAfterRecordCreate(record);
        break;

      case BEFORE_UPDATE:
        onBeforeRecordUpdate(record);
        break;

      case AFTER_UPDATE:
        onAfterRecordUpdate(record);
        break;

      case BEFORE_DELETE:
        onBeforeRecordDelete(record);
        break;

      case AFTER_DELETE:
        onAfterRecordDelete(record);
        break;
    }
  }
}
