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
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import javax.annotation.Nullable;

/**
 * returns the number of keys for an index
 */
public class SQLFunctionIndexKeySize extends SQLFunctionAbstract {

  public static final String NAME = "indexKeySize";

  public SQLFunctionIndexKeySize() {
    super(NAME, 1, 1);
  }

  @Override
  @Nullable
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext context) {
    final var value = iParams[0];

    var indexName = String.valueOf(value);
    final var database = context.getDatabaseSession();
    var index = database.getSharedContext().getIndexManager().getIndex(indexName);
    if (index == null) {
      return null;
    }
    try (var stream = index
        .ascEntries(context.getDatabaseSession())) {
      try (var rids = index.getRids(context.getDatabaseSession(), null)) {
        return stream.map(RawPair::first).distinct().count() + rids.count();
      }
    }
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "indexKeySize(<indexName-string>)";
  }
}
