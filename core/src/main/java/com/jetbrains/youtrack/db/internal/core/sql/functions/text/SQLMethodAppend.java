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
package com.jetbrains.youtrack.db.internal.core.sql.functions.text;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.AbstractSQLMethod;

/**
 * Appends strings. Acts as a concatenation.
 */
public class SQLMethodAppend extends AbstractSQLMethod {

  public static final String NAME = "append";

  public SQLMethodAppend() {
    super(NAME, 1, -1);
  }

  @Override
  public String getSyntax() {
    return "append([<value|expression|field>]*)";
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iThis == null || iParams[0] == null) {
      return iThis;
    }

    final var buffer = new StringBuilder(iThis.toString());
    for (var i = 0; i < iParams.length; ++i) {
      if (iParams[i] != null) {
        buffer.append(iParams[i]);
      }
    }

    return buffer.toString();
  }
}
