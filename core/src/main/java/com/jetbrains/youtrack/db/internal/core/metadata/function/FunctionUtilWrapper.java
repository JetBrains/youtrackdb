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
package com.jetbrains.youtrack.db.internal.core.metadata.function;

/**
 * Wrapper of function with additional utility methods to help inside functions.
 */
public class FunctionUtilWrapper {

  public FunctionUtilWrapper() {
  }

  public boolean exists(final Object... iValues) {
    if (iValues != null) {
      for (var o : iValues) {
        if (o != null && !o.equals("undefined") && !o.equals("null")) {
          return true;
        }
      }
    }
    return false;
  }

  public Object value(final Object iValue) {
    return iValue;
  }
}
