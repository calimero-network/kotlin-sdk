package com.calimero.mero.compose.invitations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The namespaces this process has just joined and is still waiting on — mero-react
 * `invitations/justJoined.ts`. Process memory, not persisted (mero-react uses `sessionStorage`):
 * the flag covers the window between a join returning and that namespace's state arriving, and
 * surviving a restart would gate a workspace that settled long ago. [JoinSync]'s watchdog bounds
 * how long a stale entry can matter.
 *
 * Set by redemption ([InviteRedemption], at the app root) and cleared by whichever screen reads
 * the namespace's data ([JoinSync] with `settled`), so the one learns about the other without the
 * app threading screen state up to its root.
 */
object JustJoinedNamespaces {
    private val _namespaces = MutableStateFlow<Set<String>>(emptySet())

    /** The flagged namespace ids, as a flow a composable can collect. */
    val namespaces: StateFlow<Set<String>> = _namespaces.asStateFlow()

    private val listeners = LinkedHashSet<() -> Unit>()

    /** Call the moment a join succeeds, before navigating. */
    fun mark(namespaceId: String) {
        if (namespaceId.isEmpty()) return
        val changed = synchronized(this) { (namespaceId !in _namespaces.value).also { if (it) _namespaces.value += namespaceId } }
        if (changed) notifyListeners()
    }

    fun clear(namespaceId: String) {
        val changed = synchronized(this) { (namespaceId in _namespaces.value).also { if (it) _namespaces.value -= namespaceId } }
        if (changed) notifyListeners()
    }

    fun isJustJoined(namespaceId: String?): Boolean = namespaceId != null && namespaceId in _namespaces.value

    /** Hear every mark and clear. One listener's failure does not keep the others from hearing it. */
    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(this) { listeners += listener }
        return { synchronized(this) { listeners -= listener } }
    }

    private fun notifyListeners() = synchronized(this) { listeners.toList() }.forEach { runCatching { it() } }

    /** Test seam. */
    fun resetForTests() {
        synchronized(this) { _namespaces.value = emptySet() }
    }
}

/** mero-react `markNamespaceJustJoined`. */
fun markNamespaceJustJoined(namespaceId: String) = JustJoinedNamespaces.mark(namespaceId)

/** mero-react `clearNamespaceJustJoined`. */
fun clearNamespaceJustJoined(namespaceId: String) = JustJoinedNamespaces.clear(namespaceId)

/** mero-react `isNamespaceJustJoined`. */
fun isNamespaceJustJoined(namespaceId: String?): Boolean = JustJoinedNamespaces.isJustJoined(namespaceId)

/** mero-react `subscribeJustJoined`. */
fun subscribeJustJoined(listener: () -> Unit): () -> Unit = JustJoinedNamespaces.subscribe(listener)
