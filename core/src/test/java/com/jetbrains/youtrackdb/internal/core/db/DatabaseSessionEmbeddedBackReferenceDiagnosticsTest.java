package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import org.junit.Test;

/**
 * Unit tests for {@link DatabaseSessionEmbedded#describeMissingBackReference}, the diagnostic
 * builder used when commit-time back-reference maintenance ({@code updateOppositeLinks}) finds an
 * opposite link bag that does not contain a link it is about to remove.
 *
 * <p>The underlying consistency violation is rare and reproduces only under heavy concurrency on
 * weak-memory-model (ARM) hardware (see {@code LocalPaginatedStorageRestoreFromWALIT}), so it
 * cannot be triggered deterministically. These tests instead exercise the diagnostic builder
 * directly with constructed entities and bags to verify that: (1) the message carries the state a
 * future investigator needs (source/opposite identity and version, the source's forward links, and
 * the bag's type, size, pointer, and contents); (2) the original violation summary is preserved
 * verbatim as the message prefix for log-grep continuity; and (3) gathering diagnostics is total —
 * a null or failing input degrades a single named field instead of throwing and masking the real
 * error. The bag-type (embedded/btree), new-entity, and cap-boundary branches are each pinned.
 */
public class DatabaseSessionEmbeddedBackReferenceDiagnosticsTest extends DbTestBase {

  /** Creates a persisted, schemaless entity carrying one marker property so it gets a real RID. */
  private EntityImpl newPersistentEntity(String marker) {
    session.begin();
    var entity = (EntityImpl) session.newEntity();
    entity.setProperty("marker", marker);
    session.commit();
    return entity;
  }

  /**
   * A populated embedded bag that is missing the source link: the message must keep the base
   * summary as its exact prefix and then append the source/opposite state fields (full shape,
   * including version and dirty flag), the source forward-links field, the opposite bag-property
   * name, the bag type/size/pointer, and the bag contents and diff. This is the dominant signal —
   * it tells the investigator whether the bag lost only this one entry (other RIDs still present)
   * or something larger. Assertions pin whole fields, not scattered fragments, so a regression
   * that drops a sub-field or mislabels one is caught.
   */
  @Test
  public void diagnosticsCapturePopulatedBagState() {
    var source = newPersistentEntity("source");
    var opposite = newPersistentEntity("opposite");

    session.begin();
    // Reload the committed source in the active transaction so its properties are
    // readable: the forward-links field must actually render (as null here — the property
    // is absent) instead of degrading to an error marker.
    source = session.load(source.getIdentity());
    var bag = new LinkBag(session);
    var present1 = new RecordId(12, 100);
    var present2 = new RecordId(12, 101);
    bag.add(present1);
    bag.add(present2);

    var base = "Cannot remove link " + source.getIdentity()
        + " from opposite entity because it does not exist in opposite link bag : #linkMap";
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        base, source, "linkMap", opposite, "#linkMap", bag, -1);
    // Source/opposite are committed and untouched in this tx, so they render v<n> dirty=false.
    var expectedSourceField =
        "source=" + source.getIdentity() + " v" + source.getVersion() + " dirty=false";
    var expectedOppositeField =
        "opposite=" + opposite.getIdentity() + " v" + opposite.getVersion() + " dirty=false";
    session.rollback();

