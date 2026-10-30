package com.sadad.app.data.recovery

import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.session.StoreScope
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Moves unsent commands between phones and to the admin: a sealed copy for another phone, a bare
 * manifest of one origin's commands for the dashboard, and the permit that lets sync send them.
 */
object Recovery {
  private const val WRONG_STORE = "ملف الاسترداد لمتجر أو مشروع مختلف."
  private val commands = ListSerializer(LedgerCommand.serializer())

  /** Every queued command of this store, sealed under [passphrase]. */
  suspend fun export(scope: StoreScope, passphrase: CharArray): ByteArray =
      withContext(Dispatchers.Default) { sealed(scope, passphrase) }

  private suspend fun sealed(scope: StoreScope, passphrase: CharArray): ByteArray {
    val queued =
        scope.outbox.snapshot().filter { it.state == OutboxState.QUEUED }.map { it.command }
    val bundle = buildJsonObject {
      put("storeUserId", scope.account.storeUserId)
      put("username", scope.account.username)
      put("commands", LedgerJson.encodeToJsonElement(commands, queued))
    }
    return RecoveryFile.seal(passphrase, bundle)
  }

  /**
   * Appends [file]'s commands as queued rows, skipping ones this outbox already holds; returns how
   * many.
   */
  suspend fun import(scope: StoreScope, file: ByteArray, passphrase: CharArray): Int =
      withContext(Dispatchers.Default) { opened(scope, file, passphrase) }

  private suspend fun opened(scope: StoreScope, file: ByteArray, passphrase: CharArray): Int {
    val bundle = RecoveryFile.open(file, passphrase)
    val storeUserId =
        try {
          bundle.getValue("storeUserId").jsonPrimitive.content
        } catch (_: RuntimeException) {
          throw IllegalArgumentException(RecoveryFile.WRONG_PASSPHRASE)
        }
    if (storeUserId != scope.account.storeUserId) throw IllegalArgumentException(WRONG_STORE)
    val copied =
        try {
          LedgerJson.decodeFromJsonElement(commands, bundle.getValue("commands"))
        } catch (_: RuntimeException) {
          throw IllegalArgumentException(RecoveryFile.WRONG_PASSPHRASE)
        }
    val known = scope.outbox.snapshot().mapTo(HashSet()) { it.operationId }
    val fresh = copied.distinctBy { it.operationId }.filter { it.operationId !in known }
    scope.outbox.append(fresh)
    return fresh.size
  }

  /** The installation ids of another phone whose queued offline rows wait for an admin list. */
  suspend fun origins(scope: StoreScope): List<String> =
      scope.outbox
          .snapshot()
          .filter {
            it.state == OutboxState.QUEUED &&
                it.command.type.offline &&
                it.command.originInstallationId != scope.account.installationId
          }
          .map { it.command.originInstallationId }
          .distinct()

  /**
   * The bare JSON array of [origin]'s queued offline commands, the same elements sync will send.
   */
  suspend fun manifest(scope: StoreScope, origin: String): ByteArray {
    val selected =
        scope.outbox
            .snapshot()
            .filter {
              it.state == OutboxState.QUEUED &&
                  it.command.type.offline &&
                  it.command.originInstallationId == origin
            }
            .map { it.command }
    require(selected.size in 1..1000) {
      "قائمة الاعتماد تحتاج من إدخال واحد إلى ١٠٠٠ إدخال للأصل نفسه؛ راجع الإدارة."
    }
    val file =
        JsonArray(selected.map { LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), it) })
            .toString()
            .toByteArray(UTF_8)
    require(file.size <= 4_000_000) { "قائمة الاعتماد أكبر من الحد المسموح." }
    return file
  }

  /** How many rows the permit covered, and how many of the origin's rows it did not. */
  data class PermitResult(val stamped: Int, val outside: Int)

  /**
   * Puts the admin's [permit] on the rows [manifest] named: the bytes the admin approved. Sync
   * then sends them in their own batches. A row of [origin] that appeared after the export stays
   * queued without a permit instead of poisoning the batch with a command the permit cannot name.
   */
  suspend fun setPermit(
      scope: StoreScope,
      origin: String,
      permit: String,
      manifest: ByteArray,
  ): PermitResult {
    // The server's check is a canonical UUID; a typo stored on the rows would block the sends.
    val canonical = permit.trim().lowercase()
    require(PERMIT.matches(canonical)) { "رقم التصريح غير صالح." }
    require(origin != scope.account.installationId) { "هذا الهاتف لا يحتاج إلى تصريح لإدخالاته." }
    val approved =
        try {
          LedgerJson.decodeFromJsonElement(
              commands,
              Json.parseToJsonElement(String(manifest, UTF_8)),
          )
        } catch (_: RuntimeException) {
          throw IllegalArgumentException(MANIFEST_UNREADABLE)
        }
    require(approved.isNotEmpty()) { MANIFEST_UNREADABLE }
    val approvedIds = approved.mapTo(HashSet()) { it.operationId }
    val stamped = scope.outbox.setPermit(origin, canonical, approvedIds)
    val outside =
        scope.outbox
            .snapshot()
            .count {
              it.state == OutboxState.QUEUED &&
                  it.command.type.offline &&
                  it.command.originInstallationId == origin &&
                  it.operationId !in approvedIds
            }
    return PermitResult(stamped, outside)
  }

  const val MANIFEST_UNREADABLE = "قائمة الاعتماد غير صالحة؛ أنشئها من جديد ثم أدخل التصريح."
  private val PERMIT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
