package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Execution-owned, first-seen identity groups at a proved native property-map barrier. */
final class ElementBulkingWindow implements Iterator<ElementBulkingWindow.Group> {

  static final class Group {
    final Result row;
    private long bulk;

    Group(Result row, long bulk) {
      this.row = row;
      this.bulk = bulk;
    }

    long bulk() {
      return bulk;
    }
  }

  private final ExecutionStream stream;
  private final CommandContext context;
  private final String alias;
  private final int capacity;
  private Iterator<Group> window = Collections.emptyIterator();
  private Result firstRow;
  private long firstBulk;
  private long bulk;

  ElementBulkingWindow(ExecutionStream stream, CommandContext context, String alias, int capacity) {
    if (capacity < 1 || capacity > 2500) {
      throw new IllegalArgumentException("Element bulking capacity must be between 1 and 2500");
    }
    this.stream = stream;
    this.context = context;
    this.alias = alias;
    this.capacity = capacity;
  }

  @Override
  public boolean hasNext() {
    if (firstRow != null || window.hasNext()) {
      return true;
    }
    // Drop an exhausted map iterator before buffering a new singleton window.
    window = Collections.emptyIterator();
    if (!stream.hasNext(context)) {
      return false;
    }
    var row = stream.next(context);
    var firstIdentity = identity(row);
    firstRow = row;
    firstBulk = 1;
    LinkedHashMap<Object, Group> groups = null;
    int distinct = 1;
    // Stop as soon as the distinct-entry capacity is reached, like NoOpBarrierStep. Do not
    // read ahead even when the next row might duplicate an entry in this window.
    while (distinct < capacity && stream.hasNext(context)) {
      row = stream.next(context);
      var identity = identity(row);
      if (groups == null) {
        if (Objects.equals(identity, firstIdentity)) {
          firstBulk++;
          continue;
        }
        // A single identity needs no map, Group, values view or iterator while buffering.
        // Only a second distinct identity promotes it into the first-seen grouping map.
        groups = new LinkedHashMap<>();
        groups.put(firstIdentity, new Group(firstRow, firstBulk));
        firstRow = null;
        firstBulk = 0;
      }
      var group = groups.get(identity);
      if (group == null) {
        groups.put(identity, new Group(row, 1));
        distinct++;
      } else {
        group.bulk++;
      }
    }
    if (groups != null) {
      window = groups.values().iterator();
    }
    return true;
  }

  @Override
  public Group next() {
    var row = nextRow();
    return new Group(row, bulk);
  }

  /** Direct boundary emission avoids allocating a Group for a singleton window. */
  Result nextRow() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    if (firstRow != null) {
      var row = firstRow;
      bulk = firstBulk;
      firstRow = null;
      firstBulk = 0;
      return row;
    }
    var group = window.next();
    bulk = group.bulk();
    return group.row;
  }

  /** Multiplicity of the row most recently consumed by either emission API. */
  long bulk() {
    return bulk;
  }

  private Object identity(Result row) {
    var raw = row.getProperty(alias);
    if (!(raw instanceof Identifiable identifiable)) {
      throw new IllegalStateException("Element bulking requires a bound element identity");
    }
    return identifiable.getIdentity();
  }
}
