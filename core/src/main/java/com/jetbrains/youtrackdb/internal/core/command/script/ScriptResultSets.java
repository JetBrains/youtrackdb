package com.jetbrains.youtrackdb.internal.core.command.script;

import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Collections;

/**
 * Static Factories of ScriptResultSet objects
 *
 * <p>Used in script results with conversion to Result for single iteration
 * on 27/01/17.
 */
public class ScriptResultSets {

  /**
   * Empty result set
   *
   * @return
   */
  public static ScriptResultSet empty(DatabaseSessionEmbedded db) {
    return new ScriptResultSet(db, Collections.EMPTY_LIST.iterator(), null);
  }

  /**
   * Result set with a single result;
   *
   * @return
   */
  public static ScriptResultSet singleton(DatabaseSessionEmbedded db, Object entity,
      ScriptTransformer transformer) {
    return new ScriptResultSet(db, Collections.singletonList(entity).iterator(), transformer);
  }
}
