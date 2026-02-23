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
package com.jetbrains.youtrackdb.internal.core.metadata.sequence;

import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import java.util.Set;

/**
 * Interface for managing database sequences within a schema.
 *
 * @since 3/2/2015
 */
public interface SequenceLibrary {

  Set<String> getSequenceNames();

  int getSequenceCount();

  DBSequence createSequence(String iName, SEQUENCE_TYPE sequenceType,
      DBSequence.CreateParams params)
      throws DatabaseException;

  DBSequence getSequence(String iName);

  void dropSequence(String iName) throws DatabaseException;

  @Deprecated
  void create();

  @Deprecated
  void close();

  @Deprecated
  void load();
}
