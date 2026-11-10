package dev.xdvdfs.android

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GITHUB_URL = "https://github.com/BunBunMikael/XISO-Droid"
private const val ORIGINAL_GITHUB_URL = "https://github.com/antangelo/xdvdfs"

// -- header and information ------------------------------------------------------

@Composable
fun Header(dark: Boolean, onToggleDark: () -> Unit) {
    val bp = LocalBp.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "XISO Droid",
            modifier = Modifier.weight(1f),
            color = bp.text,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Toggle Dark Mode",
            modifier = Modifier.width(72.dp),
            color = bp.text,
            fontSize = 12.sp,
            lineHeight = 14.sp,
        )
        Switch(
            checked = dark,
            onCheckedChange = { onToggleDark() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = bp.primary,
                checkedBorderColor = bp.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = bp.muted.copy(alpha = 0.5f),
                uncheckedBorderColor = bp.muted.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
fun Intro() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Body(
            "This tool packs, extracts, and compresses XISO images from a source folder or image, " +
                "entirely on your device.",
        )
        Body("It is powered by Rust. No data leaves your device during the conversion process.")
        Callout(BpIntent.Warning, title = "Disclaimer") {
            Body(
                "The developers of this tool do not endorse or promote piracy. This tool is intended for use " +
                    "with software you have legal right to use and repack.",
            )
        }
        AboutXiso()
    }
}

/** The "what are XISO files" text of the web page, collapsed by default so the tools stay near the top. */
@Composable
private fun AboutXiso() {
    val bp = LocalBp.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(expanded)
        Spacer(Modifier.width(10.dp))
        Text(
            "What are XISO files, and how are they different from ISOs?",
            color = bp.text,
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Body("An ISO is a simple disc image file. ISOs make no distinction as to what is inside the disc image.")
            Body(
                "In contrast, an XISO file contains data that is of the Xbox DVD Filesystem, or XDVDFS, and " +
                    "thus can be read by the Xbox directly",
            )
            Body(
                "An ISO copy of an official Xbox disc is not in and of itself an XISO. An official Xbox disc " +
                    "contains two parts: a standard DVD video, and potentially a demo, and the XDVDFS partition " +
                    "containing software. To convert this to an XISO, everything but the XDVDFS partition must be " +
                    "stripped.",
            )
        }
    }
}

@Composable
fun Footer() {
    val bp = LocalBp.current
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Version: ${BuildConfig.VERSION_NAME}-${BuildConfig.GIT_SHA}",
            color = bp.text,
            fontSize = 14.sp,
        )
        Text(
            "View on GitHub",
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable { runCatching { uriHandler.openUri(GITHUB_URL) } }
                .padding(top = 12.dp),
            color = bp.primary,
            fontSize = 14.sp,
            textDecoration = TextDecoration.Underline,
        )
        Text(
            "View original project on GitHub",
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable { runCatching { uriHandler.openUri(ORIGINAL_GITHUB_URL) } },
            color = bp.primary,
            fontSize = 14.sp,
            textDecoration = TextDecoration.Underline,
        )
    }
}

// -- tools -----------------------------------------------------------------------

@Composable
fun Tools() {
    var selected by rememberSaveable { mutableStateOf(0) }
    val tools = Tool.values()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BpTabs(selected, listOf("Pack", "Unpack", "Compress")) { selected = it }
        // The tab panels are not animated: a file picker launched from a panel has to
        // stay registered until its result arrives.
        WorkflowPanel(tools[selected])
    }
}

private fun stepIntent(current: Boolean) = if (current) BpIntent.Primary else BpIntent.Success

