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

package com.jetbrains.youtrack.db.internal.common.parser;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;

/**
 * Resolve entity class and descriptors using the paths configured.
 */
public class VariableParser {

  public static Object resolveVariables(
      final String iText,
      final String iBegin,
      final String iEnd,
      final VariableParserListener iListener) {
    return resolveVariables(iText, iBegin, iEnd, iListener, null);
  }

  public static Object resolveVariables(
      final String iText,
      final String iBegin,
      final String iEnd,
      final VariableParserListener iListener,
      final Object iDefaultValue) {
    if (iListener == null) {
      throw new IllegalArgumentException("Missed VariableParserListener listener");
    }

    var beginPos = iText.lastIndexOf(iBegin);
    if (beginPos == -1) {
      return iText;
    }

    var endPos = iText.indexOf(iEnd, beginPos + 1);
    if (endPos == -1) {
      return iText;
    }

    var pre = iText.substring(0, beginPos);
    var var = iText.substring(beginPos + iBegin.length(), endPos);
    var post = iText.substring(endPos + iEnd.length());

    var resolved = iListener.resolve(var);

    if (resolved == null) {
      if (iDefaultValue == null) {
        LogManager.instance()
            .info(
                VariableParser.class,
                "[VariableParser.resolveVariables] Property not found: %s",
                var);
      } else {
        resolved = iDefaultValue;
      }
    }

    if (pre.length() > 0 || post.length() > 0) {
      final var path = pre + (resolved != null ? resolved.toString() : "") + post;
      return resolveVariables(path, iBegin, iEnd, iListener);
    }

    return resolved;
  }
}
