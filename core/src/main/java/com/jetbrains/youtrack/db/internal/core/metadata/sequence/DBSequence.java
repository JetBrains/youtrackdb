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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SequenceLimitReachedException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.thread.NonDaemonThreadFactory;
import com.jetbrains.youtrack.db.internal.common.thread.ThreadPoolExecutorWithLogging;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SequenceException;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;

/**
 * @since 3/2/2015
 */
public abstract class DBSequence {

  private static final ExecutorService sequenceExecutor =
      new ThreadPoolExecutorWithLogging(
          0,
          Runtime.getRuntime().availableProcessors(),
          1,
          TimeUnit.MINUTES,
          new LinkedBlockingQueue<>(1024),
          new NonDaemonThreadFactory("SequenceExecutor"));
  public static final long DEFAULT_START = 0;
  public static final int DEFAULT_INCREMENT = 1;
  public static final int DEFAULT_CACHE = 20;
  public static final Long DEFAULT_LIMIT_VALUE = null;
  public static final boolean DEFAULT_RECYCLABLE_VALUE = false;

  protected static final int DEF_MAX_RETRY =
      GlobalConfiguration.SEQUENCE_MAX_RETRY.getValueAsInteger();
  public static final String CLASS_NAME = "OSequence";

  private static final String FIELD_START = "start";
  private static final String FIELD_INCREMENT = "incr";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_LIMIT_VALUE = "lvalue";
  private static final String FIELD_ORDER_TYPE = "otype";
  private static final String FIELD_RECYCLABLE = "recycle";
  // initialy set this value to true, so those one who read it can pull upper limit value from
  // entity

  private static final String FIELD_NAME = "name";
  private static final String FIELD_TYPE = "type";

  protected RID entityRid = new ChangeableRecordId();

  private final ReentrantLock updateLock = new ReentrantLock();

  public static final SequenceOrderType DEFAULT_ORDER_TYPE = SequenceOrderType.ORDER_POSITIVE;

  public static class CreateParams {

    protected Long start = DEFAULT_START;
    protected Integer increment = DEFAULT_INCREMENT;
    // significant only for cached sequences
    protected Integer cacheSize = DEFAULT_CACHE;
    protected Long limitValue = DEFAULT_LIMIT_VALUE;
    protected SequenceOrderType orderType = DEFAULT_ORDER_TYPE;
    protected Boolean recyclable = DEFAULT_RECYCLABLE_VALUE;
    protected Boolean turnLimitOff = false;
    protected Long currentValue = null;

    public CreateParams setStart(Long start) {
      this.start = start;
      return this;
    }

    public CreateParams setIncrement(Integer increment) {
      this.increment = increment;
      return this;
    }

    public CreateParams setCacheSize(Integer cacheSize) {
      this.cacheSize = cacheSize;
      return this;
    }

    public CreateParams setLimitValue(Long limitValue) {
      this.limitValue = limitValue;
      return this;
    }

    public CreateParams setOrderType(SequenceOrderType orderType) {
      this.orderType = orderType;
      return this;
    }

    public CreateParams setRecyclable(boolean recyclable) {
      this.recyclable = recyclable;
      return this;
    }

    public CreateParams setTurnLimitOff(Boolean turnLimitOff) {
      this.turnLimitOff = turnLimitOff;
      return this;
    }

    public CreateParams setCurrentValue(Long currentValue) {
      this.currentValue = currentValue;
      return this;
    }

    public CreateParams() {
    }

    public CreateParams resetNull() {
      start = null;
      increment = null;
      cacheSize = null;
      limitValue = null;
      orderType = null;
      recyclable = null;
      turnLimitOff = false;
      currentValue = null;
      return this;
    }

