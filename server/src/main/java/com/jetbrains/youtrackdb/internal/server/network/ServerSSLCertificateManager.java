package com.jetbrains.youtrackdb.internal.server.network;

import com.jetbrains.youtrackdb.internal.server.security.SelfSignedCertificate;
import com.jetbrains.youtrackdb.internal.server.security.SwitchToDefaultParamsException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class ServerSSLCertificateManager {

  private char[] keyStorePass;
  private File keyStoreFile;
  private KeyStore keyStore;
  private ServerSSLSocketFactory oSSLSocketInfo = null;
  private SelfSignedCertificate selfSignedCertificate = null;
  private KeyStore trustStore;
  private char[] trustStorePass;
  private File trustStoreFile;

  private ServerSSLCertificateManager() {
  }

  public ServerSSLCertificateManager(
      ServerSSLSocketFactory oServerSSLSocketFactory,
      KeyStore keyStore,
      File keyStoreFile,
      char[] keyStorePass) {
    oSSLSocketInfo = oServerSSLSocketFactory;
    this.keyStore = keyStore;
    this.keyStoreFile = keyStoreFile;
    this.keyStorePass = keyStorePass;
  }

  public static ServerSSLCertificateManager getInstance() {
    return new ServerSSLCertificateManager();
  }

  public static ServerSSLCertificateManager getInstance(
      ServerSSLSocketFactory oServerSSLSocketFactory,
      KeyStore keyStore,
      File keyStoreFile,
      char[] keyStorePass) {
    return new ServerSSLCertificateManager(
        oServerSSLSocketFactory, keyStore, keyStoreFile, keyStorePass);
  }

  public void loadKeyStoreForSSLSocket() throws Exception {
    try {
      if (!keyStoreFile.exists()) {
        initKeyStore(this.keyStoreFile, this.keyStore, this.keyStorePass);
      } else {
        loadKeyStore(this.keyStoreFile, this.keyStore, this.keyStorePass);
      }
      this.checkKeyStoreContentValidity();

    } catch (IOException e) {
      // the keystore file is corrupt
      throw e;
    } catch (CertificateException e) {
      // the content of keystore is not compliant....
      this.reactToCerificateLack();
    } catch (NoSuchAlgorithmException e) {
      // the chosen algorithm is wrong
      throw e;
    }
  }

  public void loadTrustStoreForSSLSocket(
      KeyStore trustStore, File trustStoreFile, char[] trustStorePass) throws Exception {

    this.trustStore = trustStore;
    this.trustStoreFile = trustStoreFile;
    this.trustStorePass = trustStorePass;

    try {

      if (!trustStoreFile.exists()) {
        initKeyStore(trustStoreFile, trustStore, trustStorePass);
      } else {
        loadKeyStore(trustStoreFile, trustStore, trustStorePass);
      }

    } catch (CertificateException e) {
      // forecatst of initKeyStore throw
    } catch (IOException e) {
      // the keystore file is corrupt
      throw e;
    } finally {
      if (this.selfSignedCertificate != null) {
        trustCertificate(
            this.trustStoreFile,
            this.trustStore,
            this.trustStorePass,
            this.selfSignedCertificate.getCertificateName(),
            this.selfSignedCertificate.getCertificate());
      }
    }
  }

  public void checkKeyStoreContentValidity() throws CertificateException, KeyStoreException {
    if (!this.keyStore.aliases().hasMoreElements()) {
      throw new CertificateException("the KeyStore is empty");
    }
  }

  public void reactToCerificateLack() throws Exception {
    try {

      if (this.selfSignedCertificate == null) {
        this.initOSelfSignedCertificateParameters();
      }

      autoGenerateSelfSignedX509Cerificate(this.selfSignedCertificate);

      storeCertificate(
          this.selfSignedCertificate.getCertificate(),
          this.selfSignedCertificate.getPrivateKey(),
          this.selfSignedCertificate.getCertificateName(),
          this.keyStorePass,
          this.keyStoreFile,
          this.keyStore,
          this.keyStorePass);

    } catch (CertificateException e) {
      e.printStackTrace();
    } catch (Exception e) {
      throw e;
    }
  }

  private void initOSelfSignedCertificateParameters() {
    this.selfSignedCertificate = new SelfSignedCertificate();
    this.selfSignedCertificate.setAlgorithm(SelfSignedCertificate.DEFAULT_CERTIFICATE_ALGORITHM);
    this.selfSignedCertificate.setCertificateName(SelfSignedCertificate.DEFAULT_CERTIFICATE_NAME);
    try {
      this.selfSignedCertificate.setCertificateSN(
          0); // trick to force it to conpute a random BigInteger
    } catch (SwitchToDefaultParamsException e) {
    }

    this.selfSignedCertificate.setKey_size(SelfSignedCertificate.DEFAULT_CERTIFICATE_KEY_SIZE);
    this.selfSignedCertificate.setOwnerFDN(SelfSignedCertificate.DEFAULT_CERTIFICATE_OWNER);
    this.selfSignedCertificate.setValidity(SelfSignedCertificate.DEFAULT_CERTIFICATE_VALIDITY);
  }

  public static SelfSignedCertificate autoGenerateSelfSignedX509Cerificate(
      SelfSignedCertificate oCert)
      throws SwitchToDefaultParamsException,
      NoSuchAlgorithmException,
      CertificateException,
      NoSuchProviderException,
      InvalidKeyException,
      SignatureException {
    oCert.generateCertificateKeyPair();
    oCert.composeSelfSignedCertificate();
    oCert.checkThisCertificate();

    return oCert;
  }

  public static void initKeyStore(
      File keyStoreFilePointer, KeyStore keyStoreInstance, char[] ks_pwd)
      throws IOException, CertificateException, NoSuchAlgorithmException {
    try {
      if (!keyStoreFilePointer.exists()) {
        keyStoreInstance.load(null, null);
      }
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    } finally {
      throw new CertificateException("the KeyStore is empty");
    }
  }

  public static void loadKeyStore(
      File keyStoreFilePointer, KeyStore keyStoreInstance, char[] ks_pwd)
      throws IOException, CertificateException, NoSuchAlgorithmException {

    FileInputStream ksFIs = null;
    try {
      ksFIs = new FileInputStream(keyStoreFilePointer);

      keyStoreInstance.load(ksFIs, ks_pwd);

    } catch (FileNotFoundException e) {
      e.printStackTrace();
      throw e;
    } finally {
      ksFIs.close();
    }
  }

  public static void storeCertificate(
      X509Certificate cert,
      PrivateKey key,
      String certName,
      char[] certPwd,
      File keyStore_FilePointer,
      KeyStore keyStore_instance,
      char[] ksPwd)
      throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException {

    FileOutputStream ksFOs = null;
    try {
      ksFOs = new FileOutputStream(keyStore_FilePointer, true);

      keyStore_instance.setKeyEntry(
          certName, key, certPwd, new java.security.cert.Certificate[]{cert});

      keyStore_instance.store(ksFOs, ksPwd);

    } catch (FileNotFoundException e) {
      e.printStackTrace();
      throw e;
    } finally {
      ksFOs.close();
    }
  }

  public static void trustCertificate(
      File keyStoreFilePointer,
      KeyStore keyStoreInstance,
      char[] ksPwd,
      String certName,
      X509Certificate cert)
      throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException {

    FileOutputStream ksFOs = null;
    try {
      ksFOs = new FileOutputStream(keyStoreFilePointer, true);

      keyStoreInstance.setCertificateEntry(certName, cert);

      keyStoreInstance.store(ksFOs, ksPwd);

    } catch (FileNotFoundException e) {
      e.printStackTrace();
      throw e;
    } finally {
      ksFOs.close();
    }
  }
}
