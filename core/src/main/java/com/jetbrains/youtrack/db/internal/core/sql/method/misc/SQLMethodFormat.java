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
package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 *
 */
public class SQLMethodFormat extends AbstractSQLMethod {

  public static final String NAME = "format";

  public SQLMethodFormat() {
    super(NAME, 1, 2);
  }

  @Override
  public Object execute(
      final Object iThis,
      final Result iRecord,
      final CommandContext iContext,
      Object ioResult,
      final Object[] iParams) {

    var session = iContext.getDatabaseSession();
    // TRY TO RESOLVE AS DYNAMIC VALUE
    var v = getParameterValue(session, iRecord, iParams[0].toString());
    if (v == null)
    // USE STATIC ONE
    {
      v = iParams[0].toString();
    }

    if (v != null) {
      if (isCollectionOfDates(ioResult)) {
        List<String> result = new ArrayList<String>();
        var iterator = MultiValue.getMultiValueIterator(ioResult);
        final var format = new SimpleDateFormat(v.toString());
        if (iParams.length > 1) {
          format.setTimeZone(TimeZone.getTimeZone(iParams[1].toString()));
        } else {
          format.setTimeZone(DateHelper.getDatabaseTimeZone(session));
        }
        while (iterator.hasNext()) {
          result.add(format.format(iterator.next()));
        }
        ioResult = result;
      } else if (ioResult instanceof Date) {
        final var format = new SimpleDateFormat(v.toString());
        if (iParams.length > 1) {
          format.setTimeZone(TimeZone.getTimeZone(iParams[1].toString()));
        } else {
          format.setTimeZone(DateHelper.getDatabaseTimeZone(session));
        }
        ioResult = format.format(ioResult);
      } else {
        ioResult = ioResult != null ? String.format(v.toString(), ioResult) : null;
      }
    }

    return ioResult;
  }

  private boolean isCollectionOfDates(Object ioResult) {
    if (MultiValue.isMultiValue(ioResult)) {
      var iterator = MultiValue.getMultiValueIterator(ioResult);
      while (iterator.hasNext()) {
        var item = iterator.next();
        if (item != null && !(item instanceof Date)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }
}
