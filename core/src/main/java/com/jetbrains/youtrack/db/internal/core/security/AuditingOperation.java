/*
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
package com.jetbrains.youtrack.db.internal.core.security;

import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;

/**
 * Enumerates the available auditing AuditingOperation types.
 */
public enum AuditingOperation {
  UNSPECIFIED((byte) -1, "unspecified"),
  CREATED(RecordOperation.CREATED, "created"),
  LOADED((byte) 0, "loaded"),
  UPDATED(RecordOperation.UPDATED, "updated"),
  DELETED(RecordOperation.DELETED, "deleted"),
  COMMAND((byte) 4, "command"),
  CREATEDCLASS((byte) 5, "createdClass"),
  DROPPEDCLASS((byte) 6, "droppedClass"),
  CHANGEDCONFIG((byte) 7, "changedConfig"),
  NODEJOINED((byte) 8, "nodeJoined"),
  NODELEFT((byte) 9, "nodeLeft"),
  SECURITY((byte) 10, "security"),
  RELOADEDSECURITY((byte) 11, "reloadedSecurity"),
  CHANGED_PWD((byte) 12, "changedPassword");

  private final byte byteOp; // -1: unspecified;
  private final String stringOp;

  AuditingOperation(byte byteOp, String stringOp) {
    this.byteOp = byteOp;
    this.stringOp = stringOp;
  }

  public byte getByte() {
    return byteOp;
  }

  @Override
  public String toString() {
    return stringOp;
  }

  public static AuditingOperation getByByte(byte value) {
    for (var op : values()) {
      if (op.byteOp == value) {
        return op;
      }
    }

    return UNSPECIFIED;
  }
}
