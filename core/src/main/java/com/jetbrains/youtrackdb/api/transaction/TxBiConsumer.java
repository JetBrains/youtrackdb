package com.jetbrains.youtrackdb.api.transaction;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface TxBiConsumer<T extends Transaction, U, X extends Exception> {

  void accept(@Nonnull T t, U u) throws X;
}
