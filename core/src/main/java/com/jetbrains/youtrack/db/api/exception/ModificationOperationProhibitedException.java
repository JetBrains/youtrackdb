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

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.CoreException;

/**
 * Exception is thrown in case DB is locked for modifications but modification request ist trying to
 * be acquired.
 *
 * @since 03.07.12
 */
public class ModificationOperationProhibitedException extends CoreException
    implements HighLevelException {
  public ModificationOperationProhibitedException(
      ModificationOperationProhibitedException exception) {
    super(exception);
  }

  public ModificationOperationProhibitedException(DatabaseSessionInternal db, String message) {
    super(db, message);
  }
}
