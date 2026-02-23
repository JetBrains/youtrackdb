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
package com.jetbrains.youtrackdb.internal.core.metadata.sequence;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.SequenceLimitReachedException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

/**
 * A sequence with sequential guarantees that produces no holes even on transaction rollback.
 *
 * <p>This is slower than {@link SequenceCached} because each value is allocated individually.
 *
 * @see SequenceCached
 * @since 2/28/2015
 */
public class SequenceOrdered extends DBSequence {

  public SequenceOrdered(final EntityImpl entity) {
    super(entity);
  }

  public SequenceOrdered(DatabaseSessionEmbedded db, CreateParams params, String name) {
    super(db, params, name);
  }

  @Override
  public long nextWork(DatabaseSessionEmbedded session) throws SequenceLimitReachedException {
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
  protected long currentWork(DatabaseSessionEmbedded session) {
    return callRetry(session, (db, entity) -> getValue(entity), "current");
  }

  @Override
  public long resetWork(DatabaseSessionEmbedded session) {
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
