package com.example.avideo.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.importing.DedupMatch
import com.avideo.core.importing.DuplicateCandidate
import com.avideo.core.importing.DuplicateChoice
import com.avideo.core.importing.ImportOutcome
import com.avideo.core.importing.ImportRequest
import com.avideo.core.importing.ImportSource
import com.example.avideo.crypto.KeyRepository
import com.example.avideo.crypto.VaultGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 导入的唯一状态机。UI 只读 [state]，所有推进都由这里做。
 *
 * 为什么要分成「预检」和「写入」两段：
 *  - 预检（重复判定）必须**在写盘之前**完成，否则「跳过」会白写一份密文
 *    （spec: duplicate-detection「跳过不在写盘之后发生」）；
 *  - 预检里可能需要问用户（三选一），所以它不能放在服务里 —— 服务没有界面；
 *  - 真正的写盘放在 [ImportService]（前台服务）里，切后台/旋屏都不会中断。
 *
 * 单例是安全的：服务和界面永远在同一个进程里，因此不存在跨进程同步问题。
 */
object ImportController {

    sealed interface State {
        /** 等用户选文件。 */
        object Idle : State

        /** 预检中：可能正在为「长度撞车」的源算哈希。 */
        data class Preparing(val total: Int) : State

        /** 需要用户对一条重复项做决定。 */
        data class NeedsDedup(
            val candidate: DuplicateCandidate,
            /** 还需要问几个（不含当前这个）。 */
            val remaining: Int,
            val total: Int,
        ) : State

        data class Running(val rows: List<Row>) : State
        data class Finished(val rows: List<Row>) : State

        /** 预检阶段就进行不下去（未解锁等）。 */
        data class Rejected(val message: String) : State
    }

    data class Row(val label: String, val state: RowState)

    sealed interface RowState {
        object Pending : RowState
        data class Writing(val bytesRead: Long, val statedSize: Long?) : RowState
        object Hidden : RowState
        data class NotHidden(val detail: String?) : RowState
        data class Skipped(val reason: String) : RowState
        data class Failed(val reason: String) : RowState
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var sources: List<ImportSource> = emptyList()
    private var pending: List<ImportRequest>? = null
    private var queue: MutableList<DuplicateCandidate> = mutableListOf()
    private val choices = mutableMapOf<ImportSource, Pair<DedupMatch, DuplicateChoice>>()
    private var total = 0

    /**
     * 是否有**正在进行**的写盘。导入页被关掉时服务还在跑，此时再点一次导入会覆盖
     * `pending`，两批写入共用同一个状态机 —— 与其让状态错乱，不如直接拒绝第二批。
     */
    @Volatile
    private var busy = false

    /** 用户选完文件后调用。空列表 MUST 什么都不做（spec: import-flow「空选择」）。 */
    fun prepare(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (busy) {
            _state.value = State.Rejected("上一批还在导入，等它完成或重新打开页面")
            return
        }
        val app = context.applicationContext
        appContext = app
        total = uris.size
        choices.clear()
        pending = null

        // 让服务在 Activity 之后仍能读这些 URI（ACTION_OPEN_DOCUMENT 的授权是可持久化的）。
        // 失败不影响本次导入：进程内还有 Activity 临时授予的读权限。
        uris.forEach { uri ->
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        sources = uris.map { DocumentSource(app, it) }
        _state.value = State.Preparing(total)

        scope.launch {
            val masterKey = KeyRepository.getMasterKey()
            if (masterKey == null) {
                _state.value = State.Rejected("未解锁：先解锁再导入")
                reset()
                return@launch
            }
            try {
                val duplicates = VaultGateway.pipeline(app).findDuplicates(masterKey, sources)
                queue = duplicates.toMutableList()
                if (queue.isEmpty()) startWriting() else askNextDedup()
            } catch (e: Exception) {
                // 预检失败不阻断导入：宁可漏判一次重复，也不要让用户导不进来。
                // spec 只要求「命中时先提示」，没要求「预检失败必须中止」。
                queue = mutableListOf()
                startWriting()
            } finally {
                PasswordNormalizer.wipe(masterKey)
            }
        }
    }

    /** 用户对当前重复项做出选择。 */
    fun chooseDuplicate(choice: DuplicateChoice) {
        val current = (_state.value as? State.NeedsDedup)?.candidate ?: return
        choices[current.source] = current.match to choice
        askNextDedup()
    }

    /** 前台服务调用：真正写盘。 */
    fun execute() {
        val context = appContext ?: return
        val requests = pending ?: return
        val masterKey = KeyRepository.getMasterKey()
        if (masterKey == null) {
            _state.value = State.Rejected("未解锁：导入已取消")
            reset()
            return
        }
        busy = true
        val rows = requests.map { Row(it.source.label, RowState.Pending) }.toMutableList()
        _state.value = State.Running(rows)
        try {
            var next = 0
            VaultGateway.pipeline(context).run(
                masterKey = masterKey,
                requests = requests,
                onProgress = { progress ->
                    rows[progress.index] = rows[progress.index].copy(
                        state = RowState.Writing(progress.bytesRead, progress.statedSize),
                    )
                    _state.value = State.Running(rows.toList())
                },
                onOutcome = { outcome ->
                    // 结局按顺序逐个到达，所以计数器就是下标（ImportPipeline.run 保证）。
                    val index = next++
                    rows[index] = rows[index].copy(state = outcome.toRowState())
                    if (outcome is ImportOutcome.Imported && !outcome.hidden) {
                        NotHiddenStore.mark(context, outcome.uuid)
                    }
                    _state.value = State.Running(rows.toList())
                },
            )
            _state.value = State.Finished(rows.toList())
        } catch (e: Exception) {
            for (i in rows.indices) {
                val state = rows[i].state
                if (state == RowState.Pending || state is RowState.Writing) {
                    rows[i] = rows[i].copy(state = RowState.Failed("导入中断（${e.javaClass.simpleName}）"))
                }
            }
            _state.value = State.Finished(rows.toList())
        } finally {
            PasswordNormalizer.wipe(masterKey)
            releaseGrants()
            reset()
            busy = false
        }
    }

    /** 是否有导入正在写入。导出前 MUST 检查（spec: vault-export「导出与导入互斥」）。 */
    fun isRunning(): Boolean = busy

    /** 用户退出导入页时调用：把状态收回 Idle（结果已经显示过了）。 */
    fun finish() {
        _state.value = State.Idle
    }

    private fun startWriting() {
        val context = appContext ?: return
        pending = sources.map { source ->
            choices[source]
                ?.let { (match, choice) -> ImportRequest(source, match, choice) }
                ?: ImportRequest(source)
        }
        _state.value = State.Running(pending.orEmpty().map { Row(it.source.label, RowState.Pending) })
        ImportService.start(context)
    }

    private fun askNextDedup() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            startWriting()
            return
        }
        _state.value = State.NeedsDedup(next, remaining = queue.size, total = total)
    }

    private fun releaseGrants() {
        val context = appContext ?: return
        sources.forEach { source ->
            if (source !is DocumentSource) return@forEach
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    source.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun reset() {
        sources = emptyList()
        pending = null
        choices.clear()
        queue = mutableListOf()
    }

    private fun ImportOutcome.toRowState(): RowState = when (this) {
        is ImportOutcome.Imported -> if (hidden) RowState.Hidden else RowState.NotHidden(cleanupDetail)
        is ImportOutcome.Skipped -> RowState.Skipped(reason)
        is ImportOutcome.Failed -> RowState.Failed(reason)
    }
}
