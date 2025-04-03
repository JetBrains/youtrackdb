package com.jetbrains.youtrack.db.internal.server.token;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.exception.SystemException;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.ImmutableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.metadata.security.TokenException;
import com.jetbrains.youtrack.db.internal.core.metadata.security.binary.BinaryToken;
import com.jetbrains.youtrack.db.internal.core.metadata.security.binary.BinaryTokenPayloadImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.binary.BinaryTokenSerializer;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.JwtPayload;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.TokenHeader;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.YouTrackDBJwtHeader;
import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.core.security.TokenSign;
import com.jetbrains.youtrack.db.internal.core.security.TokenSignImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.TokenHandler;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocolData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 *
 */
public class TokenHandlerImpl implements TokenHandler {

  protected static final int JWT_DELIMITER = '.';
  private BinaryTokenSerializer binarySerializer;
  private long sessionInMills = 1000 * 60 * 60; // 1 HOUR
  private final TokenSign sign;

  public TokenHandlerImpl(ContextConfiguration config) {
    this(
        new TokenSignImpl(config),
        config.getValueAsLong(GlobalConfiguration.NETWORK_TOKEN_EXPIRE_TIMEOUT));
  }

  protected TokenHandlerImpl(byte[] key, long sessionLength, String algorithm) {
    this(new TokenSignImpl(key, algorithm), sessionLength);
  }

  public TokenHandlerImpl(TokenSign sign, ContextConfiguration config) {
    this(sign, config.getValueAsLong(GlobalConfiguration.NETWORK_TOKEN_EXPIRE_TIMEOUT));
  }

  protected TokenHandlerImpl(TokenSign sign, long sessionLength) {
    this.sign = sign;
    sessionInMills = sessionLength * 1000 * 60;
    this.binarySerializer =
        new BinaryTokenSerializer(
            new String[]{"disk", "memory"},
            this.sign.getKeys(),
            new String[]{this.sign.getAlgorithm()},
            new String[]{"YouTrackDB", "node"});
  }

  protected TokenHandlerImpl() {
    this.sign = null;
  }

  @Override
  public Token parseWebToken(byte[] tokenBytes) {
    var parsedToken = parseOnlyWebToken(tokenBytes);
    var token = parsedToken.getToken();
    token.setIsVerified(this.sign.verifyTokenSign(parsedToken));
    return token;
  }

  @Override
  public ParsedToken parseOnlyWebToken(byte[] tokenBytes) {
    JsonWebToken token = null;

    // / <header>.<payload>.<signature>
    var firstDot = -1;
    var secondDot = -1;
    for (var x = 0; x < tokenBytes.length; x++) {
      if (tokenBytes[x] == JWT_DELIMITER) {
        if (firstDot == -1) {
          firstDot = x; // stores reference to first '.' character in JWT token
        } else {
          secondDot = x;
          break;
        }
      }
    }

    if (firstDot == -1) {
      throw new RuntimeException("Token data too short: missed header");
    }

    if (secondDot == -1) {
      throw new RuntimeException("Token data too short: missed signature");
    }
    final var decodedHeader =
        Base64.getUrlDecoder().decode(ByteBuffer.wrap(tokenBytes, 0, firstDot)).array();
    final var decodedPayload =
        Base64.getUrlDecoder()
            .decode(ByteBuffer.wrap(tokenBytes, firstDot + 1, secondDot - (firstDot + 1)))
            .array();
    final var decodedSignature =
        Base64.getUrlDecoder()
            .decode(ByteBuffer.wrap(tokenBytes, secondDot + 1, tokenBytes.length - (secondDot + 1)))
            .array();

    final var header = deserializeWebHeader(decodedHeader);
    final var deserializeWebPayload =
        deserializeWebPayload(header.getType(), decodedPayload);
    token = new JsonWebToken(header, deserializeWebPayload);
    var onlyTokenBytes = new byte[secondDot];
    System.arraycopy(tokenBytes, 0, onlyTokenBytes, 0, secondDot);
    return new ParsedToken(token, onlyTokenBytes, decodedSignature);
  }

  @Override
  public boolean validateToken(ParsedToken token, String command, String database) {
    if (!token.getToken().getIsVerified()) {
      var value = this.sign.verifyTokenSign(token);
      token.getToken().setIsVerified(value);
    }
    return token.getToken().getIsVerified() && validateToken(token.getToken(), command, database);
  }

