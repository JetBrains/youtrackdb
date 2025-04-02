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
package com.jetbrains.youtrack.db.internal.core.sql.functions.conversion;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.LiveQueryListenerImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.AbstractSQLMethod;
import javax.annotation.Nullable;

/**
 * ONLY FOR LIVE QUERY. Returns the value of current record (as an Result) before it was updated.
 * Null if the record is new <br> eg. on update, get only records whose "name" attribute was update
 * <code> db.live("select from Person where @this.beforeUpdate().name != name
 * </code>
 */
public class SQLMethodBeforeUpdate extends AbstractSQLMethod {

  public static final String NAME = "beforeUpdate";

  public SQLMethodBeforeUpdate() {
    super(NAME, 0, 0);
  }

  @Override
  public String getSyntax() {
    return "beforeUpdate()";
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iThis instanceof ResultInternal internal) {
      return internal.getMetadata(LiveQueryListenerImpl.BEFORE_METADATA_KEY);
    }
    return null;
  }
}
