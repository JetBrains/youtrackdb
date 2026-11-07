package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind.COLLECTION;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind.CONFIGURATION;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind.INDEX_ENGINE;

/** A page file's purpose within its owning kind. Role numbers start at zero for each kind. */
public enum PageFileRole {
  RECORD_DATA(COLLECTION, 0, "record-data", ".pcl"), POSITION_MAP(COLLECTION, 1,
      "position-map", ".cpm"), FREE_SPACE_MAP(COLLECTION, 2, "free-space-map",
          ".fsm"), DIRTY_PAGE_BIT_SET(COLLECTION, 3, "dirty-page-bit-set",
              ".dpb"), LINK_BAG_TREE(COLLECTION, 4, "link-bag-tree", ".grb"), INDEX_DATA(
                  INDEX_ENGINE, 0, "tree-data", ".cbt"), INDEX_NULL_BUCKET(INDEX_ENGINE, 1,
                      "tree-null-bucket", ".nbt"), NULL_KEY_DATA(INDEX_ENGINE, 2,
                          "null-key-data", ".cbt"), NULL_KEY_NULL_BUCKET(INDEX_ENGINE, 3,
                              "null-key-null-bucket", ".nbt"), HISTOGRAM(INDEX_ENGINE, 4,
                                  "histogram", ".ixs"), CONFIG_DATA(CONFIGURATION, 0,
                                      "data", ".cd"), CONFIG_POSITION_MAP(CONFIGURATION, 1,
                                          "position-map", ".ccm"), CONFIG_FREE_SPACE_MAP(
                                              CONFIGURATION, 2, "free-space-map",
                                              ".fcm"), CONFIG_DIRTY_PAGE_BIT_SET(CONFIGURATION, 3,
                                                  "dirty-page-bit-set",
                                                  ".dcm"), PROPERTY_DATA(CONFIGURATION, 4,
                                                      "property-data",
                                                      ".bd"), PROPERTY_NULL_BUCKET(CONFIGURATION,
                                                          5, "property-null-bucket", ".nd");

  private final PageFileKind kind;
  private final int code;
  private final String label;
  private final String layout25Extension;

  PageFileRole(PageFileKind kind, int code, String label, String layout25Extension) {
    this.kind = kind;
    this.code = code;
    this.label = label;
    this.layout25Extension = layout25Extension;
  }

  public PageFileKind kind() {
    return kind;
  }

  public int code() {
    return code;
  }

  public String label() {
    return label;
  }

  public String layout25Extension() {
    return layout25Extension;
  }

  public static PageFileRole fromCode(PageFileKind kind, int code) {
    for (var role : values()) {
      if (role.kind == kind && role.code == code) {
        return role;
      }
    }
    throw new IllegalArgumentException("Invalid page-file role " + code + " for " + kind);
  }
}
