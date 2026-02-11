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

import com.jetbrains.youtrackdb.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SequenceException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence.SEQUENCE_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * @since 3/2/2015
 */
public class SequenceLibraryImpl {

  public static final String DROPPED_SEQUENCES_MAP = "droppedSequencesMap";
  private final ConcurrentHashMap<String, DBSequence> sequences = new ConcurrentHashMap<>();
  private final AtomicLong reloadNeeded = new AtomicLong();
  private final Lock lock = new ReentrantLock();

  public static void create(DatabaseSessionEmbedded database) {
    init(database);
  }

  public void load(final DatabaseSessionEmbedded session) {
    lock.lock();
    try {
      sequences.clear();

      if (session.getMetadata().getImmutableSchemaSnapshot().existsClass(DBSequence.CLASS_NAME)) {
        session.executeInTx(tx -> {
          try (final var result = session.query("SELECT FROM " + DBSequence.CLASS_NAME)) {
            while (result.hasNext()) {
              var res = result.next();

              final var sequence = SequenceHelper.createSequence((EntityImpl) res.asEntity());
              sequences.put(normalizeName(sequence.getName(session)), sequence);
            }
          }
        });
      }
    } finally {
      lock.unlock();
    }
  }

  public void close() {
    sequences.clear();
  }

  public Set<String> getSequenceNames(DatabaseSessionEmbedded session) {
    reloadIfNeeded(session);
    return sequences.keySet();
  }

  public int getSequenceCount(DatabaseSessionEmbedded session) {
    reloadIfNeeded(session);
    return sequences.size();
  }

  public DBSequence getSequence(
      final DatabaseSessionEmbedded session,
      final String iName
  ) {
    reloadIfNeeded(session);
    return sequences.get(normalizeName(iName));
  }

  public DBSequence createSequence(
      final DatabaseSessionEmbedded session,
      final String iName,
      final SEQUENCE_TYPE sequenceType,
      final DBSequence.CreateParams params) {
    lock.lock();
    try {
      init(session);
      reloadIfNeeded(session);

      final var key = normalizeName(iName);
      validateSequenceNoExists(key);

      final var sequence = SequenceHelper.createSequence(session, sequenceType, params, iName);
      sequences.put(key, sequence);

      return sequence;
    } finally {
      lock.unlock();
    }
  }

  public void dropSequence(
      final DatabaseSessionEmbedded session, final String iName) {
    lock.lock();
    try {
      final var seq = getSequence(session, iName);
      if (seq != null) {
        try {
          var entity = session.loadEntity(seq.entityRid);
          session.delete(entity);
          sequences.remove(normalizeName(iName));
        } catch (NeedRetryException e) {
          var rec = session.load(seq.entityRid);
          rec.delete();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  public void onSequenceCreated(final DatabaseSessionEmbedded session, final EntityImpl entity) {
    init(session);

    final var name = normalizeName(DBSequence.getSequenceName(entity));
    if (name == null) {
      return;
    }

    lock.lock();
    try {
      final var seq = getSequence(session, name);
      if (seq == null) {
        sequences.put(name, SequenceHelper.createSequence(entity));
      }
    } finally {
      lock.unlock();
    }

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
      final DatabaseSessionEmbedded session, final RID rid) {
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

    sequences.remove(normalizeName(sequenceName));
    onSequenceLibraryUpdate(session);
  }

  private static void init(final DatabaseSessionEmbedded session) {
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

  private static void onSequenceLibraryUpdate(DatabaseSessionEmbedded session) {
    for (var one : session.getSharedContext().browseListeners()) {
      one.onSequenceLibraryUpdate(session, session.getDatabaseName());
    }
  }

  private void reloadIfNeeded(DatabaseSessionEmbedded database) {
    var reloadRequests = reloadNeeded.getAndSet(0);
    if (reloadRequests > 0) {
      load(database);
    }
  }

  public void update() {
    reloadNeeded.incrementAndGet();
  }

  @Nullable
  private static String normalizeName(String name) {
    return name == null ? null : name.toUpperCase(Locale.ROOT);
  }
}
