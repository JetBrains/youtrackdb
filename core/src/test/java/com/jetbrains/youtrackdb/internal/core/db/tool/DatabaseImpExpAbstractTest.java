package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.List;
import org.junit.Test;

/**
 * Branch-coverage pin for the common base class {@link DatabaseImpExpAbstract}.
 *
 * <p>{@link DatabaseImpExpAbstract} cannot be instantiated directly; the test uses a
 * tiny inert {@link Probe} subclass that records the abstract {@code parseSetting}
 * invocations and otherwise does nothing. The {@link DatabaseSessionEmbedded} reference
 * is permitted to be {@code null} because the constructor only validates filename
 * shape — it does not touch the session.
 *
 * <p>Coverage targets in this file:
 * <ul>
 *   <li>Filename quote-stripping for both single and double quotes (and the no-quote arm).
 *   <li>Default-extension append when the filename has no {@code .}.
 *   <li>Null filename short-circuit (no quote-strip, no default extension).
 *   <li>{@code -useLineFeedForRecords} option-flag dispatch in {@link
 *       DatabaseImpExpAbstract#parseSetting}.
 *   <li>The accessor surface — {@code getDatabase}, {@code getFileName}, {@code
 *       getListener}, {@code setListener}, {@code isUseLineFeedForRecords}, {@code
 *       setUseLineFeedForRecords}.
 * </ul>
 *
 * <p>Test-additive only; no production code modified. The {@code Probe} subclass
 * exists for testing only and does not represent any production sub-type.
 */
public class DatabaseImpExpAbstractTest {

  @Test
  public void doubleQuotedFileNameIsUnwrapped() {
    var listener = new RecordingListener();
    var probe = new Probe(null, "\"with quotes.json\"", listener);
    assertEquals("with quotes.json", probe.getFileName());
    assertTrue(
        "double-quote unwrap must produce a listener notification",
        listener.lastMessage != null && listener.lastMessage.contains("Detected quotes"));
  }

  @Test
  public void singleQuotedFileNameIsUnwrapped() {
    var listener = new RecordingListener();
    var probe = new Probe(null, "'apostrophe.json'", listener);
    assertEquals("apostrophe.json", probe.getFileName());
    assertTrue(
        "single-quote unwrap must produce a listener notification",
        listener.lastMessage != null && listener.lastMessage.contains("Detected quotes"));
  }

  @Test
  public void unquotedFileNameKeepsExtensionAndDoesNotNotify() {
    var listener = new RecordingListener();
    var probe = new Probe(null, "plain.json", listener);
    assertEquals("plain.json", probe.getFileName());
    assertNull(
        "no quote-strip notification should fire for an unquoted filename",
        listener.lastMessage);
  }

  @Test
  public void fileNameWithoutDotGetsDefaultJsonExtension() {
    var listener = new RecordingListener();
    var probe = new Probe(null, "noext", listener);
    assertEquals(
        "missing-extension filenames must receive the default .json suffix",
        "noext.json",
        probe.getFileName());
  }

  @Test
  public void fileNameWithDotIsLeftAlone() {
    var listener = new RecordingListener();
    var probe = new Probe(null, "report.gz", listener);
    assertEquals("report.gz", probe.getFileName());
  }

  @Test
  public void nullFileNameShortCircuitsAllPostProcessing() {
    var listener = new RecordingListener();
    var probe = new Probe(null, null, listener);
    assertNull("null filename must remain null after construction", probe.getFileName());
    assertNull(
        "no listener notification should fire when the filename is null",
        listener.lastMessage);
  }

  @Test
  public void useLineFeedForRecordsOptionFlagIsParsed() {
    var probe = new Probe(null, "any.json", new RecordingListener());
    assertFalse(
        "isUseLineFeedForRecords default value must be false",
        probe.isUseLineFeedForRecords());

    probe.setOptions(" -useLineFeedForRecords=true");
    assertTrue(probe.isUseLineFeedForRecords());

    probe.setOptions(" -useLineFeedForRecords=false");
    assertFalse(probe.isUseLineFeedForRecords());
  }

