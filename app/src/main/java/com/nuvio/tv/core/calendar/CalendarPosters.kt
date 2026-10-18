package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Posters for titles none of their sources gave one, by id alias. The addon's own poster comes first when the
 * title's details are already in memory, as on Home, then the one Continue Watching saved. Nothing is fetched.
 */
fun calendarCachedPosters(
    candidates: List<CalendarTitleCandidate>,
    metaPoster: (type: String, id: String) -> String?,
    nextUpPosters: List<Pair<String, String?>>
): Map<String, String> {
    val missing = candidates.filter { it.poster.isNullOrBlank() }
    if (missing.isEmpty()) return emptyMap()
    val wanted = missing.flatMapTo(HashSet()) { it.ids.aliases() }
    val posters = HashMap<String, String>()
    nextUpPosters.forEach { (contentId, poster) ->
        val url = poster?.takeIf(String::isNotBlank) ?: return@forEach
        CalendarIds.fromContentId(contentId).aliases().filter { it in wanted }.forEach { posters.putIfAbsent(it, url) }
    }
    missing.forEach { candidate ->
        val type = if (candidate.type == CalendarTitleType.MOVIE) "movie" else "series"
        val url = listOfNotNull(candidate.contentId, candidate.ids.imdb, candidate.ids.tmdb?.let { "tmdb:$it" })
            .distinct()
            .firstNotNullOfOrNull { id -> metaPoster(type, id)?.takeIf(String::isNotBlank) }
            ?: return@forEach
        candidate.ids.aliases().forEach { posters[it] = url }
    }
    return posters
}

fun List<CalendarFollowedTitle>.withCachedPosters(posters: Map<String, String>): List<CalendarFollowedTitle> =
    if (posters.isEmpty()) this else map { title ->
        if (!title.poster.isNullOrBlank()) return@map title
        title.ids.aliases().firstNotNullOfOrNull(posters::get)?.let { title.copy(poster = it) } ?: title
    }

data class CalendarArt(val backdrop: String?, val logo: String? = null)

val CALENDAR_BACKDROP_RECHECK: Duration = Duration.ofDays(1)
const val CALENDAR_BACKDROP_FETCH_MAX = 20

/**
 * Backdrops by id alias: the addon's own when the title's details are already in memory, then the one Continue
 * Watching saved. Nothing is fetched.
 */
fun calendarCachedArt(
    candidates: List<CalendarTitleCandidate>,
    metaArt: (type: String, id: String) -> CalendarArt?,
    continueWatchingArt: List<Pair<String, CalendarArt>>
): Map<String, CalendarArt> {
    if (candidates.isEmpty()) return emptyMap()
    val wanted = candidates.flatMapTo(HashSet()) { it.ids.aliases() }
    val art = HashMap<String, CalendarArt>()
    continueWatchingArt.forEach { (contentId, item) ->
        if (item.backdrop.isNullOrBlank()) return@forEach
        CalendarIds.fromContentId(contentId).aliases().filter { it in wanted }.forEach { art.putIfAbsent(it, item) }
    }
    candidates.forEach { candidate ->
        val type = if (candidate.type == CalendarTitleType.MOVIE) "movie" else "series"
        val found = listOfNotNull(candidate.contentId, candidate.ids.imdb, candidate.ids.tmdb?.let { "tmdb:$it" })
            .distinct()
            .firstNotNullOfOrNull { id -> metaArt(type, id)?.takeIf { !it.backdrop.isNullOrBlank() } }
            ?: return@forEach
        candidate.ids.aliases().forEach { art[it] = found }
    }
    return art
}

/** A title takes the backdrop the app holds now; one the app holds nothing for keeps what it saved. */
fun List<CalendarTitleRecord>.withCachedArt(art: Map<String, CalendarArt>): List<CalendarTitleRecord> =
    if (art.isEmpty()) this else map { record ->
        val found = record.ids.aliases().firstNotNullOfOrNull(art::get) ?: return@map record
        val logo = found.logo?.takeIf(String::isNotBlank) ?: record.logo
        if (found.backdrop == record.backdrop && logo == record.logo) record else record.copy(backdrop = found.backdrop, logo = logo)
    }

/** Coming up titles still without a backdrop, at most [max]; a title is tried again a day after its last try. */
fun calendarBackdropsWanted(
    titles: List<CalendarTitleRecord>,
    watched: CalendarWatchedIndex?,
    now: Instant,
    zone: ZoneId,
    max: Int = CALENDAR_BACKDROP_FETCH_MAX
): List<CalendarTitleRecord> =
    buildCalendarComingUp(titles, watched, now, zone, max = max)
        .map { it.title }
        .filter { it.backdrop.isNullOrBlank() && now.toEpochMilli() - it.backdropCheckedAtMs >= CALENDAR_BACKDROP_RECHECK.toMillis() }

fun CalendarTitleRecord.withFetchedArt(art: CalendarArt?, now: Instant): CalendarTitleRecord {
    val logo = art?.logo?.takeIf(String::isNotBlank) ?: logo
    val backdrop = art?.backdrop?.takeIf(String::isNotBlank)
        ?: return copy(logo = logo, backdropCheckedAtMs = now.toEpochMilli())
    return copy(backdrop = backdrop, logo = logo)
}