    public CreateParams setDefaults() {
      this.start = this.start != null ? this.start : DEFAULT_START;
      this.increment = this.increment != null ? this.increment : DEFAULT_INCREMENT;
      this.cacheSize = this.cacheSize != null ? this.cacheSize : DEFAULT_CACHE;
      orderType = orderType == null ? DEFAULT_ORDER_TYPE : orderType;
      recyclable = recyclable == null ? DEFAULT_RECYCLABLE_VALUE : recyclable;
      turnLimitOff = turnLimitOff != null && turnLimitOff;
      return this;
    }

    public Long getStart() {
      return start;
    }

    public Integer getIncrement() {
      return increment;
    }

    public Integer getCacheSize() {
      return cacheSize;
    }

    public Long getLimitValue() {
      return limitValue;
    }

    public SequenceOrderType getOrderType() {
      return orderType;
    }

    public Boolean getRecyclable() {
      return recyclable;
    }

    public Boolean getTurnLimitOff() {
      return turnLimitOff;
    }

    public Long getCurrentValue() {
      return currentValue;
    }
  }

  public enum SEQUENCE_TYPE {
    CACHED((byte) 0),
    ORDERED((byte) 1);

    private final byte val;

    SEQUENCE_TYPE(byte val) {
      this.val = val;
    }

    public byte getVal() {
      return val;
    }

    public static SEQUENCE_TYPE fromVal(byte val) {
      return switch (val) {
        case 0 -> CACHED;
        case 1 -> ORDERED;
        default -> throw new SequenceException("Unknown sequence type: " + val);
      };
    }
  }

  private int maxRetry = DEF_MAX_RETRY;

  protected DBSequence(EntityImpl entity) {
    Objects.requireNonNull(entity);
    entityRid = entity.getIdentity();
  }

  protected DBSequence(DatabaseSessionInternal session, CreateParams params, @Nonnull String name) {
    Objects.requireNonNull(name);

    entityRid =
        session.computeInTx(
            transaction -> {
              var entity = session.newInstance(CLASS_NAME);

              CreateParams currentParams;
              if (params == null) {
                currentParams = new CreateParams().setDefaults();
              } else {
                currentParams = params;
              }

              initSequence(entity, currentParams);
              setName(entity, name);

              return entity.getIdentity();
            });
  }

  private void initSequence(EntityImpl entity, DBSequence.CreateParams params) {
    setStart(entity, params.start);
    setIncrement(entity, params.increment);
    if (params.currentValue == null) {
      setValue(entity, params.start);
    } else {
      setValue(entity, params.currentValue);
    }
    setLimitValue(entity, params.limitValue);
    setOrderType(entity, params.orderType);
    setRecyclable(entity, params.recyclable);

    setSequenceType(entity);
  }

  public boolean updateParams(DatabaseSessionInternal db, CreateParams params)
      throws DatabaseException {
    var entity = db.<EntityImpl>load(entityRid);
    var result = updateParams(entity, params, false);

    return result;
  }

  boolean updateParams(EntityImpl entity, CreateParams params, boolean executeViaDistributed)
      throws DatabaseException {
    var any = false;

    if (params.start != null && DBSequence.getStart(entity) != params.start) {
      DBSequence.setStart(entity, params.start);
      any = true;
    }

    if (params.increment != null && DBSequence.getIncrement(entity) != params.increment) {
      DBSequence.setIncrement(entity, params.increment);
      any = true;
    }

    if (params.limitValue != null && !params.limitValue.equals(DBSequence.getLimitValue(entity))) {
      DBSequence.setLimitValue(entity, params.limitValue);
      any = true;
    }

    if (params.orderType != null && DBSequence.getOrderType(entity) != params.orderType) {
      DBSequence.setOrderType(entity, params.orderType);
      any = true;
    }

    if (params.recyclable != null && DBSequence.getRecyclable(entity) != params.recyclable) {
      DBSequence.setRecyclable(entity, params.recyclable);
      any = true;
    }

    if (params.turnLimitOff != null && params.turnLimitOff) {
      DBSequence.setLimitValue(entity, null);
    }

    if (params.currentValue != null && getValue(entity) != params.currentValue) {
      DBSequence.setValue(entity, params.currentValue);
    }

    return any;
  }