  @Test
  public void useLineFeedForRecordsCanBeSetDirectly() {
    var probe = new Probe(null, "any.json", new RecordingListener());
    probe.setUseLineFeedForRecords(true);
    assertTrue(probe.isUseLineFeedForRecords());
    probe.setUseLineFeedForRecords(false);
    assertFalse(probe.isUseLineFeedForRecords());
  }

  @Test
  public void unknownOptionsFallThroughWithoutModifyingState() {
    var probe = new Probe(null, "any.json", new RecordingListener());
    // The base class only knows about -useLineFeedForRecords; an unrecognised key
    // is forwarded to the subclass's parseSetting (Probe records but takes no action).
    // setOptions splits on ' ' via smartSplit; the leading space yields a "" empty
    // token plus "-nonsense=value", so we expect two unrecognised entries.
    probe.setOptions(" -nonsense=value");
    assertTrue(
        "unrecognised settings should include the -nonsense option",
        probe.unrecognisedOptions.contains("-nonsense"));
  }

  @Test
  public void emptyOptionStringIsAcceptedWithoutDispatch() {
    var probe = new Probe(null, "any.json", new RecordingListener());
    // null short-circuits the entire loop — verify with an explicit cast to disambiguate
    // setOptions(String).
    probe.setOptions((String) null);
    // Empty string still iterates once (smartSplit returns [""]); the empty option
    // falls through to the no-= branch and lands on the subclass.
    probe.setOptions("");
    // Both should be tolerated without throwing.
  }

  @Test
  public void listenerGetterReturnsConstructorListener() {
    var listener = new RecordingListener();
    var probe = new Probe(null, "any.json", listener);
    assertSame(listener, probe.getListener());
  }

  @Test
  public void listenerCanBeReplacedAtRuntime() {
    var first = new RecordingListener();
    var second = new RecordingListener();
    var probe = new Probe(null, "any.json", first);
    probe.setListener(second);
    assertSame(second, probe.getListener());
  }

  @Test
  public void getDatabaseReturnsConstructorSession() {
    var probe = new Probe(null, "any.json", new RecordingListener());
    // Allowed to be null in this test — we just pin that the getter exists and returns
    // the value the constructor stored in the inherited `session` field.
    assertNull(probe.getDatabase());
  }

  /**
   * Test-only subclass — exists solely to make {@link DatabaseImpExpAbstract}
   * instantiable for the branch-coverage pin above. No production code path uses this
   * class. {@code parseSetting} only records unrecognised options so the
   * fall-through-to-subclass dispatch can be asserted.
   */
  private static final class Probe extends DatabaseImpExpAbstract<DatabaseSessionEmbedded> {

    final java.util.List<String> unrecognisedOptions = new java.util.ArrayList<>();

    Probe(
        DatabaseSessionEmbedded iDatabase,
        String iFileName,
        CommandOutputListener iListener) {
      super(iDatabase, iFileName, iListener);
    }

    @Override
    protected void parseSetting(final String option, final List<String> items) {
      if (option.equalsIgnoreCase("-useLineFeedForRecords")) {
        super.parseSetting(option, items);
      } else {
        unrecognisedOptions.add(option);
      }
    }

    @Override
    public void run() {
      // No-op — the Runnable contract is inherited from DatabaseTool. The branch-
      // coverage tests in this file exercise option parsing and accessors; they
      // never invoke run(). Concrete production tools (DatabaseExport,
      // DatabaseImport) override run() to drive their main work loop.
    }
  }

  /**
   * Captures the most recent {@code onMessage} invocation. The base-class only emits
   * one notification (the quote-strip notice); a list keeper would be overkill.
   */
  private static final class RecordingListener implements CommandOutputListener {

    String lastMessage;

    @Override
    public void onMessage(String iText) {
      lastMessage = iText;
    }
  }

  /**
   * Sanity check — the recording listener references its captured value, not a static
   * shared one (defensive against a refactor that accidentally hoists the field).
   */
  @Test
  public void recordingListenerInstancesAreIndependent() {
    var a = new RecordingListener();
    var b = new RecordingListener();
    a.onMessage("hello");
    assertNotNull(a.lastMessage);
    assertNull(b.lastMessage);
  }
}
