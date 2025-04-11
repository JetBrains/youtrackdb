package com.jetbrains.youtrack.db.internal.core.command.script.transformer.result;

import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.Map;
import java.util.Spliterator;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 *
 */
public class MapTransformer implements ResultTransformer<Map<Object, Object>> {

  private final ScriptTransformer transformer;

  public MapTransformer(ScriptTransformer transformer) {
    this.transformer = transformer;
  }

  @Override
  public Result transform(DatabaseSessionInternal db, Map<Object, Object> element) {
    var internal = new ResultInternal(db);
    element.forEach(
        (key, val) -> {
          if (transformer.doesHandleResult(val)) {
            internal.setProperty(key.toString(), transformer.toResult(db, val));
          } else {

            if (val instanceof Iterable) {
              var spliterator = ((Iterable) val).spliterator();
              var collect =
                  StreamSupport.stream(spliterator, false)
                      .map((e) -> this.transformer.toResult(db, e))
                      .collect(Collectors.toList());
              internal.setProperty(key.toString(), collect);
            } else {
              internal.setProperty(key.toString(), val);
            }
          }
        });
    return internal;
  }
}
