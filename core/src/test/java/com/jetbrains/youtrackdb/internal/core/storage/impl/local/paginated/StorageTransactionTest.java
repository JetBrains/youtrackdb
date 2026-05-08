package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import org.junit.Test;

/**
 * Unit tests for the {@link StorageTransaction} record. Pins the auto-generated
 * accessor name and the record-equality semantics used by the storage layer to wrap a
 * client-supplied frontend transaction.
 */
public class StorageTransactionTest {

  /**
   * The accessor returns the constructor argument unchanged.
   */
  @Test
  public void testAccessorReturnsClientTx() {
    var tx = mock(FrontendTransaction.class);
    var wrapper = new StorageTransaction(tx);

    assertThat(wrapper.clientTx()).isSameAs(tx);
  }

  /**
   * A null inner transaction is permitted (the record does not enforce a non-null contract;
   * the calling code disposes of nulls explicitly). Pin the current behaviour so a defensive
   * non-null check addition does not silently break call paths that rely on the lenient form.
   */
  @Test
  public void testNullClientTxAllowed() {
    var wrapper = new StorageTransaction(null);
    assertThat(wrapper.clientTx()).isNull();
  }

  /**
   * Two records wrapping the same client tx are equal and have equal hash codes.
   */
  @Test
  public void testEqualityAndHash() {
    var tx = mock(FrontendTransaction.class);
    var a = new StorageTransaction(tx);
    var b = new StorageTransaction(tx);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  /**
   * Records wrapping different client transactions are not equal.
   */
  @Test
  public void testInequality() {
    var tx1 = mock(FrontendTransaction.class);
    var tx2 = mock(FrontendTransaction.class);
    var a = new StorageTransaction(tx1);
    var b = new StorageTransaction(tx2);

    assertThat(a).isNotEqualTo(b);
  }
}