  @Override
  public boolean validateToken(final Token token, final String command, final String database) {
    var valid = false;
    if (!(token instanceof JsonWebToken)) {
      return false;
    }
    final var curTime = System.currentTimeMillis();
    if (token.getDatabaseName().equalsIgnoreCase(database)
        && token.getExpiry() > curTime
        && (token.getExpiry() - (sessionInMills + 1)) < curTime) {
      valid = true;
    }
    token.setIsValid(valid);
    return valid;
  }

  @Override
  public boolean validateBinaryToken(ParsedToken token) {
    if (!token.getToken().getIsVerified()) {
      var value = this.sign.verifyTokenSign(token);
      token.getToken().setIsVerified(value);
    }
    return token.getToken().getIsVerified() && validateBinaryToken(token.getToken());
  }

  @Override
  public boolean validateBinaryToken(final Token token) {
    var valid = false;
    // The "node" token is for backward compatibility for old ditributed binary, may be removed if
    // we do not support runtime compatiblity with 3.1 or less
    if ("node".equals(token.getHeader().getType())) {
      valid = true;
    } else {
      final var curTime = System.currentTimeMillis();
      if (token.getExpiry() > curTime && (token.getExpiry() - (sessionInMills + 1)) < curTime) {
        valid = true;
      }
    }
    token.setIsValid(valid);

    return valid;
  }

  public byte[] getSignedWebToken(final DatabaseSessionInternal session, final SecurityUser user) {
    final var tokenByteOS = new ByteArrayOutputStream(1024);
    final var header = new YouTrackDBJwtHeader();
    header.setAlgorithm("HS256");
    header.setKeyId("");

    final var payload = createPayload(session, user);
    header.setType(getPayloadType(payload));
    try {
      var bytes = serializeWebHeader(header);
      tokenByteOS.write(
          Base64.getUrlEncoder().encode(ByteBuffer.wrap(bytes, 0, bytes.length)).array());
      tokenByteOS.write(JWT_DELIMITER);
      bytes = serializeWebPayload(payload);
      tokenByteOS.write(
          Base64.getUrlEncoder().encode(ByteBuffer.wrap(bytes, 0, bytes.length)).array());
      var unsignedToken = tokenByteOS.toByteArray();
      tokenByteOS.write(JWT_DELIMITER);

      bytes = this.sign.signToken(header, unsignedToken);
      tokenByteOS.write(
          Base64.getUrlEncoder().encode(ByteBuffer.wrap(bytes, 0, bytes.length)).array());
    } catch (Exception ex) {
      throw BaseException.wrapException(new SystemException("Error on token parsing"), ex, session);
    }

    return tokenByteOS.toByteArray();
  }

  public byte[] getSignedWebTokenServerUser(final SecurityUser user) {
    final var tokenByteOS = new ByteArrayOutputStream(1024);
    final var header = new YouTrackDBJwtHeader();
    header.setAlgorithm("HS256");
    header.setKeyId("");

    final var payload = createPayloadServerUser(user);
    header.setType(getPayloadType(payload));
    try {
      var bytes = serializeWebHeader(header);
      tokenByteOS.write(
          Base64.getUrlEncoder().encode(ByteBuffer.wrap(bytes, 0, bytes.length)).array());
      tokenByteOS.write(JWT_DELIMITER);
      bytes = serializeWebPayload(payload);
      tokenByteOS.write(
          Base64.getUrlEncoder().encode(ByteBuffer.wrap(bytes, 0, bytes.length)).array());
      var unsignedToken = tokenByteOS.toByteArray();
      tokenByteOS.write(JWT_DELIMITER);

      bytes = this.sign.signToken(header, unsignedToken);
      tokenByteOS.write(
          Base64.getUrlEncoder().encode(ByteBuffer.wrap(bytes, 0, bytes.length)).array());
    } catch (Exception ex) {
      throw BaseException.wrapException(new SystemException("Error on token parsing"), ex,
          (String) null);
    }

    return tokenByteOS.toByteArray();
  }

  @Override
  public boolean validateServerUserToken(Token token, String command, String database) {
    var valid = false;
    if (!(token instanceof JsonWebToken)) {
      return false;
    }
    final var payload = (YouTrackDBJwtPayload) ((JsonWebToken) token).getPayload();
    if (token.isNowValid()) {
      valid = true;
    }
    token.setIsValid(valid);
    return valid;
  }

