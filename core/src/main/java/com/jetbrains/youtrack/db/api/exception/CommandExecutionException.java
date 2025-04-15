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
package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.core.exception.CoreException;

public class CommandExecutionException extends CoreException {
  public CommandExecutionException(CommandExecutionException exception) {
    super(exception);
  }

  public CommandExecutionException(String message) {
    super(message);
  }

  public CommandExecutionException(String dbName, String message) {
    super(dbName, message);
  }

  public CommandExecutionException(DatabaseSession session, String message) {
    super(session, message);
  }
}
