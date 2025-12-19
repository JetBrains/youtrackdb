package com.jetbrains.youtrackdb.internal.core.tx;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface TxBiFunction<T extends Transaction, U, R, X extends Exception> {

  R apply(@Nonnull T t, U u) throws X;
}
