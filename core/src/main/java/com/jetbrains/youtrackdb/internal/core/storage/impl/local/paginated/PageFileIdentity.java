package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

/**
 * An identity for a layout 28 page file.
 *
 * <p>The positive internal id uses bits 30..28 for kind, 27..25 for role and 24..0 for
 * component id. The sign bit stays clear. Component zero is valid because every kind is nonzero.
 * The storage-id portion of a cache-facing file id is outside this codec.
 */
public record PageFileIdentity(PageFileRole role, int componentId) {

  /** The configuration identity is available before its counters can be read. */
  public static final int CONFIGURATION_COMPONENT_ID = 0;

  public static final int MAX_COMPONENT_ID = (1 << 25) - 1;

  public PageFileIdentity {
    if (role == null) {
      throw new IllegalArgumentException("Page-file role is required");
    }
    if (componentId < 0 || componentId > MAX_COMPONENT_ID) {
      throw new IllegalArgumentException("Page-file component id out of range: " + componentId);
    }
  }

  public PageFileKind kind() {
    return role.kind();
  }

  public int internalId() {
    return (kind().code() << 28) | (role.code() << 25) | componentId;
  }

  public static int encode(int kind, int role, int componentId) {
    return new PageFileIdentity(PageFileRole.fromCode(PageFileKind.fromCode(kind), role),
        componentId).internalId();
  }

  public static PageFileIdentity decode(int internalId) {
    if (internalId <= 0) {
      throw new IllegalArgumentException("Page-file internal id must be positive: " + internalId);
    }
    var kind = PageFileKind.fromCode(internalId >>> 28);
    var role = PageFileRole.fromCode(kind, (internalId >>> 25) & 7);
    return new PageFileIdentity(role, internalId & MAX_COMPONENT_ID);
  }
}
