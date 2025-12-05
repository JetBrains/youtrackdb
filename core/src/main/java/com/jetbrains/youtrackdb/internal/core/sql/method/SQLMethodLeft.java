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
package com.jetbrains.youtrackdb.internal.core.sql.method;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.AbstractSQLMethod;
import javax.annotation.Nullable;

/**
 * Returns the first characters from the beginning of the string.
 */
public class SQLMethodLeft extends AbstractSQLMethod {

  public static final String NAME = "left";

  public SQLMethodLeft() {
    super(NAME, 1, 1);
  }

  @Override
  public String getSyntax() {
    return "left(<characters>)";
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iParams[0] == null || iThis == null) {
      return null;
    }

    final var valueAsString = iThis.toString();

    final var len = Integer.parseInt(iParams[0].toString());
    return valueAsString.substring(0, len <= valueAsString.length() ? len : valueAsString.length());
  }
}
