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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import javax.annotation.Nullable;

/**
 * Mostly used for testing purpose. It just throws an ConcurrentModificationException
 */
public class SQLFunctionThrowCME extends SQLFunctionAbstract {

  public static final String NAME = "throwCME";

  /**
   * Get the date at construction to have the same date for all the iteration.
   */
  public SQLFunctionThrowCME() {
    super(NAME, 4, 4);
  }

  @Override
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    throw new ConcurrentModificationException(iContext.getDatabaseSession().getDatabaseName()
        , (RecordIdInternal) iParams[0], (int) iParams[1], (int) iParams[2], (int) iParams[3]);
  }

  public boolean aggregateResults(final Object[] configuredParameters) {
    return false;
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "throwCME(RID, DatabaseVersion, RecordVersion, RecordOperation)";
  }

  @Nullable
  @Override
  public Object getResult() {
    return null;
  }
}
