package com.max.assistant.actions

import android.net.Uri

/**
 * What the user wants DONE INSIDE an app.
 *
 * An operation is app-agnostic. "Search for Arijit Singh" and "search YouTube
 * for tutorials" are the same [SEARCH] operation with a different entity, and
 * one [PLAY] operation covers "play X on Spotify" and "play X on YouTube".
 * Because the operation is separate from the app, supporting an app is a
 * CATALOG ROW, not a new branch in the executor.
 */
enum class AppOperation {
    /** Look something up inside the app. */
    SEARCH,

    /** Start playing media. */
    PLAY,

    /** Open a specific profile / channel / person. */
    PROFILE,

    /** Open a conversation. */
    CHAT,

    /** Turn-by-turn directions to a place. */
    NAVIGATE,

    /** Compose something (a message, a post). */
    COMPOSE,

    /** Just show the app. */
    OPEN
}

/**
 * How one app exposes one operation.
 *
 * Each entry is a URI TEMPLATE built from an official, public Android
 * mechanism - an https deep link or a documented custom scheme. MAX never
 * invents private APIs and never claims an app did something it did not: the
 * executor checks the URI is actually handled before starting it, and falls
 * back honestly when it is not.
 *
 * @param deepLink `{q}` is replaced with the URL-encoded entity.
 * @param webFallback an https URL reaching the same place in a browser, used
 *   when the app is not installed. That is what makes an action useful on a
 *   phone without the app, rather than simply failing.
 */
data class AppCapability(
    val packages: List<String>,
    val deepLink: String,
    val webFallback: String? = null
)
/**
 * THE GENERIC APP-ACTION CATALOG.
 *
 * This is the answer to "do not special-case YouTube, WhatsApp and Spotify".
 * MAX supports a general model:
 *
 *     TARGET APP  +  OPERATION  +  ENTITY  +  SUPPORTED INTEGRATION
 *
 * Every row is a publicly documented Android deep link, looked up by
 * (package, operation). An app with no row still opens via AppResolver; what it
 * lacks is the ability to be steered from outside without a private API, which
 * Android does not permit - and MAX says so rather than pretending.
 */
object AppActionCatalog {

    private fun q(s: String) = Uri.encode(s)

    private fun cap(vararg pkg: String, deep: String, web: String? = null) =
        AppCapability(pkg.toList(), deep, web)

    /**
     * Keyed by operation, then tried in order until an INSTALLED package is
     * found, so the first entry this phone actually has wins.
     */
    private val byOperation: Map<AppOperation, List<AppCapability>> = mapOf(
        AppOperation.SEARCH to listOf(
            cap("com.google.android.youtube",
                deep = "https://www.youtube.com/results?search_query={q}",
                web = "https://www.youtube.com/results?search_query={q}"),
            cap("com.spotify.music", deep = "spotify:search:{q}",
                web = "https://open.spotify.com/search/{q}"),
            cap("com.google.android.apps.youtube.music", deep = "https://music.youtube.com/search?q={q}"),
            cap("com.google.android.apps.maps", deep = "geo:0,0?q={q}",
                web = "https://www.google.com/maps/search/{q}"),
            cap("com.instagram.android",
                deep = "https://www.instagram.com/explore/search/keyword/?q={q}"),
            cap("com.whatsapp", deep = "https://wa.me/?text={q}"),
            cap("com.amazon.mShop.android.shopping", deep = "https://www.amazon.com/s?k={q}"),
            cap("com.flipkart.android", deep = "https://www.flipkart.com/search?q={q}"),
            cap("com.netflix.mediaclient", deep = "https://www.netflix.com/search?q={q}"),
            cap("com.duckduckgo.mobile.android", deep = "https://duckduckgo.com/?q={q}")
        ),
        AppOperation.PLAY to listOf(
            // Spotify's documented search URI. Playback itself is controlled by
            // the app or the system media session, never silently by MAX.
            cap("com.spotify.music", deep = "spotify:search:{q}",
                web = "https://open.spotify.com/search/{q}"),
            cap("com.google.android.youtube",
                deep = "https://www.youtube.com/results?search_query={q}",
                web = "https://www.youtube.com/results?search_query={q}"),
            cap("com.google.android.apps.youtube.music", deep = "https://music.youtube.com/search?q={q}")
        ),
        AppOperation.PROFILE to listOf(
            cap("com.instagram.android", deep = "https://www.instagram.com/{q}/",
                web = "https://www.instagram.com/{q}/"),
            cap("com.google.android.youtube", deep = "https://www.youtube.com/@{q}")
        ),
        AppOperation.CHAT to listOf(
            // WhatsApp is reached by the official wa.me number link; the
            // executor fills in the number from the user's own contacts.
            cap("com.whatsapp", deep = "https://wa.me/{q}")
        ),
        AppOperation.NAVIGATE to listOf(
            cap("com.google.android.apps.maps", deep = "google.navigation:q={q}",
                web = "https://www.google.com/maps/search/?api=1&query={q}")
        ),
        AppOperation.COMPOSE to listOf(
            cap("com.whatsapp", deep = "https://wa.me/?text={q}")
        )
    )

    /**
     * The capability for (package, operation), or null when this app exposes no
     * public way to do it. Null is a legitimate, honest answer.
     */
    fun capabilityFor(packageName: String, operation: AppOperation): AppCapability? =
        byOperation[operation]?.firstOrNull { packageName in it.packages }

    /** Builds the real URI for [capability], or null when the entity is empty. */
    fun buildUri(capability: AppCapability, entity: String): String? {
        val e = entity.trim()
        if (e.isEmpty()) return null
        return capability.deepLink.replace("{q}", q(e))
    }

    /** The https equivalent, used when the app is not installed. */
    fun buildWebFallback(capability: AppCapability, entity: String): String? =
        capability.webFallback?.replace("{q}", q(entity.trim()))

    /** The plain https search URL, for an app with no deep link of its own. */
    fun genericWebSearch(entity: String): String = "https://www.google.com/search?q=" + q(entity)
}