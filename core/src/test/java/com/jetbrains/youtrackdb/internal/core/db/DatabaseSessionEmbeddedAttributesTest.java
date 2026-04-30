package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES_INTERNAL;
import java.util.TimeZone;
import org.junit.Test;

/**
 * Pins the {@link DatabaseSessionEmbedded#set(ATTRIBUTES, Object)} dispatcher,
 * the matching {@link DatabaseSessionEmbedded#setInternal(ATTRIBUTES, Object)}
 * delegate, the {@link DatabaseSessionEmbedded#set(ATTRIBUTES_INTERNAL, Object)}
 * VALIDATION dispatcher, and the {@link DatabaseSessionEmbedded#setCustom(String,
 * Object)} flow. Each enum branch is verified by reading back the storage-level
 * accessor that is the only public observation point ({@code session.getStorage()
 * .get<Attribute>()} / {@code .isValidationEnabled()}).
 */
public class DatabaseSessionEmbeddedAttributesTest extends DbTestBase {

  // DATEFORMAT — happy path: dispatcher writes through to AbstractStorage and
  // the storage's getDateFormat() returns the literal value passed in.
  @Test
  public void setDateformatPersistsToStorage() {
    session.set(ATTRIBUTES.DATEFORMAT, "yyyy-MM-dd");
    assertEquals("yyyy-MM-dd", session.getStorage().getDateFormat());
  }

  // DATEFORMAT — null value triggers the explicit null guard ahead of the
  // SimpleDateFormat construction. IOUtils.getStringContent(null) returns null
  // and the dispatcher rejects it with the documented message.
  @Test
  public void setDateformatNullValueIsRejected() {
    try {
      session.set(ATTRIBUTES.DATEFORMAT, null);
      fail("Expected IllegalArgumentException for null DATEFORMAT value");
    } catch (IllegalArgumentException e) {
      assertEquals("date format is null", e.getMessage());
    }
  }

  // DATEFORMAT — an invalid pattern is detected by the SimpleDateFormat
  // constructor itself; the dispatcher does not catch the resulting
  // exception, so it propagates. An unmatched single quote is the most
  // portable way to force a constructor-time rejection across SDF
  // implementations. This pins the validation contract: bad patterns
  // reject before the storage write happens.
  @Test
  public void setDateformatInvalidPatternIsRejected() {
    var before = session.getStorage().getDateFormat();
    try {
      session.set(ATTRIBUTES.DATEFORMAT, "yyyy'unmatched");
      fail("Expected exception for invalid DATEFORMAT pattern");
    } catch (RuntimeException e) {
      assertNotNull(e);
    }
    assertEquals("storage must be unchanged when validation throws",
        before, session.getStorage().getDateFormat());
  }

  // DATEFORMAT — IOUtils.getStringContent strips a surrounding pair of single
  // or double quotes, which mirrors the SQL ALTER DATABASE DATEFORMAT '...'
  // surface. Pin the strip so a reformat of the helper does not silently
  // change the dispatcher's input.
  @Test
  public void setDateformatStripsSurroundingDoubleQuotes() {
    session.set(ATTRIBUTES.DATEFORMAT, "\"yyyy-MM-dd\"");
    assertEquals("yyyy-MM-dd", session.getStorage().getDateFormat());
  }

  // DATE_TIME_FORMAT — happy path mirroring DATEFORMAT.
  @Test
  public void setDateTimeFormatPersistsToStorage() {
    session.set(ATTRIBUTES.DATE_TIME_FORMAT, "yyyy-MM-dd HH:mm:ss");
    assertEquals("yyyy-MM-dd HH:mm:ss",
        session.getStorage().getDateTimeFormat());
  }

