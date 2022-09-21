package com.impactupgrade.nucleus.entity;

import com.google.common.base.Strings;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.nio.charset.StandardCharsets;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@Table(name = "core_organization")
public class Organization {

  private static final Logger log = LogManager.getLogger(Organization.class);

  // Must match nucleus-portal's SECRET_KEY, which django-fernet-fields uses by default as the encryption key.
  protected static final byte[] ENCRYPTION_KEY;
  static {
    if (!Strings.isNullOrEmpty(System.getenv("SECRET_KEY"))) {
      ENCRYPTION_KEY = Base64.encodeBase64(
          System.getenv("SECRET_KEY").getBytes(StandardCharsets.UTF_8)
      );
    } else {
      // unreachable in real runs, but need something for unit tests
      ENCRYPTION_KEY = "this will not work".getBytes(StandardCharsets.UTF_8);
    }
  }

  @Id
  private long id;

  private String name;

  @Column(name = "nucleus_apikey")
  private String nucleusApiKey;

  @Column(name = "environment")
  private String environmentEncrypted;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getNucleusApiKey() {
    return nucleusApiKey;
  }

  public void setNucleusApiKey(String nucleusApiKey) {
    this.nucleusApiKey = nucleusApiKey;
  }

  public String getEnvironmentEncrypted() {
    return environmentEncrypted;
  }

  public void setEnvironmentEncrypted(String environmentEncrypted) {
    this.environmentEncrypted = environmentEncrypted;
  }

  @Transient
  public String getEnvironment() {
    return decrypt(environmentEncrypted);
  }

  @Transient
  public void setEnvironment(String environment) {
    environmentEncrypted = encrypt(environment);
  }

  @Transient
  public JSONObject getEnvironmentJson() {
    return new JSONObject(getEnvironment());
  }

  @Transient
  public void setEnvironmentJson(JSONObject jsonObject) {
    setEnvironment(jsonObject.toString(2));
  }

  // Matches what django-mirage-field is using in nucleus-portal

  protected static String decrypt(String s) {
    if (s == null) return null;
    if (s.isEmpty()) return "";

    byte[] decrypted;
    try {
      SecretKeySpec key = new SecretKeySpec(ENCRYPTION_KEY, 0, 32, "AES");
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, key);
      decrypted = cipher.doFinal(Base64.decodeBase64(s));
    } catch (Exception e) {
      log.error(e);
      return "";
    }
    return new String(decrypted);
  }

  protected static String encrypt(String s) {
    if (s == null) return null;
    if (s.isEmpty()) return "";

    byte[] encrypted;
    try {
      SecretKeySpec key = new SecretKeySpec(ENCRYPTION_KEY, 0, 32, "AES");
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE, key);
      encrypted = cipher.doFinal(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.error(e);
      return "";
    }
    return Base64.encodeBase64String(encrypted);
  }
}
