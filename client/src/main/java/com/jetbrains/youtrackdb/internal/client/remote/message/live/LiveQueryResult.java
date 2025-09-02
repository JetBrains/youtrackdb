package com.jetbrains.youtrackdb.internal.client.remote.message.live;

import com.jetbrains.youtrackdb.api.common.query.BasicResult;

/**
 *
 */
public class LiveQueryResult {

  public static final byte CREATE_EVENT = 1;
  public static final byte UPDATE_EVENT = 2;
  public static final byte DELETE_EVENT = 3;

  private final byte eventType;
  private final BasicResult currentValue;
  private final BasicResult oldValue;

  public LiveQueryResult(byte eventType, BasicResult currentValue, BasicResult oldValue) {
    this.eventType = eventType;
    this.currentValue = currentValue;
    this.oldValue = oldValue;
  }

  public byte getEventType() {
    return eventType;
  }

  public BasicResult getCurrentValue() {
    return currentValue;
  }

  public BasicResult getOldValue() {
    return oldValue;
  }
}
