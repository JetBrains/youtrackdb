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
package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.command.CommandExecutorAbstract;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;

/**
 * SQL abstract Command Executor implementation.
 */
public abstract class CommandExecutorSQLAbstract extends CommandExecutorAbstract {

  public static final String KEYWORD_TIMEOUT = "TIMEOUT";
  public static final String COLLECTION_PREFIX = "COLLECTION:";
  public static final String CLASS_PREFIX = "CLASS:";
  public static final String INDEX_PREFIX = "INDEX:";

  public static final String INDEX_VALUES_PREFIX = "INDEXVALUES:";
  public static final String INDEX_VALUES_ASC_PREFIX = "INDEXVALUESASC:";
  public static final String INDEX_VALUES_DESC_PREFIX = "INDEXVALUESDESC:";

  public static final String METADATA_PREFIX = "METADATA:";
  public static final String METADATA_SCHEMA = "SCHEMA";
  public static final String METADATA_INDEXMGR = "INDEXMANAGER";

  @Override
  public boolean isIdempotent() {
    return false;
  }

  @Override
  protected void throwSyntaxErrorException(String dbName, final String iText) {
    throw new CommandSQLParsingException(dbName,
        iText + ". Use " + getSyntax(), parserText, parserGetPreviousPosition());
  }
}
