package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

public class IndexEngineReferenceTest {

  /** A reference exposes its immutable slot identity and accepts one idempotent owner binding. */
  @Test
  public void bindsOwnerOnce() {
    var reference = new IndexEngineReference(7, 1, 42);
    var owner = new RecordId(3, 11);

    reference.bindOwner(owner);
    reference.bindOwner(owner);

    assertThat(reference.slot()).isEqualTo(7);
    assertThat(reference.apiVersion()).isEqualTo(1);
    assertThat(reference.generation()).isEqualTo(42);
    assertThat(reference.ownerDescriptorIdentity()).isEqualTo(owner);
  }

  /** Rebinding to another durable descriptor fails fast and reports both conflicting owners. */
  @Test
  public void rejectsRebindingToDifferentOwner() {
    var reference = new IndexEngineReference(7, 1, 42);
    var firstOwner = new RecordId(3, 11);
    var secondOwner = new RecordId(3, 12);
    reference.bindOwner(firstOwner);

    assertThatThrownBy(() -> reference.bindOwner(secondOwner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(firstOwner.toString())
        .hasMessageContaining(secondOwner.toString());
  }

  /** Slots and generations use only valid local-engine identity ranges. */
  @Test
  public void rejectsInvalidIdentityParts() {
    assertThatThrownBy(() -> new IndexEngineReference(-1, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IndexEngineReference(0, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
