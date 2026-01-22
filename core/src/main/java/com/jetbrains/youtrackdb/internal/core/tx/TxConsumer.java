package com.jetbrains.youtrackdb.internal.core.tx;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface TxConsumer<T extends Transaction, X extends Exception> {

  void accept(@Nonnull T t) throws X;
}
