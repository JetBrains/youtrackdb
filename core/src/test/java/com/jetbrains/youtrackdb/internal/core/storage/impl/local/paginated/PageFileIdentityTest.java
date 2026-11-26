package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import org.junit.Test;

public class PageFileIdentityTest {

  // Each kind has a local role range. Both component boundaries must survive the codec.
  @Test
  public void everyRoleRoundTripsAtBothComponentBoundariesWithPositiveUniqueIds() {
    var ids = new HashSet<Integer>();
    for (int kind = 1; kind <= 3; kind++) {
      int roles = kind == 3 ? 6 : 5;
      for (int role = 0; role < roles; role++) {
        for (int component : new int[] {0, 33554431}) {
          int encoded = PageFileIdentity.encode(kind, role, component);
          assertTrue(encoded > 0);
          assertTrue(ids.add(encoded));
          var decoded = PageFileIdentity.decode(encoded);
          assertEquals(kind, decoded.kind().code());
          assertEquals(role, decoded.role().code());
          assertEquals(component, decoded.componentId());
          assertEquals(encoded, decoded.internalId());
        }
      }
    }
    assertEquals(32, ids.size());
    assertEquals(33554431, PageFileIdentity.MAX_COMPONENT_ID);
  }

  // Literal vectors pin the encoding independently of pack and the inverse.
  @Test
  public void mixedEncodingMatchesKnownAnswers() {
    assertEquals(1676378299, PageFileIdentity.encode(3, 0, 0));
    assertEquals(650489682, PageFileIdentity.encode(1, 0, 0));
    assertEquals(2055768198, PageFileIdentity.encode(2, 2, 7));
    assertEquals(0, PageFileIdentity.mix(0));
    assertEquals(0, PageFileIdentity.unmix(0));
    assertEquals(1, (0x7feb352dL * 0x1d69e2a5L) & Integer.MAX_VALUE);
    assertEquals(1, (0x046ca68bL * 0x43021123L) & Integer.MAX_VALUE);
  }

  // Check both inverse directions over boundaries, every single bit and a million seeded values.
  @Test
  public void mixIsReversibleAcrossThe31BitDomain() {
    var random = new java.util.Random(1620);
    for (int i = 0; i < 1_000_000; i++) {
      int value = random.nextInt() & Integer.MAX_VALUE;
      assertEquals(value, PageFileIdentity.unmix(PageFileIdentity.mix(value)));
      assertEquals(value, PageFileIdentity.mix(PageFileIdentity.unmix(value)));
    }
    for (int bit = 0; bit < 31; bit++) {
      for (int value : new int[] {1 << bit, (1 << bit) - 1,
          Integer.MAX_VALUE ^ (1 << bit), Integer.MAX_VALUE}) {
        assertEquals(value, PageFileIdentity.unmix(PageFileIdentity.mix(value)));
        assertEquals(value, PageFileIdentity.mix(PageFileIdentity.unmix(value)));
      }
    }
  }

  // Reject every unused bit-field value, not just one representative unknown kind or role.
  @Test
  public void unusedKindsAndRolesFailInBothDirections() {
    for (int kind = 0; kind <= 7; kind++) {
      for (int role = 0; role <= 7; role++) {
        if (kind >= 1 && kind <= 3 && role < (kind == 3 ? 6 : 5)) {
          continue;
        }
        int k = kind;
        int r = role;
        assertThrows(IllegalArgumentException.class, () -> PageFileIdentity.encode(k, r, 1));
        int encoded = PageFileIdentity.mix(PageFileIdentity.pack(kind, role, 1));
        assertThrows(IllegalArgumentException.class, () -> PageFileIdentity.decode(encoded));
      }
    }
  }

  // Values outside fields must fail before packing rather than losing high bits.
  @Test
  public void outOfRangeArgumentsAndNonpositiveIdsFailExplicitly() {
    for (int invalid : new int[] {-1, Integer.MIN_VALUE, 8, Integer.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class,
          () -> PageFileIdentity.encode(invalid, 0, 1));
      assertThrows(IllegalArgumentException.class,
          () -> PageFileIdentity.encode(1, invalid, 1));
    }
    for (int invalid : new int[] {-1, Integer.MIN_VALUE, 33554432, Integer.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class,
          () -> PageFileIdentity.encode(1, 0, invalid));
    }
    for (int invalid : new int[] {0, -1, Integer.MIN_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> PageFileIdentity.decode(invalid));
    }
    assertThrows(IllegalArgumentException.class, () -> new PageFileIdentity(null, 0));
  }
}
