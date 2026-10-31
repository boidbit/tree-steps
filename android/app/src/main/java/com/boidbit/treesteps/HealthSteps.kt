package com.boidbit.treesteps

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Steps from Health Connect, where Samsung Health, Google Fit and other apps share them. Those apps filter
 * out car rides and count with their own sensors, so they are a second opinion on the phone's counter.
 * Every call answers on the main thread; a failure answers -1 (or an empty map) instead of throwing.
 */
object HealthSteps {
    fun interface Count { fun done(steps: Long) }
    fun interface Days { fun done(days: Map<String, Long>) }
    fun interface Granted { fun done(granted: Boolean) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val PERMS = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private const val PROVIDER = "com.google.android.apps.healthdata"

    /** available | update (Health Connect needs installing or updating) | unavailable */
    @JvmStatic
    fun status(c: Context): String = when (HealthConnectClient.getSdkStatus(c, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
        else -> "unavailable"
    }

    /** Opens Health Connect's page in the Play Store, to install or update it. */
    @JvmStatic
    fun storeIntent(): Intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))

    /** The system screen that asks to read steps; its result goes to [granted]. */
    @JvmStatic
    fun permissionIntent(c: Context): Intent =
        PermissionController.createRequestPermissionResultContract(PROVIDER).createIntent(c, PERMS)

    @JvmStatic
    fun granted(c: Context, cb: Granted) {
        if (status(c) != "available") { cb.done(false); return }
        scope.launch {
            val ok = try {
                HealthConnectClient.getOrCreate(c, PROVIDER).permissionController.getGrantedPermissions().containsAll(PERMS)
            } catch (e: Exception) { false }
            cb.done(ok)
        }
    }

    /** Today's steps so far, from midnight. */
    @JvmStatic
    fun today(c: Context, cb: Count) {
        if (status(c) != "available") { cb.done(-1); return }
        scope.launch {
            val steps = try {
                val zone = ZoneId.systemDefault()
                val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val r = HealthConnectClient.getOrCreate(c, PROVIDER).aggregate(
                    AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, Instant.now())))
                r[StepsRecord.COUNT_TOTAL] ?: 0L
            } catch (e: Exception) { -1L }
            cb.done(steps)
        }
    }

    /** Each of the last [n] finished days, as yyyyMMdd to steps. */
    @JvmStatic
    fun days(c: Context, n: Int, cb: Days) {
        if (status(c) != "available") { cb.done(emptyMap()); return }
        scope.launch {
            val out = HashMap<String, Long>()
            try {
                val today = LocalDate.now()
                val r = HealthConnectClient.getOrCreate(c, PROVIDER).aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL),
                        TimeRangeFilter.between(today.minusDays(n.toLong()).atStartOfDay(), today.atStartOfDay()), Period.ofDays(1)))
                val f = DateTimeFormatter.BASIC_ISO_DATE
                for (p in r) out[p.startTime.toLocalDate().format(f)] = p.result[StepsRecord.COUNT_TOTAL] ?: 0L
            } catch (e: Exception) { }
            cb.done(out)
        }
    }
}
