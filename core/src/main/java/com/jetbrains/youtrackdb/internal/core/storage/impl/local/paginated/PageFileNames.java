package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Builds logical page-file names and recognizes the retained write-cache disk-name form. */
public final class PageFileNames {

  // This is the layout 25 fallback policy, not the set of all page-file extensions.
  // Keep the legacy and control entries. The omitted .dpb, .fcm and .dcm are intentional.
  public static final List<String> LAYOUT_25_FALLBACK_EXTENSIONS = List.of(
      ".cm", ".ocf", ".pls", ".pcl", ".oda", ".odh", ".otx", ".ocs", ".oef", ".oem",
      ".oet", ".fl", ".flb", ".iv", ".wal", ".wmr", ".cpm", ".grb", ".ccm", ".cd",
      ".bd", ".nd", ".cbt", ".nbt", ".mbt", ".ixs", ".dwl", ".fsm", ".bsm",
      ".bsm.tmp", ".bsml");

  private static final Pattern NAME_26 = Pattern.compile(
      "ytdb-p26-(collection|index|configuration)-([0-9]+)-([a-z-]+)(?:_([0-9]+))?\\.[a-z]+");
  private static final Pattern INDEX_STEM = Pattern.compile("ie_([0-9]+)(\\$null)?");
  private static final Pattern BAG_STEM = Pattern.compile("global_collection_([0-9]+)");

  private PageFileNames() {
  }

  /** A layout 25 name retains its stem because a collection's display name is not an identity. */
  public record Layout25Name(PageFileRole role, String stem) {
  }

  /** Builds collection names. Link-bag names instead use {@link #layout25LinkBag(int)}. */
  public static String layout25Collection(String name, PageFileRole role) {
    if (role.kind() != PageFileKind.COLLECTION || role == PageFileRole.LINK_BAG_TREE) {
      throw new IllegalArgumentException("Not a collection page role: " + role);
    }
    return name + role.layout25Extension();
  }

  public static String layout25LinkBag(int collectionId) {
    return "global_collection_" + collectionId + ".grb";
  }

  /** Single-value and multi-value engines share the same file family. */
  public static String layout25Index(int fileBaseId, PageFileRole role) {
    if (role.kind() != PageFileKind.INDEX_ENGINE) {
      throw new IllegalArgumentException("Not an index page role: " + role);
    }
    var nullKey = role == PageFileRole.NULL_KEY_DATA
        || role == PageFileRole.NULL_KEY_NULL_BUCKET;
    return "ie_" + fileBaseId + (nullKey ? "$null" : "") + role.layout25Extension();
  }

  public static String layout25Configuration(PageFileRole role) {
    if (role.kind() != PageFileKind.CONFIGURATION) {
      throw new IllegalArgumentException("Not a configuration page role: " + role);
    }
    return "config" + role.layout25Extension();
  }

  /** Parses logical names only. Layout 25 component ids retain their existing allocation bounds. */
  public static Optional<Layout25Name> parseLayout25(String name) {
    if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      return Optional.empty();
    }
    for (var role : PageFileRole.values()) {
      var extension = role.layout25Extension();
      if (!name.endsWith(extension)) {
        continue;
      }
      var stem = name.substring(0, name.length() - extension.length());
      switch (role.kind()) {
        case COLLECTION -> {
          if (role == PageFileRole.LINK_BAG_TREE) {
            if (BAG_STEM.matcher(stem).matches()) {
              return Optional.of(new Layout25Name(role, stem));
            }
          } else if (!stem.isEmpty()) {
            return Optional.of(new Layout25Name(role, stem));
          }
        }
        case CONFIGURATION -> {
          if (stem.equals("config")) {
            return Optional.of(new Layout25Name(role, stem));
          }
        }
        case INDEX_ENGINE -> {
          var match = INDEX_STEM.matcher(stem);
          if (match.matches()) {
            var nullKey = match.group(2) != null;
            var nullRole = role == PageFileRole.NULL_KEY_DATA
                || role == PageFileRole.NULL_KEY_NULL_BUCKET;
            if (nullKey == nullRole) {
              return Optional.of(new Layout25Name(role, stem));
            }
          }
        }
      }
    }
    return Optional.empty();
  }

  // The prefix separates fixed layout 25 families. A collection display name can share it.
  public static String layout26(PageFileIdentity identity) {
    return "ytdb-p26-" + identity.kind().label() + "-" + identity.componentId() + "-"
        + identity.role().label() + identity.role().layout25Extension();
  }

  /** The write cache inserts the internal id before the final extension. */
  public static String layout26DiskName(PageFileIdentity identity) {
    var logical = layout26(identity);
    var extension = identity.role().layout25Extension();
    return logical.substring(0, logical.length() - extension.length())
        + "_" + identity.internalId() + extension;
  }

  public static Optional<PageFileIdentity> parseLayout26(String name) {
    return parse26(name, false);
  }

  public static Optional<PageFileIdentity> parseLayout26DiskName(String name) {
    return parse26(name, true);
  }

  private static Optional<PageFileIdentity> parse26(String name, boolean disk) {
    if (name == null) {
      return Optional.empty();
    }
    var match = NAME_26.matcher(name);
    if (!match.matches() || (match.group(4) != null) != disk) {
      return Optional.empty();
    }
    try {
      var componentId = Integer.parseInt(match.group(2));
      for (var role : PageFileRole.values()) {
        if (role.kind().label().equals(match.group(1)) && role.label().equals(match.group(3))) {
          var identity = new PageFileIdentity(role, componentId);
          // Canonical equality checks the role extension, numeric spelling and internal id.
          var canonical = disk ? layout26DiskName(identity) : layout26(identity);
          return canonical.equals(name) ? Optional.of(identity) : Optional.empty();
        }
      }
    } catch (IllegalArgumentException ignored) {
      // A malformed name is not evidence that a directory belongs to a database.
    }
    return Optional.empty();
  }
}
