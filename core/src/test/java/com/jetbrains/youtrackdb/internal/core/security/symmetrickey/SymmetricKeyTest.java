package com.jetbrains.youtrackdb.internal.core.security.symmetrickey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

/**
 * Tests symmetric key encryption and decryption including key generation and credential
 * management. Covers the live constructor subset (4 reachable constructors), the
 * {@code encrypt(byte[])} / {@code decrypt(String)} round-trip, the Base64-encoded JSON document
 * encoding contract used internally by those methods, the IV-length invariant for CBC-mode ciphers,
 * and the {@code Object} identity semantics of {@code equals} / {@code hashCode} / {@code toString}
 * (no custom overrides are defined on {@code SymmetricKey}).
 *
 * <p>Dead surface ({@code fromConfig}, {@code fromString}, {@code fromFile}, {@code fromKeystore}
 * family, and consumers {@code SymmetricKeyCI} / {@code SymmetricKeySecurity} /
 * {@code UserSymmetricKeyConfig}) is covered by dead-code shape pins in a separate test class.
 */
public class SymmetricKeyTest extends DbTestBase {

  // ----------------------------------------------------------
  // Pre-existing tests (unchanged)
  // ----------------------------------------------------------

  @Test
  public void shouldTestDefaultConstructor() throws Exception {
    // Verify that the no-arg constructor generates a key and supports round-trip encryption.
    var sk = new SymmetricKey();

    var msgToEncrypt = "Please, encrypt this!";

    var magic = sk.encrypt(msgToEncrypt);

    var decryptedMsg = sk.decryptAsString(magic);

    assertThat(msgToEncrypt).isEqualTo(decryptedMsg);
  }

  @Test
  public void shouldTestSpecificAESKey() throws Exception {
    // Verify that the (String algorithm, String base64Key) constructor accepts a known AES key
    // and produces a ciphertext that round-trips correctly.
    var sk = new SymmetricKey("AES", "8BC7LeGkFbmHEYNTz5GwDw==");

    var msgToEncrypt = "Please, encrypt this!";

    var magic = sk.encrypt("AES/CBC/PKCS5Padding", msgToEncrypt);

    var decryptedMsg = sk.decryptAsString(magic);

    assertThat(msgToEncrypt).isEqualTo(decryptedMsg);
  }

  @Test
  public void shouldTestGeneratedAESKey() throws Exception {
    // Verify that the (String algorithm, String transform, int keySize) constructor generates
    // a key and that the key can be exported and re-imported for cross-instance decryption.
    var sk = new SymmetricKey("AES", "AES/CBC/PKCS5Padding", 128);

    var key = sk.getBase64Key();

    var msgToEncrypt = "Please, encrypt this!";

    var magic = sk.encrypt(msgToEncrypt);

    var sk2 = new SymmetricKey("AES", key);

    var decryptedMsg = sk2.decryptAsString(magic);

    assertThat(msgToEncrypt).isEqualTo(decryptedMsg);
  }

  // ----------------------------------------------------------
  // New tests — live constructor coverage
  // ----------------------------------------------------------

  @Test
  public void secretKeyConstructorShouldWrapProvidedKeyAndAllowRoundTrip() {
    // The (SecretKey) constructor must accept a valid SecretKey, derive the algorithm from it,
    // and produce a SymmetricKey that can encrypt and decrypt data correctly.
    SecretKey secretKey =
        new SecretKeySpec(Base64.getDecoder().decode("8BC7LeGkFbmHEYNTz5GwDw=="), "AES");

    var sk = new SymmetricKey(secretKey);
    // Confirm the key algorithm is propagated from the SecretKey.
    assertThat(sk.getKeyAlgorithm("ignored")).isEqualTo("AES");

    var plaintext = "hello symmetric key";
    var encrypted = sk.encrypt("AES/CBC/PKCS5Padding", plaintext.getBytes(StandardCharsets.UTF_8));
    var decrypted = new String(sk.decrypt(encrypted), StandardCharsets.UTF_8);
    assertThat(decrypted).isEqualTo(plaintext);
  }

  @Test
  public void secretKeyConstructorShouldRejectNullSecretKey() {
    // The (SecretKey) constructor must throw SecurityException when given a null key, not NPE.
    assertThatThrownBy(() -> new SymmetricKey((SecretKey) null))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("secretKey is null");
  }

  @Test
  public void algorithmAndBase64KeyConstructorShouldRejectInvalidBase64() {
    // The (String algorithm, String base64Key) constructor must throw SecurityException when the
    // key string is not valid Base64, wrapping the underlying decode error.
    assertThatThrownBy(() -> new SymmetricKey("AES", "!!!not-valid-base64!!!"))
        .isInstanceOf(SecurityException.class);
  }

  // ----------------------------------------------------------
  // encrypt(byte[]) / decrypt(String) round-trip
  // ----------------------------------------------------------

  @Test
  public void encryptByteArrayAndDecryptShouldRoundTrip() {
    // encrypt(byte[]) must return a non-null Base64-encoded JSON document and decrypt(String)
    // must recover the original byte array exactly.
    var sk = new SymmetricKey("AES", "AES/CBC/PKCS5Padding", 128);
    var original = "round-trip bytes".getBytes(StandardCharsets.UTF_8);

    var encoded = sk.encrypt(original);

    assertThat(encoded).isNotNull();
    var recovered = sk.decrypt(encoded);
    assertThat(recovered).isEqualTo(original);
  }

  @Test
  public void decryptShouldRejectNullInput() {
    // decrypt(String) must throw SecurityException immediately when the argument is null,
    // not NPE or NullPointerException from an internal deserialization step.
    var sk = new SymmetricKey("AES", "AES/CBC/PKCS5Padding", 128);
    assertThatThrownBy(() -> sk.decrypt(null))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("encodedJSON is null");
  }

