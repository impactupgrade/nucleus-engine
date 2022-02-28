package com.impactupgrade.nucleus.entity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "core_organization")
public class Organization {

  private static final Logger log = LogManager.getLogger(Organization.class);

  // Must match nucleus-portal's SECRET_KEY, which django-fernet-fields uses by default as the encryption key.
//  private static final byte[] ENCRYPTION_KEY = Base64.encodeBase64(
//      System.getenv("SECRET_KEY").getBytes(StandardCharsets.UTF_8)
//  );

  @Id
  private long id;

  private String name;

  @Column(name = "nucleus_apikey")
  private String nucleusApiKey;

//  @Column(name = "environment")
//  private String environmentEncrypted;

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

//  public String getEnvironmentEncrypted() {
//    return environmentEncrypted;
//  }
//
//  public void setEnvironmentEncrypted(String environmentEncrypted) {
//    this.environmentEncrypted = environmentEncrypted;
//  }
//
//  @Transient
//  public String getEnvironment() {
//    return decrypt(environmentEncrypted);
//  }
//
//  // Matches what django-mirage-field is using in nucleus-portal
//  private static String decrypt(String s) {
//    if (s == null) return null;
//    if (s.isEmpty()) return "";
//
//    byte[] decrypted;
//    try {
//      SecretKeySpec key = new SecretKeySpec(ENCRYPTION_KEY, 0, 32, "AES");
//      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
//      cipher.init(Cipher.DECRYPT_MODE, key);
//      decrypted = cipher.doFinal(Base64.decodeBase64(s));
//    } catch (Exception e) {
//      log.error(e);
//      return "";
//    }
//    return new String(decrypted);
//  }
}
