package Server.session;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Simple encryption utility for sensitive session data (like Discogs tokens).
 * Uses AES-256-GCM authenticated encryption.
 * 
 * Note: The master key is derived from a system property or environment variable.
 * For production, set VINYLMATCH_MASTER_KEY to a secure random string (min 32 chars).
 */
public class TokenEncryption {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    
    private final SecretKeySpec keySpec;
    
    public TokenEncryption() {
        this(getMasterKey());
    }
    
    TokenEncryption(String masterKey) {
        byte[] keyBytes = deriveKey(masterKey);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }
    
    /**
     * Get master key from environment or system property.
     * Falls back to a development key (NOT for production!)
     */
    private static String getMasterKey() {
        String key = System.getenv("VINYLMATCH_MASTER_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("vinylmatch.master.key");
        }
        if (key == null || key.isBlank()) {
            // Fallback for development only - logs warning
            return "DEV_KEY_DO_NOT_USE_IN_PRODUCTION_VINYL_" + System.currentTimeMillis();
        }
        return key;
    }
    
    /**
     * Derive a 256-bit AES key from master key string.
     */
    private byte[] deriveKey(String masterKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }
    
    /**
     * Encrypt a token with AES-256-GCM.
     * Format: base64(iv + ciphertext + authTag)
     */
    public String encrypt(String token) {
        if (token == null || token.isBlank()) {
            return token;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);
            
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);
            
            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt token", e);
        }
    }
    
    /**
     * Decrypt a token encrypted with encrypt().
     */
    public String decrypt(String encryptedToken) {
        if (encryptedToken == null || encryptedToken.isBlank()) {
            return encryptedToken;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedToken);
            
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);
            
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt token - possible key mismatch or tampering", e);
        }
    }
}