  // ----------------------------------------------------------
  // JSON encoding shape (encodeJSON / mapFromJson round-trip)
  // ----------------------------------------------------------

  @Test
  public void encryptedPayloadShouldBeBase64EncodedJsonWithExpectedFields() {
    // encrypt(byte[]) internally calls encodeJSON(), which Base64-encodes a JSON document
    // containing "algorithm", "transform", "payload", and "iv" fields. This test verifies the
    // document structure by decoding it through JSONSerializerJackson (the same path used by
    // decrypt()).
    var sk = new SymmetricKey("AES", "AES/CBC/PKCS5Padding", 128);
    var plaintext = "json-shape check".getBytes(StandardCharsets.UTF_8);

    var encoded = sk.encrypt(plaintext);
    assertThat(encoded).isNotNull();

    // Decode the outer Base64 layer to get the JSON string.
    var jsonBytes = Base64.getDecoder().decode(encoded);
    var json = new String(jsonBytes, StandardCharsets.UTF_8);

    // Parse via the same deserializer that decrypt() uses internally.
    var map = JSONSerializerJackson.INSTANCE.mapFromJson(json);

    assertThat(map).containsKey("algorithm");
    assertThat(map).containsKey("transform");
    assertThat(map).containsKey("payload");
    // AES/CBC requires an IV; it must be present in the JSON document.
    assertThat(map).containsKey("iv");

    assertThat(map.get("algorithm").toString()).isEqualTo("AES");
    assertThat(map.get("transform").toString()).isEqualTo("AES/CBC/PKCS5Padding");
    // payload and iv must be non-empty strings (Base64 encoded).
    assertThat(map.get("payload").toString()).isNotEmpty();
    assertThat(map.get("iv").toString()).isNotEmpty();
  }

  // ----------------------------------------------------------
  // IV-length invariant
  // ----------------------------------------------------------

  @Test
  public void ivLengthShouldEqualCipherBlockSizeForAesCbc() {
    // For AES/CBC/PKCS5Padding the JCE provider generates an IV whose length equals the AES
    // block size (16 bytes = 128 bits). This property is required for correct decryption and
    // must hold regardless of the key size (128-bit key used here for portability without
    // unlimited-strength policy files).
    var sk = new SymmetricKey("AES", "AES/CBC/PKCS5Padding", 128);
    var plaintext = "iv-length check".getBytes(StandardCharsets.UTF_8);

    var encoded = sk.encrypt(plaintext);
    var jsonBytes = Base64.getDecoder().decode(encoded);
    var json = new String(jsonBytes, StandardCharsets.UTF_8);
    var map = JSONSerializerJackson.INSTANCE.mapFromJson(json);

    var ivBase64 = map.get("iv").toString();
    var ivBytes = Base64.getDecoder().decode(ivBase64);

    // AES block size is always 16 bytes regardless of key length.
    assertThat(ivBytes.length).isEqualTo(16);
  }

  // ----------------------------------------------------------
  // equals / hashCode / toString — Object identity semantics
  // ----------------------------------------------------------

  @Test
  public void equalsAndHashCodeShouldUseObjectIdentity() {
    // SymmetricKey does not override equals() or hashCode(); two instances wrapping the same
    // raw key bytes are not equal under Object.equals() — reference identity is used. This
    // shape must remain stable so that any future override is a conscious decision.
    SecretKey secretKey =
        new SecretKeySpec(Base64.getDecoder().decode("8BC7LeGkFbmHEYNTz5GwDw=="), "AES");

    var sk1 = new SymmetricKey(secretKey);
    var sk2 = new SymmetricKey(secretKey);

    // Same object is equal to itself.
    assertThat(sk1).isEqualTo(sk1);
    // Two distinct instances are not equal even with the same underlying key.
    assertThat(sk1).isNotEqualTo(sk2);
    // hashCode differs for distinct instances (Object.hashCode() is identity-based).
    assertThat(sk1.hashCode()).isNotEqualTo(sk2.hashCode());
  }

  @Test
  public void toStringShouldReturnObjectDefaultRepresentation() {
    // SymmetricKey does not override toString(); the default Object.toString() format
    // ("ClassName@hexHashCode") must be returned. This shape pin ensures a future override
    // is intentional.
    var sk = new SymmetricKey("AES", "AES/CBC/PKCS5Padding", 128);
    var str = sk.toString();

    // Object.toString() always contains "@" separating the class name from the hash code.
    assertThat(str).contains("@");
    // Must not produce null or empty string.
    assertThat(str).isNotEmpty();
  }

  /* Fails under develop
  @Test
  public void shouldTestOSymmetricKeySecurity() throws Exception {
    DatabaseDocumentTx db = new DatabaseDocumentTx("memory:" + SymmetricKeyTest.class.getSimpleName());
  
    if (db.exists()) {
      db.open("admin", "admin");
      db.drop();
    }
  
    db.create();
  
    final String user = "test";
  
    command(db, "insert into OUser set name=?, password='password', status='ACTIVE', roles=(SELECT FROM Role WHERE name = ?)", user, "admin");
    command(db, "update OUser set properties={'@type':'d', 'key':'8BC7LeGkFbmHEYNTz5GwDw==','keyAlgorithm':'AES'} where name = ?", user);
  
    db.close();
  
    db.setProperty(ODatabase.OPTIONS.SECURITY.toString(), SymmetricKeySecurity.class);
  
    SymmetricKey sk = new SymmetricKey("AES", "8BC7LeGkFbmHEYNTz5GwDw==");
  
    // We encrypt the username and specify the Base64-encoded JSON document as the password.
    db.open(user, sk.encrypt("AES/CBC/PKCS5Padding", user));
    db.close();
  } */
}
