package com.jetbrains.youtrack.db.api.transaction;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface TxFunction<T extends Transaction, R, X extends Exception> {

  R apply(@Nonnull T t) throws X;
}
