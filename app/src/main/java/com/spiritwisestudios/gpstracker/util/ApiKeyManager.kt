package com.spiritwisestudios.gpstracker.util

import android.content.Context
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * Manages API keys with obfuscation and encryption for enhanced security.
 * This class provides methods to store and retrieve API keys in an obfuscated format.
 */
class ApiKeyManager private constructor(private val context: Context) {
    
    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"
        private const val KEY_SIZE = 16
        
        @Volatile
        private var instance: ApiKeyManager? = null
        
        fun getInstance(context: Context): ApiKeyManager {
            return instance ?: synchronized(this) {
                instance ?: ApiKeyManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * Generates a secure key for encryption based on device-specific information.
     * This makes the encryption key unique to each device.
     */
    private fun generateSecureKey(): SecretKeySpec {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        
        val packageName = context.packageName
        val combinedString = "$deviceId$packageName"
        
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combinedString.toByteArray())
        
        return SecretKeySpec(hash.copyOf(KEY_SIZE), ALGORITHM)
    }
    
    /**
     * Encrypts the provided API key using AES encryption.
     * @param apiKey The plain text API key to encrypt
     * @return Base64 encoded encrypted string
     */
    fun encryptApiKey(apiKey: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = generateSecureKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val encryptedBytes = cipher.doFinal(apiKey.toByteArray())
        return Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
    }
    
    /**
     * Decrypts the obfuscated API key.
     * @param encryptedApiKey The encrypted API key in Base64 format
     * @return The decrypted API key
     */
    fun decryptApiKey(encryptedApiKey: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = generateSecureKey()
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        
        val encryptedBytes = Base64.decode(encryptedApiKey, Base64.DEFAULT)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes)
    }
    
    /**
     * Retrieves the Google Maps API key, decrypting it if necessary.
     * @return The decrypted Google Maps API key
     */
    fun getGoogleMapsApiKey(): String {
        val encryptedKey = context.getString(R.string.google_maps_key_encrypted)
        return decryptApiKey(encryptedKey)
    }
} 