  public byte[] getSignedBinaryToken(
      final DatabaseSessionInternal session,
      final SecurityUser user,
      final NetworkProtocolData data) {
    try {

      final var token = new BinaryToken();

      var curTime = System.currentTimeMillis();

      final var header = new YouTrackDBJwtHeader();
      header.setAlgorithm(this.sign.getAlgorithm());
      header.setKeyId(this.sign.getDefaultKey());
      header.setType("YouTrackDB");
      token.setHeader(header);
      var payload = new BinaryTokenPayloadImpl();
      if (session != null) {
        payload.setDatabase(session.getDatabaseName());
        payload.setDatabaseType(session.getStorage().getType());
      }
      if (data.serverUser) {
        payload.setServerUser(true);
        payload.setUserName(data.serverUsername);
      }
      if (user != null) {
        payload.setUserRid(user.getIdentity().getIdentity());
      }
      payload.setExpiry(curTime + sessionInMills);
      payload.setProtocolVersion(data.protocolVersion);
      payload.setSerializer(data.getSerializationImpl());
      payload.setDriverName(data.driverName);
      payload.setDriverVersion(data.driverVersion);
      token.setPayload(payload);

      return serializeSignedToken(token);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw BaseException.wrapException(new SystemException("Error on token parsing"), e, session);
    }
  }

  private byte[] serializeSignedToken(BinaryToken token) throws IOException {
    var baos = new ByteArrayOutputStream();
    binarySerializer.serialize(token, baos);

    final var signature = this.sign.signToken(token.getHeader(), baos.toByteArray());
    baos.write(signature);

    return baos.toByteArray();
  }

  @Nullable
  public NetworkProtocolData getProtocolDataFromToken(
      ClientConnection connection, final Token token) {
    if (token instanceof BinaryToken binary) {
      final var data = new NetworkProtocolData();
      // data.clientId = binary.get;
      data.protocolVersion = binary.getProtocolVersion();
      data.setSerializationImpl(binary.getSerializer());
      data.driverName = binary.getDriverName();
      data.driverVersion = binary.getDriverVersion();
      data.serverUser = binary.isServerUser();
      data.serverUsername = binary.getUserName();
      data.serverUsername = binary.getUserName();
      data.supportsLegacyPushMessages = connection.getData().supportsLegacyPushMessages;
      data.collectStats = connection.getData().collectStats;
      return data;
    }
    return null;
  }

  @Override
  public Token parseNotVerifyBinaryToken(byte[] binaryToken) {
    final var bais = new ByteArrayInputStream(binaryToken);
    return deserializeBinaryToken(bais);
  }

  @Override
  public ParsedToken parseOnlyBinary(byte[] binaryToken) {
    try {
      final var bais = new ByteArrayInputStream(binaryToken);

      final var token = deserializeBinaryToken(bais);
      final var end = binaryToken.length - bais.available();
      final var decodedSignature = new byte[bais.available()];
      bais.read(decodedSignature);
      var onlyTokenBytes = new byte[end];
      System.arraycopy(binaryToken, 0, onlyTokenBytes, 0, end);
      return new ParsedToken(token, onlyTokenBytes, decodedSignature);
    } catch (Exception e) {
      throw BaseException.wrapException(new SystemException("Error on token parsing"), e,
          (String) null);
    }
  }

  public Token parseBinaryToken(final byte[] binaryToken) {
    var parsedToken = parseOnlyBinary(binaryToken);
    var token = parsedToken.getToken();
    token.setIsVerified(this.sign.verifyTokenSign(parsedToken));
    return token;
  }

  @Override
  public byte[] renewIfNeeded(final Token token) {
    if (token == null) {
      throw new IllegalArgumentException("Token is null");
    }

    final var curTime = System.currentTimeMillis();
    if (token.getExpiry() - curTime < (sessionInMills / 2) && token.getExpiry() >= curTime) {
      final var expiryMinutes = sessionInMills;
      final var currTime = System.currentTimeMillis();
      token.setExpiry(currTime + expiryMinutes);
      try {
        if (token instanceof BinaryToken) {
          return serializeSignedToken((BinaryToken) token);
        } else {
          throw new TokenException("renew of web token not supported");
        }
      } catch (IOException e) {
        throw BaseException.wrapException(new SystemException("Error on token parsing"), e,
            (String) null);
      }
    }
    return CommonConst.EMPTY_BYTE_ARRAY;
  }

  public long getSessionInMills() {
    return sessionInMills;
  }

  public boolean isEnabled() {
    return true;
  }

  protected static YouTrackDBJwtHeader deserializeWebHeader(final byte[] decodedHeader) {
    final var map = JSONSerializerJackson.mapFromJson(
        new String(decodedHeader, StandardCharsets.UTF_8));
    final var header = new YouTrackDBJwtHeader();
    header.setType((String) map.get("typ"));
    header.setAlgorithm((String) map.get("alg"));
    header.setKeyId((String) map.get("kid"));
    return header;
  }

