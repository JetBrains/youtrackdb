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
package com.jetbrains.youtrack.db.internal.core.exception;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;

/**
 * Exception thrown in case the execution of the command has been interrupted.
 */
public class CommandInterruptedException extends NeedRetryException {

  private static final long serialVersionUID = -7430575036316163711L;

  public CommandInterruptedException(CommandInterruptedException exception) {
    super(exception);
  }

  public CommandInterruptedException(String dbName, String message) {
    super(dbName, message);
  }

  public CommandInterruptedException(DatabaseSession session, String message) {
    super(session != null ? session.getDatabaseName() : null, message);
  }
}
