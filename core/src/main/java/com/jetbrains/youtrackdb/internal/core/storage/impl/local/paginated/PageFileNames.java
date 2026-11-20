package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Builds logical page-file names and recognizes the retained write-cache disk-name form. */
public final class PageFileNames {

  /** Stem prefix for legacy index-engine files keyed by their stable file base id. */
  public static final String INDEX_ENGINE_FILE_STEM_PREFIX = "ie_";

  /** Suffix appended to legacy index-engine stems for null-key trees. */
  public static final String NULL_TREE_SUFFIX = "$null";

  // This is the layout 27 fallback policy, not the set of all page-file extensions.
  // Keep the legacy and control entries. The omitted .dpb, .fcm and .dcm are intentional.
  public static final List<String> LAYOUT_27_FALLBACK_EXTENSIONS = List.of(
      ".cm", ".ocf", ".pls", ".pcl", ".oda", ".odh", ".otx", ".ocs", ".oef", ".oem",
      ".oet", ".fl", ".flb", ".iv", ".wal", ".wmr", ".cpm", ".grb", ".ccm", ".cd",
      ".bd", ".nd", ".cbt", ".nbt", ".mbt", ".ixs", ".dwl", ".fsm", ".bsm",
      ".bsm.tmp", ".bsml");

  private static final Pattern NAME_28 = Pattern.compile(
      "ytdb-p28-(collection|index|configuration)-([0-9]+)-([a-z-]+)(?:_([0-9]+))?\\.[a-z]+");
  private static final Pattern INDEX_STEM = Pattern.compile(
      Pattern.quote(INDEX_ENGINE_FILE_STEM_PREFIX) + "([0-9]+)("
          + Pattern.quote(NULL_TREE_SUFFIX) + ")?");
  private static final Pattern BAG_STEM = Pattern.compile("global_collection_([0-9]+)");

  private PageFileNames() {
  }

  /** A layout 27 name retains its stem because a collection's display name is not an identity. */
  public record Layout27Name(PageFileRole role, String stem) {
  }

  /** Builds collection names. Link-bag names instead use {@link #layout27LinkBag(int)}. */
  public static String layout27Collection(String name, PageFileRole role) {
    if (role.kind() != PageFileKind.COLLECTION || role == PageFileRole.LINK_BAG_TREE) {
      throw new IllegalArgumentException("Not a collection page role: " + role);
    }
    return name + role.layout27Extension();
  }

  public static String layout27LinkBag(int collectionId) {
    return "global_collection_" + collectionId + ".grb";
  }

  /** Single-value and multi-value engines share the same file family. */
  public static String layout27Index(int fileBaseId, PageFileRole role) {
    if (role.kind() != PageFileKind.INDEX_ENGINE) {
      throw new IllegalArgumentException("Not an index page role: " + role);
    }
    var nullKey = role == PageFileRole.NULL_KEY_DATA
        || role == PageFileRole.NULL_KEY_NULL_BUCKET;
    return INDEX_ENGINE_FILE_STEM_PREFIX + fileBaseId
        + (nullKey ? NULL_TREE_SUFFIX : "") + role.layout27Extension();
  }

  public static String layout27Configuration(PageFileRole role) {
    if (role.kind() != PageFileKind.CONFIGURATION) {
      throw new IllegalArgumentException("Not a configuration page role: " + role);
    }
    return "config" + role.layout27Extension();
  }

