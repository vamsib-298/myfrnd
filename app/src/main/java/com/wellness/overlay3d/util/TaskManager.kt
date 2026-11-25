package com.wellness.overlay3d.util

import android.content.Context
import com.wellness.overlay3d.model.TaskReminder
import org.json.JSONArray
import org.json.JSONException

class TaskStorageException(cause: Throwable) : IllegalStateException("Unable to read saved reminders", cause)

object TaskManager {
    private const val PREFS_NAME = "myfrnd_task_prefs"
    private const val KEY_REMINDERS = "key_scheduled_reminders"

    fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllReminders(context: Context): List<TaskReminder> {
        val source = preferences(context).getString(KEY_REMINDERS, null) ?: return emptyList()
        try {
            val array = JSONArray(source)
            val reminders = List(array.length()) { TaskReminder.fromJson(array.getJSONObject(it)) }
            require(reminders.map { it.id }.distinct().size == reminders.size) { "Duplicate reminder IDs" }
            // Timed reminders come first sorted by due time, then quick notes sorted newest first.
            return reminders.sortedWith(compareBy<TaskReminder> { it.isNote }.thenBy {
                if (it.isNote) -it.timestampMillis else it.timestampMillis
            })
        } catch (error: JSONException) {
            throw TaskStorageException(error)
        } catch (error: IllegalArgumentException) {
            throw TaskStorageException(error)
        }
    }

    fun getPendingReminders(context: Context): List<TaskReminder> =
        getAllReminders(context).filterNot { it.isTriggered }

    fun getPendingTimedReminders(context: Context): List<TaskReminder> =
        getAllReminders(context).filter { !it.isTriggered && !it.isNote }

    fun addReminder(context: Context, reminder: TaskReminder, now: Long = System.currentTimeMillis()) {
        if (!reminder.isNote) {
            require(reminder.timestampMillis > now) { "Choose a future reminder time" }
        }
        val current = getAllReminders(context)
        require(current.none { it.id == reminder.id }) { "Reminder already exists" }
        saveReminders(context, current + reminder)
    }

    fun addNote(context: Context, title: String, now: Long = System.currentTimeMillis()): TaskReminder {
        val note = TaskReminder(title = title.trim(), timestampMillis = now.coerceAtLeast(1L), isNote = true)
        addReminder(context, note, now)
        return note
    }

    fun removeReminder(context: Context, reminderId: String) {
        saveReminders(context, getAllReminders(context).filterNot { it.id == reminderId })
    }

    fun updateReminder(context: Context, reminder: TaskReminder, now: Long = System.currentTimeMillis()): Boolean {
        if (!reminder.isNote) {
            require(reminder.timestampMillis > now) { "Choose a future reminder time" }
        }
        val current = getAllReminders(context)
        if (current.none { it.id == reminder.id && !it.isTriggered }) return false
        saveReminders(context, current.map { if (it.id == reminder.id) reminder else it })
        return true
    }

    fun snoozeReminder(context: Context, reminderId: String, delayMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        val current = getAllReminders(context)
        val target = current.firstOrNull { it.id == reminderId } ?: return false
        val updated = target.copy(
            timestampMillis = now + delayMillis.coerceAtLeast(60_000L),
            isTriggered = false,
            isNote = false
        )
        saveReminders(context, current.map { if (it.id == reminderId) updated else it })
        return true
    }

    fun markDelivered(context: Context, reminderId: String, now: Long = System.currentTimeMillis()) {
        val updated = getAllReminders(context).map {
            if (it.id == reminderId) it.copy(isTriggered = true) else it
        }.filter { !it.isTriggered || now - it.timestampMillis < 86_400_000L }
        saveReminders(context, updated)
    }

    private fun saveReminders(context: Context, reminders: List<TaskReminder>) {
        val array = JSONArray()
        reminders.forEach { array.put(it.toJson()) }
        preferences(context).edit().putString(KEY_REMINDERS, array.toString()).apply()
    }
}
