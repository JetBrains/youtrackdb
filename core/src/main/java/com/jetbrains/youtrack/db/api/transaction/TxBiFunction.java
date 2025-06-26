package com.jetbrains.youtrack.db.api.transaction;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface TxBiFunction<T extends Transaction, U, R, X extends Exception> {

  R apply(@Nonnull T t, U u) throws X;
}
