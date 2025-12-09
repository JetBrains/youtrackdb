/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrackdb.internal.core.sql.functions;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;

/**
 * This interface represents SQL functions whose implementation can rely on an index. If used in a
 * WHERE condition, this kind of function can be invoked to retrieve target records from an
 * underlying structure, like an index
 */
public interface IndexableSQLFunction extends SQLFunction {

  /**
   * returns all the entries belonging to the target that match the binary condition where this
   * function appears
   *
   * @param target     the query target
   * @param operator   the operator after the function, eg. in <code> select from Foo where
   *                   myFunction(name) &gt; 4</code> the operator is &gt;
   * @param rightValue the value that has to be compared to the function result, eg. in <code>
   *                   select from Foo where myFunction(name) &gt; 4</code> the right value is 4
   * @param ctx        the command context for this query
   * @param args       the function arguments, eg. in <code>select from Foo where myFunction(name)
   *                   &gt; 4
   *                   </code> the arguments are [name]
   * @return an iterable of records that match the condition; null means that the execution could
   * not be performed for some reason.
   */
  Iterable<Identifiable> searchFromTarget(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args);

  /**
   * estimates the number of entries returned by searchFromTarget() with these parameters
   *
   * @param target     the query target
   * @param operator   the operator after the function, eg. in <code> select from Foo where
   *                   myFunction(name) &gt; 4</code> the operator is &gt;
   * @param rightValue the value that has to be compared to the function result, eg. in <code>
   *                   select from Foo where myFunction(name) &gt; 4</code> the right value is 4
   * @param ctx        the command context for this query
   * @param args       the function arguments, eg. in <code>select from Foo where myFunction(name)
   *                   &gt; 4
   *                   </code> the arguments are [name]
   * @return an estimantion of how many entries will be returned by searchFromTarget() with these
   * parameters, -1 if the estimation cannot be done
   */
  long estimate(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args);

  /**
   * checks if the function can be used even on single record, not as an indexed function (even if
   * the index does not exist at all)
   *
   * @param target     the query target
   * @param operator   the operator after the function, eg. in <code> select from Foo where
   *                   myFunction(name) &gt; 4</code> the operator is &gt;
   * @param rightValue the value that has to be compared to the function result, eg. in <code>
   *                   select from Foo where myFunction(name) &gt; 4</code> the right value is 4
   * @param ctx        the command context for this query
   * @param args       the function arguments, eg. in <code>select from Foo where myFunction(name)
   *                   &gt; 4
   *                   </code> the arguments are [name]
   * @return true if the funciton can be calculated without the index. False otherwise
   */
  boolean canExecuteInline(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args);

  /**
   * Checks if this function can be used to fetch data from this target and with these arguments
   * (eg. if the index exists on this target and it's defined on these fields)
   *
   * @param target     the query target
   * @param operator   the operator after the function, eg. in <code> select from Foo where
   *                   myFunction(name) &gt; 4</code> the operator is &gt;
   * @param rightValue the value that has to be compared to the function result, eg. in <code>
   *                   select from Foo where myFunction(name) &gt; 4</code> the right value is 4
   * @param ctx        the command context for this query
   * @param args       the function arguments, eg. in <code>select from Foo where myFunction(name)
   *                   &gt; 4
   *                   </code> the arguments are [name]
   * @return True if the funciton can be used to fetch from an index. False otherwise
   */
  boolean allowsIndexedExecution(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args);

  /**
   * Checks if this function should be called even if the method {@link #searchFromTarget} is
   * executed.
   *
   * @param target     the query target
   * @param operator   the operator after the function, eg. in <code> select from Foo where
   *                   myFunction(name) &gt; 4</code> the operator is &gt;
   * @param rightValue the value that has to be compared to the function result, eg. in <code>
   *                   select from Foo where myFunction(name) &gt; 4</code> the right value is 4
   * @param ctx        the command context for this query
   * @param args       the function arguments, eg. in <code>select from Foo where myFunction(name)
   *                   &gt; 4
   *                   </code> the arguments are [name]
   * @return True if this function should be called even if the method {@link #searchFromTarget} is
   * executed. False otherwise
   */
  boolean shouldExecuteAfterSearch(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args);
}
