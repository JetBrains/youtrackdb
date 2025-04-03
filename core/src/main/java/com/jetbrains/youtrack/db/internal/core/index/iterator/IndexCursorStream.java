package com.jetbrains.youtrack.db.internal.core.index.iterator;

import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.index.IndexAbstractCursor;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class IndexCursorStream extends IndexAbstractCursor {

  private final Iterator<RawPair<Object, RID>> iterator;

  public IndexCursorStream(final Stream<RawPair<Object, RID>> stream) {
    iterator = stream.iterator();
  }

  @Nullable
  @Override
  public Map.Entry<Object, Identifiable> nextEntry() {
    if (iterator.hasNext()) {
      final var pair = iterator.next();

      return new Map.Entry<Object, Identifiable>() {
        @Override
        public Object getKey() {
          return pair.first;
        }

        @Override
        public Identifiable getValue() {
          return pair.second;
        }

        @Override
        public Identifiable setValue(Identifiable value) {
          throw new UnsupportedOperationException();
        }
      };
    }

    return null;
  }
}
