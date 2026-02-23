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
package com.jetbrains.youtrackdb.internal.core.sql.functions.conversion;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.AbstractSQLMethod;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.text.ParseException;
import java.util.Date;
import javax.annotation.Nullable;

/**
 * Transforms a value to datetime. If the conversion is not possible, null is returned.
 */
public class SQLMethodAsDateTime extends AbstractSQLMethod {

  public static final String NAME = "asdatetime";

  public SQLMethodAsDateTime() {
    super(NAME, 0, 0);
  }

  @Override
  public String getSyntax() {
    return "asDatetime()";
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iThis != null) {
      if (iThis instanceof Date) {
        return iThis;
      } else if (iThis instanceof Number n) {
        return new Date(n.longValue());
      } else {
        try {
          return DateHelper.getDateTimeFormatInstance(iContext.getDatabaseSession())
              .parse(iThis.toString());
        } catch (ParseException e) {
          LogManager.instance().error(this, "Error during %s execution", e, NAME);
          // IGNORE IT: RETURN NULL
        }
      }
    }
    return null;
  }
}
