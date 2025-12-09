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
package com.jetbrains.youtrackdb.internal.core.sql.method.misc;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;

/**
 * Remove all the occurrences of elements from a collection.
 *
 * @see SQLMethodRemove
 */
public class SQLMethodRemoveAll extends AbstractSQLMethod {

  public static final String NAME = "removeall";

  public SQLMethodRemoveAll() {
    super(NAME, 1, -1);
  }

  @Override
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      final CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iParams != null && iParams.length > 0 && iParams[0] != null) {
      iParams =
          MultiValue.array(
              iParams,
              Object.class,
              new CallableFunction<Object, Object>() {

                @Override
                public Object call(final Object iArgument) {
                  if (iArgument instanceof String && ((String) iArgument).startsWith("$")) {
                    return iContext.getVariable((String) iArgument);
                  }
                  return iArgument;
                }
              });
      for (var o : iParams) {
        ioResult = MultiValue.remove(ioResult, o, true);
      }
    }

    return ioResult;
  }
}
