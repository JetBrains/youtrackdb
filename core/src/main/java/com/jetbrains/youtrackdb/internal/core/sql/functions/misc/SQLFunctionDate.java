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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import javax.annotation.Nullable;

/**
 * Builds a date object from the format passed. If no arguments are passed, than the system date is
 * built (like sysdate() function)
 *
 * @see SQLFunctionSysdate
 */
public class SQLFunctionDate extends SQLFunctionAbstract {

  public static final String NAME = "date";

  private final Date date;
  private DateFormat format;

  /**
   * Get the date at construction to have the same date for all the iteration.
   */
  public SQLFunctionDate() {
    super(NAME, 0, 3);
    date = new Date();
  }

  @Nullable
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    if (iParams.length == 0) {
      return date;
    }

    if (iParams[0] == null) {
      return null;
    }

    if (iParams[0] instanceof Number) {
      return new Date(((Number) iParams[0]).longValue());
    }

    var session = iContext.getDatabaseSession();
    if (format == null) {
      if (iParams.length > 1) {
        format = new SimpleDateFormat((String) iParams[1]);
        format.setTimeZone(DateHelper.getDatabaseTimeZone(session));
      } else {
        format = DateHelper.getDateTimeFormatInstance(session);
      }

      if (iParams.length == 3) {
        format.setTimeZone(TimeZone.getTimeZone(iParams[2].toString()));
      }
    }

    try {
      return format.parse((String) iParams[0]);
    } catch (ParseException e) {
      throw BaseException.wrapException(
          new QueryParsingException(session.getDatabaseName(),
              "Error on formatting date '"
                  + iParams[0]
                  + "' using the format: "
                  + ((SimpleDateFormat) format).toPattern()),
          e, session);
    }
  }

  public static boolean aggregateResults(final Object[] configuredParameters) {
    return false;
  }

  public String getSyntax(DatabaseSession session) {
    return "date([<date-as-string>] [,<format>] [,<timezone>])";
  }

  @Nullable
  @Override
  public Object getResult() {
    format = null;
    return null;
  }
}
