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
package com.jetbrains.youtrackdb.internal.core.sql.functions.text;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.AbstractSQLMethod;

/**
 * Replaces all the occurrences.
 */
public class SQLMethodReplace extends AbstractSQLMethod {

  public static final String NAME = "replace";

  public SQLMethodReplace() {
    super(NAME, 2, 2);
  }

  @Override
  public String getSyntax() {
    return "replace(<to-find>, <to-replace>)";
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iThis == null || iParams[0] == null || iParams[1] == null) {
      return iParams[0];
    }

    return iThis.toString().replace(iParams[0].toString(), iParams[1].toString());
  }
}
