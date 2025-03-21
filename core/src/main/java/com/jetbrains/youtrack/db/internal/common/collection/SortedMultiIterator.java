/*
 *
 *
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
package com.jetbrains.youtrack.db.internal.common.collection;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLOrderByItem;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nonnull;

public class SortedMultiIterator<T extends Identifiable> implements Iterator<T> {

  private static final int STATUS_INIT = 0;
  private static final int STATUS_RUNNING = 1;

  private final SQLOrderBy orderBy;

  private final List<Iterator<T>> sourceIterators = new ArrayList<Iterator<T>>();
  private final List<T> heads = new ArrayList<T>();

  private int status = STATUS_INIT;
  private final DatabaseSessionInternal db;

  public SortedMultiIterator(@Nonnull DatabaseSessionInternal db, SQLOrderBy orderBy) {
    this.db = db;
    this.orderBy = orderBy;
  }

  public void add(Iterator<T> iterator) {
    if (status == STATUS_INIT) {
      sourceIterators.add(iterator);
      if (iterator.hasNext()) {
        heads.add(iterator.next());
      } else {
        heads.add(null);
      }
    } else {
      throw new IllegalStateException(
          "You are trying to add a sub-iterator on a running SortedMultiIterator");
    }
  }

  @Override
  public boolean hasNext() {
    if (status == STATUS_INIT) {
      status = STATUS_RUNNING;
    }
    for (var o : heads) {
      if (o != null) {
        return true;
      }
    }
    return false;
  }

  @Override
  public T next() {
    if (status == STATUS_INIT) {
      status = STATUS_RUNNING;
    }
    var nextItemPosition = findNextPosition();
    var result = heads.get(nextItemPosition);
    if (sourceIterators.get(nextItemPosition).hasNext()) {
      heads.set(nextItemPosition, sourceIterators.get(nextItemPosition).next());
    } else {
      heads.set(nextItemPosition, null);
    }
    return result;
  }

  private int findNextPosition() {
    var lastPosition = 0;
    while (heads.size() < lastPosition && heads.get(lastPosition) == null) {
      lastPosition++;
    }
    var lastItem = heads.get(lastPosition);
    for (var i = lastPosition + 1; i < heads.size(); i++) {
      var item = heads.get(i);
      if (item == null) {
        continue;
      }
      if (comesFrist(item, lastItem)) {
        lastItem = item;
        lastPosition = i;
      }
    }
    return lastPosition;
  }

  protected boolean comesFrist(T left, T right) {
    if (orderBy == null || orderBy.getItems() == null || orderBy.getItems().size() == 0) {
      return true;
    }
    if (right == null) {
      return true;
    }
    if (left == null) {
      return false;
    }

    EntityImpl leftEntity;
    if ((left instanceof EntityImpl)) {
      leftEntity = (EntityImpl) left;
    } else {
      var transaction = db.getActiveTransaction();
      leftEntity = transaction.load(left);
    }
    EntityImpl rightEntity;
    if ((right instanceof EntityImpl)) {
      rightEntity = (EntityImpl) right;
    } else {
      var transaction = db.getActiveTransaction();
      rightEntity = transaction.load(right);
    }

    for (var orderItem : orderBy.getItems()) {
      var leftVal = leftEntity.getProperty(orderItem.getRecordAttr());
      var rightVal = rightEntity.getProperty(orderItem.getRecordAttr());
      if (rightVal == null) {
        return true;
      }
      if (leftVal == null) {
        return false;
      }
      if (leftVal instanceof Comparable) {
        var compare = ((Comparable) leftVal).compareTo(rightVal);
        if (compare == 0) {
          continue;
        }
        var greater = compare > 0;
        if (SQLOrderByItem.DESC.equals(orderItem.getType())) {
          return greater;
        } else {
          return !greater;
        }
      }
    }

    return false;
  }

  public void remove() {
    throw new UnsupportedOperationException("remove");
  }
}
