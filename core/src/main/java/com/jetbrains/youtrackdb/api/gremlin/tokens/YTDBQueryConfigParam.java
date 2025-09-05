package com.jetbrains.youtrackdb.api.gremlin.tokens;

public enum YTDBQueryConfigParam {

  polymorphicQuery(Boolean.class);

  private final Class<?> type;

  YTDBQueryConfigParam(Class<?> type) {
    this.type = type;
  }

  public Class<?> type() {
    return type;
  }
}
