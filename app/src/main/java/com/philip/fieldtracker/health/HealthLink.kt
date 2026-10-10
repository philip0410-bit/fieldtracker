package com.philip.fieldtracker.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.philip.fieldtracker.data.HcType
import com.philip.fieldtracker.data.Session
import com.philip.fieldtracker.data.SessionStats
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Health Connect : c'est par là que passent les données de Samsung Health
 * (Samsung Health doit avoir la synchronisation Health Connect activée).
 */
object HealthLink {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    fun isAvailable(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun client(ctx: Context): HealthConnectClient = HealthConnectClient.getOrCreate(ctx)

    suspend fun hasAllPermissions(ctx: Context): Boolean = try {
        client(ctx).permissionController.getGrantedPermissions().containsAll(permissions)
    } catch (e: Exception) {
        false
    }

    private fun exerciseType(t: HcType): Int = when (t) {
        HcType.SOCCER -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        HcType.FOOTBALL_AMERICAN -> ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN
        HcType.FOOTBALL_AUSTRALIAN -> ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN
        HcType.RUGBY -> ExerciseSessionRecord.EXERCISE_TYPE_RUGBY
        HcType.HANDBALL -> ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL
        HcType.BASKETBALL -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
        HcType.VOLLEYBALL -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL
        HcType.TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
        HcType.BADMINTON -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
        HcType.SQUASH -> ExerciseSessionRecord.EXERCISE_TYPE_SQUASH
        HcType.TABLE_TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
        HcType.BASEBALL -> ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL
        HcType.SOFTBALL -> ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL
        HcType.CRICKET -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
        HcType.ICE_HOCKEY -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY
        HcType.FRISBEE -> ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC
        HcType.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        HcType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /** Enregistre la séance (sport, distance, calories, FC) dans Health Connect / Samsung Health. */
    suspend fun writeSession(ctx: Context, s: Session, stats: SessionStats) {
        val start = Instant.ofEpochMilli(s.startMs)
        val end = Instant.ofEpochMilli(s.endMs)
        val off: ZoneOffset = ZoneId.systemDefault().rules.getOffset(start)
        val records = ArrayList<Record>()
        records.add(
            ExerciseSessionRecord(
                startTime = start, startZoneOffset = off,
                endTime = end, endZoneOffset = off,
                exerciseType = exerciseType(s.sport.hc),
                title = "${s.sport.name} – FieldTracker",
            )
        )
        if (stats.distanceM > 0) records.add(
            DistanceRecord(
                startTime = start, startZoneOffset = off,
                endTime = end, endZoneOffset = off,
                distance = Length.meters(stats.distanceM),
            )
        )
        if (stats.calories > 0) records.add(
            TotalCaloriesBurnedRecord(
                startTime = start, startZoneOffset = off,
                endTime = end, endZoneOffset = off,
                energy = Energy.kilocalories(stats.calories.toDouble()),
            )
        )
        // FC : seulement si elle vient de la montre ou d'un capteur (pas déjà de Health Connect)
        val samples = s.hrSamples.filter { it.second in 30..240 && it.first in s.startMs..s.endMs }
        samples.chunked(500).forEach { chunk ->
            val first = Instant.ofEpochMilli(chunk.first().first)
            val last = Instant.ofEpochMilli(chunk.last().first + 1)
            records.add(
                HeartRateRecord(
                    startTime = first, startZoneOffset = off,
                    endTime = last, endZoneOffset = off,
                    samples = chunk.map {
                        HeartRateRecord.Sample(time = Instant.ofEpochMilli(it.first), beatsPerMinute = it.second.toLong())
                    },
                )
            )
        }
        client(ctx).insertRecords(records)
    }

    /** Lit la FC enregistrée par Samsung Health / la montre pendant la séance. */
    suspend fun readHeartRate(ctx: Context, startMs: Long, endMs: Long): List<Pair<Long, Int>> {
        val resp = client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)),
            )
        )
        return resp.records.flatMap { r -> r.samples.map { it.time.toEpochMilli() to it.beatsPerMinute.toInt() } }
            .sortedBy { it.first }
    }
}
