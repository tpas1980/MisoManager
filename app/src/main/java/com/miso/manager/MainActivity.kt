package com.miso.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private data class ColorTheme(
    val id: String,
    val label: String,
    val background: Int,
    val backgroundEnd: Int,
    val glass: Int,
    val glassStrong: Int,
    val stroke: Int,
    val accent: Int,
    val soft: Int,
    val text: Int,
    val muted: Int,
    val isLight: Boolean = true
)

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("miso", MODE_PRIVATE) }
    private val folderStack = mutableListOf<DocumentFile>()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var listGeneration = 0
    private var displayedFiles: List<DocumentFile> = emptyList()
    private var palette = COLOR_THEMES.first()
    private lateinit var pageRoot: LinearLayout
    private lateinit var addButton: FloatingActionButton
    private var sortByName = true
    private var pendingAction: Pair<DocumentFile, Boolean>? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var locationTitle: TextView
    private lateinit var locationSubtitle: TextView
    private lateinit var searchField: EditText
    private lateinit var list: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var chooseButton: MaterialButton
    private lateinit var pasteButton: MaterialButton

    private val selectFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importFiles(uris)
    }

    private val requestLegacyStorage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasStorageAccess()) openMainStorage() else render()
    }

    private val requestAllFilesAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasStorageAccess()) openMainStorage() else render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = COLOR_THEMES.firstOrNull { it.id == prefs.getString(KEY_COLOR_THEME, "sage") } ?: COLOR_THEMES.first()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildInterface()
        applyPalette()
        if (hasStorageAccess()) openMainStorage() else render()
    }

    override fun onResume() {
        super.onResume()
        if (::pageRoot.isInitialized) {
            if (hasStorageAccess() && folderStack.isEmpty()) openMainStorage()
            else if (!hasStorageAccess() && folderStack.isNotEmpty()) {
                folderStack.clear()
                render()
            }
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildInterface() {
        pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        toolbar = MaterialToolbar(this).apply {
            title = "MisoManager"
            setTitleTextColor(palette.text)
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            navigationIcon = null
        }
        toolbar.menu.clear()
        toolbar.menu.add(Menu.NONE, MENU_HOME, 0, "Main storage").apply {
            setIcon(android.R.drawable.ic_menu_myplaces)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(Menu.NONE, MENU_SORT, 1, "Sort").apply {
            setIcon(android.R.drawable.ic_menu_sort_by_size)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(Menu.NONE, MENU_MORE, 2, "More").apply {
            setIcon(android.R.drawable.ic_menu_more)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HOME -> { openMainStorage(); true }
                MENU_SORT -> { toggleSort(); true }
                MENU_MORE -> { showMoreMenu(); true }
                else -> false
            }
        }
        pageRoot.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, dp(60))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(12))
        }
        locationTitle = TextView(this).apply {
            text = "Your files, in one place"
            textSize = 22f
            setTextColor(palette.text)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
        }
        locationSubtitle = TextView(this).apply {
            text = "Main storage is your starting point"
            textSize = 13f
            setTextColor(palette.muted)
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
        }
        header.addView(locationTitle)
        header.addView(locationSubtitle)
        pageRoot.addView(header)

        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }
        searchField = EditText(this).apply {
            hint = "Search this folder"
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(12), 0)
            background = glassDrawable(palette.glassStrong, 18f)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            addTextChangedListener(SimpleTextWatcher { render() })
        }
        searchWrap.addView(searchField, LinearLayout.LayoutParams(0, dp(48), 1f))
        pasteButton = MaterialButton(this).apply {
            text = "Paste"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { pasteIntoCurrentFolder() }
        }
        val pasteParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { leftMargin = dp(8) }
        searchWrap.addView(pasteButton, pasteParams)
        pageRoot.addView(searchWrap)

        val content = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, dp(90))
        }
        content.addView(list, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(20), dp(32), dp(40))
            visibility = View.GONE
        }
        val folderEmoji = TextView(this).apply {
            text = "▱"
            textSize = 50f
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
        }
        emptyTitle = TextView(this).apply {
            textSize = 19f
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setPadding(0, dp(8), 0, dp(6))
        }
        emptyMessage = TextView(this).apply {
            textSize = 14f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        }
        chooseButton = MaterialButton(this).apply {
            text = "Grant storage access"
            isAllCaps = false
            setOnClickListener { requestStorageAccess() }
        }
        emptyState.addView(folderEmoji, LinearLayout.LayoutParams.MATCH_PARENT, dp(66))
        emptyState.addView(emptyTitle)
        emptyState.addView(emptyMessage)
        emptyState.addView(chooseButton)
        emptyState.background = glassDrawable(palette.glassStrong, 28f)
        val emptyParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(20), dp(16), dp(20), dp(28))
        }
        content.addView(emptyState, emptyParams)

        addButton = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            contentDescription = "Create file or folder"
            backgroundTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { showCreateMenu() }
        }
        val fabParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(20), dp(20))
        }
        content.addView(addButton, fabParams)
        pageRoot.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(pageRoot)
    }

    private fun applyPalette() {
        pageRoot.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.background, palette.backgroundEnd)
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.isLight
            isAppearanceLightNavigationBars = palette.isLight
        }
        toolbar.setTitleTextColor(palette.text)
        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.mutate()?.setTint(palette.text)
        }
        locationTitle.setTextColor(palette.text)
        locationSubtitle.setTextColor(palette.muted)
        searchField.setTextColor(palette.text)
        searchField.setHintTextColor(palette.muted)
        searchField.background = glassDrawable(palette.glassStrong, 18f)
        val searchIcon = getDrawable(android.R.drawable.ic_menu_search)?.mutate()?.apply { setTint(palette.muted) }
        searchField.setCompoundDrawablesRelativeWithIntrinsicBounds(searchIcon, null, null, null)
        pasteButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
        pasteButton.setTextColor(if (palette.isLight) Color.WHITE else palette.background)
        emptyState.background = glassDrawable(palette.glassStrong, 28f)
        emptyTitle.setTextColor(palette.text)
        emptyMessage.setTextColor(palette.muted)
        chooseButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
        chooseButton.setTextColor(if (palette.isLight) Color.WHITE else palette.background)
        addButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
        addButton.imageTintList = ColorStateList.valueOf(if (palette.isLight) Color.WHITE else palette.background)
        list.setPadding(dp(14), dp(8), dp(14), dp(90))
        if (currentFolder() != null) list.adapter = FileAdapter(displayedFiles, ::openFile, ::showFileMenu)
    }

    private fun toggleSort() {
        sortByName = !sortByName
        displayedFiles = sortFiles(displayedFiles, sortByName)
        list.adapter = FileAdapter(displayedFiles, ::openFile, ::showFileMenu)
        toast(if (sortByName) "Sorted by name" else "Sorted by latest")
    }

    private fun sortFiles(files: List<DocumentFile>, byName: Boolean): List<DocumentFile> = try {
        if (byName) {
            files.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase(Locale.getDefault()) ?: "" })
        } else {
            files.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenByDescending { it.lastModified() })
        }
    } catch (_: Exception) {
        files
    }

    private fun glassDrawable(fill: Int, radiusDp: Float = 20f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(fill)
        setStroke(dp(1), palette.stroke)
    }

    private fun showThemePicker() {
        val labels = COLOR_THEMES.map { theme ->
            (if (theme.id == palette.id) "✓  " else "     ") + theme.label
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Glass color theme")
            .setSingleChoiceItems(labels, COLOR_THEMES.indexOfFirst { it.id == palette.id }) { dialog, which ->
                palette = COLOR_THEMES[which]
                prefs.edit().putString(KEY_COLOR_THEME, palette.id).apply()
                val selectedLabel = palette.label
                dialog.dismiss()
                pageRoot.post {
                    if (!isFinishing && !isDestroyed) {
                        runCatching { applyPalette() }
                            .onFailure { toast("Couldn't fully apply the theme") }
                    }
                }
                toast("$selectedLabel theme applied")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            readGranted && writeGranted
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                requestAllFilesAccess.launch(appSettings)
            } catch (_: Exception) {
                requestAllFilesAccess.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestLegacyStorage.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }

    private fun openMainStorage() {
        if (!hasStorageAccess()) {
            folderStack.clear()
            render()
            return
        }
        val root = DocumentFile.fromFile(Environment.getExternalStorageDirectory())
        if (!root.exists() || !root.canRead()) {
            folderStack.clear()
            render()
            toast("Internal storage isn't available")
            return
        }
        folderStack.clear()
        folderStack.add(root)
        searchField.setText("")
        render()
    }

    private fun currentFolder(): DocumentFile? = folderStack.lastOrNull()

    private fun render() {
        if (!::list.isInitialized) return
        val folder = currentFolder()
        if (folder == null) {
            listGeneration++
            displayedFiles = emptyList()
            locationTitle.text = "Internal storage"
            locationSubtitle.text = "Your device files, in one place"
            searchField.isEnabled = false
            pasteButton.visibility = View.GONE
            list.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            emptyTitle.text = "Allow access to internal storage"
            emptyMessage.text = "Grant MisoManager storage access to browse your main storage. You won't need to choose or import a folder."
            chooseButton.visibility = View.VISIBLE
            chooseButton.text = "Grant storage access"
            return
        }
        searchField.isEnabled = true
        locationTitle.text = if (folderStack.size == 1) "Internal storage" else folder.name ?: "Files"
        locationSubtitle.text = if (folderStack.size > 1) "${folderStack.size - 1} folder${if (folderStack.size == 2) "" else "s"} deep" else "Main storage on this device"
        pasteButton.visibility = if (pendingAction != null) View.VISIBLE else View.GONE

        val query = searchField.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val sortByNameSnapshot = sortByName
        val generation = ++listGeneration
        list.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        emptyTitle.text = "Loading files…"
        emptyMessage.text = "Reading this location"
        chooseButton.visibility = View.GONE

        ioExecutor.execute {
            val result = runCatching {
                val children = folder.listFiles().filter { child ->
                    query.isBlank() || (child.name ?: "").contains(query, ignoreCase = true)
                }
                if (sortByNameSnapshot) {
                    children.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase(Locale.getDefault()) ?: "" })
                } else {
                    children.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenByDescending { it.lastModified() })
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != listGeneration || currentFolder()?.uri != folder.uri) return@runOnUiThread
                val loadedFiles = result.getOrElse {
                    displayedFiles = emptyList()
                    list.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    emptyTitle.text = "Can't read this location"
                    emptyMessage.text = it.localizedMessage ?: "Check storage access and try again."
                    chooseButton.visibility = View.GONE
                    return@runOnUiThread
                }
                val files = if (sortByNameSnapshot == sortByName) loadedFiles else sortFiles(loadedFiles, sortByName)
                displayedFiles = files
                list.adapter = FileAdapter(files, ::openFile, ::showFileMenu)
                val isEmpty = files.isEmpty()
                list.visibility = if (isEmpty) View.GONE else View.VISIBLE
                emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                chooseButton.visibility = View.GONE
                emptyTitle.text = if (query.isNotEmpty()) "No matching files" else "This folder is empty"
                emptyMessage.text = if (query.isNotEmpty()) "Try another name or clear your search." else "Tap + to create a folder, or use ⋮ → Import files to add files."
            }
        }
    }

    private fun openFile(file: DocumentFile) {
        if (!file.isDirectory && isTextFile(file.name)) {
            showTextEditor(file)
            return
        }
        if (file.isDirectory) {
            if (!file.canRead()) { toast("This folder can't be opened"); return }
            folderStack.add(file)
            searchField.setText("")
            render()
            return
        }
        try {
            val filePath = file.uri.path ?: throw IllegalStateException("File path unavailable")
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(filePath))
            val mime = guessMime(file.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name ?: "file", contentUri)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: Exception) { toast("No app found to open this file") }
    }

    private fun showFileMenu(file: DocumentFile, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(if (isTextFile(file.name) && !file.isDirectory) "Edit text" else "Open")
                .setOnMenuItemClickListener { openFile(file); true }
            menu.add("Copy").setOnMenuItemClickListener { pendingAction = file to false; render(); toast("Ready to copy"); true }
            menu.add("Move").setOnMenuItemClickListener { pendingAction = file to true; render(); toast("Choose a destination folder, then tap Paste"); true }
            menu.add("Rename").setOnMenuItemClickListener { renameDialog(file); true }
            menu.add("Delete").setOnMenuItemClickListener { confirmDelete(file); true }
            show()
        }
    }

    private fun showMoreMenu() {
        PopupMenu(this, toolbar).apply {
            menu.add(if (sortByName) "Sort by latest" else "Sort by name").setOnMenuItemClickListener {
                toggleSort(); true
            }
            menu.add("Refresh").setOnMenuItemClickListener { render(); true }
            menu.add("Glass color theme").setOnMenuItemClickListener { showThemePicker(); true }
            menu.add("Import files").setOnMenuItemClickListener {
                if (currentFolder() == null) requestStorageAccess() else selectFiles.launch(arrayOf("*/*"))
                true
            }
            menu.add("Go to main storage").setOnMenuItemClickListener { openMainStorage(); true }
            menu.add("About MisoManager").setOnMenuItemClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("MisoManager")
                    .setMessage("An open-source Android file manager released under the MIT License.\n\nMisoManager opens your device's main storage after you grant Android's storage-access permission. Text files can be created and edited in the built-in editor.")
                    .setPositiveButton("OK", null).show()
                true
            }
            show()
        }
    }

    private fun createFolderDialog() {
        val parent = currentFolder() ?: run { requestStorageAccess(); return }
        val input = EditText(this).apply { hint = "Folder name"; setSingleLine(true); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        android.app.AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name.contains('/')) toast("Enter a valid folder name")
                else if (parent.findFile(name) != null) toast("A file with that name already exists")
                else if (parent.createDirectory(name) == null) toast("Couldn't create folder") else render()
            }.show()
    }

    private fun showCreateMenu() {
        PopupMenu(this, addButton).apply {
            menu.add("New folder").setOnMenuItemClickListener { createFolderDialog(); true }
            menu.add("New text file").setOnMenuItemClickListener { createTextFileDialog(); true }
            show()
        }
    }

    private fun createTextFileDialog() {
        val parent = currentFolder() ?: run { requestStorageAccess(); return }
        val nameInput = EditText(this).apply {
            hint = "Filename, e.g. notes.txt"
            setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("New text file")
            .setView(nameInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var name = nameInput.text.toString().trim()
                if (name.isBlank() || name.contains('/') || name.contains('\\') || name.endsWith('.')) {
                    toast("Enter a valid filename")
                } else {
                    if (!name.contains('.')) name += ".txt"
                    if (!isTextFile(name)) {
                        toast("Use a text extension such as .txt, .md, or .csv")
                        return@setOnClickListener
                    }
                    if (parent.findFile(name) != null) {
                        toast("A file with that name already exists")
                    } else {
                        dialog.dismiss()
                        showTextEditor(file = null, parent = parent, newName = name)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showTextEditor(file: DocumentFile?, parent: DocumentFile? = null, newName: String? = null) {
        if (file != null && !isTextFile(file.name)) {
            toast("This file type can't be edited as plain text")
            return
        }
        val initialText = try {
            if (file == null) "" else {
                if (file.length() > MAX_TEXT_FILE_BYTES) {
                    toast("Text files larger than 2 MB aren't supported by the editor")
                    return
                }
                val path = file.uri.path ?: throw IllegalStateException("File path unavailable")
                File(path).readText(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            toast("Couldn't read this text file")
            return
        }
        val editor = EditText(this).apply {
            setText(initialText)
            setSelection(text.length)
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            hint = "Write something…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            setSingleLine(false)
            setHorizontallyScrolling(false)
            minLines = 16
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = glassDrawable(palette.glassStrong, 14f)
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(editor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val title = if (file != null) "Edit ${file.name}" else "New $newName"
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll, dp(20), dp(8), dp(20), dp(4))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val target = file ?: run {
                        val targetParent = parent ?: throw IllegalStateException("No destination folder")
                        val targetName = newName ?: throw IllegalStateException("No filename")
                        targetParent.createFile("text/plain", targetName)
                            ?: throw IllegalStateException("Couldn't create the file")
                    }
                    val path = target.uri.path ?: throw IllegalStateException("File path unavailable")
                    val bytes = editor.text.toString().toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_TEXT_FILE_BYTES) {
                        toast("Text files larger than 2 MB aren't supported by the editor")
                        return@setOnClickListener
                    }
                    FileOutputStream(File(path)).use { output -> output.write(bytes) }
                    dialog.dismiss()
                    render()
                    toast("Saved ${target.name ?: "text file"}")
                } catch (_: Exception) {
                    toast("Couldn't save this text file")
                }
            }
        }
        dialog.show()
    }

    private fun isTextFile(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return false
        return extension in setOf(
            "txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "html", "htm",
            "css", "js", "kt", "java", "py", "sh", "yaml", "yml", "properties", "ini", "conf", "c", "h"
        )
    }

    private fun renameDialog(file: DocumentFile) {
        val input = EditText(this).apply { setText(file.name ?: ""); setSingleLine(true); setSelection(text.length); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name.contains('/')) toast("Enter a valid name")
                else if (file.renameTo(name)) render() else toast("Couldn't rename this item")
            }.show()
    }

    private fun confirmDelete(file: DocumentFile) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${file.name ?: "item"}?")
            .setMessage("This permanently deletes the item and its contents.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) render() else toast("Couldn't delete this item")
            }.show()
    }

    private fun pasteIntoCurrentFolder() {
        val destination = currentFolder() ?: return
        val (source, move) = pendingAction ?: return
        if (source.uri == destination.uri || (source.isDirectory && isDescendant(destination, source))) {
            toast("A folder can't be copied into itself")
            return
        }
        try {
            val copied = copyDocument(source, destination)
            if (copied && (!move || source.delete())) {
                pendingAction = null
                render()
                toast(if (move) "Item moved" else "Item copied")
            } else toast("Couldn't complete this operation")
        } catch (_: Exception) { toast("Couldn't copy this item") }
    }

    private fun isDescendant(candidate: DocumentFile, ancestor: DocumentFile): Boolean =
        candidate.uri != ancestor.uri && folderStack.any { it.uri == ancestor.uri }

    private fun copyDocument(source: DocumentFile, destination: DocumentFile): Boolean {
        val name = source.name ?: "Untitled"
        if (source.isDirectory) {
            val target = destination.createDirectory(uniqueName(destination, name)) ?: return false
            return source.listFiles().all { child -> copyDocument(child, target) }
        }
        val target = destination.createFile(guessMime(name), uniqueName(destination, name)) ?: return false
        val sourcePath = source.uri.path ?: return false
        val targetPath = target.uri.path ?: return false
        FileInputStream(File(sourcePath)).use { input ->
            FileOutputStream(File(targetPath)).use { output -> input.copyTo(output) }
        }
        return true
    }

    private fun uniqueName(folder: DocumentFile, original: String): String {
        if (folder.findFile(original) == null) return original
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var n = 1
        while (folder.findFile("$base ($n)$ext") != null) n++
        return "$base ($n)$ext"
    }

    private fun importFiles(uris: List<Uri>) {
        val destination = currentFolder() ?: return
        if (uris.isEmpty()) return
        var imported = 0
        uris.forEach { uri ->
            try {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Imported file"
                val target = destination.createFile(contentResolver.getType(uri) ?: guessMime(name), uniqueName(destination, name))
                    ?: return@forEach
                val targetPath = target.uri.path ?: return@forEach
                contentResolver.openInputStream(uri).use { input ->
                    FileOutputStream(File(targetPath)).use { output ->
                        if (input != null) {
                            input.copyTo(output)
                            imported++
                        } else target.delete()
                    }
                }
            } catch (_: Exception) { /* Continue importing the remaining selected files. */ }
        }
        render()
        toast(if (imported == uris.size) "${imported} file${if (imported == 1) "" else "s"} imported" else "Imported $imported of ${uris.size} files")
    }

    private fun guessMime(name: String?): String {
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    override fun onBackPressed() {
        if (::searchField.isInitialized && searchField.text.isNotEmpty()) {
            searchField.setText("")
        } else if (folderStack.size > 1) {
            folderStack.removeAt(folderStack.lastIndex)
            render()
        } else super.onBackPressed()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class FileAdapter(
        private val files: List<DocumentFile>,
        private val onClick: (DocumentFile) -> Unit,
        private val onMenu: (DocumentFile, View) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.Holder>() {
        inner class Holder(val row: LinearLayout, val icon: TextView, val name: TextView, val details: TextView, val more: TextView) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(10), dp(10))
                minimumHeight = dp(72)
                background = glassDrawable(palette.glass, 20f)
                foreground = android.util.TypedValue().let { tv ->
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    getDrawable(tv.resourceId)
                }
                elevation = dp(2).toFloat()
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
            }
            val icon = TextView(this@MainActivity).apply {
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(palette.accent)
                background = glassDrawable(palette.soft, 15f)
            }
            val iconLp = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) }
            row.addView(icon, iconLp)
            val textBlock = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(this@MainActivity).apply {
                textSize = 15f
                setTextColor(palette.text)
                typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val details = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(palette.muted)
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
            }
            textBlock.addView(name)
            textBlock.addView(details)
            row.addView(textBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val more = TextView(this@MainActivity).apply {
                text = "⋮"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(palette.muted)
                contentDescription = "More options"
            }
            row.addView(more, LinearLayout.LayoutParams(dp(40), dp(48)))
            return Holder(row, icon, name, details, more)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = files[position]
            holder.icon.text = if (file.isDirectory) "▰" else file.name?.substringAfterLast('.', "")?.uppercase()?.take(3)?.ifBlank { "FILE" } ?: "FILE"
            holder.icon.textSize = if (file.isDirectory) 25f else 11f
            holder.name.text = file.name ?: "Unnamed"
            val meta = if (file.isDirectory) "Folder" else "${formatSize(file.length())} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.lastModified()))}"
            holder.details.text = meta
            holder.row.setOnClickListener { onClick(file) }
            holder.row.setOnLongClickListener { onMenu(file, holder.more); true }
            holder.more.setOnClickListener { onMenu(file, holder.more) }
        }
        override fun getItemCount(): Int = files.size
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var index = -1
        do { size /= 1024; index++ } while (size >= 1024 && index < units.lastIndex)
        return String.format(Locale.getDefault(), "%.1f %s", size, units[index])
    }

    private class SimpleTextWatcher(private val change: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = change()
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        private fun color(value: String): Int = Color.parseColor(value)
        private val COLOR_THEMES = listOf(
            ColorTheme("sage", "Sage Green", color("#EAF1E9"), color("#DDE8DC"), color("#D9FFFFFF"), color("#EFFFFFFF"), color("#FFFFFFFF"), color("#476B52"), color("#CFE0CE"), color("#202922"), color("#68766C")),
            ColorTheme("ocean", "Ocean Blue", color("#E7F1F5"), color("#D9E9EF"), color("#D9FFFFFF"), color("#EFFFFFFF"), color("#FFFFFFFF"), color("#367184"), color("#C7E1E8"), color("#1E2D34"), color("#627780")),
            ColorTheme("lavender", "Lavender", color("#F0ECF6"), color("#E4E3F1"), color("#D9FFFFFF"), color("#EFFFFFFF"), color("#FFFFFFFF"), color("#735C91"), color("#DDD3EA"), color("#2B2634"), color("#71697B")),
            ColorTheme("sunset", "Warm Sunset", color("#F7ECE6"), color("#F0E0D2"), color("#D9FFFFFF"), color("#EFFFFFFF"), color("#FFFFFFFF"), color("#A85F48"), color("#F0D5C8"), color("#342823"), color("#796A62")),
            ColorTheme("midnight", "Midnight Glass", color("#111927"), color("#202B3D"), color("#20FFFFFF"), color("#2BFFFFFF"), color("#55FFFFFF"), color("#82D9D1"), color("#354753"), color("#F1F4FB"), color("#B6C1D3"), isLight = false)
        )
        private const val KEY_COLOR_THEME = "color_theme"
        private const val MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024
        private const val MENU_HOME = 101
        private const val MENU_SORT = 102
        private const val MENU_MORE = 103
    }
}
