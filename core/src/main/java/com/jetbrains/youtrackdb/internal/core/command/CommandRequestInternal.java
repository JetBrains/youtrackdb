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
package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import java.util.Map;

/**
 * Internal specialization of generic Command interface.
 */
public interface CommandRequestInternal extends CommandRequest {

  Map<Object, Object> getParameters();

  CommandResultListener getResultListener();

  void setResultListener(CommandResultListener iListener);

  ProgressListener getProgressListener();

  CommandRequestInternal setProgressListener(ProgressListener iProgressListener);

  void reset();

  boolean isCacheableResult();

  void setCacheableResult(boolean iValue);

  /**
   * Communicate to a listener if the result set is an record based or anything else
   *
   * @param recordResultSet true if the result set contains records, false otherwise
   */
  void setRecordResultSet(boolean recordResultSet);

  boolean isRecordResultSet();
}
