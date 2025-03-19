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
   * Called on unregistration.
   */
  public void onUnregister() {
  }

  /**
   * It's called just before to create the new iRecord.
   *
   * @param iRecord The iRecord to create
   * @return True if the iRecord has been modified and a new marshalling is required, otherwise
   * false
   */
  public RESULT onRecordBeforeCreate(final DBRecord iRecord) {
    return RESULT.RECORD_NOT_CHANGED;
  }

  /**
   * It's called just after the iRecord is created.
   *
   * @param iRecord The iRecord just created
   */
  public void onRecordAfterCreate(final DBRecord iRecord) {
  }

  public void onRecordCreateFailed(final DBRecord iRecord) {
  }

  /**
   * It's called just before to read the iRecord.
   *
   * @param iRecord The iRecord to read
   */
  public void onRecordBeforeRead(final DBRecord iRecord) {
  }

  /**
   * It's called just after the iRecord is read.
   *
   * @param iRecord The iRecord just read
   */
  public void onRecordAfterRead(final DBRecord iRecord) {
  }

  public void onRecordReadFailed(final DBRecord iRecord) {
  }

  /**
   * It's called just before to update the iRecord.
   *
   * @param iRecord The iRecord to update
   * @return True if the iRecord has been modified and a new marshalling is required, otherwise
   * false
   */
  public RESULT onRecordBeforeUpdate(final DBRecord iRecord) {
    return RESULT.RECORD_NOT_CHANGED;
  }

  /**
   * It's called just after the iRecord is updated.
   *
   * @param iRecord The iRecord just updated
   */
  public void onRecordAfterUpdate(final DBRecord iRecord) {
  }

  public void onRecordUpdateFailed(final DBRecord iRecord) {
  }

  /**
   * It's called just before to delete the iRecord.
   *
   * @param iRecord The iRecord to delete
   * @return True if the iRecord has been modified and a new marshalling is required, otherwise
   * false
   */
  public RESULT onRecordBeforeDelete(final DBRecord iRecord) {
    return RESULT.RECORD_NOT_CHANGED;
  }

  /**
   * It's called just after the iRecord is deleted.
   *
   * @param iRecord The iRecord just deleted
   */
  public void onRecordAfterDelete(final DBRecord iRecord) {
  }

  public void onRecordDeleteFailed(final DBRecord iRecord) {
  }

  public void onRecordFinalizeUpdate(final DBRecord record) {
  }

  public void onRecordFinalizeCreation(final DBRecord record) {
  }

  public void onRecordFinalizeDeletion(final DBRecord record) {
  }

  public RESULT onTrigger(@Nonnull final TYPE iType,
      @Nonnull final DBRecord record) {
    switch (iType) {
      case BEFORE_CREATE:
        return onRecordBeforeCreate(record);

      case AFTER_CREATE:
        onRecordAfterCreate(record);
        break;

      case CREATE_FAILED:
        onRecordCreateFailed(record);
        break;

      case BEFORE_READ:
        onRecordBeforeRead(record);
        break;

      case AFTER_READ:
        onRecordAfterRead(record);
        break;

      case READ_FAILED:
        onRecordReadFailed(record);
        break;

      case BEFORE_UPDATE:
        return onRecordBeforeUpdate(record);

      case AFTER_UPDATE:
        onRecordAfterUpdate(record);
        break;

      case UPDATE_FAILED:
        onRecordUpdateFailed(record);
        break;

      case BEFORE_DELETE:
        return onRecordBeforeDelete(record);

      case AFTER_DELETE:
        onRecordAfterDelete(record);
        break;

      case DELETE_FAILED:
        onRecordDeleteFailed(record);
        break;

      case FINALIZE_CREATION:
        onRecordFinalizeCreation(record);
        break;

      case FINALIZE_UPDATE:
        onRecordFinalizeUpdate(record);
        break;

      case FINALIZE_DELETION:
        onRecordFinalizeDeletion(record);
        break;
    }
    return RESULT.RECORD_NOT_CHANGED;
  }
}
