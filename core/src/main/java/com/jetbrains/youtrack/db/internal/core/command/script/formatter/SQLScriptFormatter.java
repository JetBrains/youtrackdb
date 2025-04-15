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
package com.jetbrains.youtrack.db.internal.core.command.script.formatter;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import javax.annotation.Nullable;

/**
 * SQL script formatter.
 */
public class SQLScriptFormatter implements ScriptFormatter {

  @Nullable
  public String getFunctionDefinition(DatabaseSessionInternal session, final Function f) {
    return null;
  }

  @Override
  public String getFunctionInvoke(DatabaseSessionInternal session, final Function iFunction,
      final Object[] iArgs) {
    // TODO: BIND ARGS
    return iFunction.getCode();
  }
}
