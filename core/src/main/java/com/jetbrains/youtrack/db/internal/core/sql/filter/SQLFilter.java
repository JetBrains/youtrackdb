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
package com.jetbrains.youtrack.db.internal.core.sql.filter;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandPredicate;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * Parsed query. It's built once a query is parsed.
 */
public class SQLFilter extends SQLPredicate implements CommandPredicate {

  public SQLFilter(
      final String iText, @Nonnull final CommandContext iContext, final String iFilterKeyword) {
    super(iContext);

    if (iText == null) {
      throw new IllegalArgumentException("Filter expression is null");
    }

    parserText = iText;
    parserTextUpperCase = iText.toUpperCase(Locale.ENGLISH);

    try {
      final var lastPos = parserGetCurrentPosition();
      final var lastText = parserText;
      final var lastTextUpperCase = parserTextUpperCase;

      text(iContext.getDatabaseSession(), parserText.substring(lastPos));

      parserText = lastText;
      parserTextUpperCase = lastTextUpperCase;
      parserMoveCurrentPosition(lastPos);

    } catch (QueryParsingException e) {
      if (e.getText() == null)
      // QUERY EXCEPTION BUT WITHOUT TEXT: NEST IT
      {
        throw BaseException.wrapException(
            new QueryParsingException(iContext.getDatabaseSession().getDatabaseName(),
                "Error on parsing query", parserText, parserGetCurrentPosition()),
            e, context.getDatabaseSession());
      }

      throw e;
    } catch (Exception e) {
      throw BaseException.wrapException(
          new QueryParsingException(iContext.getDatabaseSession().getDatabaseName(),
              "Error on parsing query", parserText, parserGetCurrentPosition()),
          e, iContext.getDatabaseSession());
    }

    this.rootCondition = resetOperatorPrecedence(rootCondition);
  }

  private SQLFilterCondition resetOperatorPrecedence(SQLFilterCondition iCondition) {
    if (iCondition == null) {
      return iCondition;
    }
    if (iCondition.left != null && iCondition.left instanceof SQLFilterCondition) {
      iCondition.left = resetOperatorPrecedence((SQLFilterCondition) iCondition.left);
    }

    if (iCondition.right != null && iCondition.right instanceof SQLFilterCondition right) {
      iCondition.right = resetOperatorPrecedence(right);
      if (iCondition.operator != null) {
        if (!right.inBraces
            && right.operator != null
            && right.operator.precedence < iCondition.operator.precedence) {
          var newLeft =
              new SQLFilterCondition(iCondition.left, iCondition.operator, right.left);
          right.setLeft(newLeft);
          resetOperatorPrecedence(right);
          return right;
        }
      }
    }

    return iCondition;
  }

  public Object evaluate(
      final Result iRecord, final EntityImpl iCurrentResult,
      final CommandContext iContext) {
    if (rootCondition == null) {
      return true;
    }

    return rootCondition.evaluate(iRecord, iCurrentResult, iContext);
  }

  public SQLFilterCondition getRootCondition() {
    return rootCondition;
  }

  @Override
  public String toString() {
    if (rootCondition != null) {
      return "Parsed: " + rootCondition;
    }
    return "Unparsed: " + parserText;
  }
}
