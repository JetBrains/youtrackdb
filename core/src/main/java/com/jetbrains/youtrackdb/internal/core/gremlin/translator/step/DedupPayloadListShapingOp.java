package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Keeps the first projected payload per distinct value — native {@code values(k).dedup()} over a
 * {@link BoundaryOutputType#SINGLE_VALUE} stream, or {@code valueMap(…).dedup()} over a
 * {@link BoundaryOutputType#MAP} stream. MATCH {@code RETURN DISTINCT} would range over
 * {@code (entity, value)} and cannot collapse two elements that share a scalar/map payload, so this
 * stage runs after row projection on the payloads themselves.
 */
public final class DedupPayloadListShapingOp implements ListShapingOp {

  @Override
  public Iterator<Object> apply(Iterator<Object> upstream) {
    Set<Object> seen = new HashSet<>();
    return new Iterator<>() {
      private Object nextPayload;
      private boolean ready;

      @Override
      public boolean hasNext() {
        if (ready) {
          return true;
        }
        while (upstream.hasNext()) {
          var payload = upstream.next();
          if (seen.add(payload)) {
            nextPayload = payload;
            ready = true;
            return true;
          }
        }
        return false;
      }

      @Override
      public Object next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        ready = false;
        return nextPayload;
      }
    };
  }
}