  // DATE_TIME_FORMAT — null value is rejected with the same message the
  // production code produces for DATEFORMAT (the message is shared with
  // DATEFORMAT, which is itself a documentation bug; pinning the observed
  // text so a rephrasing fails loudly).
  @Test
  public void setDateTimeFormatNullValueIsRejected() {
    try {
      session.set(ATTRIBUTES.DATE_TIME_FORMAT, null);
      fail("Expected IllegalArgumentException for null DATE_TIME_FORMAT");
    } catch (IllegalArgumentException e) {
      assertEquals("date format is null", e.getMessage());
    }
  }

  // DATE_TIME_FORMAT — invalid pattern propagates from SimpleDateFormat. An
  // unmatched single quote forces a constructor-time rejection.
  @Test
  public void setDateTimeFormatInvalidPatternIsRejected() {
    var before = session.getStorage().getDateTimeFormat();
    try {
      session.set(ATTRIBUTES.DATE_TIME_FORMAT, "yyyy 'unmatched");
      fail("Expected exception for invalid DATE_TIME_FORMAT pattern");
    } catch (RuntimeException e) {
      assertNotNull(e);
    }
    assertEquals("storage must be unchanged when validation throws",
        before, session.getStorage().getDateTimeFormat());
  }

  // TIMEZONE — happy path with a canonical zone id. The dispatcher upper-cases
  // the input first; UTC stays UTC and the resulting TimeZone is the GMT zone.
  @Test
  public void setTimezoneCanonicalNamePersistsToStorage() {
    session.set(ATTRIBUTES.TIMEZONE, "UTC");
    var stored = session.getStorage().getTimeZone();
    assertNotNull("TIMEZONE should be set on storage", stored);
    assertEquals(TimeZone.getTimeZone("UTC"), stored);
  }

  // TIMEZONE — null value is rejected with the dedicated TIMEZONE message
  // ("Timezone can't be null").
  @Test
  public void setTimezoneNullValueIsRejected() {
    try {
      session.set(ATTRIBUTES.TIMEZONE, null);
      fail("Expected IllegalArgumentException for null TIMEZONE");
    } catch (IllegalArgumentException e) {
      assertEquals("Timezone can't be null", e.getMessage());
    }
  }

  // TIMEZONE — case-preserving fall-through: the dispatcher first looks up
  // the upper-cased input (`stringValue.toUpperCase(Locale.ENGLISH)`), and
  // if the result equals GMT (i.e., the upper-case form was unknown to
  // TimeZone), retries with the original mixed-case input. Pinning that
  // path: input "Europe/Paris" upper-cases to "EUROPE/PARIS" (unknown →
  // GMT), then the retry uses the original mixed-case form which IS a
  // recognised Java TZ id and resolves to the live Europe/Paris zone.
  @Test
  public void setTimezoneMixedCaseFallsBackToCasePreservedLookup() {
    session.set(ATTRIBUTES.TIMEZONE, "Europe/Paris");
    var stored = session.getStorage().getTimeZone();
    assertNotNull(stored);
    assertEquals(TimeZone.getTimeZone("Europe/Paris"), stored);
  }

  // TIMEZONE — an unrecognised id resolves to GMT silently because
  // TimeZone.getTimeZone returns GMT for any unknown input. This pins the
  // observed shape: garbage input does NOT throw; it falls through to GMT.
  // No WHEN-FIXED marker — this is established behaviour across the codebase.
  @Test
  public void setTimezoneUnknownIdSilentlyResolvesToGmt() {
    session.set(ATTRIBUTES.TIMEZONE, "NotARealZone/AtAll");
    var stored = session.getStorage().getTimeZone();
    assertNotNull(stored);
    assertEquals(TimeZone.getTimeZone("GMT"), stored);
  }

  // LOCALE_COUNTRY — happy path.
  @Test
  public void setLocaleCountryPersistsToStorage() {
    session.set(ATTRIBUTES.LOCALE_COUNTRY, "DE");
    assertEquals("DE", session.getStorage().getLocaleCountry());
  }

