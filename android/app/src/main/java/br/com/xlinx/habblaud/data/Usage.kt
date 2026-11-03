package br.com.xlinx.habblaud.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Um limite de uso: rótulo (do servidor ou fixo), percentual usado e quando reinicia. */
data class UsageLimit(val label: String, val utilization: Double?, val resetsAt: Long?, val exhausted: Boolean = false)

/** Um grupo de limites: um provedor do omp ou as janelas de uma conta Claude/Codex. */
data class UsageGroup(val provider: String?, val plan: String?, val limits: List<UsageLimit>, val note: String? = null)

/** Uma conta do servidor com o uso do plano (`accounts` do /api/snapshot). */
data class AccountQuota(val id: String, val name: String, val provider: String, val plan: String?, val status: String, val noQuota: Boolean, val groups: List<UsageGroup>, val fetchedAt: Long?)

/** Rótulos das janelas fixas (Claude/Codex), resolvidos pela tela. */
const val WINDOW_FIVE_HOUR = "fiveHour"
const val WINDOW_SEVEN_DAY = "sevenDay"
const val WINDOW_SEVEN_DAY_OPUS = "sevenDayOpus"
const val WINDOW_SEVEN_DAY_SONNET = "sevenDaySonnet"

fun parseAccounts(snapshot: String): List<AccountQuota> =
    wireJson.parseToJsonElement(snapshot).jsonObject.objects("accounts").mapNotNull { account ->
        val id = account.string("id") ?: return@mapNotNull null
        val usage = account["usage"] as? JsonObject
        val providers = usage?.objects("ompProviders").orEmpty()
        val groups = if (providers.isNotEmpty()) providers.map { p ->
            UsageGroup(p.string("provider"), p.string("plan"), p.objects("limits").mapNotNull { l ->
                UsageLimit(l.string("label") ?: return@mapNotNull null, l.string("utilization")?.toDoubleOrNull(), l.string("resetsAt")?.toLongOrNull(), l.flag("exhausted"))
            }, p.string("note"))
        } else listOfNotNull(usage?.let { u ->
            val windows = listOf(WINDOW_FIVE_HOUR, WINDOW_SEVEN_DAY, WINDOW_SEVEN_DAY_OPUS, WINDOW_SEVEN_DAY_SONNET).mapNotNull { name ->
                (u[name] as? JsonObject)?.let { w -> UsageLimit(name, w.string("utilization")?.toDoubleOrNull(), w.string("resetsAt")?.toLongOrNull()) }
            }
            UsageGroup(null, null, windows, u.string("note")).takeIf { windows.isNotEmpty() || it.note != null }
        })
        AccountQuota(id, account.string("name") ?: id, account.string("provider") ?: "claude", account.string("plan"), account.string("usageStatus") ?: "disabled",
            usage?.flag("noQuota") == true, groups, usage?.string("fetchedAt")?.toLongOrNull())
    }
