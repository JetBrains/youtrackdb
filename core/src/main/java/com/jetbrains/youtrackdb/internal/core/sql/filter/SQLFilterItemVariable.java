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
package com.jetbrains.youtrackdb.internal.core.sql.filter;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.parser.BaseParser;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import javax.annotation.Nullable;

/**
 * Represents a context variable as value in the query condition.
 */
public class SQLFilterItemVariable extends SQLFilterItemAbstract {

  protected String name;

  public SQLFilterItemVariable(DatabaseSessionEmbedded session, final BaseParser iQueryToParse,
      final String iName) {
    super(session, iQueryToParse, iName.substring(1));
  }

  @Nullable
  public Object getValue(
      final Result iRecord, Object iCurrentResult, final CommandContext iContext) {
    if (iContext == null) {
      return null;
    }

    return transformValue(iRecord, iContext, iContext.getVariable(name));
  }

  public String getRoot(DatabaseSession session) {
    return name;
  }

  public void setRoot(DatabaseSessionEmbedded session, final BaseParser iQueryToParse,
      final String iRoot) {
    this.name = iRoot;
  }

  @Override
  public String toString() {
    return "$" + super.toString();
  }
}