  protected static JwtPayload deserializeWebPayload(final String type,
      final byte[] decodedPayload) {
    if (!"YouTrackDB".equals(type)) {
      throw new SystemException("Payload class not registered:" + type);
    }
    final var map = JSONSerializerJackson.mapFromJson(
        new String(decodedPayload, StandardCharsets.UTF_8));
    final var payload = new YouTrackDBJwtPayload();
    payload.setUserName((String) map.get("username"));
    payload.setIssuer((String) map.get("iss"));
    payload.setExpiry(((Number) map.get("exp")).longValue());
    payload.setIssuedAt(((Number) map.get("iat")).longValue());
    payload.setNotBefore(((Number) map.get("nbf")).longValue());
    payload.setDatabase((String) map.get("sub"));
    payload.setAudience((String) map.get("aud"));
    payload.setTokenId((String) map.get("jti"));
    final int collection = (Integer) map.get("uidc");
    final var pos = ((Number) map.get("uidp")).longValue();

    payload.setUserRid(new RecordId(collection, pos));
    payload.setDatabaseType((String) map.get("bdtyp"));
    return payload;
  }

  protected static byte[] serializeWebHeader(final TokenHeader header) throws Exception {
    if (header == null) {
      throw new IllegalArgumentException("Token header is null");
    }

    var map = new HashMap<String, Object>();
    map.put("typ", header.getType());
    map.put("alg", header.getAlgorithm());
    map.put("kid", header.getKeyId());

    return JSONSerializerJackson.mapToJson(map).getBytes(StandardCharsets.UTF_8);
  }

  protected static byte[] serializeWebPayload(final JwtPayload payload) throws Exception {
    if (payload == null) {
      throw new IllegalArgumentException("Token payload is null");
    }

    final var map = new HashMap<String, Object>();
    map.put("username", payload.getUserName());
    map.put("iss", payload.getIssuer());
    map.put("exp", payload.getExpiry());
    map.put("iat", payload.getIssuedAt());
    map.put("nbf", payload.getNotBefore());
    map.put("sub", payload.getDatabase());
    map.put("aud", payload.getAudience());
    map.put("jti", payload.getTokenId());
    map.put("uidc", payload.getUserRid().getCollectionId());
    map.put("uidp", payload.getUserRid().getCollectionPosition());
    map.put("bdtyp", payload.getDatabaseType());
    return JSONSerializerJackson.mapToJson(map).getBytes(StandardCharsets.UTF_8);
  }

  protected JwtPayload createPayloadServerUser(SecurityUser serverUser) {
    if (serverUser == null) {
      throw new IllegalArgumentException("User is null");
    }

    final var payload = new YouTrackDBJwtPayload();
    payload.setAudience("YouTrackDBServer");
    payload.setDatabase("-");
    payload.setUserRid(ImmutableRecordId.EMPTY_RECORD_ID);

    final var expiryMinutes = sessionInMills;
    final var currTime = System.currentTimeMillis();
    payload.setIssuedAt(currTime);
    payload.setNotBefore(currTime);
    payload.setUserName(serverUser.getName(null));
    payload.setTokenId(UUID.randomUUID().toString());
    payload.setExpiry(currTime + expiryMinutes);
    return payload;
  }

  protected JwtPayload createPayload(final DatabaseSessionInternal db,
      final SecurityUser user) {
    if (user == null) {
      throw new IllegalArgumentException("User is null");
    }

    final var payload = new YouTrackDBJwtPayload();
    payload.setAudience("YouTrackDB");
    payload.setDatabase(db.getDatabaseName());
    payload.setUserRid(user.getIdentity().getIdentity());

    final var expiryMinutes = sessionInMills;
    final var currTime = System.currentTimeMillis();
    payload.setIssuedAt(currTime);
    payload.setNotBefore(currTime);
    payload.setUserName(user.getName(db));
    payload.setTokenId(UUID.randomUUID().toString());
    payload.setExpiry(currTime + expiryMinutes);
    return payload;
  }

  protected String getPayloadType(final JwtPayload payload) {
    return "YouTrackDB";
  }

  private BinaryToken deserializeBinaryToken(final InputStream bais) {
    try {
      return binarySerializer.deserialize(bais);
    } catch (Exception e) {
      throw BaseException.wrapException(new SystemException("Cannot deserialize binary token"),
          e, (String) null);
    }
  }

  public void setSessionInMills(long sessionInMills) {
    this.sessionInMills = sessionInMills;
  }
}
