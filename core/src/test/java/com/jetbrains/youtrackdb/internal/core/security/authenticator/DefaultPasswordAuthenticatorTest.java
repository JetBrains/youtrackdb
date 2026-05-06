package com.jetbrains.youtrackdb.internal.core.security.authenticator;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests {@link DefaultPasswordAuthenticator} — the in-memory password authenticator
 * used when the security.json file specifies a "users" list.
 *
 * <p>Tests pass a null session throughout because {@link DefaultPasswordAuthenticator}
 * reads its user map from the JSON config map rather than from a live database session.
 * The {@code authenticate(session, username, password)} overload internally calls
 * {@code SecurityManager.checkPassword}, which is also null-session-safe for the
 * in-memory map case.
 *
 * <p>Password hashing uses the default PBKDF2-SHA256 algorithm. We do not lower the
 * PBKDF2 iteration count here because the hash+verify cycle is called at most once per
 * test case and is fast enough at the default settings.
 */
public class DefaultPasswordAuthenticatorTest {

  /**
   * Builds a minimal JSON config map that registers one server user in the authenticator.
   * The "resources" key is required for {@code createServerUser} to recognise the entry.
   */
  private static Map<String, Object> configWith(String username, String hashedPassword) {
    var userEntry = new HashMap<String, Object>();
    userEntry.put("username", username);
    userEntry.put("password", hashedPassword);
    userEntry.put("resources", "database.*");

    List<Map<String, Object>> users = new ArrayList<>();
    users.add(userEntry);
    return Map.of("users", users);
  }

  @Test
  public void configShouldRegisterUserInMap() {
    // After config() the user is retrievable by username via getUser().
    var hashedPw = SecurityManager.createHashWithSalt("password123");
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("server_admin", hashedPw), null);

    var user = auth.getUser("server_admin", null);
    assertThat(user).isNotNull();
    assertThat(user.getName(null)).isEqualTo("server_admin");
  }

  @Test
  public void getUserShouldReturnNullForUnknownName() {
    // A username not present in the map returns null without throwing.
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("server_admin",
        SecurityManager.createHashWithSalt("pw")), null);

    assertThat(auth.getUser("nobody", null)).isNull();
  }

  @Test
  public void getUserShouldReturnNullForNullName() {
    // Null username input must return null rather than a NullPointerException.
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("server_admin",
        SecurityManager.createHashWithSalt("pw")), null);

    assertThat(auth.getUser(null, null)).isNull();
  }

  @Test
  public void authenticateShouldReturnNullForUserWithEmptyStoredPassword() {
    // DefaultPasswordAuthenticator.createServerUser() constructs an ImmutableUser with an
    // empty string as the stored password (the JSON "password" field is read but the
    // ImmutableUser(session, name, userType) constructor stores "").  The isPasswordValid()
    // guard in authenticate() therefore returns false because the stored password is empty,
    // so authenticate() returns null regardless of the supplied plaintext.  This is the
    // observable contract of the current implementation; it pin-tests the behaviour rather
    // than a corrected version so that any future fix to createServerUser() surfaces here.
    var hashed = SecurityManager.createHashWithSalt("correct_password");
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("server_admin", hashed), null);

    // isPasswordValid returns false because the ImmutableUser stored password is "".
    var user = auth.authenticate(null, "server_admin", "correct_password");
    assertThat(user).isNull();
  }

  @Test
  public void authenticateShouldReturnNullForWrongPassword() {
    // An incorrect password causes isPasswordValid to return false; the method returns
    // null (DefaultPasswordAuthenticator wraps its auth path in a try/catch and returns
    // null on failure rather than throwing SecurityAccessException).
    var hashed = SecurityManager.createHashWithSalt("correct_password");
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("server_admin", hashed), null);

    var user = auth.authenticate(null, "server_admin", "wrong_password");
    assertThat(user).isNull();
  }

  @Test
  public void authenticateShouldReturnNullForUnknownUser() {
    // An unknown username results in getUser() returning null, which causes authenticate()
    // to return null rather than throw.
    var hashed = SecurityManager.createHashWithSalt("correct_password");
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("server_admin", hashed), null);

    assertThat(auth.authenticate(null, "nonexistent", "any_password")).isNull();
  }

  @Test
  public void configShouldSkipEntryMissingUsernameOrResources() {
    // An entry that lacks both "username" and "resources" keys must be silently ignored;
    // the valid entry alongside it is still registered (username required but not resources).
    var hashedPw = SecurityManager.createHashWithSalt("pw");
    // Bad entry: has password but neither username nor resources
    Map<String, Object> badEntry = Map.of("password", hashedPw);
    // Good entry: has username + resources
    var goodEntry = new HashMap<String, Object>();
    goodEntry.put("username", "valid_user");
    goodEntry.put("password", hashedPw);
    goodEntry.put("resources", "*");

    List<Map<String, Object>> users = List.of(badEntry, goodEntry);
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, Map.of("users", users), null);

    assertThat(auth.getUser("valid_user", null)).isNotNull();
  }

  @Test
  public void caseInsensitiveLookupAfterConfigWithFlag() {
    // When caseSensitive=false is provided in the config map, the user is stored in
    // lower-case and must be retrievable by a lower-case lookup.
    var hashed = SecurityManager.createHashWithSalt("pw");
    var userEntry = new HashMap<String, Object>();
    userEntry.put("username", "ADMIN");
    userEntry.put("password", hashed);
    userEntry.put("resources", "*");

    var cfg = new HashMap<String, Object>();
    cfg.put("caseSensitive", false);
    cfg.put("users", List.of(userEntry));

    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, cfg, null);

    // Lower-case lookup must find the entry that was stored as "ADMIN".
    assertThat(auth.getUser("admin", null)).isNotNull();
  }

  @Test
  public void disposeShouldRunWithoutException() {
    // dispose() nulls out the internal map; it must not throw.
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("u",
        SecurityManager.createHashWithSalt("pw")), null);
    auth.dispose(); // must not throw
    assertThat(auth).isNotNull();
  }

  @Test
  public void configWithNoUsersKeyShouldNotThrow() {
    // An empty config map (no "users" key) must not throw; getUser always returns null.
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, Map.of(), null);
    assertThat(auth.getUser("anyone", null)).isNull();
  }

  @Test
  public void userTypeShouldBeServerUserType() {
    // createServerUser() produces an ImmutableUser with SERVER_USER_TYPE so
    // DefaultSecuritySystem recognises it as a server-config user.
    var hashed = SecurityManager.createHashWithSalt("pw");
    var auth = new DefaultPasswordAuthenticator();
    auth.config(null, configWith("server_admin", hashed), null);

    var user = auth.getUser("server_admin", null);
    assertThat(user.getUserType()).isEqualTo(SecurityUser.SERVER_USER_TYPE);
  }
}
