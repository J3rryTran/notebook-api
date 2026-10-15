package com.vtnexus.notebook.common.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CredentialCryptoTest {

    private String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    @DisplayName("T6.1: Round-trip mã hóa và giải mã chính xác")
    void testRoundTrip() {
        String key = generateKey();
        CredentialCrypto crypto = new CredentialCrypto(key);
        String plaintext = "alice:SecretP@ssword123!";

        byte[] encrypted = crypto.encrypt(plaintext);
        assertNotNull(encrypted);
        assertTrue(encrypted.length > 12);

        String decrypted = crypto.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("T6.2: Hai lần mã hóa cùng dữ liệu phải sinh nonce khác nhau")
    void testDifferentNonce() {
        String key = generateKey();
        CredentialCrypto crypto = new CredentialCrypto(key);
        String plaintext = "bob:Password456";

        byte[] enc1 = crypto.encrypt(plaintext);
        byte[] enc2 = crypto.encrypt(plaintext);

        assertFalse(Arrays.equals(enc1, enc2));

        byte[] nonce1 = Arrays.copyOfRange(enc1, 0, 12);
        byte[] nonce2 = Arrays.copyOfRange(enc2, 0, 12);
        assertFalse(Arrays.equals(nonce1, nonce2));
    }

    @Test
    @DisplayName("T6.3: Giải mã bằng sai key phải ném ngoại lệ")
    void testWrongKeyFails() {
        String key1 = generateKey();
        String key2 = generateKey();

        CredentialCrypto crypto1 = new CredentialCrypto(key1);
        CredentialCrypto crypto2 = new CredentialCrypto(key2);

        byte[] enc = crypto1.encrypt("user:password");
        assertThrows(RuntimeException.class, () -> crypto2.decrypt(enc));
    }

    @Test
    @DisplayName("T6.4: Validate độ dài key phải đúng 32 bytes")
    void testInvalidKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalArgumentException.class, () -> new CredentialCrypto(shortKey));

        String longKey = Base64.getEncoder().encodeToString(new byte[48]);
        assertThrows(IllegalArgumentException.class, () -> new CredentialCrypto(longKey));
    }
}
