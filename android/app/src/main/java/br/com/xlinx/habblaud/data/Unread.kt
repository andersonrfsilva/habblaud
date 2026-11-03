package br.com.xlinx.habblaud.data

/** Respostas do agente que chegaram depois da última vez que a conversa foi lida (relógio do servidor). */
fun unreadCount(replies: List<Long>, seenAt: Long?): Int = if (seenAt == null) 0 else replies.count { it > seenAt }

/**
 * Marcas de lido depois de um snapshot: conversa vista pela primeira vez começa lida até agora (o que já existia não
 * vira não lido de uma vez); a aberta na tela fica lida até agora. Devolve null quando nada mudou.
 */
fun updatedSeen(seen: Map<String, Long>, ids: List<String>, openId: String?, now: Long): Map<String, Long>? {
    val next = seen.toMutableMap()
    for (id in ids) if (id !in next) next[id] = now
    if (openId != null && (next[openId] ?: 0) < now) next[openId] = now
    return next.takeIf { it != seen }
}

/** Tela principal: a conversa com a resposta mais nova no topo; sem resposta conhecida, vale o último evento. */
fun byLastMessage(sessions: List<SessionItem>): List<SessionItem> = sessions.sortedByDescending { it.replies.lastOrNull() ?: it.lastAt ?: 0L }
