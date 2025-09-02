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
package com.jetbrains.youtrackdb.internal.common.parser;

import javax.annotation.Nullable;

/**
 * Resolve system variables embedded in a String.
 */
public class SystemVariableResolver implements VariableParserListener {

  public static final String VAR_BEGIN = "${";
  public static final String VAR_END = "}";

  private static final SystemVariableResolver instance = new SystemVariableResolver();

  public static String resolveSystemVariables(final String iPath) {
    return resolveSystemVariables(iPath, null);
  }

  public static String resolveSystemVariables(final String iPath, final String iDefault) {
    if (iPath == null) {
      return iDefault;
    }

    return (String) VariableParser.resolveVariables(iPath, VAR_BEGIN, VAR_END, instance, iDefault);
  }

  public static String resolveVariable(final String variable) {
    return resolveVariable(variable, null);
  }

  @Nullable
  public static String resolveVariable(final String variable, final String iDefault) {
    if (variable == null) {
      return null;
    }

    var resolved = System.getProperty(variable);

    if (resolved == null)
    // TRY TO FIND THE VARIABLE BETWEEN SYSTEM'S ENVIRONMENT PROPERTIES
    {
      resolved = System.getenv(variable);
    }

    return resolved != null ? resolved : iDefault;
  }

  @Override
  public String resolve(final String variable) {
    return resolveVariable(variable);
  }
}
