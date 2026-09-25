package com.jetbrains.youtrackdb.internal.core.exception;

/**
 * Thrown when a database whose creation (genesis) never ran to completion is opened — or
 * adopted by a {@code create(failIfExists=false)} call: the storage opens and carries internal
 * metadata, but the genesis-completion marker
 * ({@link com.jetbrains.youtrackdb.internal.core.db.SharedContext#GENESIS_COMPLETED_PROPERTY})
 * is absent from the storage configuration. Such a half-genesis corpse (a crash between storage
 * creation and the end of the metadata-creation sequence) must be discarded and re-created, never
 * used — without this check it would reopen silently with a partial or empty schema.
 *
 * <p>Track 24 moved the open-path refusal of the metadata load to
 * {@link InconsistentStorageMetadataException}, which is the named inconsistent-metadata result of
 * the two later consistency checks. This exception now reports the tolerant create path only. The
 * tolerant create path is the create call that accepts an existing database.
 *
 * <p>The check is deliberately fail-closed: a crash after a fully completed genesis but before
 * the marker write became durable produces a FALSE refusal of a genuinely complete database
 * (design W9a, accepted) — the prescribed discard-and-recreate is cheap and correct for a fresh,
 * data-free database, and no unsafe state ever opens.
 *
 * <p>{@code drop()} tolerates this exception on its internal open so the very discard the message
 * prescribes cannot be blocked by the check (design CN54); the {@code onDrop} lifecycle listeners
 * do not fire for such a corpse, because no usable session can be minted. A refused corpse's
 * storage is closed and unregistered, so its file locks never outlive the refusal.
 */
public class GenesisIncompleteException extends DatabaseException {

  @SuppressWarnings("unused")
  public GenesisIncompleteException(GenesisIncompleteException exception) {
    super(exception);
  }

  public GenesisIncompleteException(String dbName, String message) {
    super(dbName, message);
  }
}
