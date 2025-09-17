package com.jetbrains.youtrackdb.api.gremlin;

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
