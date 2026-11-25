package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

/**
 * An identity for a layout 28 page file.
 *
 * <p>Before mixing, bits 30..28 hold kind, 27..25 hold role and 24..0 hold component id.
 * A bijection over 31 bits spreads these fields across the positive internal id. Zero maps to
 * zero, so every valid identity stays positive. The storage-id portion of a cache-facing file
 * id is outside this codec.
 *
 * <p>Layout 28 requires this encoding. IDs from a different encoding can decode to a different
 * valid identity. Decode alone cannot identify the encoding.
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
    return mix(pack(kind().code(), role.code(), componentId));
  }

  public static int encode(int kind, int role, int componentId) {
    return new PageFileIdentity(PageFileRole.fromCode(PageFileKind.fromCode(kind), role),
        componentId).internalId();
  }

  public static PageFileIdentity decode(int internalId) {
    if (internalId <= 0) {
      throw new IllegalArgumentException("Page-file internal id must be positive: " + internalId);
    }
    int packed = unmix(internalId);
    var kind = PageFileKind.fromCode(packed >>> 28);
    var role = PageFileRole.fromCode(kind, (packed >>> 25) & 7);
    return new PageFileIdentity(role, packed & MAX_COMPONENT_ID);
  }

  // Raw field packing is separate so tests can mix invalid kind and role fields.
  static int pack(int kind, int role, int componentId) {
    return (kind << 28) | (role << 25) | componentId;
  }

  // Adapted from Chris Wellons's public-domain lowbias32 construction:
  // https://github.com/skeeto/hash-prospector
  // Odd multiplication modulo 2^31 and xor-shifts are bijections. Mask after each product.
  static int mix(int value) {
    value ^= value >>> 16;
    value = (value * 0x7feb352d) & Integer.MAX_VALUE;
    value ^= value >>> 15;
    value = (value * 0x046ca68b) & Integer.MAX_VALUE;
    return value ^ (value >>> 16);
  }

  static int unmix(int value) {
    value ^= value >>> 16;
    value = (value * 0x43021123) & Integer.MAX_VALUE;
    value ^= (value >>> 15) ^ (value >>> 30);
    value = (value * 0x1d69e2a5) & Integer.MAX_VALUE;
    return value ^ (value >>> 16);
  }
}
