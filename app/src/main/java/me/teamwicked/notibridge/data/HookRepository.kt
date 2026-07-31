package me.teamwicked.notibridge.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.teamwicked.notibridge.model.Hook
import me.teamwicked.notibridge.model.NotifPreset

/** CRUD access for webhook hooks plus preset import/export. */
class HookRepository(private val db: AppDatabase) {

    fun observeHooks(): Flow<List<Hook>> =
        db.hookDao().observeAll().map { list -> list.map(HookEntity::toDomain) }

    suspend fun findHook(id: String): Hook? = db.hookDao().findById(id)?.toDomain()

    suspend fun listEnabledHooks(): List<Hook> =
        db.hookDao().listEnabled().map(HookEntity::toDomain)

    suspend fun saveHook(hook: Hook): Hook {
        val errors = hook.validationErrors()
        require(errors.isEmpty()) { "Invalid hook: ${errors.joinToString()}" }
        val existing = db.hookDao().findById(hook.id)
        val sortOrder = existing?.sortOrder ?: db.hookDao().nextSortOrder()
        db.hookDao().upsert(hook.toEntity(sortOrder))
        return hook
    }

    suspend fun deleteHook(id: String) {
        db.hookDao().deleteById(id)
        // Queued deliveries for a deleted hook are pointless; drop them too.
        db.deliveryTaskDao().deleteByHookId(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        db.hookDao().setEnabled(id, enabled, System.currentTimeMillis())
    }

    fun exportPreset(hook: Hook): String =
        dbJson.encodeToString(NotifPreset.serializer(), hook.toPreset())

    fun importPreset(raw: String): Hook {
        val preset = dbJson.decodeFromString(NotifPreset.serializer(), raw)
        require(preset.format == 1) { "지원하지 않는 프리셋 형식입니다: ${preset.format}" }
        // Imported presets always become a NEW hook with a fresh id.
        return Hook.fromPreset(preset)
    }
}
