package com.vtnexus.notebook.common.auth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class CredentialCrypto {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int NONCE_LENGTH = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKey secretKey;

    public CredentialCrypto(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("Encryption key cannot be empty");
        }
        byte[] decodedKey = Base64.getDecoder().decode(base64Key.trim());
        if (decodedKey.length != 32) {
            throw new IllegalArgumentException("Encryption key must be 32 bytes (256 bits), found " + decodedKey.length + " bytes");
        }
        this.secretKey = new SecretKeySpec(decodedKey, "AES");
    }

    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            SECURE_RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(NONCE_LENGTH + cipherText.length);
            byteBuffer.put(nonce);
            byteBuffer.put(cipherText);
            return byteBuffer.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt credentials: " + e.getMessage(), e);
        }
    }

    public String decrypt(byte[] encryptedData) {
        if (encryptedData == null || encryptedData.length < NONCE_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted payload");
        }
        try {
            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedData);
            byte[] nonce = new byte[NONCE_LENGTH];
            byteBuffer.get(nonce);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt credentials: " + e.getMessage(), e);
        }
    }
}
