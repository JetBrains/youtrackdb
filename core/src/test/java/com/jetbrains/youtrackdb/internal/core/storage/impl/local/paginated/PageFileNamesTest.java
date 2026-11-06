package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.CollectionDirtyPageBitSet;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMap;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import org.junit.Test;

public class PageFileNamesTest {

  private static final String[] LEGACY = {
      "orders.pcl", "orders.cpm", "orders.fsm", "orders.dpb", "global_collection_7.grb",
      "ie_7.cbt", "ie_7.nbt", "ie_7$null.cbt", "ie_7$null.nbt", "ie_7.ixs",
      "config.cd", "config.ccm", "config.fcm", "config.dcm", "config.bd", "config.nd"
  };

  private static final String[] LEGACY_STEMS = {
      "orders", "orders", "orders", "orders", "global_collection_7",
      "ie_7", "ie_7", "ie_7$null", "ie_7$null", "ie_7",
      "config", "config", "config", "config", "config", "config"
  };

  private static final String[][] BOUNDARY_DISK_NAMES = {
      {
          "ytdb-collection-0-record-data_268435456.p26",
          "ytdb-collection-0-position-map_301989888.p26",
          "ytdb-collection-0-free-space-map_335544320.p26",
          "ytdb-collection-0-dirty-page-bit-set_369098752.p26",
          "ytdb-collection-0-link-bag-tree_402653184.p26",
          "ytdb-index-0-tree-data_536870912.p26",
          "ytdb-index-0-tree-null-bucket_570425344.p26",
          "ytdb-index-0-null-key-data_603979776.p26",
          "ytdb-index-0-null-key-null-bucket_637534208.p26",
          "ytdb-index-0-histogram_671088640.p26",
          "ytdb-configuration-0-data_805306368.p26",
          "ytdb-configuration-0-position-map_838860800.p26",
          "ytdb-configuration-0-free-space-map_872415232.p26",
          "ytdb-configuration-0-dirty-page-bit-set_905969664.p26",
          "ytdb-configuration-0-property-data_939524096.p26",
          "ytdb-configuration-0-property-null-bucket_973078528.p26"
      },
      {
          "ytdb-collection-33554431-record-data_301989887.p26",
          "ytdb-collection-33554431-position-map_335544319.p26",
          "ytdb-collection-33554431-free-space-map_369098751.p26",
          "ytdb-collection-33554431-dirty-page-bit-set_402653183.p26",
          "ytdb-collection-33554431-link-bag-tree_436207615.p26",
          "ytdb-index-33554431-tree-data_570425343.p26",
          "ytdb-index-33554431-tree-null-bucket_603979775.p26",
          "ytdb-index-33554431-null-key-data_637534207.p26",
          "ytdb-index-33554431-null-key-null-bucket_671088639.p26",
          "ytdb-index-33554431-histogram_704643071.p26",
          "ytdb-configuration-33554431-data_838860799.p26",
          "ytdb-configuration-33554431-position-map_872415231.p26",
          "ytdb-configuration-33554431-free-space-map_905969663.p26",
          "ytdb-configuration-33554431-dirty-page-bit-set_939524095.p26",
          "ytdb-configuration-33554431-property-data_973078527.p26",
          "ytdb-configuration-33554431-property-null-bucket_1006632959.p26"
      }
  };

  private static final String[] COMPUTED = {
      "ytdb-collection-7-record-data.p26", "ytdb-collection-7-position-map.p26",
      "ytdb-collection-7-free-space-map.p26", "ytdb-collection-7-dirty-page-bit-set.p26",
      "ytdb-collection-7-link-bag-tree.p26", "ytdb-index-7-tree-data.p26",
      "ytdb-index-7-tree-null-bucket.p26", "ytdb-index-7-null-key-data.p26",
      "ytdb-index-7-null-key-null-bucket.p26", "ytdb-index-7-histogram.p26",
      "ytdb-configuration-7-data.p26", "ytdb-configuration-7-position-map.p26",
      "ytdb-configuration-7-free-space-map.p26", "ytdb-configuration-7-dirty-page-bit-set.p26",
      "ytdb-configuration-7-property-data.p26",
      "ytdb-configuration-7-property-null-bucket.p26"
  };