  /**
   * Reads legacy index allocation evidence from {@code ie_<digits>[$null]} names with a
   * {@code .cbt}, {@code .nbt} or {@code .ixs} extension, including {@code $null.ixs}.
   * Digits must be ASCII. Leading zeros are allowed. Shape and extension are checked before
   * numeric bounds, so unrelated names return {@code -1} even if they contain large numbers.
   *
   * <p>Layout 27 accepts ids in {@code (0, Integer.MAX_VALUE]} and returns {@code -1} for
   * other values, including long overflow. With {@code layout28Evidence}, numeric evidence
   * above {@link PageFileIdentity#MAX_COMPONENT_ID} throws {@link StorageException},
   * including long overflow. Zero returns {@code -1} in both layouts.
   *
   * <p>A crash-window orphan is still evidence: burning its id prevents a future engine
   * from colliding with its registered files. This is not canonical page-role parsing.
   *
   * @param fileName the registered logical file name
   * @param layout28Evidence whether to enforce the layout 28 evidence bound
   * @return the positive file base id, or {@code -1} for a nonmatch
   * @throws StorageException if layout 28 numeric evidence exceeds its bound
   * @throws NullPointerException if {@code fileName} is null
   */
  public static long parseLegacyIndexEngineFileBaseIdEvidence(
      final String fileName, final boolean layout28Evidence) {
    final var extensionStart = fileName.lastIndexOf('.');
    if (extensionStart <= 0) {
      return -1;
    }
    final var extension = fileName.substring(extensionStart);
    if (!isIndexEngineFileExtension(extension)) {
      return -1;
    }
    var stem = fileName.substring(0, extensionStart);
    if (stem.endsWith(NULL_TREE_SUFFIX)) {
      stem = stem.substring(0, stem.length() - NULL_TREE_SUFFIX.length());
    }
    if (!stem.startsWith(INDEX_ENGINE_FILE_STEM_PREFIX)) {
      return -1;
    }
    final var digits = stem.substring(INDEX_ENGINE_FILE_STEM_PREFIX.length());
    if (digits.isEmpty()) {
      return -1;
    }
    for (var i = 0; i < digits.length(); i++) {
      final var c = digits.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
    }
    final long parsed;
    try {
      parsed = Long.parseLong(digits);
    } catch (final NumberFormatException ignore) {
      if (layout28Evidence) {
        throw new StorageException(null,
            "Component id " + digits + " in registered file '" + fileName
                + "' exceeds the layout 28 bound " + PageFileIdentity.MAX_COMPONENT_ID);
      }
      // More digits than a long can carry — certainly not an allocated layout 27 id.
      return -1;
    }
    // Validate numeric evidence before the legacy int ceiling can turn it into a nonmatch.
    if (layout28Evidence) {
      validateLayout28ComponentIdEvidence(parsed, "registered file '" + fileName + "'");
    }
    if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
      return -1;
    }
    return parsed;
  }

  /**
   * Whether {@code extension}, including its leading dot, belongs to the legacy index-engine
   * family. Both allocation evidence and failed-commit file-presence checks use this family
   * so other components that share the stem shape are not mistaken for engine files.
   */
  public static boolean isIndexEngineFileExtension(final String extension) {
    return BTreeMultiValueIndexEngine.DATA_FILE_EXTENSION.equals(extension)
        || BTreeMultiValueIndexEngine.NULL_BUCKET_FILE_EXTENSION.equals(extension)
        || IndexHistogramManager.IXS_EXTENSION.equals(extension);
  }

  /**
   * Validates numeric component-id evidence against the layout 28 range.
   *
   * @param componentId the id from persisted evidence
   * @param entry the evidence description included verbatim in the error message
   * @throws StorageException if the id is outside the layout 28 range
   */
  public static void validateLayout28ComponentIdEvidence(long componentId, String entry) {
    if (componentId < 0 || componentId > PageFileIdentity.MAX_COMPONENT_ID) {
      throw new StorageException(null,
          "Component id " + componentId + " in " + entry
              + " is outside the layout 28 range 0.." + PageFileIdentity.MAX_COMPONENT_ID);
    }
  }

  /** Parses logical names only. Layout 27 component ids retain their existing allocation bounds. */
  public static Optional<Layout27Name> parseLayout27(String name) {
    if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      return Optional.empty();
    }
    for (var role : PageFileRole.values()) {
      var extension = role.layout27Extension();
      if (!name.endsWith(extension)) {
        continue;
      }
      var stem = name.substring(0, name.length() - extension.length());
      switch (role.kind()) {
        case COLLECTION -> {
          if (role == PageFileRole.LINK_BAG_TREE) {
            if (BAG_STEM.matcher(stem).matches()) {
              return Optional.of(new Layout27Name(role, stem));
            }
          } else if (!stem.isEmpty()) {
            return Optional.of(new Layout27Name(role, stem));
          }
        }
        case CONFIGURATION -> {
          if (stem.equals("config")) {
            return Optional.of(new Layout27Name(role, stem));
          }
        }
        case INDEX_ENGINE -> {
          var match = INDEX_STEM.matcher(stem);
          if (match.matches()) {
            var nullKey = match.group(2) != null;
            var nullRole = role == PageFileRole.NULL_KEY_DATA
                || role == PageFileRole.NULL_KEY_NULL_BUCKET;
            if (nullKey == nullRole) {
              return Optional.of(new Layout27Name(role, stem));
            }
          }
        }
      }
    }
    return Optional.empty();
  }

  // The prefix separates fixed layout 27 families. A collection display name can share it.
  public static String layout28(PageFileIdentity identity) {
    return "ytdb-p28-" + identity.kind().label() + "-" + identity.componentId() + "-"
        + identity.role().label() + identity.role().layout27Extension();
  }

  /** The write cache inserts the internal id before the final extension. */
  public static String layout28DiskName(PageFileIdentity identity) {
    var logical = layout28(identity);
    var extension = identity.role().layout27Extension();
    return logical.substring(0, logical.length() - extension.length())
        + "_" + identity.internalId() + extension;
  }

  public static Optional<PageFileIdentity> parseLayout28(String name) {
    return parse28(name, false);
  }

  public static Optional<PageFileIdentity> parseLayout28DiskName(String name) {
    return parse28(name, true);
  }

  private static Optional<PageFileIdentity> parse28(String name, boolean disk) {
    if (name == null) {
      return Optional.empty();
    }
    var match = NAME_28.matcher(name);
    if (!match.matches() || (match.group(4) != null) != disk) {
      return Optional.empty();
    }
    try {
      var componentId = Integer.parseInt(match.group(2));
      for (var role : PageFileRole.values()) {
        if (role.kind().label().equals(match.group(1)) && role.label().equals(match.group(3))) {
          var identity = new PageFileIdentity(role, componentId);
          // Canonical equality checks the role extension, numeric spelling and internal id.
          var canonical = disk ? layout28DiskName(identity) : layout28(identity);
          return canonical.equals(name) ? Optional.of(identity) : Optional.empty();
        }
      }
    } catch (IllegalArgumentException ignored) {
      // A malformed name is not evidence that a directory belongs to a database.
    }
    return Optional.empty();
  }
}
