package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import org.junit.Test;

public class PageFileIdentityTest {

  // Each kind has a local role range. Literal shifts pin the wire layout independently.
  @Test
  public void everyRoleRoundTripsAtBothComponentBoundariesWithPositiveUniqueIds() {
    var ids = new HashSet<Integer>();
    for (int kind = 1; kind <= 3; kind++) {
      int roles = kind == 3 ? 6 : 5;
      for (int role = 0; role < roles; role++) {
        for (int component : new int[] {0, 33554431}) {
          int expected = kind * 268435456 + role * 33554432 + component;
          int encoded = PageFileIdentity.encode(kind, role, component);
          assertEquals(expected, encoded);
          assertTrue(encoded > 0);
          assertTrue(ids.add(encoded));
          var decoded = PageFileIdentity.decode(expected);
          assertEquals(kind, decoded.kind().code());
          assertEquals(role, decoded.role().code());
          assertEquals(component, decoded.componentId());
          assertEquals(expected, decoded.internalId());
        }
      }
    }
    assertEquals(32, ids.size());
    assertEquals(33554431, PageFileIdentity.MAX_COMPONENT_ID);
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
        int encoded = kind * 268435456 + role * 33554432 + 1;
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