  // Independent literals cover every constructor family and keep the two languages disjoint.
  @Test
  public void allRoleNamesMatchLiteralExpectationsAndParseOnlyInTheirOwnLayout() throws Exception {
    var roles = PageFileRole.values();
    assertEquals(16, roles.length);
    var cacheName = WOWCache.class.getDeclaredMethod("createInternalFileName", String.class,
        int.class);
    cacheName.setAccessible(true);
    for (int i = 0; i < roles.length; i++) {
      var role = roles[i];
      String legacy = switch (role.kind()) {
        case COLLECTION -> role == PageFileRole.LINK_BAG_TREE
            ? PageFileNames.layout25LinkBag(7) : PageFileNames.layout25Collection("orders", role);
        case INDEX_ENGINE -> PageFileNames.layout25Index(7, role);
        case CONFIGURATION -> PageFileNames.layout25Configuration(role);
      };
      assertEquals(LEGACY[i], legacy);
      assertEquals(new PageFileNames.Layout25Name(role, LEGACY_STEMS[i]),
          PageFileNames.parseLayout25(LEGACY[i]).orElseThrow());
      var identity = new PageFileIdentity(role, 7);
      assertEquals(COMPUTED[i], PageFileNames.layout26(identity));
      assertEquals(identity, PageFileNames.parseLayout26(COMPUTED[i]).orElseThrow());
      assertFalse(PageFileNames.parseLayout25(COMPUTED[i]).isPresent());
      assertFalse(PageFileNames.parseLayout26(LEGACY[i]).isPresent());
      assertFalse(PageFileNames.parseLayout26DiskName(LEGACY[i]).isPresent());
      var disk = PageFileNames.layout26DiskName(identity);
      assertEquals(cacheName.invoke(null, COMPUTED[i], identity.internalId()), disk);
      assertEquals(identity, PageFileNames.parseLayout26DiskName(disk).orElseThrow());
      assertFalse(PageFileNames.parseLayout25(disk).isPresent());
      assertFalse(PageFileNames.parseLayout26(disk).isPresent());
      assertFalse(PageFileNames.parseLayout26DiskName(COMPUTED[i]).isPresent());
    }
    assertEquals("ytdb-collection-7-record-data_268435463.p26",
        PageFileNames.layout26DiskName(new PageFileIdentity(PageFileRole.RECORD_DATA, 7)));
  }

  // Every role must match the extensions passed by the current component constructors.
  // Literal name tests remain separate so a coordinated constant change cannot hide a rename.
  @Test
  public void legacyExtensionsMatchConstructorConstants() throws Exception {
    var extensions = new String[] {
        PaginatedCollection.DEF_EXTENSION,
        CollectionPositionMap.DEF_EXTENSION,
        FreeSpaceMap.DEF_EXTENSION,
        CollectionDirtyPageBitSet.DEF_EXTENSION,
        LinkCollectionsBTreeManagerShared.FILE_EXTENSION,
        BTreeMultiValueIndexEngine.DATA_FILE_EXTENSION,
        BTreeMultiValueIndexEngine.NULL_BUCKET_FILE_EXTENSION,
        BTreeMultiValueIndexEngine.DATA_FILE_EXTENSION,
        BTreeMultiValueIndexEngine.NULL_BUCKET_FILE_EXTENSION,
        IndexHistogramManager.IXS_EXTENSION,
        CollectionBasedStorageConfiguration.DATA_FILE_EXTENSION,
        CollectionBasedStorageConfiguration.MAP_FILE_EXTENSION,
        CollectionBasedStorageConfiguration.FREE_MAP_FILE_EXTENSION,
        CollectionBasedStorageConfiguration.DIRTY_PAGE_FILE_EXTENSION,
        CollectionBasedStorageConfiguration.TREE_DATA_FILE_EXTENSION,
        CollectionBasedStorageConfiguration.TREE_NULL_FILE_EXTENSION
    };
    var roles = PageFileRole.values();
    assertEquals(roles.length, extensions.length);
    for (int i = 0; i < roles.length; i++) {
      assertEquals(roles[i].name(), extensions[i], roles[i].layout25Extension());
    }
    // The single-value engine keeps private copies of both shared index extensions.
    var data = BTreeSingleValueIndexEngine.class.getDeclaredField("DATA_FILE_EXTENSION");
    var nullBucket =
        BTreeSingleValueIndexEngine.class.getDeclaredField("NULL_BUCKET_FILE_EXTENSION");
    data.setAccessible(true);
    nullBucket.setAccessible(true);
    assertEquals(data.get(null), PageFileRole.INDEX_DATA.layout25Extension());
    assertEquals(nullBucket.get(null), PageFileRole.INDEX_NULL_BUCKET.layout25Extension());
  }

