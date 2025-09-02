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
package com.jetbrains.youtrackdb.api.exception;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.internal.core.exception.CoreException;

/**
 * Generic Security exception. Used in cryptography.
 */
public class SecurityException extends CoreException implements HighLevelException {

  public SecurityException(SecurityException exception) {
    super(exception);
  }

  public SecurityException(String dbName, final String message) {
    super(dbName, message);
  }

  public SecurityException(BasicDatabaseSession<?, ?> session, final String message) {
    super(session != null ? session.getDatabaseName() : null, message);
  }

  public SecurityException(final String message) {
    super(message);
  }
}
