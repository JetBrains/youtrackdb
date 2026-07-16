package com.jetbrains.youtrackdb.internal.core.storage.cache;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests for {@link ComponentEpochRegistry} — the per-storage fileId → component-epoch
 * map behind the YTDB-1203 per-component apply-phase epochs. Pins the lifecycle rules
 * the writer-side commit resolution depends on: identity-preserving lookups, miss →
 * {@code null} on production registries (so {@code commitChanges} can fail loud, AR-2),
 * overwrite-on-reuse, and the {@link ComponentEpochRegistry#uniform} standalone fallback
 * that resolves every fileId to one epoch.
 */
public class ComponentEpochRegistryTest {

  @Test
  public void testRegisterAndResolveReturnsIdenticalInstance() {
    // Lookups must return the exact registered instance — commit-time dedupe and the
    // -ea coverage guards compare epochs by reference, never by value.
    var registry = new ComponentEpochRegistry();
    var epoch = new ApplyPhaseEpoch();

    registry.register(42L, epoch);

    assertSame(epoch, registry.epochFor(42L));
  }

  @Test
  public void testMissReturnsNullOnProductionRegistry() {
    // A production registry must report a miss as null so commitChanges can fail loud
    // (AR-2) for files created or loaded outside the StorageComponent funnel.
    var registry = new ComponentEpochRegistry();
    registry.register(1L, new ApplyPhaseEpoch());

    assertNull(registry.epochFor(2L));
  }

  @Test
  public void testReRegistrationOverwritesMapping() {
    // FileId reuse (disk engine, same-name delete+recreate): the recreating component's
    // registration must replace the dead owner's epoch — subsequent commits on the
    // fileId must resolve the NEW instance.
    var registry = new ComponentEpochRegistry();
    var oldOwner = new ApplyPhaseEpoch();
    var newOwner = new ApplyPhaseEpoch();

    registry.register(7L, oldOwner);
    registry.register(7L, newOwner);

    assertSame(newOwner, registry.epochFor(7L));
  }

  @Test
  public void testUniformRegistryResolvesEveryFileIdToTheGivenEpoch() {
    // uniform() is the standalone/test fallback: every fileId — registered or not —
    // resolves to the single given epoch, reproducing the pre-per-component
    // (storage-wide) bump semantics for operations constructed outside a storage.
    var universal = new ApplyPhaseEpoch();
    var registry = ComponentEpochRegistry.uniform(universal);

    assertSame(universal, registry.epochFor(1L));
    assertSame(universal, registry.epochFor(Long.MAX_VALUE));
  }

  @Test
  public void testUniformRegistryPrefersExplicitRegistration() {
    // An explicit registration on a uniform registry takes precedence over the
    // universal fallback — the fallback only covers fileIds nobody registered.
    var universal = new ApplyPhaseEpoch();
    var explicit = new ApplyPhaseEpoch();
    var registry = ComponentEpochRegistry.uniform(universal);

    registry.register(5L, explicit);

    assertSame(explicit, registry.epochFor(5L));
    assertSame(universal, registry.epochFor(6L));
  }
}
