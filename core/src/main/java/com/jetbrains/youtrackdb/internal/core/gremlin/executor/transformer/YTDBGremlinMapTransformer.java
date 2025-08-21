package com.jetbrains.youtrackdb.internal.core.gremlin.executor.transformer;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class YTDBGremlinMapTransformer implements ResultTransformer<Map<Object, Object>> {

  protected ScriptTransformer transformer;

  public YTDBGremlinMapTransformer(ScriptTransformer transformer) {
    this.transformer = transformer;
  }

  @Override
  public Result transform(DatabaseSessionInternal session, Map<Object, Object> element) {
    var internal = new ResultInternal(session);
    element.forEach(
        (key, val) -> {
          if (this.transformer.doesHandleResult(val)) {
            internal.setProperty(key.toString(), transformer.toResult(session, val));
          } else {

            if (val instanceof Iterable<?> iterable) {
              var spliterator = iterable.spliterator();
              Object collect =
                  StreamSupport.stream(spliterator, false)
                      .map(
                          (e) -> {
                            if (e instanceof YTDBElementImpl gremlinElement) {
                              return gremlinElement.getRawEntity()
                                  .getIdentity();
                            }
                            return this.transformer.toResult(session, e);
                          })
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
