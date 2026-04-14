package com.silk.web

import androidx.compose.runtime.NoLiveLiterals
import kotlinx.coroutines.await
import kotlin.js.Date
import kotlin.js.Promise

/**
 * Manages writing exported Markdown directly into a user-selected Obsidian vault
 * using the browser File System Access API (Chrome/Edge 86+).
 */
@NoLiveLiterals
object ObsidianVaultManager {

    private var cachedHandle: dynamic = null

    fun isSupported(): Boolean {
        return js("typeof window.showDirectoryPicker === 'function'") as Boolean
    }

    fun hasCachedHandle(): Boolean = cachedHandle != null

    /**
     * Prompt user to pick a vault directory. MUST be called during a user gesture.
     */
    suspend fun pickVaultDirectory(): dynamic {
        val w: dynamic = js("window")
        val opts: dynamic = js("({})")
        opts.mode = "readwrite"
        opts.id = "silk-obsidian-vault"
        val handle = (w.showDirectoryPicker(opts) as Promise<dynamic>).await()
        cachedHandle = handle
        return handle
    }

    /**
     * Get the cached handle if permission is still valid.
     * Returns null if no cache or permission revoked.
     */
    suspend fun getCachedHandleIfValid(): dynamic {
        val h = cachedHandle ?: return null
        return try {
            val opts: dynamic = js("({})")
            opts.mode = "readwrite"
            val state = (h.queryPermission(opts) as Promise<dynamic>).await()
            if (state.toString() == "granted") h else null
        } catch (e: Exception) {
            console.warn("queryPermission failed:", e)
            null
        }
    }

    /**
     * Write markdown to: {vaultRoot}/Silk/{groupName}/{yyyy-MM}/{fileName}
     * @return the relative path written inside the vault
     */
    suspend fun saveToVault(
        handle: dynamic,
        groupName: String,
        markdown: String,
        fileName: String
    ): String {
        val safeGroupName = groupName
            .replace(Regex("[^\\w\\s\\-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "unknown" }
        val now = Date()
        val yyyy = now.getFullYear().toString()
        val mm = (now.getMonth() + 1).let { if (it < 10) "0$it" else "$it" }
        val yearMonth = "$yyyy-$mm"

        console.log("saveToVault: creating Silk/", safeGroupName, "/", yearMonth)

        val silkDir = getOrCreateSubDir(handle, "Silk")
        val groupDir = getOrCreateSubDir(silkDir, safeGroupName)
        val monthDir = getOrCreateSubDir(groupDir, yearMonth)

        console.log("saveToVault: writing file", fileName)
        val createOpts: dynamic = js("({})")
        createOpts.create = true
        val fileHandle = (monthDir.getFileHandle(fileName, createOpts) as Promise<dynamic>).await()
        val writable = (fileHandle.createWritable() as Promise<dynamic>).await()
        (writable.write(markdown) as Promise<dynamic>).await()
        (writable.close() as Promise<dynamic>).await()
        console.log("saveToVault: done")

        return "Silk/$safeGroupName/$yearMonth/$fileName"
    }

    private suspend fun getOrCreateSubDir(parent: dynamic, name: String): dynamic {
        val opts: dynamic = js("({})")
        opts.create = true
        return (parent.getDirectoryHandle(name, opts) as Promise<dynamic>).await()
    }

    fun clearCachedHandle() {
        cachedHandle = null
    }
}
