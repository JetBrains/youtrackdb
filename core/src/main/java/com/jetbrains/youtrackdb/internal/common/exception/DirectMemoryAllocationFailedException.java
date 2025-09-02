/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrackdb.internal.common.exception;

/**
 * Exception is thrown if it is impossible to allocate new piece of direct memory from OS
 */
public class DirectMemoryAllocationFailedException extends SystemException {

  public DirectMemoryAllocationFailedException(
      DirectMemoryAllocationFailedException exception) {
    super(exception);
  }

  public DirectMemoryAllocationFailedException(String message) {
    super(message);
  }
}
