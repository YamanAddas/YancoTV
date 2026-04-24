package com.yancotv.shared.reminders

import com.yancotv.shared.db.Reminders
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.EpgProgramme

/**
 * CRUD for programme reminders. Pure data layer — the Android-side
 * `ReminderScheduler` pairs each insert/delete here with an `AlarmManager`
 * schedule/cancel so the two stay consistent.
 *
 * We don't schedule alarms from commonMain because alarm APIs are
 * Android-only (iOS uses `UNUserNotificationCenter` with trigger dates and
 * lands later in MK.iOS).
 */
class ReminderRepository(
    private val db: YancoDb,
    private val clock: () -> Long,
) {
    fun all(): List<Reminder> =
        db.remindersQueries
            .selectAll()
            .executeAsList()
            .map { it.toDomain() }

    fun allUnfired(): List<Reminder> =
        db.remindersQueries
            .selectAllUnfired()
            .executeAsList()
            .map { it.toDomain() }

    fun forProgramme(programmeId: String): Reminder? =
        db.remindersQueries
            .selectByProgrammeId(programmeId)
            .executeAsOneOrNull()
            ?.toDomain()

    fun dueAt(unixSeconds: Long): List<Reminder> =
        db.remindersQueries
            .selectUnfiredDue(unixSeconds)
            .executeAsList()
            .map { it.toDomain() }

    /**
     * Create (or replace) a reminder for [programme] on [channelTvgId]. Lead
     * time defaults to 0 — firing exactly at the programme's start_time.
     * Returns the reminder so the caller can feed its [Reminder.fireAt] to
     * `AlarmManager`.
     */
    fun upsert(
        channelTvgId: String,
        programme: EpgProgramme,
        leadSeconds: Long = 0L,
    ): Reminder {
        // One reminder per programme — a second "set reminder" on the same
        // programme should overwrite the lead time, not create a duplicate.
        db.remindersQueries.deleteByProgrammeId(programme.id)
        val fireAt = programme.startTime - leadSeconds
        val id = "rem:${programme.id}"
        db.remindersQueries.insert(
            id = id,
            programme_id = programme.id,
            channel_tvg_id = channelTvgId,
            title = programme.title,
            start_time = programme.startTime,
            end_time = programme.endTime,
            lead_seconds = leadSeconds,
            fire_at = fireAt,
            fired = false,
            created_at = clock() / 1000L,
        )
        return Reminder(
            id = id,
            programmeId = programme.id,
            channelTvgId = channelTvgId,
            title = programme.title,
            startTime = programme.startTime,
            endTime = programme.endTime,
            leadSeconds = leadSeconds,
            fireAt = fireAt,
            fired = false,
        )
    }

    fun deleteByProgrammeId(programmeId: String) {
        db.remindersQueries.deleteByProgrammeId(programmeId)
    }

    fun deleteById(id: String) {
        db.remindersQueries.deleteById(id)
    }

    fun markFired(id: String) {
        db.remindersQueries.markFired(id)
    }

    /** Purge reminders whose programme has already ended. */
    fun purgeStale(cutoffSeconds: Long = clock() / 1000L) {
        db.remindersQueries.deleteStale(cutoffSeconds)
    }
}

data class Reminder(
    val id: String,
    val programmeId: String,
    val channelTvgId: String,
    val title: String,
    /** Unix seconds — programme start */
    val startTime: Long,
    /** Unix seconds — programme end */
    val endTime: Long,
    val leadSeconds: Long,
    /** Unix seconds — when AlarmManager should fire */
    val fireAt: Long,
    val fired: Boolean,
)

private fun Reminders.toDomain(): Reminder =
    Reminder(
        id = id,
        programmeId = programme_id,
        channelTvgId = channel_tvg_id,
        title = title,
        startTime = start_time,
        endTime = end_time,
        leadSeconds = lead_seconds,
        fireAt = fire_at,
        fired = fired,
    )
