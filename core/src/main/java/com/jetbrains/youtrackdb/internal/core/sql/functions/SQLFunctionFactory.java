/*
 * Copyright 2012 Geomatys.
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
package com.jetbrains.youtrackdb.internal.core.sql.functions;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import java.util.Set;

/**
 * Factory interface for creating and registering SQL functions.
 */
public interface SQLFunctionFactory {

  void registerDefaultFunctions(DatabaseSessionEmbedded db);

  boolean hasFunction(String iName, DatabaseSessionEmbedded session);

  /**
   * Returns the set of supported function names of this factory.
   *
   * @return Set of supported function names of this factory
   */
  Set<String> getFunctionNames(DatabaseSessionEmbedded session);

  /**
   * Create function for the given name. returned function may be a new instance each time or a
   * constant.
   *
   * @param name the function name to create
   * @param session the current database session
   * @return SQLFunction : created function
   * @throws CommandExecutionException : when function creation fail
   */
  SQLFunction createFunction(String name, DatabaseSessionEmbedded session)
      throws CommandExecutionException;
}
