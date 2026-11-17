package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
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
          "ytdb-p28-collection-0-record-data_268435456.pcl",
          "ytdb-p28-collection-0-position-map_301989888.cpm",
          "ytdb-p28-collection-0-free-space-map_335544320.fsm",
          "ytdb-p28-collection-0-dirty-page-bit-set_369098752.dpb",
          "ytdb-p28-collection-0-link-bag-tree_402653184.grb",
          "ytdb-p28-index-0-tree-data_536870912.cbt",
          "ytdb-p28-index-0-tree-null-bucket_570425344.nbt",
          "ytdb-p28-index-0-null-key-data_603979776.cbt",
          "ytdb-p28-index-0-null-key-null-bucket_637534208.nbt",
          "ytdb-p28-index-0-histogram_671088640.ixs",
          "ytdb-p28-configuration-0-data_805306368.cd",
          "ytdb-p28-configuration-0-position-map_838860800.ccm",
          "ytdb-p28-configuration-0-free-space-map_872415232.fcm",
          "ytdb-p28-configuration-0-dirty-page-bit-set_905969664.dcm",
          "ytdb-p28-configuration-0-property-data_939524096.bd",
          "ytdb-p28-configuration-0-property-null-bucket_973078528.nd"
      },
      {
          "ytdb-p28-collection-33554431-record-data_301989887.pcl",
          "ytdb-p28-collection-33554431-position-map_335544319.cpm",
          "ytdb-p28-collection-33554431-free-space-map_369098751.fsm",
          "ytdb-p28-collection-33554431-dirty-page-bit-set_402653183.dpb",
          "ytdb-p28-collection-33554431-link-bag-tree_436207615.grb",
          "ytdb-p28-index-33554431-tree-data_570425343.cbt",
          "ytdb-p28-index-33554431-tree-null-bucket_603979775.nbt",
          "ytdb-p28-index-33554431-null-key-data_637534207.cbt",
          "ytdb-p28-index-33554431-null-key-null-bucket_671088639.nbt",
          "ytdb-p28-index-33554431-histogram_704643071.ixs",
          "ytdb-p28-configuration-33554431-data_838860799.cd",
          "ytdb-p28-configuration-33554431-position-map_872415231.ccm",
          "ytdb-p28-configuration-33554431-free-space-map_905969663.fcm",
          "ytdb-p28-configuration-33554431-dirty-page-bit-set_939524095.dcm",
          "ytdb-p28-configuration-33554431-property-data_973078527.bd",
          "ytdb-p28-configuration-33554431-property-null-bucket_1006632959.nd"
      }
  };

  private static final String[] COMPUTED = {
      "ytdb-p28-collection-7-record-data.pcl", "ytdb-p28-collection-7-position-map.cpm",
      "ytdb-p28-collection-7-free-space-map.fsm", "ytdb-p28-collection-7-dirty-page-bit-set.dpb",
      "ytdb-p28-collection-7-link-bag-tree.grb", "ytdb-p28-index-7-tree-data.cbt",
      "ytdb-p28-index-7-tree-null-bucket.nbt", "ytdb-p28-index-7-null-key-data.cbt",
      "ytdb-p28-index-7-null-key-null-bucket.nbt", "ytdb-p28-index-7-histogram.ixs",
      "ytdb-p28-configuration-7-data.cd", "ytdb-p28-configuration-7-position-map.ccm",
      "ytdb-p28-configuration-7-free-space-map.fcm",
      "ytdb-p28-configuration-7-dirty-page-bit-set.dcm",
      "ytdb-p28-configuration-7-property-data.bd",
      "ytdb-p28-configuration-7-property-null-bucket.nd"
  };

  // Independent literals cover every constructor family and its canonical extension.
  @Test
  public void allRoleNamesMatchLiteralExpectationsAndRoundTrip() throws Exception {
    var roles = PageFileRole.values();
    assertEquals(16, roles.length);
    var cacheName = WOWCache.class.getDeclaredMethod("createInternalFileName", String.class,
        int.class);
    cacheName.setAccessible(true);
    for (int i = 0; i < roles.length; i++) {
      var role = roles[i];
      String legacy = switch (role.kind()) {
        case COLLECTION -> role == PageFileRole.LINK_BAG_TREE
            ? PageFileNames.layout27LinkBag(7) : PageFileNames.layout27Collection("orders", role);
        case INDEX_ENGINE -> PageFileNames.layout27Index(7, role);
        case CONFIGURATION -> PageFileNames.layout27Configuration(role);
      };
      assertEquals(LEGACY[i], legacy);
      assertEquals(new PageFileNames.Layout27Name(role, LEGACY_STEMS[i]),
          PageFileNames.parseLayout27(LEGACY[i]).orElseThrow());
      var identity = new PageFileIdentity(role, 7);
      assertEquals(COMPUTED[i], PageFileNames.layout28(identity));
      assertEquals(identity, PageFileNames.parseLayout28(COMPUTED[i]).orElseThrow());
      assertFalse(PageFileNames.parseLayout28(LEGACY[i]).isPresent());
      assertFalse(PageFileNames.parseLayout28DiskName(LEGACY[i]).isPresent());
      var disk = PageFileNames.layout28DiskName(identity);
      assertEquals(cacheName.invoke(null, COMPUTED[i], identity.internalId()), disk);
      assertEquals(identity, PageFileNames.parseLayout28DiskName(disk).orElseThrow());
      assertFalse(PageFileNames.parseLayout28(disk).isPresent());
      assertFalse(PageFileNames.parseLayout28DiskName(COMPUTED[i]).isPresent());
    }
    assertEquals("ytdb-p28-collection-7-record-data_268435463.pcl",
        PageFileNames.layout28DiskName(new PageFileIdentity(PageFileRole.RECORD_DATA, 7)));
  }

  // Layout 27 collection display names may use the prefix and match a layout 28 name.
  @Test
  public void prefixedLayout27CollectionNameCanAlsoBeACanonicalLayout28Name() {
    var name = "ytdb-p28-collection-7-record-data.pcl";
    assertEquals(name,
        PageFileNames.layout27Collection("ytdb-p28-collection-7-record-data",
            PageFileRole.RECORD_DATA));
    assertEquals(new PageFileNames.Layout27Name(PageFileRole.RECORD_DATA,
        "ytdb-p28-collection-7-record-data"), PageFileNames.parseLayout27(name).orElseThrow());
    assertEquals(new PageFileIdentity(PageFileRole.RECORD_DATA, 7),
        PageFileNames.parseLayout28(name).orElseThrow());
  }

  // Logical and disk names cannot overlap tracker names or legacy existence evidence.
  @Test
  public void layout28NamesAreDisjointFromControlNamesAndExistencePrefixes() {
    for (var role : PageFileRole.values()) {
      var identity = new PageFileIdentity(role, 7);
      for (var name : new String[] {
          PageFileNames.layout28(identity), PageFileNames.layout28DiskName(identity)}) {
        assertTrue(name, name.startsWith("ytdb-p28-"));
        assertFalse(name, name.startsWith("ie_"));
        assertFalse(name, name.startsWith("config"));
        assertFalse(name, name.startsWith("global_collection_"));
        assertFalse(name, ChangedPageTrackerFile.isTrackerFile(name));
        assertFalse(name, StorageBootstrapMetadata.isBootstrapArtifactName(name));
        for (var prefix : new String[] {"database.ocf", "config", "dirty.fl", "dirty.flb"}) {
          assertFalse(name, name.startsWith(prefix));
        }
      }
    }
    for (var name : new String[] {
        ChangedPageTrackerFile.FILE_NAME, ChangedPageTrackerFile.TEMPORARY_FILE_NAME,
        "database.ocf", "config", "config.bd", "dirty.fl", "dirty.flb", "dirty.fl.tmp",
        "config123.bd", "dirty.fl.extra", "storage-bootstrap-0.bsm",
        "storage-bootstrap-1.bsm", "storage-bootstrap-2.bsm",
        "storage-bootstrap-0.bsm.tmp", "storage-bootstrap-1.bsm.tmp",
        "storage-bootstrap-2.bsm.tmp", "storage-bootstrap.bsml"}) {
      assertFalse(name, PageFileNames.parseLayout28(name).isPresent());
      assertFalse(name, PageFileNames.parseLayout28DiskName(name).isPresent());
    }
  }

  // Every wrong role extension and both obsolete name forms must fail canonical parsing.
  @Test
  public void parserRejectsMismatchedRoleExtensionsAndObsoleteNames() {
    for (var role : PageFileRole.values()) {
      var identity = new PageFileIdentity(role, 7);
      var logical = PageFileNames.layout28(identity);
      var disk = PageFileNames.layout28DiskName(identity);
      for (var other : PageFileRole.values()) {
        if (other.layout27Extension().equals(role.layout27Extension())) {
          continue;
        }
        var wrongLogical = logical.substring(0, logical.lastIndexOf('.'))
            + other.layout27Extension();
        var wrongDisk = disk.substring(0, disk.lastIndexOf('.'))
            + other.layout27Extension();
        assertFalse(wrongLogical, PageFileNames.parseLayout28(wrongLogical).isPresent());
        assertFalse(wrongDisk, PageFileNames.parseLayout28DiskName(wrongDisk).isPresent());
      }
    }
    var obsoleteExtensions = new String[] {
        ".rec", ".pos", ".fsp", ".dpg", ".lbt", ".itd", ".itn", ".ind",
        ".inn", ".hgm", ".cfd", ".cfp", ".cff", ".cfb", ".cpd", ".cpn"};
    var roles = PageFileRole.values();
    for (int i = 0; i < roles.length; i++) {
      var identity = new PageFileIdentity(roles[i], 7);
      var logical = PageFileNames.layout28(identity);
      var disk = PageFileNames.layout28DiskName(identity);
      for (var extension : new String[] {obsoleteExtensions[i], ".p26"}) {
        assertFalse(PageFileNames.parseLayout28(
            logical.substring(0, logical.lastIndexOf('.')) + extension).isPresent());
        assertFalse(PageFileNames.parseLayout28DiskName(
            disk.substring(0, disk.lastIndexOf('.')) + extension).isPresent());
      }
    }
    for (var obsolete : new String[] {
        "ytdb-index-7-histogram.p26", "ytdb-index-7-histogram_671088647.p26",
        "ytdb-p28-index-7-histogram.p26", "ytdb-p28-index-7-histogram.pcl",
        "ytdb-p28-index-7-histogram_671088647.pcl"}) {
      assertFalse(obsolete, PageFileNames.parseLayout28(obsolete).isPresent());
      assertFalse(obsolete, PageFileNames.parseLayout28DiskName(obsolete).isPresent());
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
      assertEquals(roles[i].name(), extensions[i], roles[i].layout27Extension());
    }
    // The single-value engine keeps private copies of both shared index extensions.
    var data = BTreeSingleValueIndexEngine.class.getDeclaredField("DATA_FILE_EXTENSION");
    var nullBucket =
        BTreeSingleValueIndexEngine.class.getDeclaredField("NULL_BUCKET_FILE_EXTENSION");
    data.setAccessible(true);
    nullBucket.setAccessible(true);
    assertEquals(data.get(null), PageFileRole.INDEX_DATA.layout27Extension());
    assertEquals(nullBucket.get(null), PageFileRole.INDEX_NULL_BUCKET.layout27Extension());
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
        assertEquals(expected, PageFileNames.layout28DiskName(identity));
        assertEquals(identity, PageFileNames.parseLayout28DiskName(expected).orElseThrow());
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
            PageFileNames.parseLayout28(PageFileNames.layout28(identity)).orElseThrow());
      }
    }
    for (String invalid : new String[] {null, "", "foreign.p26",
        "ytdb-p26-collection-7-record-data.pcl",
        "ytdb-p26-collection-7-record-data_268435463.pcl",
        "ytdb-p28-collection-7-record-data.rec", "ytdb-p28-collection-07-record-data.pcl",
        "ytdb-p28-collection--1-record-data.pcl", "ytdb-p28-collection-33554432-record-data.pcl",
        "ytdb-p28-collection-999999999999999999999-record-data.pcl",
        "ytdb-p28-unknown-7-record-data.pcl", "ytdb-p28-index-7-record-data.pcl",
        "ytdb-p28-collection-7-histogram.pcl", "ytdb-p28-collection-7-record-data.pcl.tmp",
        "/ytdb-p28-collection-7-record-data.pcl", "ytdb-p28-collection-7-record-data_1.pcl",
        "ytdb-p28-collection-7-record-data_0268435463.pcl",
        "ytdb-p28-collection-7-record-data_999999999999999999.pcl",
        "ytdb-p28-collection-7-record-data_268435463.pcl.tmp"}) {
      assertFalse(String.valueOf(invalid), PageFileNames.parseLayout28(invalid).isPresent());
      assertFalse(String.valueOf(invalid),
          PageFileNames.parseLayout28DiskName(invalid).isPresent());
    }
  }

  // Each legacy engine extension accepts both suffix forms, including the non-role $null.ixs.
  @Test
  public void legacyIndexEvidenceAcceptsEveryExtensionAndNullSuffixInBothLayouts() {
    for (var layout28 : new boolean[] {false, true}) {
      for (var name : new String[] {
          "ie_7.cbt", "ie_7.nbt", "ie_7.ixs",
          "ie_7$null.cbt", "ie_7$null.nbt", "ie_7$null.ixs"}) {
        assertEquals(name, 7,
            PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence(name, layout28));
      }
    }
  }

  // Numeric spelling is not canonicalized. Leading zeros are evidence, but zero burns no id.
  @Test
  public void legacyIndexEvidenceRetainsLeadingZerosAndSharedBoundaries() {
    for (var layout28 : new boolean[] {false, true}) {
      assertEquals(1, PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence("ie_1.cbt", layout28));
      assertEquals(7,
          PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence("ie_0007$null.ixs", layout28));
      assertEquals(1, PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence(
          "ie_0000000000000000000000000000000000000001.nbt", layout28));
      assertEquals(-1,
          PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence("ie_0.ixs", layout28));
      assertEquals(-1,
          PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence("ie_000$null.cbt", layout28));
      assertEquals(-1, PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence(
          "ie_0000000000000000000000000000000000000000.nbt", layout28));
      assertEquals(33554431,
          PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence("ie_33554431.cbt", layout28));
    }
  }

  // Bounds differ between layouts. Literal messages pin normalized numeric and raw overflow forms.
  @Test
  public void legacyIndexEvidencePreservesLayoutSpecificBoundsAndExactErrors() {
    var names = new String[] {
        "ie_33554432.cbt", "ie_00033554432$null.nbt", "ie_2147483647.ixs",
        "ie_2147483648$null.ixs", "ie_9223372036854775807.cbt",
        "ie_9223372036854775808.nbt", "ie_999999999999999999999999$null.ixs"
    };
    var layout27Results = new long[] {33554432, 33554432, 2147483647, -1, -1, -1, -1};
    var messages = new String[] {
        "Component id 33554432 in registered file 'ie_33554432.cbt'"
            + " is outside the layout 28 range 0..33554431",
        "Component id 33554432 in registered file 'ie_00033554432$null.nbt'"
            + " is outside the layout 28 range 0..33554431",
        "Component id 2147483647 in registered file 'ie_2147483647.ixs'"
            + " is outside the layout 28 range 0..33554431",
        "Component id 2147483648 in registered file 'ie_2147483648$null.ixs'"
            + " is outside the layout 28 range 0..33554431",
        "Component id 9223372036854775807 in registered file 'ie_9223372036854775807.cbt'"
            + " is outside the layout 28 range 0..33554431",
        "Component id 9223372036854775808 in registered file 'ie_9223372036854775808.nbt'"
            + " exceeds the layout 28 bound 33554431",
        "Component id 999999999999999999999999 in registered file"
            + " 'ie_999999999999999999999999$null.ixs' exceeds the layout 28 bound 33554431"
    };
    for (int i = 0; i < names.length; i++) {
      var name = names[i];
      assertEquals(name, layout27Results[i],
          PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence(name, false));
      var error = assertThrows(name, StorageException.class,
          () -> PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence(name, true));
      assertEquals(name, messages[i], error.getMessage());
    }
  }

  // Shape and extension failures return -1 before bounds, even for oversized digit sequences.
  @Test
  public void legacyIndexEvidenceRejectsForeignAndMalformedNamesBeforeBoundsInBothLayouts() {
    for (var layout28 : new boolean[] {false, true}) {
      for (var name : new String[] {
          "", ".cbt", "ie_7", "ie_7.", "ie_7.pcl", "ie_7.CBT", "ie_7.cbt.tmp",
          "other_7.cbt", "IE_7.cbt", "ie_.cbt", "ie_$null.nbt", "ie_+7.cbt", "ie_-7.nbt",
          "ie_\u0667.cbt", "ie_\uff17.nbt", "ie_7x.cbt", "ie_7$nullx.cbt", "ie_7$null$null.cbt",
          "ie_7.0.cbt", "ie_ 7.cbt", "ie_7 .cbt", "/ie_7.cbt", "ie_7/8.cbt",
          "ie_7\\8.cbt", "ie_7_123.cbt", "ie_33554432.pcl", "ie_33554432",
          "other_33554432.cbt", "ie_33554432x.cbt", "ie_-33554432.cbt",
          "ie_999999999999999999999999.pcl", "ie_999999999999999999999999",
          "other_999999999999999999999999.cbt", "ie_999999999999999999999999x.cbt"}) {
        assertEquals(name, -1,
            PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence(name, layout28));
      }
    }
  }

  // The shared family predicate accepts only the three exact legacy engine extensions.
  @Test
  public void legacyIndexEngineExtensionFamilyRejectsForeignAndNearMissExtensions() {
    for (var extension : new String[] {".cbt", ".nbt", ".ixs"}) {
      assertTrue(extension, PageFileNames.isIndexEngineFileExtension(extension));
    }
    for (var extension : new String[] {null, "", "cbt", ".CBT", ".pcl", ".cbt.tmp"}) {
      assertFalse(String.valueOf(extension), PageFileNames.isIndexEngineFileExtension(extension));
    }
  }

  // Persisted index entries and registered files share bounds and byte-exact range messages.
  @Test
  public void layout28ComponentIdEvidenceSharesBoundsAndExactMessagesForBothSources() {
    PageFileNames.validateLayout28ComponentIdEvidence(0, "index entry 'orders'");
    PageFileNames.validateLayout28ComponentIdEvidence(33554431,
        "registered file 'ie_33554431.cbt'");
    var indexError = assertThrows(StorageException.class,
        () -> PageFileNames.validateLayout28ComponentIdEvidence(
            33554432, "index entry 'orders'"));
    assertEquals("Component id 33554432 in index entry 'orders'"
        + " is outside the layout 28 range 0..33554431", indexError.getMessage());
    var fileError = assertThrows(StorageException.class,
        () -> PageFileNames.validateLayout28ComponentIdEvidence(
            33554432, "registered file 'ie_33554432.cbt'"));
    assertEquals("Component id 33554432 in registered file 'ie_33554432.cbt'"
        + " is outside the layout 28 range 0..33554431", fileError.getMessage());
    var negativeError = assertThrows(StorageException.class,
        () -> PageFileNames.validateLayout28ComponentIdEvidence(-1, "index entry 'orders'"));
    assertEquals("Component id -1 in index entry 'orders'"
        + " is outside the layout 28 range 0..33554431", negativeError.getMessage());
  }

  // Unlike the canonical role parsers, the legacy evidence parser requires a non-null name.
  @Test
  public void legacyIndexEvidenceRetainsNullInputFailureInBothLayouts() {
    for (var layout28 : new boolean[] {false, true}) {
      assertThrows(NullPointerException.class,
          () -> PageFileNames.parseLegacyIndexEngineFileBaseIdEvidence(null, layout28));
    }
  }

  // Legacy parsing recognizes only real page roles. It does not admit control or near-miss names.
  @Test
  public void legacyParserRejectsNearMissesAndBuildersRejectWrongKinds() {
    for (String invalid : new String[] {null, "", ".pcl", "../orders.pcl", "a\\b.pcl",
        "global_collection_x.grb", "ie_x.cbt", "ie_7$null.ixs", "ie_7$nullx.cbt",
        "config2.bd", "config.cd.tmp", "database.ocf", "foreign.p26"}) {
      assertFalse(String.valueOf(invalid), PageFileNames.parseLayout27(invalid).isPresent());
    }
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout27Collection("x", PageFileRole.INDEX_DATA));
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout27Collection("x", PageFileRole.LINK_BAG_TREE));
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout27Index(7, PageFileRole.RECORD_DATA));
    assertThrows(IllegalArgumentException.class,
        () -> PageFileNames.layout27Configuration(PageFileRole.RECORD_DATA));
    assertEquals(new PageFileNames.Layout27Name(PageFileRole.RECORD_DATA, "a.b"),
        PageFileNames.parseLayout27("a.b.pcl").orElseThrow());
  }
}
