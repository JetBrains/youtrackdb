/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionFactory;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorFactory;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic sql elements factory. Backs the runtime-mutable SPI surfaces consumed by
 * {@link SQLEngine}: the static {@code FUNCTIONS} map for
 * {@link SQLEngine#registerFunction}/{@link SQLEngine#unregisterFunction}, and the static
 * {@code OPERATORS} set for {@link SQLEngine#registerOperator}.
 */
public class DynamicSQLElementFactory
    implements QueryOperatorFactory, SQLFunctionFactory {

  // Used by SQLEngine to register on the fly new elements
  static final Map<String, Object> FUNCTIONS = new ConcurrentHashMap<String, Object>();
  static final Set<QueryOperator> OPERATORS =
      Collections.synchronizedSet(new HashSet<QueryOperator>());

  @Override
  public void registerDefaultFunctions(DatabaseSessionEmbedded db) {
    // DO NOTHING
  }

  @Override
  public Set<String> getFunctionNames(DatabaseSessionEmbedded session) {
    return FUNCTIONS.keySet();
  }

  @Override
  public boolean hasFunction(final String name, DatabaseSessionEmbedded session) {
    return FUNCTIONS.containsKey(name);
  }

  @Override
  public SQLFunction createFunction(final String name, DatabaseSessionEmbedded session)
      throws CommandExecutionException {
    final var obj = FUNCTIONS.get(name);

    if (obj == null) {
      throw new CommandExecutionException(session, "Unknown function name :" + name);
    }

    if (obj instanceof SQLFunction sqlFunction) {
      return sqlFunction;
    } else {
      // it's a class
      final var clazz = (Class<?>) obj;
      try {
        return (SQLFunction) clazz.newInstance();
      } catch (Exception e) {
        throw BaseException.wrapException(
            new CommandExecutionException(session,
                "Error in creation of function "
                    + name
                    + "(). Probably there is not an empty constructor or the constructor generates"
                    + " errors"),
            e, session);
      }
    }
  }

  @Override
  public Set<QueryOperator> getOperators() {
    return OPERATORS;
  }
}
