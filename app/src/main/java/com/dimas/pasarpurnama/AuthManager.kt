package com.dimas.pasarpurnama

import android.content.Context
import org.json.JSONObject

data class UserSession(val userId: String, val email: String)

/**
 * Email + password accounts through Supabase Auth. The session (access + refresh token) is kept
 * in SharedPreferences `auth`, so the user stays signed in, also offline.
 */
class AuthManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)

    val user: UserSession?
        get() {
            val id = prefs.getString(K_USER, null) ?: return null
            return UserSession(id, prefs.getString(K_EMAIL, null).orEmpty())
        }

    /** Creates an account. Returns null when Supabase still wants the email confirmed first. */
    fun signUp(email: String, password: String): UserSession? {
        val res = JSONObject(Supabase.callJson("POST", "/auth/v1/signup", body = credentials(email, password)))
        return if (res.has("access_token")) store(res) else null
    }

    fun signIn(email: String, password: String): UserSession =
        store(JSONObject(Supabase.callJson("POST", "/auth/v1/token?grant_type=password", body = credentials(email, password))))

    fun signOut() {
        prefs.getString(K_ACCESS, null)?.let { token -> runCatching { Supabase.call("POST", "/auth/v1/logout", token) } }
        prefs.edit().clear().commit()
    }

    /**
     * A valid access token, refreshed when it is about to expire. Throws when offline; when the
     * refresh token is rejected the session is cleared (so [user] becomes null) before rethrowing.
     */
    fun accessToken(): String = synchronized(lock) {
        val token = prefs.getString(K_ACCESS, null) ?: throw SupabaseException(401, null, "Belum masuk")
        if (System.currentTimeMillis() / 1000 < prefs.getLong(K_EXPIRES, 0) - 60) return token
        val refresh = prefs.getString(K_REFRESH, null) ?: throw SupabaseException(401, null, "Belum masuk")
        try {
            val res = Supabase.callJson("POST", "/auth/v1/token?grant_type=refresh_token", body = JSONObject().put("refresh_token", refresh))
            store(JSONObject(res))
            prefs.getString(K_ACCESS, null)!!
        } catch (e: SupabaseException) {
            if (e.status in 400..499) prefs.edit().clear().commit()
            throw e
        }
    }

    private fun credentials(email: String, password: String) = JSONObject().put("email", email.trim()).put("password", password)

    private fun store(res: JSONObject): UserSession {
        val u = res.getJSONObject("user")
        val expiresAt = res.optLong("expires_at").takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000 + res.optLong("expires_in", 3600))
        prefs.edit()
            .putString(K_ACCESS, res.getString("access_token"))
            .putString(K_REFRESH, res.getString("refresh_token"))
            .putLong(K_EXPIRES, expiresAt)
            .putString(K_USER, u.getString("id"))
            .putString(K_EMAIL, u.optString("email"))
            .commit()
        return UserSession(u.getString("id"), u.optString("email"))
    }

    companion object {
        private val lock = Any()
        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES = "expires_at"
        private const val K_USER = "user_id"
        private const val K_EMAIL = "email"
    }
}
