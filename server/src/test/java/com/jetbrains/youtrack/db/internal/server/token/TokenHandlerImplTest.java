package com.jetbrains.youtrack.db.internal.server.token;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.TokenHeader;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.YouTrackDBJwtHeader;
import com.jetbrains.youtrack.db.internal.server.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocolData;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import org.junit.Test;

public class TokenHandlerImplTest extends BaseMemoryInternalDatabase {

  @Test
  public void testWebTokenCreationValidation()
      throws InvalidKeyException, NoSuchAlgorithmException, IOException {
    var original = session.getCurrentUser();
    var handler = new TokenHandlerImpl("any key".getBytes(), 60, "HmacSHA256");
    var token = handler.getSignedWebToken(session, original);

    try {
      // Make this thread wait at least 10 milliseconds before check the validity
      Thread.sleep(10);
    } catch (InterruptedException e) {
    }

    var tok = handler.parseWebToken(token);

    assertNotNull(tok);

    assertTrue(tok.getIsVerified());

    var user = tok.getUser(session);
    assertEquals(user.getName(session), original.getName(session));
    var boole = handler.validateToken(tok, "open", session.getDatabaseName());
    assertTrue(boole);
    assertTrue(tok.getIsValid());
  }

  @Test(expected = Exception.class)
  public void testInvalidToken() throws InvalidKeyException, NoSuchAlgorithmException, IOException {
    var handler = new TokenHandlerImpl("any key".getBytes(), 60, "HmacSHA256");
    handler.parseWebToken("random".getBytes());
  }

  @Test
  public void testSerializeDeserializeWebHeader() throws Exception {
    TokenHeader header = new YouTrackDBJwtHeader();
    header.setType("YouTrackDB");
    header.setAlgorithm("some");
    header.setKeyId("the_key");
    var handler = new TokenHandlerImpl();
    var headerbytes = TokenHandlerImpl.serializeWebHeader(header);

    TokenHeader des = TokenHandlerImpl.deserializeWebHeader(headerbytes);
    assertNotNull(des);
    assertEquals(header.getType(), des.getType());
    assertEquals(header.getKeyId(), des.getKeyId());
    assertEquals(header.getAlgorithm(), des.getAlgorithm());
    assertEquals(header.getType(), des.getType());
  }

  @Test
  public void testSerializeDeserializeWebPayload() throws Exception {
    var payload = new YouTrackDBJwtPayload();
    var ptype = "YouTrackDB";
    payload.setAudience("audiance");
    payload.setExpiry(1L);
    payload.setIssuedAt(2L);
    payload.setIssuer("YouTrackDB");
    payload.setNotBefore(3L);
    payload.setUserName("the subject");
    payload.setTokenId("aaa");
    payload.setUserRid(new RecordId(3, 4));

    var handler = new TokenHandlerImpl();
    var payloadbytes = TokenHandlerImpl.serializeWebPayload(payload);

    var des = TokenHandlerImpl.deserializeWebPayload(ptype, payloadbytes);
    assertNotNull(des);
    assertEquals(payload.getAudience(), des.getAudience());
    assertEquals(payload.getExpiry(), des.getExpiry());
    assertEquals(payload.getIssuedAt(), des.getIssuedAt());
    assertEquals(payload.getIssuer(), des.getIssuer());
    assertEquals(payload.getNotBefore(), des.getNotBefore());
    assertEquals(payload.getTokenId(), des.getTokenId());
    assertEquals(payload.getUserName(), des.getUserName());
  }

  @Test
  public void testTokenForge() throws InvalidKeyException, NoSuchAlgorithmException, IOException {
    var original = session.getCurrentUser();
    var handler = new TokenHandlerImpl("any key".getBytes(), 60, "HmacSHA256");

    var token = handler.getSignedWebToken(session, original);
    var token2 = handler.getSignedWebToken(session, original);
    var s = new String(token);
    var s2 = new String(token2);

    var newS = s.substring(0, s.lastIndexOf('.')) + s2.substring(s2.lastIndexOf('.'));

    var tok = handler.parseWebToken(newS.getBytes());

    assertNotNull(tok);

    assertFalse(tok.getIsVerified());
  }

  @Test
  public void testBinartTokenCreationValidation()
      throws InvalidKeyException, NoSuchAlgorithmException, IOException {
    var original = session.getCurrentUser();
    var handler = new TokenHandlerImpl("any key".getBytes(), 60, "HmacSHA256");
    var data = new NetworkProtocolData();
    data.driverName = "aa";
    data.driverVersion = "aa";
    data.setSerializationImpl("a");
    data.protocolVersion = 2;

    var token = handler.getSignedBinaryToken(session, original, data);

    var tok = handler.parseBinaryToken(token);

    assertNotNull(tok);

    assertTrue(tok.getIsVerified());

    var user = tok.getUser(session);
    assertEquals(user.getName(session), original.getName(session));
    var boole = handler.validateBinaryToken(tok);
    assertTrue(boole);
    assertTrue(tok.getIsValid());
  }

  @Test
  public void testTokenNotRenew() {
    var original = session.getCurrentUser();
    var handler = new TokenHandlerImpl("any key".getBytes(), 60, "HmacSHA256");
    var data = new NetworkProtocolData();
    data.driverName = "aa";
    data.driverVersion = "aa";
    data.setSerializationImpl("a");
    data.protocolVersion = 2;

    var token = handler.getSignedBinaryToken(session, original, data);

    var tok = handler.parseBinaryToken(token);
    token = handler.renewIfNeeded(tok);

    assertEquals(0, token.length);
  }

  @Test
  public void testTokenRenew() {
    var original = session.getCurrentUser();
    var handler = new TokenHandlerImpl("any key".getBytes(), 60, "HmacSHA256");
    var data = new NetworkProtocolData();
    data.driverName = "aa";
    data.driverVersion = "aa";
    data.setSerializationImpl("a");
    data.protocolVersion = 2;

    var token = handler.getSignedBinaryToken(session, original, data);

    var tok = handler.parseBinaryToken(token);
    tok.setExpiry(System.currentTimeMillis() + (handler.getSessionInMills() / 2) - 1);
    token = handler.renewIfNeeded(tok);

    assertTrue(token.length != 0);
  }
}
