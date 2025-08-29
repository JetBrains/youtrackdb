/*
 *
 * Copyright 2013 Geomatys.
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
package com.jetbrains.youtrackdb.internal.core.sql.method.sequence;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.AbstractSQLMethod;

/**
 * Returns the next number of a sequence.
 */
public class SQLMethodNext extends AbstractSQLMethod {

  public static final String NAME = "next";

  public SQLMethodNext() {
    super(NAME, 0, 0);
  }

  @Override
  public String getSyntax() {
    return "next()";
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iThis == null) {
      throw new CommandSQLParsingException(iContext.getDatabaseSession().getDatabaseName(),
          "Method 'next()' can be invoked only on OSequence instances, while NULL was found");
    }

    if (!(iThis instanceof DBSequence)) {
      throw new CommandSQLParsingException(iContext.getDatabaseSession().getDatabaseName(),
          "Method 'next()' can be invoked only on OSequence instances, while '"
              + iThis.getClass()
              + "' was found");
    }

    try {
      return ((DBSequence) iThis).next(iContext.getDatabaseSession());
    } catch (DatabaseException exc) {
      var message = "Unable to execute command: " + exc.getMessage();
      LogManager.instance().error(this, message, exc, (Object) null);
      throw new CommandExecutionException(iContext.getDatabaseSession(), message);
    }
  }
}
