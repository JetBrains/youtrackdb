package com.jetbrains.youtrack.db.internal.client.remote.message.live;

import com.jetbrains.youtrack.db.api.query.Result;

/**
 *
 */
public class LiveQueryResult {

  public static final byte CREATE_EVENT = 1;
  public static final byte UPDATE_EVENT = 2;
  public static final byte DELETE_EVENT = 3;

  private final byte eventType;
  private final Result currentValue;
  private final Result oldValue;

  public LiveQueryResult(byte eventType, Result currentValue, Result oldValue) {
    this.eventType = eventType;
    this.currentValue = currentValue;
    this.oldValue = oldValue;
  }

  public byte getEventType() {
    return eventType;
  }

  public Result getCurrentValue() {
    return currentValue;
  }

  public Result getOldValue() {
    return oldValue;
  }
}
