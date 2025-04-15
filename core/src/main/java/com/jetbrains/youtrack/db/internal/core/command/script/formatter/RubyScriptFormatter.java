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
import java.util.Scanner;

/**
 * Ruby script formatter
 */
public class RubyScriptFormatter implements ScriptFormatter {

  public String getFunctionDefinition(DatabaseSessionInternal session, final Function f) {

    final var fCode = new StringBuilder(1024);
    fCode.append("def ");
    fCode.append(f.getName());
    fCode.append('(');
    var i = 0;
    if (f.getParameters() != null) {
      for (var p : f.getParameters()) {
        if (i++ > 0) {
          fCode.append(',');
        }
        fCode.append(p);
      }
    }
    fCode.append(")\n");

    final var scanner = new Scanner(f.getCode());
    try {
      scanner.useDelimiter("\n").skip("\r");

      while (scanner.hasNext()) {
        fCode.append('\t');
        fCode.append(scanner.next());
      }
    } finally {
      scanner.close();
    }
    fCode.append("\nend\n");

    return fCode.toString();
  }

  @Override
  public String getFunctionInvoke(DatabaseSessionInternal session, final Function iFunction,
      final Object[] iArgs) {
    final var code = new StringBuilder(1024);

    code.append(iFunction.getName());
    code.append('(');
    if (iArgs != null) {
      var i = 0;
      for (var a : iArgs) {
        if (i++ > 0) {
          code.append(',');
        }
        code.append(a);
      }
    }
    code.append(");");

    return code.toString();
  }
}
