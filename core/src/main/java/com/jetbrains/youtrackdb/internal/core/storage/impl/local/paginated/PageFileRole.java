package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind.COLLECTION;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind.CONFIGURATION;
import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileKind.INDEX_ENGINE;

/** A page file's purpose within its owning kind. Role numbers start at zero for each kind. */
public enum PageFileRole {
  RECORD_DATA(COLLECTION, 0, "record-data", ".pcl", ".rec"), POSITION_MAP(COLLECTION, 1,
      "position-map", ".cpm", ".pos"), FREE_SPACE_MAP(COLLECTION, 2, "free-space-map", ".fsm",
          ".fsp"), DIRTY_PAGE_BIT_SET(COLLECTION, 3, "dirty-page-bit-set", ".dpb",
              ".dpg"), LINK_BAG_TREE(COLLECTION, 4, "link-bag-tree", ".grb", ".lbt"), INDEX_DATA(
                  INDEX_ENGINE, 0, "tree-data", ".cbt", ".itd"), INDEX_NULL_BUCKET(INDEX_ENGINE, 1,
                      "tree-null-bucket", ".nbt", ".itn"), NULL_KEY_DATA(INDEX_ENGINE, 2,
                          "null-key-data", ".cbt", ".ind"), NULL_KEY_NULL_BUCKET(INDEX_ENGINE, 3,
                              "null-key-null-bucket", ".nbt", ".inn"), HISTOGRAM(INDEX_ENGINE, 4,
                                  "histogram", ".ixs", ".hgm"), CONFIG_DATA(CONFIGURATION, 0,
                                      "data", ".cd", ".cfd"), CONFIG_POSITION_MAP(CONFIGURATION, 1,
                                          "position-map", ".ccm", ".cfp"), CONFIG_FREE_SPACE_MAP(
                                              CONFIGURATION, 2, "free-space-map", ".fcm",
                                              ".cff"), CONFIG_DIRTY_PAGE_BIT_SET(CONFIGURATION, 3,
                                                  "dirty-page-bit-set", ".dcm",
                                                  ".cfb"), PROPERTY_DATA(CONFIGURATION, 4,
                                                      "property-data", ".bd",
                                                      ".cpd"), PROPERTY_NULL_BUCKET(CONFIGURATION,
                                                          5, "property-null-bucket", ".nd", ".cpn");

  private final PageFileKind kind;
  private final int code;
  private final String label;
  private final String layout25Extension;
  private final String layout26Extension;

  PageFileRole(PageFileKind kind, int code, String label, String layout25Extension,
      String layout26Extension) {
    this.kind = kind;
    this.code = code;
    this.label = label;
    this.layout25Extension = layout25Extension;
    this.layout26Extension = layout26Extension;
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

  public String layout26Extension() {
    return layout26Extension;
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
