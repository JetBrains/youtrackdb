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
package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLPredicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates a complex expression.
 */
public class SQLFunctionEval extends SQLFunctionMathAbstract {

  public static final String NAME = "eval";

  private SQLPredicate predicate;

  public SQLFunctionEval() {
    super(NAME, 1, 1);
  }

  @Override
  @Nullable
  public Object execute(
      Object iThis,
      final Result iRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      @Nonnull CommandContext context) {
    if (iParams.length < 1) {
      throw new CommandExecutionException(context.getDatabaseSession(), "invalid ");
    }
    if (predicate == null) {
      predicate = new SQLPredicate(context, String.valueOf(iParams[0]));
    }

    final var currentResult =
        iCurrentResult instanceof EntityImpl ? (EntityImpl) iCurrentResult : null;
    try {
      return predicate.evaluate(iRecord, currentResult, context);
    } catch (ArithmeticException e) {
      LogManager.instance().error(this, "Division by 0", e);
      // DIVISION BY 0
      return 0;
    } catch (Exception e) {
      LogManager.instance().error(this, "Error during division", e);
      return null;
    }
  }

  @Override
  public boolean aggregateResults() {
    return false;
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "eval(<expression>)";
  }

  @Nullable
  @Override
  public Object getResult() {
    return null;
  }

}