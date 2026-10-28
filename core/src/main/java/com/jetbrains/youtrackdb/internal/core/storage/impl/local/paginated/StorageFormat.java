package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

/**
 * Immutable storage-layout identities understood by this build.
 *
 * <p>The production creation default is independent of the supported set. This type describes
 * identity only and exposes no user-selectable storage option.
 */
public enum StorageFormat {
  LAYOUT_25(25), LAYOUT_26(26);

  private final int layoutVersion;

  StorageFormat(final int layoutVersion) {
    this.layoutVersion = layoutVersion;
  }

  /** Returns the persisted storage-layout value for this descriptor. */
  public int layoutVersion() {
    return layoutVersion;
  }

  /** Returns the layout for ordinary new databases. */
  public static StorageFormat productionDefault() {
    return LAYOUT_25;
  }

  /**
   * Resolves an exact stored layout value without conversion or fallback.
   *
   * @throws IllegalArgumentException if the stored layout is not supported
   */
  public static StorageFormat resolve(final int layoutVersion) {
    return switch (layoutVersion) {
      case 25 -> LAYOUT_25;
      case 26 -> LAYOUT_26;
      default -> throw new IllegalArgumentException(
          "Unsupported storage layout version: " + layoutVersion + ". Supported versions: 25, 26");
    };
  }
}
