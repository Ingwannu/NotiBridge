package me.teamwicked.notibridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.teamwicked.notibridge.model.ContentType
import me.teamwicked.notibridge.model.ExcludeFilter
import me.teamwicked.notibridge.model.Hook
import me.teamwicked.notibridge.model.HttpMethod
import me.teamwicked.notibridge.model.RegexRule

@Database(
    entities = [
        HookEntity::class,
        DeliveryTaskEntity::class,
        SendLogEntity::class,
        GlobalVariableEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hookDao(): HookDao
    abstract fun deliveryTaskDao(): DeliveryTaskDao
    abstract fun sendLogDao(): SendLogDao
    abstract fun globalVariableDao(): GlobalVariableDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notibridge.db",
                ).build().also { instance = it }
            }
    }
}

/** Shared JSON codec. Explicit nulls are skipped to keep snapshots compact. */
val dbJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val stringListSerializer = ListSerializer(String.serializer())
private val headersSerializer = MapSerializer(String.serializer(), String.serializer())
private val regexRuleListSerializer = ListSerializer(RegexRule.serializer())
private val excludeFilterListSerializer = ListSerializer(ExcludeFilter.serializer())

fun HookEntity.toDomain(): Hook = Hook(
    id = id,
    name = name,
    enabled = enabled,
    url = url,
    method = runCatching { HttpMethod.valueOf(method) }.getOrDefault(HttpMethod.POST),
    contentType = runCatching { ContentType.valueOf(contentType) }.getOrDefault(ContentType.JSON),
    timeoutSeconds = timeoutSeconds,
    headers = runCatching { dbJson.decodeFromString(headersSerializer, headersJson) }.getOrDefault(emptyMap()),
    authHeaderName = authHeaderName,
    authToken = authToken,
    bodyTemplate = bodyTemplate,
    bodyFileName = bodyFileName,
    bodyFileBase64 = bodyFileBase64,
    appPackages = runCatching { dbJson.decodeFromString(stringListSerializer, appPackagesJson) }.getOrDefault(emptyList()),
    regexRules = runCatching { dbJson.decodeFromString(regexRuleListSerializer, regexRulesJson) }.getOrDefault(emptyList()),
    excludeFilters = runCatching { dbJson.decodeFromString(excludeFilterListSerializer, excludeFiltersJson) }.getOrDefault(emptyList()),
)

fun Hook.toEntity(sortOrder: Int, updatedAt: Long = System.currentTimeMillis()): HookEntity = HookEntity(
    id = id,
    name = name,
    enabled = enabled,
    url = url,
    method = method.name,
    contentType = contentType.name,
    timeoutSeconds = timeoutSeconds,
    headersJson = dbJson.encodeToString(headersSerializer, headers),
    authHeaderName = authHeaderName,
    authToken = authToken,
    bodyTemplate = bodyTemplate,
    bodyFileName = bodyFileName,
    bodyFileBase64 = bodyFileBase64,
    appPackagesJson = dbJson.encodeToString(stringListSerializer, appPackages),
    regexRulesJson = dbJson.encodeToString(regexRuleListSerializer, regexRules),
    excludeFiltersJson = dbJson.encodeToString(excludeFilterListSerializer, excludeFilters),
    sortOrder = sortOrder,
    updatedAt = updatedAt,
)
