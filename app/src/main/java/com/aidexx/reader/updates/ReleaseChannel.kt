package com.aidexx.reader.updates

/**
 * The two update channels the app can check.
 *
 * [PRODUCTION] matches published, non-prerelease GitHub releases (the stable
 * channel). [DEV] matches published prerelease releases (preview/dev builds).
 * Each build flavor defaults to its own channel but the user can switch.
 */
enum class ReleaseChannel(val key: String) {
    PRODUCTION("production"),
    DEV("dev");

    companion object {
        fun fromKey(key: String?): ReleaseChannel =
            entries.firstOrNull { it.key == key } ?: PRODUCTION
    }
}
