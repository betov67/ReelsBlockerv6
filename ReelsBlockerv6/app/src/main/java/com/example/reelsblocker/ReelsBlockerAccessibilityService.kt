package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ReelsBlockerAccessibilityService — v6
 *
 * v5 RESULT: Story blocking is fully fixed (confirmed). Feed/search Reel
 * blocking and background resilience remain solid. DM/Profile Reels were
 * STILL being blocked, despite v5 already reordering classifyScreen to
 * check DM_THREAD/PROFILE before REEL_VIEWER.
 *
 * WHY DM/PROFILE STAYED BROKEN WHILE STORY DETECTION FIXED CLEANLY
 * --------------------------------------------------------------------
 * Story detection is STATELESS: it classifies the CURRENT screen directly,
 * every single event, with no dependency on anything observed earlier.
 * DM/Profile *allowance*, as built through v5, was entirely STATEFUL: it
 * depended on having correctly classified the DM thread / Profile grid
 * screen on some EARLIER event, remembering that in `lastNonReelScreenType`,
 * and still having it around, unchanged, by the time the Reel viewer's own
 * window-state event fires. That is three separate things that all have to
 * go right, and any single missed or misclassified event in between (a
 * coalesced event, a screen that never got its own dedicated callback,
 * guessed ids that don't match this Instagram build) silently loses the
 * origin. Stateless beats stateful here, so v6 adds a second, direct path
 * that doesn't depend on timing or memory at all:
 *
 *   NEW: when classifyScreen lands on REEL_VIEWER, v6 additionally
 *   inspects THAT SAME screen for DM/Profile "origin chrome" — UI
 *   Instagram is known to leave visible over the video itself when a Reel
 *   was opened from inside a thread or a profile (a title bar showing the
 *   thread/participant, a "reply" affordance docked under the video, an
 *   action-bar title view) rather than the edge-to-edge, chrome-free
 *   player used for the main Reels tab/feed. This is checked directly on
 *   the Reel-viewer screen at the moment it's classified — no dependency
 *   on an earlier event having gone right. It's OR'd together with v5's
 *   existing lastNonReelScreenType memory, so either signal allowing the
 *   Reel is enough; neither one removes the other.
 *
 *   IMPORTANT DESIGN POINT: these new "origin chrome" ids are kept in
 *   their OWN separate sets (DM_REEL_CHROME_IDS / PROFILE_REEL_CHROME_IDS),
 *   NOT merged into DM_THREAD_IDS/PROFILE_IDS. Those two sets are used by
 *   classifyScreen's priority routing (see Bug A note below), and an
 *   ambiguous id like "action_bar_title_view" — which can appear on lots
 *   of screens, including the Reel viewer itself — would, if placed there,
 *   permanently mis-classify the Reel-viewer screen as "still the DM
 *   thread/Profile screen" for as long as that chrome stays visible. That
 *   would accidentally also defeat rule 5 (blocking a swipe to the NEXT
 *   Reel), since handleScroll only acts when classifyScreen says
 *   REEL_VIEWER. Keeping the two sets separate gets both right: the
 *   screen is still correctly tagged REEL_VIEWER (so swipe-blocking still
 *   works), while the chrome check independently informs the origin
 *   decision.
 *
 * CARRIED FORWARD FROM v5, UNCHANGED (confirmed working, not touched)
 * -----------------------------------------------------------------------
 *  BUG A FIX — classifyScreen() still checks DM_THREAD and PROFILE before
 *  REEL_VIEWER, for the reasons explained there.
 *  BUG B FIX — the Story/Reels naming taxonomy (legacy "Reel" = Stories,
 *  "Clips"/"reels_viewer" plural = the new Reels feature) is unchanged.
 *  All STORY_*, REEL_VIEWER_IDS, REEL_CLASS_HINTS, REELS_TAB_IDS constants
 *  are byte-for-byte what v5 shipped.
 *  All v4 try/catch crash-hardening is unchanged.
 *
 * WHAT ELSE CHANGED IN v6
 * -------------------------
 *  • Logging now distinguishes which signal decided a DM_REEL/PROFILE_REEL
 *    allowance: "on-screen chrome" (the new, stateless path) vs.
 *    "prior-screen memory" (v5's original path) — so a real device run
 *    shows exactly which mechanism is doing the work.
 *
 * RULES IMPLEMENTED (unchanged intent, every version)
 * ------------------------------------------------------
 *  1. Bottom-nav Reels tab tap             -> BACK, immediately.
 *  2. Reel opened from the main/home feed  -> BACK, immediately.
 *  3. Reel opened from a user profile      -> ALLOWED (first Reel only).
 *  4. Reel opened from Direct Messages     -> ALLOWED (first Reel only).
 *  5. A vertical swipe inside an allowed Reel (trying to reach the NEXT
 *     Reel)                                -> BACK, immediately, session
 *                                              locked down.
 *  6. Stories are 100% exempt — checked first, before anything else, on
 *     every single event, with zero side effects.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    private enum class ScreenType { REEL_VIEWER, DM_THREAD, PROFILE, OTHER }

    companion object {
        private const val TAG = "ReelsBlockerA11y"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /** Minimum gap between two performGlobalAction(BACK) calls. */
        private const val BACK_ACTION_COOLDOWN_MS = 600L

        /**
         * True while the system is actively bound to this service. Read by
         * BlockerForegroundService's watchdog.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        // ── STORY signals — unchanged from v5 (confirmed working) ──────
        // Instagram's internal name for a Story has, for years, been
        // "Reel" (singular) — predating the separate short-video "Reels"
        // feature. These ids/classes are Stories' own, not a shared/
        // ambiguous container.
        private val STORY_EXCLUSIVE_IDS = setOf(
            "reel_viewer", "reel_viewer_image_view", "story_feed_holder",
            "reel_tag",
            "story_reply_composer", "reply_composer", "story_progress_bar",
            "story_duration_bar", "story_progress_container", "stories_tray",
            "story_viewer_swipe_container", "story_viewer_reply_box",
            "quick_reaction_emoji_picker", "stories_header",
            "story_viewer_fragment_container"
        )

        private val STORY_CLASS_HINTS = listOf("reelviewer", "stor")

        // ── REEL/CLIPS PLAYER signals — unchanged from v5 ──────────────
        private val REEL_VIEWER_IDS = setOf(
            "clips_viewer_view_pager", "clips_swipe_refresh_container",
            "clips_video_container", "clips_fragment_root",
            "clips_caption_text_view", "clips_tab_fragment_container",
            "reels_viewer_fragment_root",
            "ad_unit_root", "sponsored_reel_root"
        )

        private val REEL_CLASS_HINTS = listOf("clips", "reelsviewer")

        // ── Bottom navigation bar's Reels/Clips tab button ──
        private val REELS_TAB_IDS = setOf(
            "clips_tab", "tab_icon_clips", "reels_tab", "tab_clips",
            "bottom_nav_clips", "bottom_navigation_clips"
        )

        // ── DM thread (conversation LIST) screen — used by classifyScreen's
        // priority routing (Bug A fix). Kept EXCLUSIVE to the thread/list
        // screen on purpose — a live message composer or message-recycler
        // view doesn't make sense showing over a full-screen video, so
        // these ids won't fire once the Reel viewer actually opens, and
        // the screen correctly falls through to REEL_VIEWER at that point.
        private val DM_THREAD_IDS = setOf(
            "direct_thread_fragment", "direct_thread_recycler_view",
            "thread_view_messages_recycler_view", "row_thread_composer_edittext",
            "direct_text_message_text_view", "direct_private_share", "direct_thread"
        )

        private val DM_CLASS_HINTS = listOf("direct")

        // ── NEW in v6: DM "origin chrome" — ids Instagram may leave
        // visible OVER the video when a Reel was opened from a DM thread.
        // Deliberately checked ONLY inside the REEL_VIEWER branch (see
        // class doc comment), never merged into DM_THREAD_IDS above, so a
        // Reel-viewer screen that retains this chrome is still correctly
        // tagged REEL_VIEWER (keeping rule 5's swipe-block intact) while
        // still being recognised as DM-originated.
        private val DM_REEL_CHROME_IDS = setOf(
            "direct_thread_title", "action_bar_title_view", "direct_thread_toolbar"
        )

        // ── Profile grid screen — used by classifyScreen's priority
        // routing. Kept exclusive to the header/grid screen for the same
        // reason as DM_THREAD_IDS above.
        private val PROFILE_IDS = setOf(
            "profile_header", "profile_tab_layout", "action_bar_profile_name",
            "profile_coordinator_layout", "user_detail_header",
            "profile_header_bio_text"
        )

        private val PROFILE_CLASS_HINTS = listOf("profile")

        // ── NEW in v6: Profile "origin chrome" — same idea as
        // DM_REEL_CHROME_IDS, for a Reel opened from a user's Profile grid.
        // "profile_username"/"action_bar_title_view" cover a username
        // header Instagram may keep visible over the video.
        private val PROFILE_REEL_CHROME_IDS = setOf(
            "action_bar_title_view", "action_bar_profile_name", "profile_username"
        )

        // Only structural tree changes can possibly mean a Reel viewer
        // just appeared or disappeared. A single integer AND against this
        // mask — no timer, no debounce.
        private val STRUCTURAL_CHANGE_MASK: Int = run {
            var mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
            }
            mask
        }
    }

    // ── Mutable session state ──
    @Volatile private var lastNonReelScreenType: ScreenType = ScreenType.OTHER
    @Volatile private var inReelSession = false
    @Volatile private var reelOriginAllowed = false
    @Volatile private var lastBackActionMs = 0L

    // Set once per session, at the same point reelOriginAllowed is decided,
    // and read on EVERY re-evaluation of that session (ad swaps, caption
    // changes) so log lines stay consistent for the session's whole
    // lifetime instead of only being accurate on the first event.
    @Volatile private var currentSessionLabel = "FEED_REEL"
    @Volatile private var currentSessionSource = "n/a"

    // ─── Lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            lastNonReelScreenType = ScreenType.OTHER
            inReelSession = false
            reelOriginAllowed = false
            currentSessionLabel = "FEED_REEL"
            currentSessionSource = "n/a"
            isConnected = true
            Log.i(TAG, "onServiceConnected — fresh state, isConnected=true")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        Log.w(TAG, "onUnbind — isConnected=false")
        return try {
            super.onUnbind(intent)
        } catch (e: Exception) {
            Log.e(TAG, "super.onUnbind failed", e)
            false
        }
    }

    override fun onDestroy() {
        isConnected = false
        Log.w(TAG, "onDestroy — isConnected=false")
        try {
            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "super.onDestroy failed", e)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    // ─── Top-level event dispatch ───────────────────────────────────────
    //
    // Everything below runs inside ONE outer try/catch. No exception
    // thrown anywhere in this class is allowed to escape this method.

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in onAccessibilityEvent — swallowed to protect the service", e)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = try {
            event.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.packageName", e)
            null
        }
        if (pkg != INSTAGRAM_PACKAGE) return

        val root = safeRoot()

        // ── RULE 6, HIGHEST PRIORITY: Stories are 100% exempt. ──────────
        if (root != null && isStoryScreen(root)) {
            Log.i(TAG, "Classified: STORY -> bypass, zero side effects (className='${safeClassName(root)}')")
            return
        }

        val eventType = try {
            event.eventType
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.eventType", e)
            return
        }

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                evaluateCurrentScreen(root)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isStructuralChange(event)) evaluateCurrentScreen(root)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(root)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, root)

            else -> { /* not relevant to any rule */ }
        }
    }

    private fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        Log.w(TAG, "rootInActiveWindow threw", e)
        null
    }

    private fun safeClassName(root: AccessibilityNodeInfo): String = try {
        root.className?.toString() ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun isStructuralChange(event: AccessibilityEvent): Boolean = try {
        (event.contentChangeTypes and STRUCTURAL_CHANGE_MASK) != 0
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read event.contentChangeTypes", e)
        false
    }

    // ─── Core evaluation — window-state changes and structural content changes ──

    private fun evaluateCurrentScreen(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return
            val screenType = classifyScreen(root) ?: return // couldn't classify safely; leave state untouched
            val cls = safeClassName(root)

            when (screenType) {
                ScreenType.REEL_VIEWER -> {
                    val isNewSession = !inReelSession
                    if (isNewSession) {
                        // Brand-new Reel session — decide its origin exactly once.
                        inReelSession = true

                        // PRIMARY signal (v6, NEW): origin chrome visible
                        // directly on THIS Reel-viewer screen — stateless,
                        // no dependency on an earlier event having been
                        // classified correctly.
                        val dmChrome = hasAnyId(root, DM_REEL_CHROME_IDS, "DM_REEL_CHROME")
                        val profileChrome = hasAnyId(root, PROFILE_REEL_CHROME_IDS, "PROFILE_REEL_CHROME")

                        // SECONDARY/fallback signal (v5): what the last
                        // non-Reel screen was classified as, before this
                        // Reel viewer opened.
                        val priorDm = lastNonReelScreenType == ScreenType.DM_THREAD
                        val priorProfile = lastNonReelScreenType == ScreenType.PROFILE

                        reelOriginAllowed = dmChrome || profileChrome || priorDm || priorProfile

                        // Persisted at the INSTANCE level (not a local),
                        // and only written here, at session start — so
                        // every subsequent re-evaluation of this same
                        // session (ad swaps, caption changes) logs the
                        // same, correct label instead of resetting to
                        // "unresolved" on every call after the first.
                        currentSessionSource = when {
                            dmChrome -> "on-screen chrome (DM)"
                            profileChrome -> "on-screen chrome (Profile)"
                            priorDm -> "prior-screen memory (DM)"
                            priorProfile -> "prior-screen memory (Profile)"
                            else -> "none"
                        }
                        currentSessionLabel = when {
                            !reelOriginAllowed -> "FEED_REEL"
                            dmChrome || priorDm -> "DM_REEL"
                            profileChrome || priorProfile -> "PROFILE_REEL"
                            else -> "REEL(allowed, origin unresolved)"
                        }
                    }
                    // Ad-Reel swaps, caption changes, like-button animations
                    // all re-fire this with the SAME session still active.
                    // reelOriginAllowed is only ever set above (on a
                    // brand-new session), so an allowed Reel stays allowed
                    // and a blocked one stays blocked straight through them.
                    Log.i(TAG, "Classified: $currentSessionLabel newSession=$isNewSession allowed=$reelOriginAllowed source='$currentSessionSource' className='$cls'")
                    if (!reelOriginAllowed) fireBack()
                }

                else -> {
                    // DM_THREAD, PROFILE, or OTHER: a genuine non-Reel screen.
                    Log.i(TAG, "Classified: $screenType (non-reel screen) className='$cls'")
                    lastNonReelScreenType = screenType
                    inReelSession = false
                    reelOriginAllowed = false
                    currentSessionLabel = "FEED_REEL"
                    currentSessionSource = "n/a"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateCurrentScreen failed", e)
        }
    }

    /** The only way to move to the NEXT Reel — this is what rule 5 blocks. */
    private fun handleScroll(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return

            // Defense in depth: the top-level dispatcher already excluded
            // Stories for this event, but re-check independently.
            if (isStoryScreen(root)) return

            val type = classifyScreen(root) ?: return
            if (type != ScreenType.REEL_VIEWER) return // scrolling somewhere else entirely

            if (!inReelSession) {
                // Inside a Reel viewer but we never got a window-state/
                // content event for it. Fail safe: un-allowed session.
                inReelSession = true
                reelOriginAllowed = false
            }

            if (reelOriginAllowed) {
                Log.i(TAG, "Scroll inside an allowed Reel -> locking session and blocking (next-Reel attempt)")
                reelOriginAllowed = false
            } else {
                Log.i(TAG, "Scroll inside FEED_REEL -> blocking")
            }
            fireBack()
        } catch (e: Exception) {
            Log.e(TAG, "handleScroll failed", e)
        }
    }

    /** Taps — primarily the bottom-nav Reels tab. */
    private fun handleClick(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        try {
            val clickedId = try {
                event.source?.viewIdResourceName
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read clicked view id", e)
                null
            }

            if (clickedId != null && containsIdFragment(clickedId, REELS_TAB_IDS)) {
                Log.i(TAG, "Classified: REEL_TAB click (id='$clickedId') -> BACK")
                fireBack()
                return
            }

            // Defensive fallback only when we're not already tracking a
            // session: some Reel entry points (an inline thumbnail in the
            // feed) register only as a click with no window-state change.
            if (!inReelSession) evaluateCurrentScreen(root)
        } catch (e: Exception) {
            Log.e(TAG, "handleClick failed", e)
        }
    }

    // ─── Screen classification ───────────────────────────────────────────

    /**
     * Returns null (meaning "skip this cycle, don't touch any state") if
     * classification itself throws.
     *
     * Priority order is deliberate: DM_THREAD and PROFILE are checked
     * BEFORE REEL_VIEWER (Bug A, see class doc comment).
     */
    private fun classifyScreen(root: AccessibilityNodeInfo): ScreenType? = try {
        when {
            isDmThreadScreen(root) -> ScreenType.DM_THREAD
            isProfileScreen(root) -> ScreenType.PROFILE
            isReelViewerScreen(root) -> ScreenType.REEL_VIEWER
            else -> ScreenType.OTHER
        }
    } catch (e: Exception) {
        Log.e(TAG, "classifyScreen failed", e)
        null
    }

    /**
     * Fails TOWARD "yes, it's a Story" on any internal error — rule 6 says
     * Stories must never be touched, so an error here must never result in
     * a wrongful BACK.
     */
    private fun isStoryScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, STORY_EXCLUSIVE_IDS, "STORY") || classNameContainsAny(root, STORY_CLASS_HINTS, "STORY")
    } catch (e: Exception) {
        Log.w(TAG, "isStoryScreen failed — defaulting to 'is a Story' to avoid ever blocking one", e)
        true
    }

    private fun isReelViewerScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, REEL_VIEWER_IDS, "REEL_VIEWER") || classNameContainsAny(root, REEL_CLASS_HINTS, "REEL_VIEWER")
    } catch (e: Exception) {
        Log.w(TAG, "isReelViewerScreen failed", e)
        false
    }

    private fun isDmThreadScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, DM_THREAD_IDS, "DM_THREAD") || classNameContainsAny(root, DM_CLASS_HINTS, "DM_THREAD")
    } catch (e: Exception) {
        Log.w(TAG, "isDmThreadScreen failed", e)
        false
    }

    private fun isProfileScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, PROFILE_IDS, "PROFILE") || classNameContainsAny(root, PROFILE_CLASS_HINTS, "PROFILE")
    } catch (e: Exception) {
        Log.w(TAG, "isProfileScreen failed", e)
        false
    }

    /**
     * Uses the platform's own indexed id lookup instead of a manual
     * recursive tree walk. Each id is looked up in its OWN try/catch: a
     * failure on one id logs a warning and moves on to the next instead of
     * aborting the whole check. Logs the specific id that matched, tagged
     * with `category`, so a real device run shows exactly which signal
     * fired.
     */
    private fun hasAnyId(root: AccessibilityNodeInfo, shortIds: Set<String>, category: String): Boolean {
        for (shortId in shortIds) {
            try {
                val fullId = "$INSTAGRAM_PACKAGE:id/$shortId"
                val matches = root.findAccessibilityNodeInfosByViewId(fullId)
                if (matches != null && matches.isNotEmpty()) {
                    Log.d(TAG, "MATCH[$category] viewId='$shortId'")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "hasAnyId[$category]: lookup failed for id='$shortId'", e)
                // Keep checking the remaining ids.
            }
        }
        return false
    }

    private fun classNameContainsAny(root: AccessibilityNodeInfo, fragments: List<String>, category: String): Boolean {
        return try {
            val cls = root.className?.toString() ?: return false
            val hit = fragments.firstOrNull { cls.contains(it, ignoreCase = true) }
            if (hit != null) {
                Log.d(TAG, "MATCH[$category] className='$cls' matchedFragment='$hit'")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "classNameContainsAny[$category] failed", e)
            false
        }
    }

    private fun containsIdFragment(fullId: String, shortIds: Set<String>): Boolean = try {
        val shortName = fullId.substringAfterLast('/')
        shortIds.any { it.equals(shortName, ignoreCase = true) }
    } catch (e: Exception) {
        Log.w(TAG, "containsIdFragment failed", e)
        false
    }

    // ─── Back action ──────────────────────────────────────────────────────

    private fun fireBack() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastBackActionMs < BACK_ACTION_COOLDOWN_MS) return
            lastBackActionMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "performGlobalAction(GLOBAL_ACTION_BACK) failed", e)
        }
    }
}
