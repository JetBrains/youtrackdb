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

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SequenceException;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @since 3/2/2015
 */
public class SequenceLibraryImpl {

  public static final String DROPPED_SEQUENCES_MAP = "droppedSequencesMap";
  private final Map<String, DBSequence> sequences = new ConcurrentHashMap<>();
  private final AtomicBoolean reloadNeeded = new AtomicBoolean(false);

  public static void create(DatabaseSessionInternal database) {
    init(database);
  }

  public synchronized void load(final DatabaseSessionInternal session) {
    sequences.clear();

    if (session.getMetadata().getImmutableSchemaSnapshot().existsClass(DBSequence.CLASS_NAME)) {
      try (final var result = session.query("SELECT FROM " + DBSequence.CLASS_NAME)) {
        while (result.hasNext()) {
          var res = result.next();

          final var sequence =
              SequenceHelper.createSequence((EntityImpl) res.asEntity());
          sequences.put(sequence.getName(session).toUpperCase(Locale.ENGLISH), sequence);
        }
      }
    }
  }

  public void close() {
    sequences.clear();
  }

  public synchronized Set<String> getSequenceNames(DatabaseSessionInternal session) {
    reloadIfNeeded(session);
    return sequences.keySet();
  }

  public synchronized int getSequenceCount(DatabaseSessionInternal session) {
    reloadIfNeeded(session);
    return sequences.size();
  }

  public DBSequence getSequence(final DatabaseSessionInternal session, final String iName) {
    final var name = iName.toUpperCase(Locale.ENGLISH);
    reloadIfNeeded(session);
    DBSequence seq;
    synchronized (this) {
      seq = sequences.get(name);
      if (seq == null) {
        load(session);
        seq = sequences.get(name);
      }
    }

    return seq;
  }

  public synchronized DBSequence createSequence(
      final DatabaseSessionInternal session,
      final String iName,
      final SEQUENCE_TYPE sequenceType,
      final DBSequence.CreateParams params) {
    init(session);
    reloadIfNeeded(session);

    final var key = iName.toUpperCase(Locale.ENGLISH);
    validateSequenceNoExists(key);

    final var sequence = SequenceHelper.createSequence(session, sequenceType, params, iName);
    sequences.put(key, sequence);

    return sequence;
  }

  public synchronized void dropSequence(
      final DatabaseSessionInternal session, final String iName) {
    final var seq = getSequence(session, iName);
    if (seq != null) {
      try {
        var entity = session.loadEntity(seq.entityRid);
        session.delete(entity);
        sequences.remove(iName.toUpperCase(Locale.ENGLISH));
      } catch (NeedRetryException e) {
        var rec = session.load(seq.entityRid);
        rec.delete();
      }
    }
  }

  public void onSequenceCreated(
      final DatabaseSessionInternal session, final EntityImpl entity) {
    init(session);

    var name = DBSequence.getSequenceName(entity);
    if (name == null) {
      return;
    }

    name = name.toUpperCase(Locale.ENGLISH);

    final var seq = getSequence(session, name);

    if (seq != null) {
      onSequenceLibraryUpdate(session);
      return;
    }

    final var sequence = SequenceHelper.createSequence(entity);

    sequences.put(name, sequence);
    onSequenceLibraryUpdate(session);
  }

  public static void onAfterSequenceDropped(FrontendTransactionImpl currentTx,
      EntityImpl sequenceEntity) {

    @SuppressWarnings("unchecked")
    var droppedSequencesMap = (HashMap<RID, String>) currentTx.getCustomData(DROPPED_SEQUENCES_MAP);
    if (droppedSequencesMap == null) {
      droppedSequencesMap = new HashMap<>();
    }

    currentTx.setCustomData(DROPPED_SEQUENCES_MAP, droppedSequencesMap);
    droppedSequencesMap.put(sequenceEntity.getIdentity(),
        DBSequence.getSequenceName(sequenceEntity));
  }


  public void onSequenceDropped(
      final DatabaseSessionInternal session, final RID rid) {
    var currentTx = (FrontendTransactionImpl) session.getTransactionInternal();
    @SuppressWarnings("unchecked")
    var droppedSequencesMap = (HashMap<RID, String>) currentTx.getCustomData(DROPPED_SEQUENCES_MAP);
    String sequenceName = null;

    if (droppedSequencesMap != null) {
      sequenceName = droppedSequencesMap.get(rid);
    }

    if (sequenceName == null) {
      onSequenceLibraryUpdate(session);
      return;
    }

    var name = sequenceName.toUpperCase(Locale.ENGLISH);
    sequences.remove(name);
    onSequenceLibraryUpdate(session);
  }

  private static void init(final DatabaseSessionInternal session) {
    if (session.getMetadata().getSchema().existsClass(DBSequence.CLASS_NAME)) {
      return;
    }

    final var sequenceClass = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass(DBSequence.CLASS_NAME);
    DBSequence.initClass(sequenceClass);
  }

  private void validateSequenceNoExists(final String iName) {
    if (sequences.containsKey(iName)) {
      throw new SequenceException("Sequence '" + iName + "' already exists");
    }
  }

  private static void onSequenceLibraryUpdate(DatabaseSessionInternal session) {
    for (var one : session.getSharedContext().browseListeners()) {
      one.onSequenceLibraryUpdate(session, session.getDatabaseName());
    }
  }

  private void reloadIfNeeded(DatabaseSessionInternal database) {
    if (reloadNeeded.get()) {
      load(database);
      reloadNeeded.set(false);
    }
  }

  public void update() {
    reloadNeeded.set(true);
  }
}
