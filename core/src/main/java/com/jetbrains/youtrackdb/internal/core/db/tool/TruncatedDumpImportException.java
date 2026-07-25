package com.jetbrains.youtrackdb.internal.core.db.tool;

/**
 * The loud rejection every import reader loop's EOF bound throws when a dump ends inside a
 * structure (CS80). A DEDICATED type (gate finding RG7) so pre-existing tolerance catches —
 * above all {@code importSchema}'s legacy-path swallow — can rethrow truncation instead of
 * logging it away: no honest dump of ANY version is truncated (the dangling-name-guard
 * precedent), so truncation must be loud on every path and version.
 */
@SuppressWarnings("serial")
public class TruncatedDumpImportException extends DatabaseImportException {

  public TruncatedDumpImportException(String message) {
    super(message);
  }
}
