package dev.xdvdfs.android

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Tool { Pack, Unpack, Compress }

/** Same ordering as the web app's `WorkflowState`: later steps are shown once reached. */
enum class Phase { SelectInputType, SelectInput, SelectOutput, Running, Finished, Error }

enum class Stage { CreatingFilesystem, PackingImage, Compressing, WaitingForFlush }

/** Everything the UI needs to render one workflow tab. */
data class WfState(
    val phase: Phase,
    val inputIsDir: Boolean? = null,
    val inputUri: Uri? = null,
    val inputName: String? = null,
    val outputUri: Uri? = null,
    val outputName: String? = null,
    val stage: Stage = Stage.CreatingFilesystem,
    val total: Long = 0,
    val done: Long = 0,
    val itemName: String? = null,
    val itemSize: Long = -1,
    val sectTotal: Long = 0,
    val sectDone: Long = 0,
    val error: String? = null,
) {
    fun isAtLeast(other: Phase) = phase >= other
}

class JobRequest(
    val tool: Tool,
    val inputIsDir: Boolean,
    val inputUri: Uri,
    val inputName: String,
    val outputUri: Uri,
    val outputName: String,
)

private fun initialPhase(tool: Tool) = if (tool == Tool.Unpack) Phase.SelectInput else Phase.SelectInputType

/**
 * State of one tab. It lives in [JobRunner] rather than in the activity so a running
 * job (and its progress) survives rotation and activity re-creation.
 */
class WorkflowModel(val tool: Tool) {
    var state by mutableStateOf(WfState(initialPhase(tool), inputIsDir = if (tool == Tool.Unpack) false else null))

    private fun releaseInput() {
        val uri = state.inputUri ?: return
        Saf.release(XisoApp.instance.contentResolver, uri, write = false)
    }

    fun chooseInputType(isDir: Boolean) {
        releaseInput()
        state = WfState(Phase.SelectInput, inputIsDir = isDir)
    }

    fun setInput(uri: Uri, name: String) {
        releaseInput()
        state = WfState(Phase.SelectOutput, inputIsDir = state.inputIsDir, inputUri = uri, inputName = name)
    }

    /** Marks the job as running and clears progress left over from a previous run. */
    fun begin(outputUri: Uri, outputName: String) {
        state = state.copy(
            phase = Phase.Running,
            outputUri = outputUri,
            outputName = outputName,
            stage = Stage.CreatingFilesystem,
            total = 0,
            done = 0,
            itemName = null,
            itemSize = -1,
            sectTotal = 0,
            sectDone = 0,
            error = null,
        )
    }
}

/** User visible strings, matching the wording of the web app. */
object Texts {
    fun title(tool: Tool) = when (tool) {
        Tool.Pack -> "Generating XISO Image"
        Tool.Unpack -> "Unpacking XISO Image"
        Tool.Compress -> "Generating CISO Image"
    }

    fun stage(s: WfState): String = when (s.phase) {
        Phase.Finished -> "Finished"
        Phase.Error -> "Errored"
        else -> when (s.stage) {
            Stage.CreatingFilesystem -> "Creating filesystem"
            Stage.PackingImage -> "Packing image"
            Stage.Compressing -> "Compressing image (this may take a while)"
            Stage.WaitingForFlush -> "Waiting for Android to release output file"
        }
    }

    private fun showsSectors(s: WfState) =
        s.sectTotal > 0 && (s.stage == Stage.Compressing || s.stage == Stage.WaitingForFlush || s.phase == Phase.Finished)

    fun count(tool: Tool, s: WfState): String = when (tool) {
        Tool.Pack -> "Packing ${s.done} of ${s.total} files"
        Tool.Unpack -> "Unpacked ${s.done} / ${s.total} files"
        Tool.Compress ->
            if (showsSectors(s)) "Compressing sector ${s.sectDone} of ${s.sectTotal}"
            else "Packing ${s.done} of ${s.total} files"
    }

    fun item(tool: Tool, s: WfState): String? {
        val name = s.itemName ?: return null
        val verb = if (tool == Tool.Unpack) "Unpacking" else "Packing"
        return if (s.itemSize >= 0) "$verb $name (${s.itemSize} bytes)" else "$verb $name"
    }

    /** 0..100 */
    fun percent(tool: Tool, s: WfState): Int {
        if (s.phase == Phase.Finished) return 100
        val (done, total) =
            if (tool == Tool.Compress && showsSectors(s)) Pair(s.sectDone, s.sectTotal) else Pair(s.done, s.total)
        if (total <= 0) return 0
        return (done * 100 / total).toInt().coerceIn(0, 100)
    }

    fun notificationTitle(tool: Tool) = when (tool) {
        Tool.Pack -> "Packing XISO image"
        Tool.Unpack -> "Unpacking XISO image"
        Tool.Compress -> "Compressing image"
    }

    fun doneMessage(tool: Tool, s: WfState): String = when (tool) {
        Tool.Pack -> "Saved ${s.outputName ?: "the image"}"
        Tool.Unpack -> "Unpacked ${s.total} files to ${s.outputName ?: "the output folder"}"
        Tool.Compress -> "CISO image written to ${s.outputName ?: "the output folder"}"
    }

    fun failure(tool: Tool) = when (tool) {
        Tool.Pack -> "Failed to create image"
        Tool.Unpack -> "Failed to unpack image"
        Tool.Compress -> "Failed to create image"
    }
}