  // Literal disk names pin every role and suffix at both component-id boundaries.
  @Test
  public void boundaryDiskNamesMatchLiteralsAndRoundTrip() {
    var roles = PageFileRole.values();
    var components = new int[] {0, 33554431};
    for (int boundary = 0; boundary < components.length; boundary++) {
      assertEquals(roles.length, BOUNDARY_DISK_NAMES[boundary].length);
      for (int i = 0; i < roles.length; i++) {
        var identity = new PageFileIdentity(roles[i], components[boundary]);
        var expected = BOUNDARY_DISK_NAMES[boundary][i];
        assertEquals(expected, PageFileNames.layout26DiskName(identity));
        assertEquals(identity, PageFileNames.parseLayout26DiskName(expected).orElseThrow());
      }
    }
  }

  // Boundaries are valid names. Noncanonical numbers, foreign roles and mismatched suffixes are not.
  @Test
  public void computedParserRejectsNearMissesWithoutTreatingThemAsDatabaseFiles() {
    for (var role : PageFileRole.values()) {
      for (int component : new int[] {0, 33554431}) {
        var identity = new PageFileIdentity(role, component);
        assertEquals(identity,
            PageFileNames.parseLayout26(PageFileNames.layout26(identity)).orElseThrow());
      }
    }
    for (String invalid : new String[] {null, "", "foreign.p26",
        "ytdb-collection-7-record-data.pcl", "ytdb-collection-07-record-data.p26",
        "ytdb-collection--1-record-data.p26", "ytdb-collection-33554432-record-data.p26",
        "ytdb-collection-999999999999999999999-record-data.p26",
        "ytdb-unknown-7-record-data.p26", "ytdb-index-7-record-data.p26",
        "ytdb-collection-7-histogram.p26", "ytdb-collection-7-record-data.p26.tmp",
        "/ytdb-collection-7-record-data.p26", "ytdb-collection-7-record-data_1.p26",
        "ytdb-collection-7-record-data_0268435463.p26",
        "ytdb-collection-7-record-data_999999999999999999.p26",
        "ytdb-collection-7-record-data_268435463.p26.tmp"}) {
      assertFalse(String.valueOf(invalid), PageFileNames.parseLayout26(invalid).isPresent());
      assertFalse(String.valueOf(invalid),
          PageFileNames.parseLayout26DiskName(invalid).isPresent());
    }
  }

  // Legacy parsing recognizes only real page roles. It does not admit control or near-miss names.
  @Test
  public void legacyParserRejectsNearMissesAndBuildersRejectWrongKinds() {
    for (String invalid : new String[] {null, "", ".pcl", "../orders.pcl", "a\\b.pcl",
        "global_collection_x.grb", "ie_x.cbt", "ie_7$null.ixs", "ie_7$nullx.cbt",
        "config2.bd", "config.cd.tmp", "database.ocf", "foreign.p26"}) {
      assertFalse(String.valueOf(invalid), PageFileNames.parseLayout25(invalid).isPresent());
    }
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout25Collection("x", PageFileRole.INDEX_DATA));
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout25Collection("x", PageFileRole.LINK_BAG_TREE));
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout25Index(7, PageFileRole.RECORD_DATA));
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout25Configuration(PageFileRole.RECORD_DATA));
    assertEquals(new PageFileNames.Layout25Name(PageFileRole.RECORD_DATA, "a.b"),
        PageFileNames.parseLayout25("a.b.pcl").orElseThrow());
  }
}
