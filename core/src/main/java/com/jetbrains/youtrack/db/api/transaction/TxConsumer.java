package com.jetbrains.youtrack.db.api.transaction;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface TxConsumer<T extends Transaction, X extends Exception> {

  void accept(@Nonnull T t) throws X;
}
