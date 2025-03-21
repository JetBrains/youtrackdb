package com.jetbrains.youtrack.db.internal.core.metadata.security.binary;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.BinaryTokenPayload;
import com.jetbrains.youtrack.db.internal.core.metadata.security.jwt.TokenHeader;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

public class BinaryToken implements Token {

  private boolean valid;
  private boolean verified;
  private TokenHeader header;
  private BinaryTokenPayload payload;

  @Override
  public boolean getIsVerified() {
    return verified;
  }

  @Override
  public void setIsVerified(boolean verified) {
    this.verified = verified;
  }

  @Override
  public boolean getIsValid() {
    return valid;
  }

  @Override
  public void setIsValid(boolean valid) {
    this.valid = valid;
  }

  @Override
  public String getUserName() {
    return payload.getUserName();
  }

  @Override
  public SecurityUserImpl getUser(DatabaseSessionInternal session) {
    return session.computeInTx(transaction -> {
      if (this.payload.getUserRid() != null) {
        try {
          EntityImpl result = transaction.load(new RecordId(this.payload.getUserRid()));
          if (result.getSchemaClassName().equals(SecurityUserImpl.CLASS_NAME)) {
            return new SecurityUserImpl(session, result);
          }
        } catch (RecordNotFoundException e) {
          return null;
        }
      }
      return null;
    });
  }

  @Override
  public String getDatabaseName() {
    return this.payload.getDatabase();
  }

  @Override
  public String getDatabaseType() {
    return this.payload.getDatabaseType();
  }

  @Override
  public RID getUserId() {
    return this.payload.getUserRid();
  }

  public TokenHeader getHeader() {
    return header;
  }

  public void setHeader(TokenHeader header) {
    this.header = header;
  }

  @Override
  public void setExpiry(long expiry) {
    payload.setExpiry(expiry);
  }

  @Override
  public long getExpiry() {
    return payload.getExpiry();
  }

  public short getProtocolVersion() {
    return payload.getProtocolVersion();
  }

  public String getSerializer() {
    return payload.getSerializer();
  }

  public String getDriverName() {
    return payload.getDriverName();
  }

  public String getDriverVersion() {
    return payload.getDriverVersion();
  }

  public boolean isServerUser() {
    return payload.isServerUser();
  }

  @Override
  public boolean isNowValid() {
    var now = System.currentTimeMillis();
    return getExpiry() > now;
  }

  @Override
  public boolean isCloseToExpire() {
    var now = System.currentTimeMillis();
    return getExpiry() - 120000 <= now;
  }

  public BinaryTokenPayload getPayload() {
    return payload;
  }

  public void setPayload(BinaryTokenPayload payload) {
    this.payload = payload;
  }
}
