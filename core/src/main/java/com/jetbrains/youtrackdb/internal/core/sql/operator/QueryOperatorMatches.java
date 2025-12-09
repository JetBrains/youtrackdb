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
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * MATCHES operator. Matches the left value against the regular expression contained in the second
 * one.
 */
public class QueryOperatorMatches extends QueryOperatorEqualityNotNulls {

  public QueryOperatorMatches() {
    super("MATCHES", 5, false);
  }

  @Override
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    return this.matches(iLeft.toString(), (String) iRight, iContext);
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.NO_INDEX;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, final Object iLeft,
      final Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, final Object iLeft, final Object iRight) {
    return null;
  }

  private boolean matches(
      final String iValue, final String iRegex, final CommandContext iContext) {
    final var key = "MATCHES_" + iRegex.hashCode();
    var p = (Pattern) iContext.getVariable(key);
    if (p == null) {
      p = Pattern.compile(iRegex);
      iContext.setVariable(key, p);
    }
    return p.matcher(iValue).matches();
  }
}
