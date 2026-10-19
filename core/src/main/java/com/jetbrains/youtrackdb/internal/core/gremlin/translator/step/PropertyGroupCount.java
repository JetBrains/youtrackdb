package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBPropertyImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.LinkedHashMap;

/** Immutable direct-count contract. Counts belong to one drain, never to a shared plan template. */
public record PropertyGroupCount(String alias, String propertyKey, boolean includeMissing) {

  /** Counts logical MATCH occurrences, using typed access to reuse checked records when available. */
  LinkedHashMap<Object, Object> drain(
      CommandContext context, ExecutionStream stream, YTDBGraphInternal graph) {
    var counts = new LinkedHashMap<Object, Object>();
    while (stream.hasNext(context)) {
      var entity = (EntityImpl) stream.next(context).getEntity(alias);
      // Filtered presence precedes productive-by rules. A hidden property is absent, not its
      // stored value. The provider also excludes internal names and edge endpoint fields.
      var property = entity == null || YTDBElementImpl.keyIgnored(entity, propertyKey)
          ? null : entity.getPropertyAndType(propertyKey);
      if (property == null && !includeMissing) {
        continue;
      }
      var key = property == null ? null
          : YTDBPropertyImpl.wrapIntoGraphElement(graph, property.value());
      counts.merge(key, 1L, (left, right) -> (Long) left + (Long) right);
    }
    return counts;
  }
}
