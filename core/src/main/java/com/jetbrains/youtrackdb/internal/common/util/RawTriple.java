package com.jetbrains.youtrackdb.internal.common.util;

public record RawTriple<T1, T2, T3>(T1 first, T2 second, T3 third) {

  public static <T1, T2, T3> RawTriple<T1, T2, T3> of(T1 first, T2 second, T3 third) {
    return new RawTriple<>(first, second, third);
  }
}
