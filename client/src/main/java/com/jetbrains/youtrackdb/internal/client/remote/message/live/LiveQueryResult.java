package com.jetbrains.youtrackdb.internal.client.remote.message.live;

import com.jetbrains.youtrackdb.api.common.query.BasicResult;

/**
 *
 */
public record LiveQueryResult(byte eventType, BasicResult currentValue, BasicResult oldValue) {

  public static final byte CREATE_EVENT = 1;
  public static final byte UPDATE_EVENT = 2;
  public static final byte DELETE_EVENT = 3;

}
