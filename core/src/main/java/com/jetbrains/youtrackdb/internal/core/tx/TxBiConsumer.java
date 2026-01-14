package com.jetbrains.youtrackdb.internal.core.tx;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface TxBiConsumer<T extends Transaction, U, X extends Exception> {

  void accept(@Nonnull T t, U u) throws X;
}
