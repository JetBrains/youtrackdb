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
package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.Collections;
import java.util.List;

/**
 * Base class for tools related to databases.
 */
public abstract class DatabaseTool implements Runnable {

  protected CommandOutputListener output;
  protected DatabaseSessionInternal session;
  protected boolean verbose = false;

  protected abstract void parseSetting(final String option, final List<String> items);

  protected void message(final String iMessage, final Object... iArgs) {
    if (output != null) {
      output.onMessage(String.format(iMessage, iArgs));
    }
  }

  public DatabaseTool setOptions(final String iOptions) {
    if (iOptions != null) {
      final var options = StringSerializerHelper.smartSplit(iOptions, ' ');
      for (var o : options) {
        final var sep = o.indexOf('=');
        if (sep == -1) {
          parseSetting(o, Collections.EMPTY_LIST);
        } else {
          final var option = o.substring(0, sep);
          final var value = IOUtils.getStringContent(o.substring(sep + 1));
          final var items = StringSerializerHelper.smartSplit(value, ' ');
          parseSetting(option, items);
        }
      }
    }
    return this;
  }

  public DatabaseTool setOutputListener(final CommandOutputListener iListener) {
    output = iListener;
    return this;
  }

  public DatabaseTool setDatabaseSession(final DatabaseSessionInternal session) {
    this.session = session;
    return this;
  }

  public DatabaseTool setVerbose(final boolean verbose) {
    this.verbose = verbose;
    return this;
  }
}
