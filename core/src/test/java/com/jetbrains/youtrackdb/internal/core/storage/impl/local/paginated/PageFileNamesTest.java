package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMap;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.CollectionDirtyPageBitSet;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.FreeSpaceMap;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.util.HashSet;
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
          "ytdb-p26-collection-0-record-data_268435456.rec",
          "ytdb-p26-collection-0-position-map_301989888.pos",
          "ytdb-p26-collection-0-free-space-map_335544320.fsp",
          "ytdb-p26-collection-0-dirty-page-bit-set_369098752.dpg",
          "ytdb-p26-collection-0-link-bag-tree_402653184.lbt",
          "ytdb-p26-index-0-tree-data_536870912.itd",
          "ytdb-p26-index-0-tree-null-bucket_570425344.itn",
          "ytdb-p26-index-0-null-key-data_603979776.ind",
          "ytdb-p26-index-0-null-key-null-bucket_637534208.inn",
          "ytdb-p26-index-0-histogram_671088640.hgm",
          "ytdb-p26-configuration-0-data_805306368.cfd",
          "ytdb-p26-configuration-0-position-map_838860800.cfp",
          "ytdb-p26-configuration-0-free-space-map_872415232.cff",
          "ytdb-p26-configuration-0-dirty-page-bit-set_905969664.cfb",
          "ytdb-p26-configuration-0-property-data_939524096.cpd",
          "ytdb-p26-configuration-0-property-null-bucket_973078528.cpn"
      },
      {
          "ytdb-p26-collection-33554431-record-data_301989887.rec",
          "ytdb-p26-collection-33554431-position-map_335544319.pos",
          "ytdb-p26-collection-33554431-free-space-map_369098751.fsp",
          "ytdb-p26-collection-33554431-dirty-page-bit-set_402653183.dpg",
          "ytdb-p26-collection-33554431-link-bag-tree_436207615.lbt",
          "ytdb-p26-index-33554431-tree-data_570425343.itd",
          "ytdb-p26-index-33554431-tree-null-bucket_603979775.itn",
          "ytdb-p26-index-33554431-null-key-data_637534207.ind",
          "ytdb-p26-index-33554431-null-key-null-bucket_671088639.inn",
          "ytdb-p26-index-33554431-histogram_704643071.hgm",
          "ytdb-p26-configuration-33554431-data_838860799.cfd",
          "ytdb-p26-configuration-33554431-position-map_872415231.cfp",
          "ytdb-p26-configuration-33554431-free-space-map_905969663.cff",
          "ytdb-p26-configuration-33554431-dirty-page-bit-set_939524095.cfb",
          "ytdb-p26-configuration-33554431-property-data_973078527.cpd",
          "ytdb-p26-configuration-33554431-property-null-bucket_1006632959.cpn"
      }
  };

  private static final String[] COMPUTED = {
      "ytdb-p26-collection-7-record-data.rec", "ytdb-p26-collection-7-position-map.pos",
      "ytdb-p26-collection-7-free-space-map.fsp", "ytdb-p26-collection-7-dirty-page-bit-set.dpg",
      "ytdb-p26-collection-7-link-bag-tree.lbt", "ytdb-p26-index-7-tree-data.itd",
      "ytdb-p26-index-7-tree-null-bucket.itn", "ytdb-p26-index-7-null-key-data.ind",
      "ytdb-p26-index-7-null-key-null-bucket.inn", "ytdb-p26-index-7-histogram.hgm",
      "ytdb-p26-configuration-7-data.cfd", "ytdb-p26-configuration-7-position-map.cfp",
      "ytdb-p26-configuration-7-free-space-map.cff",
      "ytdb-p26-configuration-7-dirty-page-bit-set.cfb",
      "ytdb-p26-configuration-7-property-data.cpd",
      "ytdb-p26-configuration-7-property-null-bucket.cpn"
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
    assertEquals("ytdb-p26-collection-7-record-data_268435463.rec",
        PageFileNames.layout26DiskName(new PageFileIdentity(PageFileRole.RECORD_DATA, 7)));
  }

  // Each role has a unique extension outside both legacy extension sets, including omissions.
  @Test
  public void layout26ExtensionsAreDistinctAndDisjointFromLegacyExtensions() {
    var extensions = new HashSet<String>();
    for (var role : PageFileRole.values()) {
      var extension = role.layout26Extension();
      assertTrue(role.name(), extensions.add(extension));
      for (var legacy : PageFileNames.LAYOUT_25_FALLBACK_EXTENSIONS) {
        assertFalse(extension + " overlaps " + legacy, extension.endsWith(legacy));
      }
      for (var legacyRole : PageFileRole.values()) {
        assertFalse(extension + " overlaps " + legacyRole,
            extension.endsWith(legacyRole.layout25Extension()));
      }
    }
    assertEquals(16, extensions.size());
  }

  // Logical and disk names cannot overlap tracker names or legacy existence evidence.
  @Test
  public void layout26NamesAreDisjointFromControlNamesAndExistencePrefixes() {
    for (var role : PageFileRole.values()) {
      var identity = new PageFileIdentity(role, 7);
      for (var name : new String[] {
          PageFileNames.layout26(identity), PageFileNames.layout26DiskName(identity)}) {
        assertFalse(name, ChangedPageTrackerFile.isTrackerFile(name));
        assertFalse(name, StorageBootstrapMetadata.isBootstrapArtifactName(name));
        for (var prefix : new String[] {"database.ocf", "config", "dirty.fl", "dirty.flb"}) {
          assertFalse(name, name.startsWith(prefix));
        }
      }
    }
    for (var name : new String[] {
        ChangedPageTrackerFile.FILE_NAME, ChangedPageTrackerFile.TEMPORARY_FILE_NAME,
        "database.ocf", "config", "config.bd", "dirty.fl", "dirty.flb", "dirty.fl.tmp"}) {
      assertFalse(name, PageFileNames.parseLayout26(name).isPresent());
      assertFalse(name, PageFileNames.parseLayout26DiskName(name).isPresent());
    }
  }

  // Every wrong role extension and both obsolete name forms must fail canonical parsing.
  @Test
  public void parserRejectsMismatchedRoleExtensionsAndObsoleteNames() {
    for (var role : PageFileRole.values()) {
      var identity = new PageFileIdentity(role, 7);
      var logical = PageFileNames.layout26(identity);
      var disk = PageFileNames.layout26DiskName(identity);
      for (var other : PageFileRole.values()) {
        if (other == role) {
          continue;
        }
        var wrongLogical = logical.substring(0, logical.lastIndexOf('.'))
            + other.layout26Extension();
        var wrongDisk = disk.substring(0, disk.lastIndexOf('.'))
            + other.layout26Extension();
        assertFalse(wrongLogical, PageFileNames.parseLayout26(wrongLogical).isPresent());
        assertFalse(wrongDisk, PageFileNames.parseLayout26DiskName(wrongDisk).isPresent());
      }
    }
    for (var obsolete : new String[] {
        "ytdb-index-7-histogram.p26", "ytdb-index-7-histogram_671088647.p26",
        "ytdb-p26-index-7-histogram.p26", "ytdb-p26-index-7-histogram.rec",
        "ytdb-p26-index-7-histogram_671088647.rec"}) {
      assertFalse(obsolete, PageFileNames.parseLayout26(obsolete).isPresent());
      assertFalse(obsolete, PageFileNames.parseLayout26DiskName(obsolete).isPresent());
    }
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
        "ytdb-p26-collection-7-record-data.pcl", "ytdb-p26-collection-07-record-data.rec",
        "ytdb-p26-collection--1-record-data.rec", "ytdb-p26-collection-33554432-record-data.rec",
        "ytdb-p26-collection-999999999999999999999-record-data.rec",
        "ytdb-p26-unknown-7-record-data.rec", "ytdb-p26-index-7-record-data.rec",
        "ytdb-p26-collection-7-histogram.rec", "ytdb-p26-collection-7-record-data.rec.tmp",
        "/ytdb-p26-collection-7-record-data.rec", "ytdb-p26-collection-7-record-data_1.rec",
        "ytdb-p26-collection-7-record-data_0268435463.rec",
        "ytdb-p26-collection-7-record-data_999999999999999999.rec",
        "ytdb-p26-collection-7-record-data_268435463.rec.tmp"}) {
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
