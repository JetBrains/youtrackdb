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

package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

/**
 * Dynamic script engine factory for YouTrackDB SQL commands.
 */
public class SQLScriptEngineFactory implements ScriptEngineFactory {

  private static final List<String> NAMES = new ArrayList<String>();
  private static final List<String> EXTENSIONS = new ArrayList<String>();

  static {
    NAMES.add(SQLScriptEngine.NAME);
    EXTENSIONS.add(SQLScriptEngine.NAME);
  }

  @Override
  public String getEngineName() {
    return SQLScriptEngine.NAME;
  }

  @Override
  public String getEngineVersion() {
    return YouTrackDBConstants.getVersion();
  }

  @Override
  public List<String> getExtensions() {
    return EXTENSIONS;
  }

  @Nullable
  @Override
  public List<String> getMimeTypes() {
    return null;
  }

  @Override
  public List<String> getNames() {
    return NAMES;
  }

  @Override
  public String getLanguageName() {
    return SQLScriptEngine.NAME;
  }

  @Override
  public String getLanguageVersion() {
    return YouTrackDBConstants.getVersion();
  }

  @Nullable
  @Override
  public Object getParameter(String key) {
    return null;
  }

  @Nullable
  @Override
  public String getMethodCallSyntax(String obj, String m, String... args) {
    return null;
  }

  @Nullable
  @Override
  public String getOutputStatement(String toDisplay) {
    return null;
  }

  @Override
  public String getProgram(String... statements) {
    final var buffer = new StringBuilder();
    for (var s : statements) {
      buffer.append(s).append(";\n");
    }
    return buffer.toString();
  }

  @Override
  public ScriptEngine getScriptEngine() {
    return new SQLScriptEngine(this);
  }
}
