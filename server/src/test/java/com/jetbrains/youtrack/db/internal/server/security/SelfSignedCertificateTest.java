package com.jetbrains.youtrack.db.internal.server.security;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import junit.framework.TestCase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SelfSignedCertificateTest extends TestCase {

  SelfSignedCertificate testInstance;

  @Before
  public void setUp() throws Exception {
    super.setUp();

    testInstance = new SelfSignedCertificate();
    testInstance.setAlgorithm(SelfSignedCertificate.DEFAULT_CERTIFICATE_ALGORITHM);
    testInstance.setCertificateName(SelfSignedCertificate.DEFAULT_CERTIFICATE_NAME);
    testInstance.setCertificateSN(1701198707);
    testInstance.setKey_size(SelfSignedCertificate.DEFAULT_CERTIFICATE_KEY_SIZE);
    testInstance.setOwnerFDN(SelfSignedCertificate.DEFAULT_CERTIFICATE_OWNER);
    testInstance.setValidity(SelfSignedCertificate.DEFAULT_CERTIFICATE_VALIDITY);
  }

  @Test
  public void testSetUnsuitableSerialNumber() throws Exception {
    try {
      testInstance.setCertificateSN(0);
    } catch (SwitchToDefaultParamsException e) {
      return;
    }
    Assert.fail();
  }

  @Test
  public void testGenerateCertificateKeyPair() throws Exception {
    testInstance.generateCertificateKeyPair();
  }

  @Test
  public void testComposeSelfSignedCertificate() throws Exception {
    testInstance.generateCertificateKeyPair();
    testInstance.composeSelfSignedCertificate();
    testInstance.checkThisCertificate();
  }

  @Test
  public void testCheckValidityPerid() throws Exception {
    testComposeSelfSignedCertificate();

    Assert.assertThrows(
        CertificateNotYetValidException.class,
        () -> {
          var cert = testInstance.getCertificate();
          var pubK = testInstance.getPublicKey();
          var yesterday = new Date(System.currentTimeMillis() - 86400000);
          SelfSignedCertificate.checkCertificate(cert, pubK, yesterday);
        });
  }

  @Test
  public void testCertificateSignatureAgainstTamperPublicKey() throws Exception {
    testComposeSelfSignedCertificate();

    Assert.assertThrows(
        SignatureException.class,
        () -> {
          var cert = testInstance.getCertificate();
          var tamperK =
              SelfSignedCertificate.computeKeyPair(
                  testInstance.getAlgorithm(), testInstance.getKey_size());
          var yesterday = new Date(System.currentTimeMillis());
          SelfSignedCertificate.checkCertificate(cert, tamperK.getPublic(), yesterday);
        });
  }
}