    // Base summary is the verbatim prefix, with the diagnostic block appended after it.
    assertTrue(message, message.startsWith(base + " [diag"));
    assertTrue(message, message.endsWith("]"));
    assertTrue(message, message.contains("sourceProperty=linkMap"));
    assertTrue(message, message.contains(expectedSourceField));
    // The source has no 'linkMap' property, so the forward-links field renders the null
    // gracefully. (The historical String.valueOf(char[]) overload trap turned this into
    // <error:NullPointerException>, destroying the source-side evidence.)
    assertTrue(message, message.contains("sourceForwardLinks=null"));
    assertFalse("no diagnostic field may degrade to an error marker: " + message,
        message.contains("<error:"));
    assertTrue(message, message.contains("oppositeBagProperty=#linkMap"));
    assertTrue(message, message.contains(expectedOppositeField));
    assertTrue(message, message.contains("bagType=embedded"));
    assertTrue(message, message.contains("bagSize=2"));
    assertTrue(message, message.contains("bagPointer="));
    // The RIDs the bag actually holds, as a unit — distinguishes "one entry lost" from "whole bag
    // lost"; embedded-bag iteration is RID-ascending so the order is deterministic.
    assertTrue(message, message.contains("bagContents=[" + present1 + "," + present2 + "]"));
    assertTrue(message, message.contains("diff=-1"));
  }

  /**
   * An empty bag is the "whole bag lost" signal. The message must report {@code bagSize=0} and an
   * empty contents list rather than omitting the bag fields.
   */
  @Test
  public void diagnosticsReportEmptyBag() {
    var source = newPersistentEntity("source");
    var opposite = newPersistentEntity("opposite");

    session.begin();
    var bag = new LinkBag(session);
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkMap", opposite, "#linkMap", bag, -1);
    session.rollback();

    assertTrue(message, message.contains("bagType=embedded"));
    assertTrue(message, message.contains("bagSize=0"));
    assertTrue(message, message.contains("bagContents=[]"));
  }

  /**
   * When the opposite back-reference bag property is absent entirely (the {@code linkBag == null}
   * branch of {@code updateOppositeLinks}), the message must say {@code bag=absent} and omit the
   * bag size/type fields. No transaction is opened because no bag is constructed.
   */
  @Test
  public void diagnosticsReportAbsentBag() {
    var source = newPersistentEntity("source");
    var opposite = newPersistentEntity("opposite");

    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkMap", opposite, "#linkMap", null, -1);

    assertTrue(message, message.contains("bag=absent"));
    assertFalse(message, message.contains("bagSize="));
  }

  /**
   * A B-tree-backed bag must render {@code bagType=btree} and take the
   * {@code BTreeBasedLinkBag.getCollectionPointer()} arm of {@code getPointer()}. The delegate is
   * injected directly (no {@code GlobalConfiguration} mutation, so this test stays parallel-safe)
   * to exercise the non-embedded branch the embedded-bag tests cannot reach.
   */
  @Test
  public void diagnosticsReportBtreeBagType() {
    var source = newPersistentEntity("source");
    var opposite = newPersistentEntity("opposite");

    session.begin();
    var bag = new LinkBag(session, new BTreeBasedLinkBag(session, Integer.MAX_VALUE));
    assertFalse("delegate must be the B-tree implementation", bag.isEmbedded());
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkMap", opposite, "#linkMap", bag, -1);
    session.rollback();

    assertTrue(message, message.contains("bagType=btree"));
    // getPointer() returns the collection pointer (null before persistence) for a btree bag.
    assertTrue(message, message.contains("bagPointer="));
  }

  /**
   * The contents dump is capped so a pathologically large bag cannot produce an unbounded log
   * line; the dropped count is reported and the true size is still shown. Sixty-five entries
   * exceeds the cap of 64 by one, so the dump shows "(1 more)".
   */
  @Test
  public void diagnosticsCapLargeBagContents() {
    var source = newPersistentEntity("source");
    var opposite = newPersistentEntity("opposite");

    session.begin();
    var bag = new LinkBag(session);
    for (var i = 0; i < 65; i++) {
      bag.add(new RecordId(13, i));
    }
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkMap", opposite, "#linkMap", bag, -1);
    session.rollback();

    assertTrue(message, message.contains("bagSize=65"));
    // Cap of 64 leaves exactly one entry undumped.
    assertTrue(message, message.contains("(1 more)"));
  }

  /**
   * The other side of the cap boundary: a bag of exactly 64 entries must dump all of them with no
   * truncation marker. Together with {@link #diagnosticsCapLargeBagContents} this pins the
   * {@code i >= max} comparison from both sides so an off-by-one (e.g. {@code >} vs {@code >=}) is
   * caught.
   */
  @Test
  public void diagnosticsDumpExactlyAtCapEmitsNoMoreSuffix() {
    var source = newPersistentEntity("source");
    var opposite = newPersistentEntity("opposite");

    session.begin();
    var bag = new LinkBag(session);
    for (var i = 0; i < 64; i++) {
      bag.add(new RecordId(13, i));
    }
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkMap", opposite, "#linkMap", bag, -1);
    session.rollback();

    assertTrue(message, message.contains("bagSize=64"));
    assertFalse(message, message.contains(" more)"));
  }

  /**
   * The source's forward links are the other half of the evidence: for a linkMap property
   * the rendered value must show the ACTUAL map content — the key and the target rid — not
   * an error marker. This pins the fix for the {@code String.valueOf(char[])} overload
   * trap: {@code getPropertyInternal}'s unbounded generic return let javac resolve the
   * render call to the char[] overload, so every non-char[] property value threw
   * ClassCastException and the field degraded to {@code <error:ClassCastException>}.
   */
  @Test
  public void diagnosticsRenderSourceForwardLinksContentForLinkMap() {
    var opposite = newPersistentEntity("opposite");
    var targetRid = new RecordId(15, 7);

    session.begin();
    var source = (EntityImpl) session.newEntity();
    source.getOrCreateLinkMap("linkMap").put("edgeKey", targetRid);
    var bag = new LinkBag(session);
    bag.add(new RecordId(15, 8));
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkMap", opposite, "#linkMap", bag, -1);
    session.rollback();

    // The rendered forward-links value carries the real map content: key and target rid.
    assertTrue(message, message.contains("sourceForwardLinks="));
    assertTrue("rendered value must contain the map key: " + message,
        message.contains("edgeKey"));
    assertTrue("rendered value must contain the target rid: " + message,
        message.contains(targetRid.toString()));
    assertFalse("no diagnostic field may degrade to an error marker: " + message,
        message.contains("<error:"));
  }

  /**
   * Same content pin for a second property shape (linkList): the rendered forward-links
   * value must contain every target rid. Before the overload-trap fix this field threw
   * ClassCastException for list values exactly as for maps.
   */
  @Test
  public void diagnosticsRenderSourceForwardLinksContentForLinkList() {
    var opposite = newPersistentEntity("opposite");
    var ridA = new RecordId(15, 8);
    var ridB = new RecordId(15, 9);

    session.begin();
    var source = (EntityImpl) session.newEntity();
    var linkList = source.getOrCreateLinkList("linkList");
    linkList.add(ridA);
    linkList.add(ridB);
    var bag = new LinkBag(session);
    bag.add(new RecordId(15, 10));
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkList", opposite, "#linkList", bag, -1);
    session.rollback();

    assertTrue(message, message.contains("sourceForwardLinks="));
    assertTrue("rendered value must contain the first rid: " + message,
        message.contains(ridA.toString()));
    assertTrue("rendered value must contain the second rid: " + message,
        message.contains(ridB.toString()));
    assertFalse("no diagnostic field may degrade to an error marker: " + message,
        message.contains("<error:"));
  }

  /**
   * A not-yet-persisted source entity must render the {@code NEW} marker and its version, since
   * whether the lost link involved a brand-new record is exactly the kind of state an investigator
   * needs. Exercises the {@code identity.isNew()} true branch of {@code describeEntityState}.
   */
  @Test
  public void diagnosticsMarkNewSourceEntity() {
    var opposite = newPersistentEntity("opposite");

    session.begin();
    var newSource = (EntityImpl) session.newEntity();
    var newId = newSource.getIdentity();
    assertTrue("precondition: source identity is new", newId.isNew());
    var bag = new LinkBag(session);
    bag.add(new RecordId(16, 1));
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", newSource, "linkMap", opposite, "#linkMap", bag, -1);
    session.rollback();

    assertTrue(message, message.contains("source=" + newId + " v"));
    assertTrue(message, message.contains(" NEW"));
  }

  /**
   * Diagnostic gathering must be total: a failure while reading one field (here a null opposite
   * entity, which makes the state reader throw a {@link NullPointerException}) is replaced by an
   * error marker that names the exception class, so the original consistency exception is never
   * masked and the investigator still sees why the field failed. Fields after the failing one must
   * still be gathered.
   */
  @Test
  public void diagnosticsAreDefensiveWhenAFieldThrows() {
    var source = newPersistentEntity("source");

    session.begin();
    var bag = new LinkBag(session);
    bag.add(new RecordId(14, 1));
    var message = DatabaseSessionEmbedded.describeMissingBackReference(
        "base", source, "linkMap", null, "#linkMap", bag, -1);
    session.rollback();

    // The opposite-entity field failed and degraded to a marker naming the exception class.
    assertTrue(message, message.contains("opposite=<error:NullPointerException>"));
    // Fields after the failing one are still gathered.
    assertTrue(message, message.contains("bagType=embedded"));
    assertTrue(message, message.contains("diff=-1"));
  }
}