@Composable
private fun SelectedLine(name: String?) {
    if (name != null) {
        Text(
            "Selected: $name",
            color = LocalBp.current.text,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One of the web app's three workflows (pack, unpack, compress), as a vertical list of steps. */
@Composable
fun WorkflowPanel(tool: Tool) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val model = remember(tool) { JobRunner.model(tool) }
    val s = model.state
    val busy = JobRunner.activeTool != null
    val running = s.phase == Phase.Running
    val isDir = s.inputIsDir == true

    fun pickedInput(uri: Uri, isTree: Boolean) {
        Saf.persist(resolver, uri, write = false)
        model.setInput(uri, Saf.displayName(resolver, uri, isTree))
    }

    fun pickedOutput(uri: Uri, isTree: Boolean) {
        val input = model.state.inputUri ?: return
        Saf.persist(resolver, uri, write = true)
        val request = JobRequest(
            tool = tool,
            inputIsDir = model.state.inputIsDir == true,
            inputUri = input,
            inputName = model.state.inputName ?: "image",
            outputUri = uri,
            outputName = Saf.displayName(resolver, uri, isTree),
        )
        if (!JobRunner.submit(request)) {
            Saf.release(resolver, uri, write = true)
            Toast.makeText(context, "Another job is still running", Toast.LENGTH_LONG).show()
        }
    }

    val pickInputFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedInput(uri, isTree = false)
    }
    val pickInputFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedInput(uri, isTree = true)
    }
    val pickOutputFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedOutput(uri, isTree = true)
    }
    val saveOutputFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) pickedOutput(uri, isTree = false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 1. Input type (the unpacker only takes images)
        if (tool != Tool.Unpack) {
            Callout(stepIntent(s.phase == Phase.SelectInputType)) {
                H5("Select the input source type")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BpButton(
                        "Folder",
                        Modifier.weight(1f),
                        icon = R.drawable.ic_folder,
                        enabled = !busy,
                    ) { model.chooseInputType(true) }
                    BpButton(
                        "ISO Image",
                        Modifier.weight(1f),
                        icon = R.drawable.ic_document,
                        enabled = !busy,
                    ) { model.chooseInputType(false) }
                }
            }
        }

        // 2. Input
        if (tool == Tool.Unpack || s.isAtLeast(Phase.SelectInput)) {
            Callout(stepIntent(s.phase == Phase.SelectInput)) {
                H5(
                    if (tool == Tool.Unpack) {
                        "Select an input ISO image containing Xbox software"
                    } else {
                        "Select an input ${if (isDir) "folder" else "ISO image"} containing Xbox software to pack"
                    },
                )
                BpButton(
                    when {
                        tool == Tool.Unpack -> "Select file"
                        isDir -> "Select folder"
                        else -> "Select ISO image"
                    },
                    icon = if (isDir) R.drawable.ic_folder else R.drawable.ic_document,
                    enabled = !busy,
                ) {
                    if (isDir) pickInputFolder.launch(null) else pickInputFile.launch(arrayOf("*/*"))
                }
                SelectedLine(s.inputName)
            }
        }

        // 3. Output
        if (s.isAtLeast(Phase.SelectOutput)) {
            Callout(stepIntent(s.phase == Phase.SelectOutput)) {
                H5(
                    when (tool) {
                        Tool.Pack -> "Save the output XISO image to a file"
                        Tool.Unpack -> "Select output folder"
                        Tool.Compress -> "Select folder to output CISO parts"
                    },
                )
                BpButton(
                    when (tool) {
                        Tool.Pack -> "Save image"
                        Tool.Unpack -> "Select output folder"
                        Tool.Compress -> "Select output directory"
                    },
                    icon = if (tool == Tool.Pack) R.drawable.ic_document else R.drawable.ic_folder,
                    enabled = !busy,
                ) {
                    if (tool == Tool.Pack) {
                        saveOutputFile.launch(withExtension(s.inputName ?: "image", "xiso.iso", isDir))
                    } else {
                        pickOutputFolder.launch(null)
                    }
                }
                if (s.outputUri != null) SelectedLine(s.outputName)
            }
        }

        // 4. Progress
        if (s.isAtLeast(Phase.Running)) {
            ProgressCallout(tool, s, running)
        }
    }
}

@Composable
private fun ProgressCallout(tool: Tool, s: WfState, running: Boolean) {
    val clipboard = LocalClipboardManager.current
    val intent = when (s.phase) {
        Phase.Finished -> BpIntent.Success
        Phase.Error -> BpIntent.Danger
        else -> BpIntent.Primary
    }

    Callout(intent) {
        H5(Texts.title(tool))
        BpProgressBar(Texts.percent(tool, s) / 100f, animate = running)

        // The unpacker has no separate stages in the web app either.
        if (tool != Tool.Unpack) Body(Texts.stage(s))

        if (s.phase == Phase.Error && s.error != null) {
            ReadOnlyBox(s.error)
            BpButton("Copy error") { clipboard.setText(AnnotatedString(s.error)) }
        } else {
            Body(Texts.count(tool, s))
        }

        if (running) {
            Texts.item(tool, s)?.let {
                Text(
                    it,
                    color = LocalBp.current.text,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            BpButton("Cancel") { JobRunner.cancel() }
        }
    }
}
