package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Bounded boolean answers owned by one evaluating step, never by a per-row probe. */
final class DetachedCheckMemory {

  private final DetachedCheckReadSet reads;
  private final Map<JoinKey, Boolean> answers = new HashMap<>();
  private long limit;
  private long hits;

  DetachedCheckMemory(DetachedCheckReadSet reads) {
    this.reads = reads;
  }

  DetachedCheckReadSet reads() {
    return reads;
  }

  void start() {
    clear();
    limit = MatchExecutionPlanner.getHashJoinThreshold();
  }

  void clear() {
    answers.clear();
    hits = 0;
    limit = 0;
  }

  long hits() {
    return hits;
  }

  int size() {
    return answers.size();
  }

  boolean matches(Result row, BooleanSupplier probe) {
    var key = limit > 0 && reads != null && reads.rememberable() ? key(row) : null;
    if (key == null) {
      return probe.getAsBoolean();
    }
    var answer = answers.get(key);
    if (answer != null) {
      hits++;
      return answer;
    }
    boolean found = probe.getAsBoolean();
    // Answers assume data stability for one start, as an eager hash build does. Caller writes
    // during iteration need not be visible to later rows with a remembered binding tuple.
    if (answers.size() < limit) {
      answers.put(key, found);
    }
    return found;
  }

  private JoinKey key(Result row) {
    var identities = new Object[reads.aliases().size()];
    for (int i = 0; i < identities.length; i++) {
      Object value = row.getProperty(reads.aliases().get(i));
      if (value instanceof Result result) {
        value = result.getIdentity();
        if (value == null) {
          return null;
        }
      }
      if (value instanceof Identifiable record) {
        value = record.getIdentity();
      }
      if (value != null && !(value instanceof RID)) {
        return null;
      }
      identities[i] = value;
    }
    return identities.length == 1 && identities[0] instanceof RID rid
        ? JoinKey.ofRid(rid) : JoinKey.ofObjectsOwned(identities);
  }
}
