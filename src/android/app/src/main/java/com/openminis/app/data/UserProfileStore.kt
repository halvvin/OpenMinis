package com.openminis.app.data

import android.content.Context
import org.json.JSONObject

/**
 * [T-user-profile] User profile (پروفایل کاربر) for personalised responses.
 *
 * Stored locally in SharedPreferences as JSON. When [UserProfile.enabled]
 * is true and at least one field is filled, a clearly-labelled section is
 * appended to the agent system prompt (see ChatViewModel.buildSystemPrompt)
 * so the model can tailor style, depth and content to the user.
 *
 * Nothing leaves the device except via the model call itself — same privacy
 * model as SOUL.md / memory files.
 */
data class UserProfile(
    val enabled: Boolean = true,
    val name: String = "",
    val age: String = "",
    val occupation: String = "",
    val workField: String = "",
    val skills: String = "",
    val interests: String = "",
    val goals: String = "",
    val notes: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(name, age, occupation, workField, skills, interests, goals, notes)
            .all { it.isBlank() }

    /**
     * Render as a system-prompt section, or null when disabled / empty
     * (so the prompt stays byte-stable for cache hits when unused).
     */
    fun promptSection(): String? {
        if (!enabled || isEmpty) return null
        val lines = buildList {
            if (name.isNotBlank()) add("- Name: $name")
            if (age.isNotBlank()) add("- Age: $age")
            if (occupation.isNotBlank()) add("- Occupation: $occupation")
            if (workField.isNotBlank()) add("- Field of work: $workField")
            if (skills.isNotBlank()) add("- Skills: $skills")
            if (interests.isNotBlank()) add("- Interests: $interests")
            if (goals.isNotBlank()) add("- Goals: $goals")
            if (notes.isNotBlank()) add("- Other: $notes")
        }
        return """User profile (provided by the user; use it to personalise tone,
depth and examples — do not repeat it back verbatim):
""" + lines.joinToString("\n")
    }

    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("name", name)
        put("age", age)
        put("occupation", occupation)
        put("field", workField)
        put("skills", skills)
        put("interests", interests)
        put("goals", goals)
        put("notes", notes)
    }.toString()

    companion object {
        fun fromJson(raw: String): UserProfile = runCatching {
            val o = JSONObject(raw)
            UserProfile(
                enabled = o.optBoolean("enabled", true),
                name = o.optString("name"),
                age = o.optString("age"),
                occupation = o.optString("occupation"),
                workField = o.optString("field"),
                skills = o.optString("skills"),
                interests = o.optString("interests"),
                goals = o.optString("goals"),
                notes = o.optString("notes"),
            )
        }.getOrDefault(UserProfile())
    }
}

class UserProfileStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): UserProfile = UserProfile.fromJson(prefs.getString(KEY_JSON, null) ?: "{}")

    fun save(profile: UserProfile) {
        prefs.edit().putString(KEY_JSON, profile.toJson()).apply()
    }

    /** Convenience accessor used by the system-prompt builder. */
    fun promptSection(): String? = load().promptSection()

    companion object {
        private const val PREFS_NAME = "user_profile_prefs"
        private const val KEY_JSON = "profile.json"

        @Volatile private var instance: UserProfileStore? = null

        fun get(context: Context): UserProfileStore =
            instance ?: synchronized(this) {
                instance ?: UserProfileStore(context.applicationContext).also { instance = it }
            }
    }
}
