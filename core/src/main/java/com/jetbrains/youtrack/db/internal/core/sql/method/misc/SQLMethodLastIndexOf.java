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
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;

/**
 *
 */
public class SQLMethodLastIndexOf extends AbstractSQLMethod {

  public static final String NAME = "lastindexof";

  public SQLMethodLastIndexOf() {
    super(NAME, 1, 2);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    final var toFind = IOUtils.getStringContent(iParams[0].toString());
    if (iParams.length > 1) {
      return iThis.toString().lastIndexOf(toFind, Integer.parseInt(iParams[1].toString()));
    } else {
      return iThis.toString().lastIndexOf(toFind);
    }
  }
}
