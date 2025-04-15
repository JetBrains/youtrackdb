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
package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Script utility class
 *
 * @see CommandScript
 */
public class CommandExecutorUtility {

  private static final Method java8MethodIsArray;

  static {
    Method isArray = null;

    if (!GlobalConfiguration.SCRIPT_POLYGLOT_USE_GRAAL.getValueAsBoolean()) {
      try {
        isArray =
            Class.forName("jdk.nashorn.api.scripting.JSObject").getDeclaredMethod("isArray", null);
      } catch (LinkageError
               | ClassNotFoundException
               | NoSuchMethodException
               | SecurityException ignore) {
      }
    }

    java8MethodIsArray = isArray;
  }

  /**
   * Manages cross compiler compatibility issues.
   *
   * @param result Result to transform
   */
  public static Object transformResult(Object result) {
    if (java8MethodIsArray == null || !(result instanceof Map)) {
      return result;
    }
    // PATCH BY MAT ABOUT NASHORN RETURNING VALUE FOR ARRAYS.
    try {
      if ((Boolean) java8MethodIsArray.invoke(result)) {
        List<?> partial = new ArrayList(((Map) result).values());
        List<Object> finalResult = new ArrayList<Object>();
        for (var o : partial) {
          finalResult.add(transformResult(o));
        }
        return finalResult;
      } else {
        Map<Object, Object> mapResult = (Map) result;
        List<Object> keys = new ArrayList<Object>(mapResult.keySet());
        for (var key : keys) {
          mapResult.put(key, transformResult(mapResult.get(key)));
        }
        return mapResult;
      }
    } catch (Exception e) {
      LogManager.instance().error(CommandExecutorUtility.class, "", e);
    }

    return result;
  }
}
