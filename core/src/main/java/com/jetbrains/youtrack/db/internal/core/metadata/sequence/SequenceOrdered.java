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
package com.jetbrains.youtrack.db.internal.core.metadata.sequence;

import com.jetbrains.youtrack.db.api.exception.SequenceLimitReachedException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

/**
 * @see SequenceCached
 * @since 2/28/2015
 * <p>A sequence with sequential guarantees. Even when a transaction is rolled back, there will
 * still be no holes. However, as a result, it is slower.
 */
public class SequenceOrdered extends DBSequence {

  public SequenceOrdered(final EntityImpl entity) {
    super(entity);
  }

  public SequenceOrdered(DatabaseSessionInternal db, CreateParams params, String name) {
    super(db, params, name);
  }

  @Override
  public long nextWork(DatabaseSessionInternal session) throws SequenceLimitReachedException {
    return callRetry(session,
        (db, entity) -> {
          long newValue;
          var limitValue = getLimitValue(entity);
          var increment = getIncrement(entity);

          if (getOrderType(entity) == SequenceOrderType.ORDER_POSITIVE) {
            newValue = getValue(entity) + increment;
            if (limitValue != null && newValue > limitValue) {
              if (getRecyclable(entity)) {
                newValue = getStart(entity);
              } else {
                throw new SequenceLimitReachedException("Limit reached");
              }
            }
          } else {
            newValue = getValue(entity) - increment;
            if (limitValue != null && newValue < limitValue) {
              if (getRecyclable(entity)) {
                newValue = getStart(entity);
              } else {
                throw new SequenceLimitReachedException("Limit reached");
              }
            }
          }

          setValue(entity, newValue);
          if (limitValue != null && !getRecyclable(entity)) {
            var tillEnd = (float) Math.abs(limitValue - newValue) / increment;
            var delta = (float) Math.abs(limitValue - getStart(entity)) / increment;
            // warning on 1%
            if (tillEnd <= (delta / 100.f) || tillEnd <= 1) {
              var warningMessage =
                  "Non-recyclable sequence: "
                      + getSequenceName(entity)
                      + " reaching limt, current value: "
                      + newValue
                      + " limit value: "
                      + limitValue
                      + " with step: "
                      + increment;
              LogManager.instance().warn(this, warningMessage);
            }
          }

          return newValue;
        }, "next");
  }

  @Override
  protected long currentWork(DatabaseSessionInternal session) {
    return callRetry(session, (db, entity) -> getValue(entity), "current");
  }

  @Override
  public long resetWork(DatabaseSessionInternal session) {
    return callRetry(session,
        (db, entity) -> {
          var newValue = getStart(entity);
          setValue(entity, newValue);
          return newValue;
        }, "reset");
  }

  @Override
  public SEQUENCE_TYPE getSequenceType() {
    return SEQUENCE_TYPE.ORDERED;
  }
}
