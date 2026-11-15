package com.ft8ok.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.ft8ok.model.ContestSetup
import com.ft8ok.model.StationProfile
import com.ft8ok.model.TxText
import com.ft8ok.rx.FeedFilter
import com.ft8ok.rx.Roster
import com.ft8ok.tx.AutoTx

/**
 * The station profile and the log folder, kept across restarts.
 *
 * `SharedPreferences` rather than Room: this is four values that are read once and
 * written rarely, and `data`'s Room database is for the log, where migrations and
 * durability actually matter (rule 8). Putting settings there too would mean a
 * schema migration every time a checkbox is added.
 *
 * `allowBackup=false` is set in the manifest, so these do not leave the device.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The current profile. Writing it saves immediately. */
    var profile by mutableStateOf(load())
        private set

    private fun load() = StationProfile(
        callsign = prefs.getString(KEY_CALLSIGN, "") ?: "",
        grid = prefs.getString(KEY_GRID, "") ?: "",
        swrAbort = prefs.getFloat(KEY_SWR_ABORT, StationProfile.DEFAULT_SWR_ABORT.toFloat()).toDouble()
    )

    /**
     * The folder the ADIF file is written to, as the Storage Access Framework tree URI
     * the picker returned. Null until the operator chooses one in Setup.
     */
    var logFolder by mutableStateOf(prefs.getString(KEY_LOG_FOLDER, null))
        private set

    fun chooseLogFolder(uri: String) {
        logFolder = uri
        prefs.edit { putString(KEY_LOG_FOLDER, uri) }
    }

    /**
     * The logbook new contacts go into, by database id. Null means
     * the default one, as before logbooks could be chosen; kept across restarts.
     */
    var activeLogbookId by mutableStateOf(prefs.getLong(KEY_ACTIVE_LOGBOOK, -1L).takeIf { it > 0 })
        private set

    fun chooseLogbook(id: Long) {
        activeLogbookId = id
        prefs.edit { putLong(KEY_ACTIVE_LOGBOOK, id) }
    }

    /**
     * Receive whenever the app is on screen with the radio attached. On by default:
     * there is no Start button any more, and this is the rare
     * "screen on, not listening" case. Rule 2 still stops capture off screen.
     */
    var receiveOn by mutableStateOf(prefs.getBoolean(KEY_RECEIVE_ON, true))
        private set

    fun chooseReceiveOn(on: Boolean) {
        receiveOn = on
        prefs.edit { putBoolean(KEY_RECEIVE_ON, on) }
    }

    /**
     * Settings › Diagnostics › Detailed logs: troubleshooting lines, every decode and
     * the a priori test slots' audio, kept on the phone. Off by default in a release
     * build, on in a debug build, so a test phone keeps collecting after an install.
     */
    var detailedLogs by mutableStateOf(prefs.getBoolean(KEY_DETAILED_LOGS, com.ft8ok.BuildConfig.DEBUG))
        private set

    fun chooseDetailedLogs(on: Boolean) {
        detailedLogs = on
        prefs.edit { putBoolean(KEY_DETAILED_LOGS, on) }
    }

    /** The Roster window, seconds (a slider from 30 s to 5 min). */
    var rosterWindowSec by mutableIntStateOf(prefs.getInt(KEY_ROSTER_WINDOW, DEFAULT_ROSTER_WINDOW_SEC))
        private set

    fun chooseRosterWindowSec(seconds: Int) {
        rosterWindowSec = seconds.coerceIn(MIN_ROSTER_WINDOW_SEC, MAX_ROSTER_WINDOW_SEC)
        prefs.edit { putInt(KEY_ROSTER_WINDOW, rosterWindowSec) }
    }

    /**
     * The Roster's sort. Recent until the operator picks another, then that one sticks
     * across restarts. Saved by name, so a sort removed in a later
     * build falls back to Recent rather than to whatever took its ordinal.
     */
    var rosterSort by mutableStateOf(
        Roster.Sort.entries.firstOrNull { it.name == prefs.getString(KEY_ROSTER_SORT, null) }
            ?: Roster.Sort.RECENT
    )
        private set

    fun chooseRosterSort(sort: Roster.Sort) {
        rosterSort = sort
        prefs.edit { putString(KEY_ROSTER_SORT, sort.name) }
    }

    /** Map grid lines and square labels; heard squares show either way. Off by default. Sticks. */
    var mapGrid by mutableStateOf(prefs.getBoolean(KEY_MAP_GRID, false))
        private set

    fun chooseMapGrid(on: Boolean) {
        mapGrid = on
        prefs.edit { putBoolean(KEY_MAP_GRID, on) }
    }

    /** The Live feed's filter chip. All by default; sticks, like the Roster sort. */
    var feedFilter by mutableStateOf(
        FeedFilter.entries.firstOrNull { it.name == prefs.getString(KEY_FEED_FILTER, null) } ?: FeedFilter.ALL
    )
        private set

    fun chooseFeedFilter(filter: FeedFilter) {
        feedFilter = filter
        prefs.edit { putString(KEY_FEED_FILTER, filter.name) }
    }

    /** The Custom chip's terms, as typed. Kept while another chip is chosen. */
    var feedCustom by mutableStateOf(prefs.getString(KEY_FEED_CUSTOM, null) ?: "")
        private set

    fun chooseFeedCustom(text: String) {
        feedCustom = text
        prefs.edit { putString(KEY_FEED_CUSTOM, text) }
    }

    /**
     * Dial frequencies the operator added, in Hz, sorted. They
     * appear in the frequency picker and pass the TX guard like the FT8 table's own.
     */
    var customDialsHz by mutableStateOf(
        (prefs.getString(KEY_CUSTOM_DIALS, "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.sorted()
    )
        private set

    fun addCustomDial(hz: Long) = saveCustomDials((customDialsHz + hz).distinct().sorted())

    fun removeCustomDial(hz: Long) = saveCustomDials(customDialsHz - hz)

    private fun saveCustomDials(list: List<Long>) {
        customDialsHz = list
        prefs.edit { putString(KEY_CUSTOM_DIALS, list.joinToString(",")) }
    }

    /**
     * The word after CQ (`POTA`, `SOTA`...), or empty for a plain CQ.
     * A choice for the whole activation, so it sticks across restarts. Only words
     * [TxText.isCqWord] accepts are ever stored.
     */
    var cqWord by mutableStateOf(prefs.getString(KEY_CQ_WORD, "")?.takeIf(TxText::isCqWord) ?: "")
        private set

    fun chooseCqWord(word: String) {
        cqWord = word.takeIf(TxText::isCqWord) ?: ""
        prefs.edit { putString(KEY_CQ_WORD, cqWord) }
    }

    /**
     * Texts saved for one tap in the TEXT tab. Comma-separated on disk, which is safe
     * because a comma is not in FT8's free-text alphabet. Two to start with, until
     * the operator changes the list.
     */
    var savedTexts by mutableStateOf(
        prefs.getString(KEY_SAVED_TEXTS, null)?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_SAVED_TEXTS
    )
        private set

    fun saveText(text: String) = saveTexts((savedTexts + text).distinct())

    fun removeText(text: String) = saveTexts(savedTexts - text)

    private fun saveTexts(list: List<String>) {
        savedTexts = list.filter { it.isNotEmpty() && ',' !in it }
        prefs.edit { putString(KEY_SAVED_TEXTS, savedTexts.joinToString(",")) }
    }


    /**
     * Contest mode: the sub-mode chosen in Settings › Contest, or
     * null for off. Survives a restart, as WSJT-X's does; nothing turns it off but the
     * operator. Saved by name, like the Roster sort.
     */
    var contestKind by mutableStateOf(
        ContestSetup.Kind.entries.firstOrNull { it.name == prefs.getString(KEY_CONTEST_KIND, null) }
    )
        private set

    /** The grid contests' CQ word as picked: TEST, WW or [CONTEST_WORD_CUSTOM]. Kept while the mode is off. */
    var contestGridChoice by mutableStateOf(
        prefs.getString(KEY_CONTEST_GRID_WORD, null)
            ?.takeIf { it in ContestSetup.GRID_CQ_WORDS || it == CONTEST_WORD_CUSTOM }
            ?: ContestSetup.GRID_CQ_WORDS.first()
    )
        private set

    /** The word typed for [CONTEST_WORD_CUSTOM], kept as typed; [ContestSetup.build] judges it. */
    var contestGridCustomWord by mutableStateOf(prefs.getString(KEY_CONTEST_GRID_CUSTOM, "") ?: "")
        private set

    /** The word a grid contest calls with. */
    val contestGridWord: String
        get() = if (contestGridChoice == CONTEST_WORD_CUSTOM) contestGridCustomWord else contestGridChoice

    /**
     * Report+grid's CQ word as picked: "" for a plain CQ (the
     * default), TEST, or [CONTEST_WORD_CUSTOM] for the typed word it shares with the
     * grid contests. Kept while the mode is off.
     */
    var contestReportGridChoice by mutableStateOf(
        prefs.getString(KEY_CONTEST_RG_WORD, null)
            ?.takeIf { it in ContestSetup.REPORT_GRID_CQ_WORDS || it == CONTEST_WORD_CUSTOM }
            ?: ContestSetup.REPORT_GRID_CQ_WORDS.first()
    )
        private set

    /** The word a Report+grid contest calls with; "" for a plain CQ. */
    val contestReportGridWord: String
        get() = if (contestReportGridChoice == CONTEST_WORD_CUSTOM) contestGridCustomWord else contestReportGridChoice

    fun chooseContestReportGridWord(word: String) {
        if (word !in ContestSetup.REPORT_GRID_CQ_WORDS && word != CONTEST_WORD_CUSTOM) return
        contestReportGridChoice = word
        prefs.edit { putString(KEY_CONTEST_RG_WORD, word) }
    }

    /** Our Field Day class, `2A`, as typed. Kept while the mode is off. */
    var contestFdClass by mutableStateOf(prefs.getString(KEY_CONTEST_FD_CLASS, "") ?: "")
        private set

    /** Our Field Day section, `MB`, as typed. Kept while the mode is off. */
    var contestFdSection by mutableStateOf(prefs.getString(KEY_CONTEST_FD_SECTION, "") ?: "")
        private set

    /** What [kind] would be with the values as they stand: the setup, or why not. */
    fun contestFor(kind: ContestSetup.Kind): ContestSetup.Result = ContestSetup.build(
        kind,
        profile.normalisedCallsign,
        profile.normalisedGrid,
        contestGridWord,
        TxText.normalise(contestFdClass),
        TxText.normalise(contestFdSection),
        contestReportGridWord
    )

    /**
     * The contest we are in, or null when contest mode is off - or on with values that
     * no longer pass (a callsign or grid changed since), in which case Settings says
     * why and the dock shows no Contest line, so nothing claims a format we cannot send.
     */
    val contest: ContestSetup?
        get() = contestKind?.let { (contestFor(it) as? ContestSetup.Result.Ok)?.setup }

    /**
     * Switch contest mode to [kind], or off with null. Refused, and left as it was,
     * when the values do not pass for [kind]: the problem is returned for Settings to
     * show (checked before the mode can be switched on).
     */
    fun chooseContest(kind: ContestSetup.Kind?): ContestSetup.Problem? {
        if (kind != null) (contestFor(kind) as? ContestSetup.Result.Refused)?.let { return it.problem }
        contestKind = kind
        prefs.edit { putString(KEY_CONTEST_KIND, kind?.name) }
        return null
    }

    fun chooseContestGridWord(word: String) {
        if (word !in ContestSetup.GRID_CQ_WORDS && word != CONTEST_WORD_CUSTOM) return
        contestGridChoice = word
        prefs.edit { putString(KEY_CONTEST_GRID_WORD, word) }
    }

    fun chooseContestGridCustomWord(word: String) {
        contestGridCustomWord = word
        prefs.edit { putString(KEY_CONTEST_GRID_CUSTOM, word) }
    }

    /**
     * Settings › CQ PREFERENCES: in a CQ run, move on after this many
     * missed replies, or 0 for off. Survives a restart, like contest mode.
     */
    var cqMissLimit by mutableIntStateOf(
        prefs.getInt(KEY_CQ_MISS_LIMIT, AutoTx.DEFAULT_CQ_MISS_LIMIT).coerceIn(0, AutoTx.MAX_CQ_MISS_LIMIT)
    )
        private set

    fun chooseCqMissLimit(value: Int) {
        val limit = value.coerceIn(0, AutoTx.MAX_CQ_MISS_LIMIT)
        cqMissLimit = limit
        prefs.edit { putInt(KEY_CQ_MISS_LIMIT, limit) }
    }

    /**
     * Settings › CQ PREFERENCES: stop CQ after this many
     * unanswered CQs, or 0 for never. One of [AutoTx.CQ_STOP_CHOICES]; survives a restart.
     */
    var cqStopLimit by mutableIntStateOf(
        prefs.getInt(KEY_CQ_STOP_LIMIT, AutoTx.CQ_LIMIT).takeIf { it in AutoTx.CQ_STOP_CHOICES } ?: AutoTx.CQ_LIMIT
    )
        private set

    fun chooseCqStopLimit(value: Int) {
        if (value !in AutoTx.CQ_STOP_CHOICES) return
        cqStopLimit = value
        prefs.edit { putInt(KEY_CQ_STOP_LIMIT, value) }
    }

    fun chooseContestFdClass(value: String) {
        contestFdClass = value
        prefs.edit { putString(KEY_CONTEST_FD_CLASS, value) }
    }

    fun chooseContestFdSection(value: String) {
        contestFdSection = value
        prefs.edit { putString(KEY_CONTEST_FD_SECTION, value) }
    }

    fun setCallsign(value: String) = save(profile.copy(callsign = value))

    fun setGrid(value: String) = save(profile.copy(grid = value))

    fun setSwrAbort(value: Double) = save(profile.copy(swrAbort = value))

    private fun save(updated: StationProfile) {
        profile = updated
        prefs.edit()
            .putString(KEY_CALLSIGN, updated.callsign)
            .putString(KEY_GRID, updated.grid)
            .putFloat(KEY_SWR_ABORT, updated.swrAbort.toFloat())
            .apply()
    }

    companion object {
        const val MIN_ROSTER_WINDOW_SEC = 30
        const val MAX_ROSTER_WINDOW_SEC = 300

        /** Three minutes. */
        const val DEFAULT_ROSTER_WINDOW_SEC = 180

        private const val NAME = "ft8ok-station"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_GRID = "grid"
        private const val KEY_SWR_ABORT = "swr_abort"
        private const val KEY_LOG_FOLDER = "log_folder"
        private const val KEY_ACTIVE_LOGBOOK = "active_logbook"
        private const val KEY_RECEIVE_ON = "receive_on"
        private const val KEY_DETAILED_LOGS = "detailed_logs"
        private const val KEY_ROSTER_WINDOW = "roster_window_sec"
        private const val KEY_ROSTER_SORT = "roster_sort"
        private const val KEY_MAP_GRID = "map_grid"
        private const val KEY_FEED_FILTER = "feed_filter"
        private const val KEY_FEED_CUSTOM = "feed_custom"
        private const val KEY_CUSTOM_DIALS = "custom_dials_hz"
        private const val KEY_CQ_WORD = "cq_word"
        private const val KEY_SAVED_TEXTS = "saved_texts"
        private const val KEY_CONTEST_KIND = "contest_kind"
        private const val KEY_CQ_MISS_LIMIT = "cq_miss_limit"
        private const val KEY_CQ_STOP_LIMIT = "cq_stop_limit"
        private const val KEY_CONTEST_GRID_WORD = "contest_grid_word"
        private const val KEY_CONTEST_GRID_CUSTOM = "contest_grid_custom"
        private const val KEY_CONTEST_RG_WORD = "contest_report_grid_word"

        /** The pick that means "the word typed". Too long to be a CQ word itself. */
        const val CONTEST_WORD_CUSTOM = "CUSTOM"
        private const val KEY_CONTEST_FD_CLASS = "contest_fd_class"
        private const val KEY_CONTEST_FD_SECTION = "contest_fd_section"

        /** Chosen default: the first saved texts, until the operator edits the list. */
        val DEFAULT_SAVED_TEXTS = listOf("TNX 73 GL", "QRT 73")
    }
}
