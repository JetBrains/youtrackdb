package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Governance tests for the YTDB-1203 apply-tier declarations (Track 03).
 *
 * <p>The golden table resource ({@code apply-tier-golden-table.txt}) is the durable home of the
 * tier census: one row per registered {@link PageOperation} class with its declared
 * {@link ApplyTier} and the per-row metadata needed to machine-check the SC-P / SC-R side
 * conditions (see {@link ApplyTier} for the contract). These tests make every classification
 * change a conscious, reviewed diff:
 *
 * <ul>
 *   <li>{@link #registryMatchesGoldenTable()} fails on ANY drift between the classes registered
 *       by {@link PageOperationRegistry} and the golden table (added class, removed class, or
 *       tier mismatch).</li>
 *   <li>{@link #sideConditionInequalitiesHold()} mechanically asserts the per-entry
 *       inequalities from the golden metadata under per-page max-merge, with an explicit
 *       allowlist for the two known cascading-split violations Track 04 must resolve.</li>
 * </ul>
 */
public class ApplyTierGoldenTableTest {

  private static final String GOLDEN_RESOURCE = "apply-tier-golden-table.txt";

  /**
   * The two known SC-R violations: in a cascading split, the parent-pointer insert (the
   * primary publish of the leaf-split relocation) can land on a parent page that also shrinks,
   * lifting the merged tier to RETIRE and tying with the old-leaf shrink. Track 04 must resolve
   * this (fallback predicate on the co-location, a within-tier ordering rule, or reader
   * hop-compensation) and then EMPTY this allowlist. Entries are namespaced by side condition
   * ({@code SC-P:} or {@code SC-R:} prefix) so an allowlisted SC-R pair can never suppress a
   * future SC-P violation between the same two classes, followed by
   * {@code obligationRow->targetRow} simple class names.
   */
  private static final Set<String> KNOWN_VIOLATIONS = Set.of(
      "SC-R:BTreeSVBucketV3AddNonLeafEntryOp->BTreeSVBucketV3ShrinkOp",
      "SC-R:RidbagBucketAddNonLeafEntryOp->RidbagBucketShrinkOp");

  /** One parsed golden-table row. */
  private record GoldenRow(
      String className,
      String simpleName,
      ApplyTier tier,
      boolean establishes,
      boolean primaryPublish,
      String publishedBy,
      String retiredBy,
      List<String> colocations,
      String note) {

  }

  private static Map<String, GoldenRow> goldenByFqn;
  private static Map<String, GoldenRow> goldenBySimpleName;

  @BeforeClass
  public static void loadGoldenTable() throws Exception {
    goldenByFqn = new LinkedHashMap<>();
    goldenBySimpleName = new LinkedHashMap<>();
    var resourceStream = ApplyTierGoldenTableTest.class.getResourceAsStream(GOLDEN_RESOURCE);
    Assert.assertNotNull(
        "Golden tier table resource '" + GOLDEN_RESOURCE + "' not found next to "
            + ApplyTierGoldenTableTest.class.getName()
            + ". If the file was moved or renamed, update GOLDEN_RESOURCE — the golden table"
            + " is the governance gate for all apply-tier declarations.",
        resourceStream);
    try (var reader = new BufferedReader(new InputStreamReader(
        resourceStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        var columns = line.split("\\|");
        Assert.assertEquals("Malformed golden row (expected 8 columns): " + line,
            8, columns.length);
        var className = columns[0].strip();
        var simpleName = className.substring(className.lastIndexOf('.') + 1);
        var colocColumn = columns[6].strip();
        var colocations = colocColumn.equals("-")
            ? List.<String>of()
            : Arrays.stream(colocColumn.split(",")).map(String::strip).toList();
        var row = new GoldenRow(
            className,
            simpleName,
            ApplyTier.valueOf(columns[1].strip()),
            parseYesNo(columns[2].strip(), line),
            parseYesNo(columns[3].strip(), line),
            columns[4].strip(),
            columns[5].strip(),
            colocations,
            columns[7].strip());
        Assert.assertNull("Duplicate golden row for " + className,
            goldenByFqn.put(className, row));
        Assert.assertNull("Duplicate simple class name in golden table: " + simpleName,
            goldenBySimpleName.put(simpleName, row));
      }
    }
    Assert.assertFalse("Golden table is empty", goldenByFqn.isEmpty());
  }

  private static boolean parseYesNo(String value, String line) {
    return switch (value) {
      case "yes" -> true;
      case "no" -> false;
      default -> throw new AssertionError("Expected yes/no, got '" + value + "' in: " + line);
    };
  }

  /**
   * Registers all operations into a fresh factory (exactly as AbstractStorage does at startup)
   * and returns the registered PageOperation classes via the factory's package-private
   * registration snapshot. Using the real registration path guarantees the test sees every
   * class a production storage would register — a hand-maintained list could silently miss
   * additions.
   */
  private static List<Class<?>> registeredOperationClasses() {
    var factory = new WALRecordsFactory();
    PageOperationRegistry.registerAll(factory);

    var classes = new ArrayList<Class<?>>();
    for (var entry : factory.registeredRecordTypes().entrySet()) {
      var type = entry.getValue();
      Assert.assertTrue(
          "Registered WAL record type " + type.getName() + " (ID " + entry.getKey()
              + ") is not a PageOperation; PageOperationRegistry must register only"
              + " PageOperation subclasses",
          PageOperation.class.isAssignableFrom(type));
      classes.add(type);
    }
    return classes;
  }

  /**
   * Verifies the ApplyTier ordering contract the apply loop will sort by: NEW < PAYLOAD <
   * PUBLISH < RETIRE < GATE, with UNORDERED as the trailing escape tier. A reordering of the
   * enum constants would silently change the apply order, so it must fail loudly here.
   */
  @Test
  public void tierOrderingContract() {
    Assert.assertArrayEquals(
        "ApplyTier constants must stay in apply order NEW < PAYLOAD < PUBLISH < RETIRE < GATE"
            + " < UNORDERED - the apply loop sorts by ordinal",
        new ApplyTier[] {
            ApplyTier.NEW, ApplyTier.PAYLOAD, ApplyTier.PUBLISH, ApplyTier.RETIRE,
            ApplyTier.GATE, ApplyTier.UNORDERED
        },
        ApplyTier.values());
  }

  /**
   * Golden-file drift gate: the complete mapping {registered op class → declared ApplyTier}
   * must exactly match the golden table. Fails when a newly registered class is absent from
   * the golden file, when a golden row's class is no longer registered, and when a declared
   * tier differs from the golden tier.
   */
  @Test
  public void registryMatchesGoldenTable() throws Exception {
    var registered = registeredOperationClasses();

    var registeredNames = new TreeSet<String>();
    for (var type : registered) {
      registeredNames.add(type.getName());
    }
    Assert.assertEquals("Duplicate class registered under multiple WAL record IDs",
        registered.size(), registeredNames.size());

    var missingFromGolden = new TreeSet<>(registeredNames);
    missingFromGolden.removeAll(goldenByFqn.keySet());
    Assert.assertTrue(
        "PageOperation classes registered but ABSENT from the golden tier table: "
            + missingFromGolden + ". Classify each against the SC-P/SC-R/G2 side conditions"
            + " (see ApplyTier), then deliberately add a golden row with tier and metadata"
            + " (establishes / primaryPublish / publishedBy / retiredBy / colocations / note)"
            + " to " + GOLDEN_RESOURCE + ".",
        missingFromGolden.isEmpty());

    var staleGoldenRows = new TreeSet<>(goldenByFqn.keySet());
    staleGoldenRows.removeAll(registeredNames);
    Assert.assertTrue(
        "Golden tier table rows whose classes are NO LONGER registered: " + staleGoldenRows
            + ". Remove the stale rows from " + GOLDEN_RESOURCE
            + " deliberately (and check whether dependent rows reference them).",
        staleGoldenRows.isEmpty());

    for (var type : registered) {
      var row = goldenByFqn.get(type.getName());
      var op = (PageOperation) type.getDeclaredConstructor().newInstance();
      Assert.assertSame(
          "Declared tier of " + type.getSimpleName() + " does not match the golden table."
              + " If the reclassification is intended, re-audit the class against the"
              + " SC-P/SC-R/G2 side conditions (see ApplyTier) and update " + GOLDEN_RESOURCE
              + " in the same commit; otherwise fix applyTier().",
          row.tier(), op.applyTier());
    }
  }

  /**
   * Structural validity of the golden metadata: every cross-reference (publishedBy, retiredBy,
   * colocations) must resolve to a golden row; co-location lists must be symmetric (if A can
   * share a page with B, B can share it with A — one-sided edits would otherwise leave a stale
   * list silently feeding the worst-case merge); establishing rows must name their publisher;
   * UNORDERED rows must be justified as dead and carry no ordering metadata (they force the
   * epoch-bracket fallback, so no inequality applies to them).
   */
  @Test
  public void goldenMetadataIsWellFormed() {
    for (var row : goldenByFqn.values()) {
      var name = row.simpleName();
      Assert.assertFalse("Golden row " + name + " must carry a non-empty note",
          row.note().isEmpty());

      for (var reference : row.colocations()) {
        var peer = goldenBySimpleName.get(reference);
        Assert.assertNotNull(
            "Golden row " + name + " references unknown co-location class " + reference,
            peer);
        Assert.assertTrue(
            "Co-location lists must be symmetric: " + name + " lists " + reference
                + " but " + reference + " does not list " + name
                + ". Update both rows together — asymmetric lists drift stale and feed"
                + " worstMergedTier incomplete co-location sets.",
            reference.equals(name) || peer.colocations().contains(name));
      }
      if (!row.publishedBy().equals("-")) {
        Assert.assertTrue(
            "Golden row " + name + " references unknown publishedBy class "
                + row.publishedBy(),
            goldenBySimpleName.containsKey(row.publishedBy()));
      }
      if (!row.retiredBy().equals("-")) {
        Assert.assertTrue(
            "Golden row " + name + " references unknown retiredBy class " + row.retiredBy(),
            goldenBySimpleName.containsKey(row.retiredBy()));
      }

      if (row.establishes()) {
        Assert.assertNotEquals(
            "Establishing row " + name + " must name the same-commit pointer op targeting it"
                + " (publishedBy) so SC-P can be machine-checked",
            "-", row.publishedBy());
      } else {
        Assert.assertEquals(
            "Non-establishing row " + name + " must not carry a publishedBy reference",
            "-", row.publishedBy());
      }
      if (!row.primaryPublish()) {
        Assert.assertEquals(
            "Non-primary-publish row " + name + " must not carry a retiredBy reference",
            "-", row.retiredBy());
      }

      if (row.tier() == ApplyTier.UNORDERED) {
        Assert.assertTrue(
            "UNORDERED row " + name + " must justify deadness: note must start with 'DEAD:'"
                + " and cite why no live code path produces the operation",
            row.note().startsWith("DEAD:"));
        Assert.assertFalse("UNORDERED row " + name + " must not be an establishing row",
            row.establishes());
        Assert.assertFalse("UNORDERED row " + name + " must not be a primary-publish row",
            row.primaryPublish());
        Assert.assertTrue(
            "UNORDERED row " + name + " must not carry co-locations (fallback commits are"
                + " never tier-ordered)",
            row.colocations().isEmpty());
      }
    }
  }

  /**
   * Machine check of the per-entry side conditions (PR #1241, comment on per-entry SC-P/SC-R
   * forms): for every dependent pair the inequalities must hold under per-page max-merge with
   * NEW-force.
   *
   * <ul>
   *   <li><b>SC-P</b> — for content established in place, the establishing delta's worst-case
   *       merged tier (max over the row and its declared co-locations) must stay strictly
   *       below the declared tier of the same-commit pointer targeting it.</li>
   *   <li><b>SC-R</b> — for a relocation, the primary publish's worst-case merged tier must
   *       stay strictly below the declared tier of the retire removing the old copy on
   *       another page.</li>
   * </ul>
   *
   * <p>The worst-case merge deliberately models the published-page scenario, which is the
   * binding one: pages carrying a NEW peer are freshly allocated, NEW-forced, and shielded by
   * reachability, and NEW peers cannot raise the max anyway (NEW is the lowest tier).
   * UNORDERED rows carry no inequalities — any commit containing one takes the epoch-bracket
   * fallback (asserted in {@link #goldenMetadataIsWellFormed()}).
   *
   * <p>The two known cascading-split violations are allowlisted in {@link #KNOWN_VIOLATIONS};
   * the test also fails when an allowlist entry stops violating, so the suppression cannot go
   * stale once Track 04 fixes the co-location.
   */
  @Test
  public void sideConditionInequalitiesHold() {
    var violations = new TreeSet<String>();

    for (var row : goldenByFqn.values()) {
      if (row.establishes()) {
        var publisher = goldenBySimpleName.get(row.publishedBy());
        Assert.assertNotNull(
            "Golden row " + row.simpleName() + " has dangling publishedBy reference '"
                + row.publishedBy() + "' — fix the golden table (see also"
                + " goldenMetadataIsWellFormed)",
            publisher);
        if (worstMergedTier(row).ordinal() >= publisher.tier().ordinal()) {
          violations.add("SC-P:" + row.simpleName() + "->" + publisher.simpleName());
        }
      }
      if (row.primaryPublish() && !row.retiredBy().equals("-")) {
        var retire = goldenBySimpleName.get(row.retiredBy());
        Assert.assertNotNull(
            "Golden row " + row.simpleName() + " has dangling retiredBy reference '"
                + row.retiredBy() + "' — fix the golden table (see also"
                + " goldenMetadataIsWellFormed)",
            retire);
        if (worstMergedTier(row).ordinal() >= retire.tier().ordinal()) {
          violations.add("SC-R:" + row.simpleName() + "->" + retire.simpleName());
        }
      }
    }

    var unexpected = new TreeSet<>(violations);
    unexpected.removeAll(KNOWN_VIOLATIONS);
    Assert.assertTrue(
        "Side-condition inequality violations OUTSIDE the known allowlist: " + unexpected
            + ". A prefix cut of a tier-ordered commit could show the affected entry at"
            + " neither its old nor its new site (torn read). Do NOT extend the allowlist"
            + " silently: either reclassify the involved ops against SC-P/SC-R (see"
            + " ApplyTier) or take the co-location to the Track 04 fallback predicates.",
        unexpected.isEmpty());

    var stale = new TreeSet<>(KNOWN_VIOLATIONS);
    stale.removeAll(violations);
    Assert.assertTrue(
        "KNOWN_VIOLATIONS entries that no longer violate: " + stale
            + ". The suppression is stale - remove it so the machine check guards the"
            + " (now fixed) pair again.",
        stale.isEmpty());
  }

  /**
   * Worst-case merged tier of the page carrying {@code row}: the max over the row's own tier
   * and its declared same-commit co-locations. NEW peers need no special-casing — NEW is the
   * lowest tier, so they can never raise the max, and the check deliberately targets the
   * published-page (non-NEW-forced) scenario, which is the binding one. UNORDERED peers are
   * skipped defensively: a commit containing one takes the epoch-bracket fallback and is never
   * tier-ordered (goldenMetadataIsWellFormed forbids such co-locations outright; the skip only
   * keeps this model honest if that invariant is ever relaxed).
   */
  private static ApplyTier worstMergedTier(GoldenRow row) {
    var worst = row.tier();
    for (var peerName : row.colocations()) {
      var peer = goldenBySimpleName.get(peerName);
      Assert.assertNotNull(
          "Golden row " + row.simpleName() + " has dangling co-location reference '"
              + peerName + "' — fix the golden table (see also goldenMetadataIsWellFormed)",
          peer);
      if (peer.tier() == ApplyTier.UNORDERED) {
        continue;
      }
      if (peer.tier().ordinal() > worst.ordinal()) {
        worst = peer.tier();
      }
    }
    return worst;
  }
}
