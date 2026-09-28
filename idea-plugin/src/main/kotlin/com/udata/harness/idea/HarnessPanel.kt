package com.udata.harness.idea

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.GridLayout
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.Timer

class HarnessPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val gson = Gson()
    private val settings = project.getService(HarnessSettings::class.java)
    private val urlField = JTextField(settings.state.backendUrl)
    private val userField = JTextField(settings.state.userId)
    private val status = JLabel("连接本机 Solon 服务；后端必须能访问当前项目目录。")
    private val connectButton = JButton("测试连接并打开项目")
    private val form = JPanel(BorderLayout(0, 12))
    private var browser: JBCefBrowser? = null
    private var trustedUrl: String? = null
    private var workspaceId: String? = null
    private var ready = false
    private val bridgeStatus = JLabel("页面加载中…")
    private var bridgeTimer: Timer? = null
    private var disposed = false
    private val attachments = mutableListOf<Map<String, Any>>()
    private val pendingReferences = linkedMapOf<String, String>()
    private val referenceDeliveryTimer = Timer(5000) {
        if (pendingReferences.isNotEmpty() && !disposed) {
            bridgeStatus.text = "引用等待页面接收，请更新后端页面并重新连接"
        }
    }.apply { isRepeats = false }
    private var lastReference: Map<String, Any>? = null
    private val referenceTimer = Timer(80) { publishReference() }.apply { isRepeats = false }

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { scheduleReference() }
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) { scheduleReference() }
        })
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) {
                if (event.editor.project === project) scheduleReference()
            }
        }, this)
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { scheduleReference() }
        }, this)
        form.border = JBUI.Borders.empty(16)
        val fields = JPanel(GridLayout(0, 1, 0, 8))
        fields.add(JLabel("后端地址（本机 HTTP 服务）")); fields.add(urlField)
        fields.add(JLabel("用户标识（与网页使用相同 userId）")); fields.add(userField)
        fields.add(JLabel("项目：${project.basePath ?: "无项目目录"}"))
        fields.add(connectButton)
        form.add(fields, BorderLayout.NORTH)
        form.add(status, BorderLayout.SOUTH)
        connectButton.addActionListener { connect() }
        add(form)
        if (settings.state.userId.isNotBlank()) connect()
    }

    private fun connect() {
        val base: URI
        val user = userField.text.trim()
        try {
            base = URI(urlField.text.trim().trimEnd('/'))
            require(base.scheme == "http" && base.host in listOf("localhost", "127.0.0.1", "[::1]", "::1") &&
                base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null &&
                base.path.orEmpty() in listOf("", "/")) { "首版请使用本机 HTTP 地址，例如 http://127.0.0.1:8080" }
            require(user.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "请输入有效的 userId" }
            require(project.basePath != null) { "请先打开本地项目" }
            require(JBCefApp.isSupported()) { "当前 IDEA 运行环境不支持 JCEF，请使用包含 JCEF 的 JetBrains Runtime" }
        } catch (error: Exception) { status.text = error.message; return }
        connectButton.isEnabled = false
        status.text = "正在连接并注册项目…"
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                val health = request(client, base.resolve("/api/health"), user)
                require(health.get("protocolVersion")?.asInt == 1) { "后端协议不兼容，请更新 UData Harness" }
                val body = "path=${encode(Path.of(project.basePath!!).toRealPath().toString())}&activate=false"
                val workspace = request(client, base.resolve("/api/workspaces"), user, body)
                val id = workspace.get("workspaceId").asString
                val page = "$base/?host=idea&userId=${encode(user)}&workspaceId=${encode(id)}"
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed) {
                        settings.state.backendUrl = base.toString()
                        settings.state.userId = user
                        workspaceId = id
                        showBrowser(page)
                    }
                }
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) { status.text = "连接失败：${error.message}"; connectButton.isEnabled = true }
                }
            }
        }
    }

    private fun request(client: HttpClient, uri: URI, user: String, body: String? = null): JsonObject {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("X-User-Id", user)
        if (body == null) builder.GET() else builder.header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        val result = JsonParser.parseString(response.body()).asJsonObject
        require(!result.has("code") || result.get("code").asInt < 400) {
            result.get("description")?.asString ?: "后端请求失败"
        }
        return if (result.has("data")) result.getAsJsonObject("data") else result
    }

    private fun showBrowser(page: String) {
        releaseBrowser()
        trustedUrl = page
        ready = false
        val view = JBCefBrowser()
        browser = view
        val query = JBCefJSQuery.create(view as JBCefBrowserBase)
        Disposer.register(view, query)
        query.addHandler { raw ->
            if (view.cefBrowser.url != trustedUrl) return@addHandler JBCefJSQuery.Response(null, 403, "Untrusted page")
            val message = try { JsonParser.parseString(raw).asJsonObject }
                catch (_: Exception) { return@addHandler JBCefJSQuery.Response(null, 400, "Invalid bridge request") }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && browser === view && !project.isDisposed && view.cefBrowser.url == trustedUrl) {
                    val id = message.get("requestId")?.asString ?: ""
                    try { respond(id, dispatch(message.get("method").asString, message.getAsJsonObject("params") ?: JsonObject())) }
                    catch (error: Exception) { respond(id, null, error.message ?: "IDEA 操作失败") }
                }
            }
            null
        }
        // JBCefBrowser already registers the underlying CefClient's single handler slots.
        // Register through the wrapper to join its dispatcher rather than being ignored.
        view.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest,
                                        userGesture: Boolean, isRedirect: Boolean): Boolean {
                return frame.isMain && request.url != page
            }
        }, view.cefBrowser)
        view.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
                if (!frame.isMain) return
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && this@HarnessPanel.browser === view) {
                        ready = false
                        bridgeStatus.text = "页面加载中…"
                    }
                }
            }

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain || browser.url != page) return
                if (httpStatusCode !in 200..299) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!disposed && this@HarnessPanel.browser === view) bridgeStatus.text = "页面加载失败：HTTP $httpStatusCode"
                    }
                    return
                }
                val script = HarnessPanel::class.java.getResourceAsStream("/bridge.js")!!.bufferedReader().use { it.readText() }
                    .replace("/*__NATIVE_QUERY__*/", query.inject("JSON.stringify(m)", "function() {}", "rejectQuery"))
                browser.executeJavaScript(script, page, 0)
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && this@HarnessPanel.browser === view && !ready) bridgeStatus.text = "IDEA 桥接连接中…"
                }
            }
        }, view.cefBrowser)
        removeAll()
        val connectionBar = JPanel(BorderLayout())
        connectionBar.border = JBUI.Borders.empty(4, 8)
        bridgeStatus.text = "页面加载中…"
        connectionBar.add(bridgeStatus, BorderLayout.CENTER)
        connectionBar.toolTipText = settings.state.backendUrl
        val reconnect = JButton("连接设置")
        reconnect.addActionListener { showConnectionForm() }
        connectionBar.add(reconnect, BorderLayout.EAST)
        add(connectionBar, BorderLayout.NORTH)
        add(view.component, BorderLayout.CENTER)
        revalidate(); repaint()
        bridgeTimer = Timer(15000) {
            if (!ready && !disposed && browser === view) {
                bridgeStatus.text = "桥接未就绪，请点击连接设置重连"
                Logger.getInstance(HarnessPanel::class.java).warn("UData Harness bridge handshake timed out")
            }
        }.apply { isRepeats = false; start() }
        view.loadURL(page)
    }

    private fun dispatch(method: String, params: JsonObject): Any = when (method) {
        "ready" -> {
            ready = true
            bridgeTimer?.stop()
            bridgeStatus.text = "IDEA 已连接 · ${settings.state.userId}"
            flushAttachments()
            publishReference(true)
            flushReferences()
            mapOf("ready" to true)
        }
        "getReference" -> currentReference() ?: emptyMap<String, Any>()
        "getPendingReferences" -> pendingReferences.map { (id, text) -> mapOf("id" to id, "text" to text) }
        "ackReferences" -> {
            params.getAsJsonArray("ids")?.forEach { pendingReferences.remove(it.asString) }
            if (pendingReferences.isEmpty()) {
                referenceDeliveryTimer.stop()
                bridgeStatus.text = "IDEA 已连接 · ${settings.state.userId}"
            }
            mapOf("received" to true)
        }
        "getSelection", "getCurrentFile" -> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ?: error("请先打开一个文本文件")
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: error("当前文件没有磁盘路径")
            editorContext(project, editor, file, method == "getSelection") ?: error("选区为空或文件不属于当前项目")
        }
        "openFile" -> {
            val path = checkedPath(params.get("path").asString)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("文件不存在")
            OpenFileDescriptor(project, file, ((params.get("line")?.asInt ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
            mapOf("opened" to true)
        }
        "prepareRun" -> {
            val manager = FileDocumentManager.getInstance()
            val documents = manager.unsavedDocuments.filter { document ->
                manager.getFile(document)?.let { isProjectFile(it) } == true
            }
            if (documents.isNotEmpty()) {
                val answer = Messages.showYesNoDialog(project,
                    "智能体读取磁盘文件。发送前保存当前项目的 ${documents.size} 个未保存文件？",
                    "保存并发送", "保存并发送", "取消", Messages.getQuestionIcon())
                require(answer == Messages.YES) { "已取消发送，编辑器内容未保存" }
                documents.forEach { manager.saveDocument(it) }
                require(documents.none { manager.isDocumentUnsaved(it) }) { "文件保存失败，请检查后重试" }
            }
            mapOf("prepared" to true)
        }
        "refreshFiles" -> refreshFiles()
        "settings" -> { showConnectionForm(); mapOf("opened" to true) }
        "openWeb" -> {
            val target = "${settings.state.backendUrl}/?userId=${encode(settings.state.userId)}&workspaceId=${encode(workspaceId ?: "")}" 
            BrowserUtil.browse(target)
            mapOf("opened" to true)
        }
        else -> error("不支持的 IDEA 操作：$method")
    }

    private fun refreshFiles(): Map<String, Any> {
        val manager = FileDocumentManager.getInstance()
        val dirty = manager.unsavedDocuments.filter { manager.getFile(it)?.let(::isProjectFile) == true }
        if (dirty.isNotEmpty()) {
            for (document in dirty) {
                val file = manager.getFile(document) ?: continue
                val path = checkedPath(file.path)
                if (Files.isRegularFile(path) && Files.size(path) <= 2 * 1024 * 1024) {
                    val disk = Files.readString(path, file.charset)
                    if (disk != document.text) {
                        val factory = DiffContentFactory.getInstance()
                        DiffManager.getInstance().showDiff(project, SimpleDiffRequest(file.name,
                            factory.create(project, disk), factory.create(project, document.text), "磁盘内容", "未保存的编辑器内容"))
                        break
                    }
                }
            }
            return mapOf("warning" to "项目有未保存内容，已保留编辑器内容；保存或处理差异后点击同步文件。")
        }
        LocalFileSystem.getInstance().findFileByPath(project.basePath!!)?.refresh(true, true)
        return mapOf("refreshed" to true)
    }

    private fun checkedPath(value: String): Path {
        val root = Path.of(project.basePath!!).toRealPath()
        val path = root.resolve(value).normalize()
        require(path.startsWith(root)) { "文件路径超出当前项目" }
        val real = path.toRealPath()
        require(real.startsWith(root)) { "文件链接超出当前项目" }
        return real
    }

    private fun isProjectFile(file: VirtualFile): Boolean = try { checkedPath(file.path); true } catch (_: Exception) { false }

    fun attach(attachment: Map<String, Any>) {
        attachments.removeAll { it["path"] == attachment["path"] }
        attachments.add(attachment)
        if (ready) flushAttachments()
    }

    private fun scheduleReference() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed && ready) referenceTimer.restart()
        }
    }

    private fun currentReference(): Map<String, Any>? {
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
            ?: manager.selectedFiles.firstOrNull() ?: return null
        return fileReference(project, file, editor)
    }

    private fun publishReference(force: Boolean = false) {
        if (!ready || disposed || project.isDisposed) return
        val reference = currentReference()
        if (force || reference != lastReference) {
            lastReference = reference
            emit("udata:reference", reference)
        }
    }

    fun insertReferences(references: List<String>) {
        references.forEach { pendingReferences[UUID.randomUUID().toString()] = it }
        referenceDeliveryTimer.restart()
        if (ready) flushReferences()
    }

    private fun flushReferences() {
        if (pendingReferences.isEmpty()) return
        emit("udata:references-available", null)
    }

    private fun emit(event: String, detail: Any?) {
        browser?.cefBrowser?.executeJavaScript(
            "window.dispatchEvent(new CustomEvent(${gson.toJson(event)},{detail:${gson.toJson(detail)}}));", trustedUrl, 0)
    }

    private fun flushAttachments() {
        attachments.forEach { item -> browser?.cefBrowser?.executeJavaScript(
            "window.dispatchEvent(new CustomEvent('udata:context',{detail:${gson.toJson(item)}}));", trustedUrl, 0) }
        attachments.clear()
    }

    private fun respond(id: String, result: Any?, error: String? = null) {
        browser?.cefBrowser?.executeJavaScript(
            "window.udataReceive(${gson.toJson(mapOf("requestId" to id, "result" to result, "error" to error))});", trustedUrl, 0)
    }

    private fun releaseBrowser() {
        bridgeTimer?.stop()
        bridgeTimer = null
        ready = false
        referenceTimer.stop()
        referenceDeliveryTimer.stop()
        lastReference = null
        browser?.let { Disposer.dispose(it) }
        browser = null
    }

    private fun showConnectionForm() {
        releaseBrowser()
        removeAll()
        connectButton.isEnabled = true
        status.text = "连接本机 Solon 服务；后端必须能访问当前项目目录。"
        add(form)
        revalidate(); repaint()
    }

    override fun dispose() { disposed = true; releaseBrowser(); attachments.clear(); pendingReferences.clear() }

    companion object {
        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        fun fileReference(project: Project, file: VirtualFile, editor: Editor? = null,
                          wholeFileRange: Boolean = false): Map<String, Any>? = try {
            val root = Path.of(project.basePath ?: error("No project directory")).toRealPath()
            val path = Path.of(file.path).toRealPath()
            if (!path.startsWith(root)) null else {
                val relative = root.relativize(path).toString().replace('\\', '/').ifEmpty { "." }
                val result = mutableMapOf<String, Any>("path" to relative)
                if (editor != null && FileDocumentManager.getInstance().getFile(editor.document) == file &&
                    editor.selectionModel.hasSelection()) {
                    val selection = editor.selectionModel
                    result["startLine"] = editor.document.getLineNumber(selection.selectionStart) + 1
                    result["endLine"] = editor.document.getLineNumber((selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)) + 1
                } else if (wholeFileRange && !file.isDirectory && file.length <= 2 * 1024 * 1024) {
                    FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                        result["startLine"] = 1
                        result["endLine"] = document.lineCount.coerceAtLeast(1)
                    }
                }
                result
            }
        } catch (_: Exception) { null }

        fun editorContext(project: Project, editor: Editor, file: VirtualFile, selectionOnly: Boolean): Map<String, Any>? {
            val root = Path.of(project.basePath ?: return null).toRealPath()
            val path = Path.of(file.path).toRealPath()
            if (!path.startsWith(root)) return null
            val selection = editor.selectionModel
            if (selectionOnly && !selection.hasSelection()) return null
            val content = if (selectionOnly) selection.selectedText ?: return null else editor.document.text
            if (content.length > 200000) {
                Messages.showInfoMessage(project, "上下文最多 200000 个字符，请选择较小的代码范围。", "UData Harness")
                return null
            }
            val start = if (selectionOnly) selection.selectionStart else 0
            val end = if (selectionOnly) (selection.selectionEnd - 1).coerceAtLeast(start) else (editor.document.textLength - 1).coerceAtLeast(0)
            return mapOf("path" to root.relativize(path).toString().replace('\\', '/'),
                "content" to content, "language" to file.fileType.name,
                "startLine" to editor.document.getLineNumber(start) + 1,
                "endLine" to editor.document.getLineNumber(end) + 1)
        }
    }
}
