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
package com.jetbrains.youtrack.db.internal.core.metadata.sequence;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.DBSequence.CreateParams;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import java.util.Set;

/**
 * @since 3/2/2015
 */
public class SequenceLibraryProxy extends SequenceLibraryAbstract {

  public SequenceLibraryProxy(
      final SequenceLibraryImpl iDelegate, final DatabaseSessionInternal iDatabase) {
    super(iDelegate, iDatabase);
  }

  @Override
  public Set<String> getSequenceNames() {
    return delegate.getSequenceNames(session);
  }

  @Override
  public int getSequenceCount() {
    return delegate.getSequenceCount(session);
  }

  @Override
  public DBSequence getSequence(String iName) {
    return delegate.getSequence(session, iName);
  }


  @Override
  public DBSequence createSequence(
      String iName,
      SEQUENCE_TYPE sequenceType,
      CreateParams params)
      throws DatabaseException {
      return delegate.createSequence(session, iName, sequenceType, params);
  }

  @Override
  public void dropSequence(String iName) throws DatabaseException {
      delegate.dropSequence(session, iName);
  }

  @Override
  public void create() {
    SequenceLibraryImpl.create(session);
  }

  @Override
  public void load() {
    delegate.load(session);
  }

  @Override
  public void close() {
    delegate.close();
  }

  public SequenceLibraryImpl getDelegate() {
    return delegate;
  }
}
