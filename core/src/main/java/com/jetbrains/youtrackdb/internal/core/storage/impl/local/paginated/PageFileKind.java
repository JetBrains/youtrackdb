package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

/** The type of component that owns a page file. Numbers are part of layout 26. */
public enum PageFileKind {
  COLLECTION(1, "collection"), INDEX_ENGINE(2, "index"), CONFIGURATION(3, "configuration");

  private final int code;
  private final String label;

  PageFileKind(int code, String label) {
    this.code = code;
    this.label = label;
  }

  public int code() {
    return code;
  }

  public String label() {
    return label;
  }

  public static PageFileKind fromCode(int code) {
    for (var kind : values()) {
      if (kind.code == code) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unknown page-file kind: " + code);
  }
}
