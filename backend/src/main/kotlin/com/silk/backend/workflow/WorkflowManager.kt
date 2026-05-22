package com.silk.backend.workflow

import com.silk.backend.models.AgentSessionState
import com.silk.backend.models.Workflow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class WorkflowStore(
    val workflows: MutableList<Workflow> = mutableListOf()
)

class WorkflowManager(
    private val baseDir: String =
        System.getProperty("silk.workflowDir")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("SILK_WORKFLOW_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${System.getProperty("user.home")}/.silk-data/workflows"
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(WorkflowManager::class.java)
    private val storeFile get() = File("$baseDir/workflow_store.json")

    init {
        File(baseDir).mkdirs()
    }

    @Synchronized
    private fun load(): WorkflowStore {
        return if (storeFile.exists()) {
            try {
                json.decodeFromString(storeFile.readText())
            } catch (e: Exception) {
                logger.error("Failed to load workflow store: {}", e.message)
                WorkflowStore()
            }
        } else WorkflowStore()
    }

    @Synchronized
    private fun save(store: WorkflowStore) {
        File(baseDir).mkdirs()
        val tmp = File("${storeFile.path}.tmp")
        tmp.writeText(json.encodeToString(store))
        Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun listWorkflows(userId: String): List<Workflow> =
        load().workflows.filter { it.ownerId == userId }

    @Synchronized
    fun createWorkflow(name: String, description: String, userId: String, groupId: String): Workflow =
        createWorkflow(name, description, userId, groupId, "claude_code", "")

    @Synchronized
    fun createWorkflow(name: String, description: String, userId: String, groupId: String, agentType: String, taskFocus: String = ""): Workflow {
        val store = load()
        val workflow = Workflow(
            id = "wf_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = name,
            description = description,
            ownerId = userId,
            groupId = groupId,
            agentType = agentType,
            taskFocus = taskFocus,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        store.workflows.add(0, workflow)
        save(store)
        logger.info("Created workflow: {} for user {}, groupId={}, agentType={}", workflow.id, userId, groupId, agentType)
        return workflow
    }

    fun getWorkflow(workflowId: String, userId: String): Workflow? =
        load().workflows.find { it.id == workflowId && it.ownerId == userId }

    fun getWorkflowByGroupId(groupId: String): Workflow? =
        load().workflows.find { it.groupId == groupId }

    /**
     * 持久化用户为工作流设置的工作目录（创建时的 initialDir 或运行时的「更改」按钮）。
     * 后端重启后据此 seed CC state 的 workingDir。返回是否真的写盘了（无变化时跳过 I/O）。
     */
    @Synchronized
    fun updateWorkingDir(groupId: String, workingDir: String): Boolean {
        val store = load()
        val idx = store.workflows.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        val old = store.workflows[idx]
        if (old.workingDir == workingDir) return false
        store.workflows[idx] = old.copy(
            workingDir = workingDir,
            updatedAt = System.currentTimeMillis(),
        )
        save(store)
        logger.info("Workflow {} workingDir 持久化: {}", old.id, workingDir)
        return true
    }

    @Synchronized
    fun renameWorkflow(workflowId: String, userId: String, newName: String): Workflow? {
        val store = load()
        val idx = store.workflows.indexOfFirst { it.id == workflowId && it.ownerId == userId }
        if (idx < 0) return null
        val updated = store.workflows[idx].copy(
            name = newName,
            updatedAt = System.currentTimeMillis()
        )
        store.workflows[idx] = updated
        save(store)
        logger.info("Renamed workflow {} to '{}'", workflowId, newName)
        return updated
    }

    /**
     * 持久化 bridge 上一次的 sessionId + sessionStarted。后端重启后据此发起 resume，
     * 让用户续上之前的对话历史（前提：bridge 端的本地 session 文件还在）。
     * 跳过无变化的写入，避免 prompt 高频持久化时的 I/O 抖动。
     */
    @Synchronized
    fun updateSessionState(groupId: String, sessionId: String, sessionStarted: Boolean): Boolean {
        val store = load()
        val idx = store.workflows.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        val old = store.workflows[idx]
        if (old.sessionId == sessionId && old.sessionStarted == sessionStarted) return false
        store.workflows[idx] = old.copy(
            sessionId = sessionId,
            sessionStarted = sessionStarted,
            updatedAt = System.currentTimeMillis(),
        )
        save(store)
        return true
    }

    /**
     * M4 Task 3: per-agent 持久化。同步写到：
     *  - agentSessions[agentType]（per-agent 多 agent 互不覆盖）
     *  - 旧 sessionId/sessionStarted（仅当 agentType 与 workflow.activeAgent 或默认 agentType 匹配时
     *    更新，保持 backward-compat：老 client 读旧字段还能拿到"当前 agent"的值）
     *
     * agentType 应该是 runtime 的 dash form（例如 "claude-code" / "codex"）。
     * 空字符串 sessionId 表示"清空该 agent 的 resume 状态"（与 /new、cdSync 行为一致）。
     */
    @Synchronized
    fun updateSessionState(groupId: String, agentType: String, sessionId: String, sessionStarted: Boolean): Boolean {
        if (agentType.isBlank()) return updateSessionState(groupId, sessionId, sessionStarted)
        val store = load()
        val idx = store.workflows.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        val old = store.workflows[idx]
        val newSessions = old.agentSessions.toMutableMap()
        val newState = AgentSessionState(sessionId = sessionId, sessionStarted = sessionStarted)
        val prevState = newSessions[agentType]
        val perAgentChanged = prevState?.sessionId != sessionId || prevState.sessionStarted != sessionStarted
        if (perAgentChanged) newSessions[agentType] = newState

        // 当 agentType 等于 workflow 当前激活 agent（或默认 agentType）时，
        // 同步更新旧 sessionId/sessionStarted 字段保持向后兼容。
        val activeAgentDash = old.activeAgent.takeIf { it.isNotBlank() } ?: normalizeWfAgentType(old.agentType)
        val syncLegacy = agentType == activeAgentDash
        val legacyChanged = syncLegacy && (old.sessionId != sessionId || old.sessionStarted != sessionStarted)

        if (!perAgentChanged && !legacyChanged) return false
        store.workflows[idx] = old.copy(
            agentSessions = newSessions,
            sessionId = if (syncLegacy) sessionId else old.sessionId,
            sessionStarted = if (syncLegacy) sessionStarted else old.sessionStarted,
            updatedAt = System.currentTimeMillis(),
        )
        save(store)
        return true
    }

    /**
     * M4 Task 3: 持久化用户当前激活的 agent（dash form）。
     * 空字符串 → 等价于"使用默认 agentType"，仍然写入。
     */
    @Synchronized
    fun updateActiveAgent(groupId: String, activeAgent: String): Boolean {
        val store = load()
        val idx = store.workflows.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        val old = store.workflows[idx]
        if (old.activeAgent == activeAgent) return false
        store.workflows[idx] = old.copy(
            activeAgent = activeAgent,
            updatedAt = System.currentTimeMillis(),
        )
        save(store)
        logger.info("Workflow {} activeAgent 持久化: {}", old.id, activeAgent)
        return true
    }

    /** 持久化工具权限模式。 */
    @Synchronized
    fun updatePermissionMode(groupId: String, permissionMode: String): Boolean {
        val store = load()
        val idx = store.workflows.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        val old = store.workflows[idx]
        if (old.permissionMode == permissionMode) return false
        store.workflows[idx] = old.copy(
            permissionMode = permissionMode,
            updatedAt = System.currentTimeMillis(),
        )
        save(store)
        logger.info("Workflow {} permissionMode 持久化: {}", old.id, permissionMode)
        return true
    }

    /** workflow.agentType（underscore form）→ runtime agentType（dash form）。 */
    private fun normalizeWfAgentType(wfAgentType: String): String = when (wfAgentType) {
        "claude_code" -> "claude-code"
        else -> wfAgentType
    }

    @Synchronized
    fun deleteWorkflow(workflowId: String, userId: String): Boolean {
        val store = load()
        val removed = store.workflows.removeAll { it.id == workflowId && it.ownerId == userId }
        if (removed) {
            save(store)
            logger.info("Deleted workflow: {}", workflowId)
        }
        return removed
    }
}