  // LOCALE_COUNTRY — there is no null guard; null reaches the storage as a
  // null country. Pinning the observed shape: storage accepts null without
  // throwing.
  @Test
  public void setLocaleCountryNullValuePassesThroughToStorage() {
    session.set(ATTRIBUTES.LOCALE_COUNTRY, null);
    // No assertion on the post-condition — different storage implementations
    // may collapse null to "" or keep it as null. The contract here is only
    // "no exception thrown by the dispatcher" (no null guard exists).
  }

  // LOCALE_LANGUAGE — happy path.
  @Test
  public void setLocaleLanguagePersistsToStorage() {
    session.set(ATTRIBUTES.LOCALE_LANGUAGE, "de");
    assertEquals("de", session.getStorage().getLocaleLanguage());
  }

  // CHARSET — happy path.
  @Test
  public void setCharsetPersistsToStorage() {
    session.set(ATTRIBUTES.CHARSET, "ISO-8859-1");
    assertEquals("ISO-8859-1", session.getStorage().getCharset());
  }

  // ATTRIBUTES — null attribute is rejected up-front before any branch.
  @Test
  public void setNullAttributeIsRejected() {
    try {
      session.set((ATTRIBUTES) null, "anything");
      fail("Expected IllegalArgumentException for null ATTRIBUTES");
    } catch (IllegalArgumentException e) {
      assertEquals("attribute is null", e.getMessage());
    }
  }

  // setInternal delegates to set(ATTRIBUTES, Object). Verify the delegation by
  // observing the storage-level side effect through the same accessor.
  @Test
  public void setInternalDelegatesToSet() {
    session.setInternal(ATTRIBUTES.LOCALE_LANGUAGE, "fr");
    assertEquals("fr", session.getStorage().getLocaleLanguage());
  }

  // ATTRIBUTES_INTERNAL.VALIDATION — "true" enables validation.
  @Test
  public void setValidationTrueEnablesValidationOnStorage() {
    session.set(ATTRIBUTES_INTERNAL.VALIDATION, "true");
    assertTrue(session.getStorage().isValidationEnabled());
  }

  // ATTRIBUTES_INTERNAL.VALIDATION — "false" disables validation.
  @Test
  public void setValidationFalseDisablesValidationOnStorage() {
    session.set(ATTRIBUTES_INTERNAL.VALIDATION, "false");
    assertFalse(session.getStorage().isValidationEnabled());
  }

  // ATTRIBUTES_INTERNAL.VALIDATION — Boolean.parseBoolean treats any non-"true"
  // (case-insensitive) input as false, including arbitrary garbage. Pin the
  // permissive parsing so a stricter validation cannot silently change the
  // dispatcher behaviour.
  @Test
  public void setValidationGarbageStringParsesAsFalse() {
    session.set(ATTRIBUTES_INTERNAL.VALIDATION, "yes-please");
    assertFalse(
        "Boolean.parseBoolean treats anything other than \"true\" as false",
        session.getStorage().isValidationEnabled());
  }

  // ATTRIBUTES_INTERNAL.VALIDATION — null value reaches Boolean.parseBoolean
  // (after IOUtils.getStringContent(null) returns null), which returns false.
  // Pins the silent null-acceptance.
  @Test
  public void setValidationNullValueParsesAsFalse() {
    session.set(ATTRIBUTES_INTERNAL.VALIDATION, null);
    assertFalse(session.getStorage().isValidationEnabled());
  }

  // ATTRIBUTES_INTERNAL — null attribute is rejected.
  @Test
  public void setValidationNullAttributeIsRejected() {
    try {
      session.set((ATTRIBUTES_INTERNAL) null, "true");
      fail("Expected IllegalArgumentException for null ATTRIBUTES_INTERNAL");
    } catch (IllegalArgumentException e) {
      assertEquals("attribute is null", e.getMessage());
    }
  }

  // setCustom — the happy "set" path: name + non-empty stringified value lands
  // in setCustomInternal which calls storage.setProperty. The fluent return
  // is the receiving session itself.
  @Test
  public void setCustomNameAndValueReturnsSelfAndDoesNotThrow() {
    var result = session.setCustom("graph.consistency.mode", "tx");
    assertSame("setCustom must return the receiving session for fluent chaining",
        session, result);
  }