  protected static long getValue(EntityImpl entity) {
    if (!entity.hasProperty(FIELD_VALUE)) {
      var boundedSession = entity.getBoundedToSession();
      throw new SequenceException(boundedSession != null ? boundedSession.getDatabaseName() : null,
          "Value property not found in entity");
    }
    return entity.getProperty(FIELD_VALUE);
  }

  protected static void setValue(EntityImpl entity, long value) {
    entity.setProperty(FIELD_VALUE, value);
  }

  protected static int getIncrement(EntityImpl entity) {
    return entity.getProperty(FIELD_INCREMENT);
  }

  protected static void setLimitValue(EntityImpl entity, Long limitValue) {
    entity.setProperty(FIELD_LIMIT_VALUE, limitValue);
  }

  protected static Long getLimitValue(EntityImpl entity) {
    return entity.getProperty(FIELD_LIMIT_VALUE);
  }

  protected static void setOrderType(EntityImpl entity, SequenceOrderType orderType) {
    entity.setProperty(FIELD_ORDER_TYPE, orderType.getValue());
  }

  protected static SequenceOrderType getOrderType(EntityImpl entity) {
    Byte val = entity.getProperty(FIELD_ORDER_TYPE);
    return val == null ? SequenceOrderType.ORDER_POSITIVE : SequenceOrderType.fromValue(val);
  }

  protected static void setIncrement(EntityImpl entity, int value) {
    entity.setProperty(FIELD_INCREMENT, value);
  }

  protected static long getStart(EntityImpl entity) {
    return entity.getProperty(FIELD_START);
  }

  protected static void setStart(EntityImpl entity, long value) {
    entity.setProperty(FIELD_START, value);
  }

  public int getMaxRetry() {
    return maxRetry;
  }

  public void setMaxRetry(final int maxRetry) {
    this.maxRetry = maxRetry;
  }

  public String getName(DatabaseSessionInternal db) {
    return getSequenceName(db.load(entityRid));
  }

  protected static void setName(EntityImpl entity, final String name) {
    entity.setProperty(FIELD_NAME, name);
  }

  protected static boolean getRecyclable(EntityImpl entity) {
    return entity.getProperty(FIELD_RECYCLABLE);
  }

  protected static void setRecyclable(EntityImpl entity, final boolean recyclable) {
    entity.setProperty(FIELD_RECYCLABLE, recyclable);
  }

  private void setSequenceType(EntityImpl entity) {
    entity.setProperty(FIELD_TYPE, getSequenceType());
  }

  public static String getSequenceName(final EntityImpl entity) {
    return entity.getProperty(FIELD_NAME);
  }

  public static SEQUENCE_TYPE getSequenceType(final EntityImpl entity) {
    String sequenceTypeStr = entity.getProperty(FIELD_TYPE);
    if (sequenceTypeStr != null) {
      return SEQUENCE_TYPE.valueOf(sequenceTypeStr);
    }

    var boundedSession = entity.getBoundedToSession();
    throw new SequenceException(boundedSession != null ? boundedSession.getDatabaseName() : null,
        "Sequence type not found in entity");
  }