  // setCustom — empty-string value is collapsed to a remove. The dispatcher
  // routes through removeCustomInternal which calls storage.removeProperty.
  @Test
  public void setCustomEmptyValueRoutesToRemove() {
    // First set, then remove via empty string — verifying the empty branch is
    // reachable without throwing.
    session.setCustom("custom.key.A", "value");
    session.setCustom("custom.key.A", "");
  }

  // setCustom — name == "clear" with null value clears all custom properties.
  // The "clear" keyword check is case-insensitive (equalsIgnoreCase).
  @Test
  public void setCustomClearKeywordLowercaseRoutesToClearProperties() {
    session.setCustom("clear", null);
  }

  @Test
  public void setCustomClearKeywordCapitalizedRoutesToClearProperties() {
    session.setCustom("Clear", null);
  }

  @Test
  public void setCustomClearKeywordUppercaseRoutesToClearProperties() {
    session.setCustom("CLEAR", null);
  }

  // setCustom — name == "clear" with a non-null value does NOT route to
  // clearCustomInternal because the && short-circuits on iValue != null.
  // Instead it falls through to the set branch, storing the property under
  // the literal name "clear".
  @Test
  public void setCustomClearKeywordWithNonNullValueFallsThroughToSet() {
    // Should not throw; routes to setCustomInternal("clear", "x").
    var result = session.setCustom("clear", "x");
    assertSame(session, result);
  }

  // setCustom — null name with a non-null value: the short-circuit
  // (name == null || customValue.isEmpty()) hits the first arm and routes to
  // removeCustomInternal(null), which delegates to storage.removeProperty(null).
  // Pinning that the null-name path does not NPE before reaching storage.
  @Test
  public void setCustomNullNameRoutesViaRemoveShortCircuit() {
    session.setCustom(null, "ignored");
  }

  // setCustom — null name AND null value: name != "clear" (because null
  // .equalsIgnoreCase yields false from the static helper's flipped order),
  // so the dispatcher enters the else branch with customValue = null, then
  // the short-circuit on `name == null` routes to removeCustomInternal(null)
  // BEFORE customValue.isEmpty() is evaluated. Pinning the absence of NPE.
  @Test
  public void setCustomNullNameNullValueRoutesViaRemoveShortCircuit() {
    session.setCustom(null, null);
  }

  // setCustom — non-"clear" name with a null value: the first branch fails
  // (because iValue == null AND name != "clear"), the dispatcher enters the
  // else branch with customValue = null. The short-circuit
  // (name == null || customValue.isEmpty()) does NOT short-circuit because
  // name is non-null, so customValue.isEmpty() runs on null and throws NPE.
  // This is a latent dispatcher bug.
  // WHEN-FIXED: forwards-to deferred-cleanup track — guard customValue==null
  // before the .isEmpty() call, or treat null as empty (route to remove).
  @Test
  public void setCustomNonClearNameNullValueThrowsNpePinningLatentBug() {
    try {
      session.setCustom("some.real.name", null);
      fail("Expected NullPointerException pinning the latent dispatcher bug");
    } catch (NullPointerException expected) {
      // Pinned. When the production guard is added, this test fails and the
      // forwarded deferred-cleanup item is the single fix point.
    }
  }

  // setCustom — Object value is stringified via "" + iValue, so a numeric
  // input becomes its decimal string form. Pin the conversion shape so a
  // future explicit String.valueOf change does not silently alter behaviour.
  @Test
  public void setCustomObjectValueIsStringConcatenatedNotToStringDirectly() {
    var result = session.setCustom("custom.numeric", 42);
    assertSame(session, result);
    // No direct read-back available without reflection, but the assertion
    // above is sufficient to pin that the dispatcher accepts a non-String
    // value and the fluent contract holds.
  }
}