  public static void initClass(SchemaClassInternal sequenceClass) {
    sequenceClass.createProperty(DBSequence.FIELD_START, PropertyTypeInternal.LONG,
        (PropertyTypeInternal) null, true);
    sequenceClass.createProperty(DBSequence.FIELD_INCREMENT, PropertyTypeInternal.INTEGER,
        (PropertyTypeInternal) null,
        true);
    sequenceClass.createProperty(DBSequence.FIELD_VALUE, PropertyTypeInternal.LONG,
        (PropertyTypeInternal) null, true);

    sequenceClass.createProperty(DBSequence.FIELD_NAME, PropertyTypeInternal.STRING,
        (PropertyTypeInternal) null,
        true);
    sequenceClass.createProperty(DBSequence.FIELD_TYPE, PropertyTypeInternal.STRING,
        (PropertyTypeInternal) null,
        true);

    sequenceClass.createProperty(DBSequence.FIELD_LIMIT_VALUE, PropertyTypeInternal.LONG,
        (PropertyTypeInternal) null,
        true);
    sequenceClass.createProperty(DBSequence.FIELD_ORDER_TYPE, PropertyTypeInternal.BYTE,
        (PropertyTypeInternal) null,
        true);
    sequenceClass.createProperty(DBSequence.FIELD_RECYCLABLE, PropertyTypeInternal.BOOLEAN,
        (PropertyTypeInternal) null,
        true);
  }

  /*
   * Forwards the sequence by one, and returns the new value.
   */
  public long next(DatabaseSessionInternal db)
      throws SequenceLimitReachedException, DatabaseException {
    return nextWork(db);
  }

  public abstract long nextWork(DatabaseSessionInternal session)
      throws SequenceLimitReachedException;

  /*
   * Returns the current sequence value. If next() was never called, returns null
   */
  public long current(DatabaseSessionInternal db) throws DatabaseException {
    return currentWork(db);
  }

  protected abstract long currentWork(DatabaseSessionInternal session);

  public long reset(DatabaseSessionInternal db) throws DatabaseException {
    return resetWork(db);
  }

  public abstract long resetWork(DatabaseSessionInternal session);

  /*
   * Returns the sequence type
   */
  public abstract SEQUENCE_TYPE getSequenceType();

  protected long callRetry(DatabaseSessionInternal db, final SequenceCallable callable,
      final String method) {
    try (var dbCopy = db.copy()) {
      for (var retry = 0; retry < maxRetry; ++retry) {
        updateLock.lock();
        try {
          return dbCopy.computeInTx(
              transaction -> {
                var entity = transaction.<EntityImpl>load(entityRid);
                return callable.call(dbCopy, entity);
              });
        } catch (ConcurrentModificationException ignore) {
          try {
            //noinspection BusyWait
            Thread.sleep(
                1
                    + new Random()
                    .nextInt(
                        dbCopy
                            .getConfiguration()
                            .getValueAsInteger(
                                GlobalConfiguration.SEQUENCE_RETRY_DELAY)));
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            break;
          }

        } catch (StorageException e) {
          if (!(e.getCause() instanceof ConcurrentModificationException)) {
            throw BaseException.wrapException(
                new SequenceException(db.getDatabaseName(),
                    "Error in transactional processing of "
                        + getName(dbCopy)
                        + "."
                        + method
                        + "()"),
                e, db.getDatabaseName());
          }
        } catch (Exception e) {
          dbCopy.executeInTx(
              transaction -> {
                throw BaseException.wrapException(
                    new SequenceException(db.getDatabaseName(),
                        "Error in transactional processing of "
                            + getName(dbCopy)
                            + "."
                            + method
                            + "()"),
                    e, db.getDatabaseName());
              });
        } finally {
          updateLock.unlock();
        }
      }
      updateLock.lock();
      try {
        return dbCopy.computeInTx(
            transaction -> {
              var entity = (EntityImpl) transaction.loadEntity(entityRid);
              return callable.call(dbCopy, entity);
            });
      } catch (Exception e) {
        throw BaseException.wrapException(
            new SequenceException(db.getDatabaseName(),
                "Error in transactional processing of "
                    + getName(dbCopy)
                    + "."
                    + method
                    + "()"),
            e, db.getDatabaseName());
      } finally {
        updateLock.unlock();
      }
    }
  }

  @FunctionalInterface
  public interface SequenceCallable {

    long call(DatabaseSession db, EntityImpl entity);
  }
}
