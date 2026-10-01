package com.pockethost.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pockethost.app.databinding.ActivityFileManagerBinding
import com.pockethost.app.databinding.ItemFileBinding
import com.pockethost.app.embedded.EmbeddedPaths
import com.pockethost.core.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Р¤Р°Р№Р»РѕРІРёР№ РјРµРЅРµРґР¶РµСЂ: РїСЂР°С†СЋС” Р· РІРЅСѓС‚СЂС–С€РЅСЊРѕСЋ РїР°РїРєРѕСЋ (File API, Р±РµР· РґРѕР·РІРѕР»С–РІ).
 * РЎС‚Р°СЂС– SAF-СЃРµСЂРІРµСЂРё (content://) РІС–РґРєСЂРёРІР°СЋС‚СЊСЃСЏ РІ СЂРµР¶РёРјС– С‡РёС‚Р°РЅРЅСЏ С‡РµСЂРµР· DocumentFile.
 *
 * Р РµР¶РёРј РІРёР±РѕСЂСѓ РїР°РїРєРё (selectMode): РіРѕСЂС‚Р°РЅРЅСЏ СЃРїС–Р»СЊРЅРёРј СЃС…РѕРІРёС‰РµРј + РєРЅРѕРїРєР°
 * "Р’РёР±СЂР°С‚Рё С†СЋ РїР°РїРєСѓ". Р’РёРєРѕСЂРёСЃС‚РѕРІСѓС”С‚СЊСЃСЏ РµРєСЂР°РЅРѕРј СЃС‚РІРѕСЂРµРЅРЅСЏ СЃРµСЂРІРµСЂР° РґР»СЏ
 * РґРѕРІС–Р»СЊРЅРѕРіРѕ СЂРѕР·С‚Р°С€СѓРІР°РЅРЅСЏ (РїРѕС‚СЂС–Р±РµРЅ РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ).
 */
class FileManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECT_MODE = "select_mode"
        const val EXTRA_ROOT_PATH = "root_path"
        const val RESULT_SELECTED_PATH = "selected_path"

        fun createSelectIntent(context: android.content.Context, rootPath: String): android.content.Intent {
            return android.content.Intent(context, FileManagerActivity::class.java).apply {
                putExtra(EXTRA_SELECT_MODE, true)
                putExtra(EXTRA_ROOT_PATH, rootPath)
            }
        }
    }

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var adapter: UnifiedAdapter

    private var currentFile: File? = null
    private var rootFile: File? = null
    private var currentDocUri: Uri? = null
    private var rootDocUri: Uri? = null
    private var useSaf = false
    private var selectMode = false

    // Full unfiltered listing of the current dir; applyFilter() derives the view.
    private var fullItems: List<FileItem> = emptyList()
    private var searchQuery: String = ""
    private var sortMode: String = "Name"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectMode = intent.getBooleanExtra(EXTRA_SELECT_MODE, false)

        val uriStr = intent.getStringExtra("workDirUri")
            ?: intent.getStringExtra("working_directory")
            ?: intent.getStringExtra("path")

        if (uriStr == null && !selectMode) {
            Toast.makeText(this, "РќРµРІС–СЂРЅРёР№ С€Р»СЏС…", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()
        setupSearchSort()
        setupActionRow()

        if (selectMode) {
            openSelectRoot()
            return
        }
        // H8: Р±РµР· !! вЂ” СЏРІРЅРёР№ null-check Р· Toast+return.
        val target = uriStr ?: run {
            Toast.makeText(this, "РќРµРІС–СЂРЅРёР№ С€Р»СЏС…", Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (target.startsWith("content://")) {
            useSaf = true
            currentDocUri = try { Uri.parse(target) } catch (_: Exception) { null }
            rootDocUri = currentDocUri
            val docUri = currentDocUri ?: run {
                Toast.makeText(this, "РќРµРІС–СЂРЅРёР№ SAF С€Р»СЏС…", Toast.LENGTH_LONG).show()
                finish(); return
            }
            loadSafFiles(docUri)
        } else {
            useSaf = false
            val file = resolveFile(target)
            // C5: РЅРµ finish() РѕРґСЂР°Р·Сѓ вЂ” resolveFile РІР¶Рµ РїСЂРѕР±СѓРІР°РІ mkdirs, РґР°С”РјРѕ РґСЂСѓРіРёР№ С€Р°РЅСЃ
            // С‡РµСЂРµР· СЏРІРЅРёР№ mkdirs РїРµСЂРµРґ С‚РёРј СЏРє Р·РґР°С‚РёСЃСЏ.
            val ready = file?.let {
                if (!it.exists()) {
                    android.util.Log.w("FileManager", "onCreate: creating missing dir ${it.absolutePath}")
                    try { it.mkdirs() } catch (_: Exception) {}
                }
                it.exists()
            } == true
            if (!ready) {
                Toast.makeText(this, "РџР°РїРєР° РЅРµ Р·РЅР°Р№РґРµРЅР°: $target", Toast.LENGTH_LONG).show()
                finish(); return
            }
            // H8: Р±РµР· !! вЂ” ready==true РіР°СЂР°РЅС‚СѓС” non-null, Р°Р»Рµ РїРµСЂРµРІС–СЂСЏС”РјРѕ СЏРІРЅРѕ.
            val safeFile = file ?: run {
                Toast.makeText(this, "РџР°РїРєР° РЅРµ Р·РЅР°Р№РґРµРЅР°: $target", Toast.LENGTH_LONG).show()
                finish(); return
            }
            currentFile = safeFile
            rootFile = safeFile
            loadLocalFiles(safeFile)
        }
    }

    /** Р’С…С–Рґ Сѓ СЂРµР¶РёРј РІРёР±РѕСЂСѓ РїР°РїРєРё: РєРѕСЂС–РЅСЊ вЂ” СЃРїС–Р»СЊРЅРµ СЃС…РѕРІРёС‰Рµ. */
    // H4: getExternalStorageDirectory deprecated, Р°Р»Рµ РґР»СЏ selectMode РїРѕС‚СЂС–Р±РµРЅ СЃР°РјРµ
    // legacy shared-root (MediaStore/SAF-РјС–РіСЂР°С†С–СЏ СЃРєР»Р°РґРЅР° вЂ” Р»РёС€Р°С”РјРѕ Р· Suppress).
    // TODO: РјС–РіСЂСѓРІР°С‚Рё РІРёР±С–СЂ РїР°РїРєРё РЅР° SAF (ACTION_OPEN_DOCUMENT_TREE) / MediaStore.
    @Suppress("DEPRECATION")
    private fun openSelectRoot() {
        useSaf = false
        val startPath = intent.getStringExtra(EXTRA_ROOT_PATH)
            ?.takeIf { it.isNotEmpty() }
            ?: try { android.os.Environment.getExternalStorageDirectory().absolutePath } catch (_: Exception) { "/storage/emulated/0" }
        val root = try { File(startPath) } catch (_: Exception) { null }
        if (root == null || !root.isDirectory || !root.canRead()) {
            Toast.makeText(this, "РЎС…РѕРІРёС‰Рµ РЅРµРґРѕСЃС‚СѓРїРЅРµ. РќР°РґР°Р№ РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        currentFile = root
        rootFile = root
        binding.btnSelectFolder.visibility = android.view.View.VISIBLE
        binding.btnSelectFolder.setOnClickListener { confirmSelectFolder() }
        loadLocalFiles(root)
    }

    private fun confirmSelectFolder() {
        val dir = currentFile
        if (dir == null || !dir.isDirectory) {
            Toast.makeText(this, "Р’С–РґРєСЂРёР№ РїРѕС‚СЂС–Р±РЅСѓ РїР°РїРєСѓ", Toast.LENGTH_SHORT).show()
            return
        }
        if (!EmbeddedPaths.isDirWritable(dir)) {
            Toast.makeText(this, "РџР°РїРєР° РЅРµРґРѕСЃС‚СѓРїРЅР° РґР»СЏ Р·Р°РїРёСЃСѓ: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(RESULT_SELECTED_PATH, dir.absolutePath))
        finish()
    }

    private fun resolveFile(uriStr: String): File? {
        // C5: embedded://external:// Р±С–Р»СЊС€Рµ РЅРµ РїРѕРІРµСЂС‚Р°СЋС‚СЊ null РґР»СЏ РЅРµС–СЃРЅСѓСЋС‡РѕС— РїР°РїРєРё вЂ”
        // РїСЂРѕР±СѓС”РјРѕ СЃС‚РІРѕСЂРёС‚Рё С‡РµСЂРµР· lookupServerDir+mkdirs (РіРѕРЅРєР°: СЃРµСЂРІРµСЂ С‰Рµ РЅРµ СЃС‚РІРѕСЂРёРІ РїР°РїРєСѓ,
        // Р° FileManager РІР¶Рµ РІС–РґРєСЂРёРІСЃСЏ). Log.w РєРѕР»Рё СЃС‚РІРѕСЂСЋС”РјРѕ.
        return try {
            when {
                uriStr.startsWith("embedded://") -> {
                    val id = uriStr.removePrefix("embedded://")
                    if (id.isEmpty()) return null
                    val dir = EmbeddedPaths.lookupServerDir(this, id)
                    if (!dir.exists()) {
                        android.util.Log.w("FileManager", "creating missing embedded dir: ${dir.absolutePath}")
                        try { dir.mkdirs() } catch (_: Exception) {}
                    }
                    if (dir.exists()) dir else null
                }
                uriStr.startsWith("external://") -> {
                    val id = uriStr.removePrefix("external://")
                    if (id.isEmpty()) return null
                    val dir = EmbeddedPaths.lookupServerDir(this, id)
                    if (!dir.exists()) {
                        android.util.Log.w("FileManager", "creating missing external dir: ${dir.absolutePath}")
                        try { dir.mkdirs() } catch (_: Exception) {}
                    }
                    if (dir.exists()) dir else null
                }
                uriStr.startsWith("/") -> {
                    val f = File(uriStr)
                    if (!f.exists()) {
                        android.util.Log.w("FileManager", "creating missing abs dir: ${f.absolutePath}")
                        try { f.mkdirs() } catch (_: Exception) {}
                    }
                    if (f.exists()) f else null
                }
                uriStr.startsWith("file://") -> {
                    val p = try { Uri.parse(uriStr).path ?: return null } catch (_: Exception) { return null }
                    val f = File(p)
                    if (!f.exists()) {
                        android.util.Log.w("FileManager", "creating missing file:// dir: ${f.absolutePath}")
                        try { f.mkdirs() } catch (_: Exception) {}
                    }
                    if (f.exists()) f else null
                }
                else -> {
                    // СЃРїСЂРѕР±Р° СЏРє serverId
                    val dir = EmbeddedPaths.lookupServerDir(this, uriStr)
                    if (!dir.exists()) {
                        android.util.Log.w("FileManager", "creating missing serverId dir: ${dir.absolutePath}")
                        try { dir.mkdirs() } catch (_: Exception) {}
                    }
                    if (dir.exists()) dir else null
                }
            }
        } catch (_: Exception) { null }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (selectMode) "Р’РёР±С–СЂ РїР°РїРєРё" else "File Manager (РІР±СѓРґРѕРІР°РЅРёР№)"
    }

    private fun setupRecyclerView() {
        adapter = UnifiedAdapter(
            onClick = { item ->
                if (item.isDirectory) {
                    if (useSaf) item.docUri?.let { loadSafFiles(it) }
                    else item.file?.let { loadLocalFiles(it) }
                } else {
                    openItem(item)
                }
            },
            onLong = { item -> showFileOptions(item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    // ---------- local (File) mode ----------

    private fun setupSearchSort() {
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
        })
        val sorts = listOf("Name", "Date", "Size")
        val sortAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sorts)
        binding.sortInput.setAdapter(sortAdapter)
        binding.sortInput.setOnItemClickListener { _, _, position, _ ->
            sortMode = sorts[position]
            applyFilter()
        }
    }

    private fun setupActionRow() {
        binding.btnNewFile.setOnClickListener { createFileInCurrent() }
        binding.btnNewFolder.setOnClickListener { createFolderInCurrent() }
    }

    /** Client-side search + sort over the real listing. Dirs stay first. */
    private fun applyFilter() {
        try {
            var list = fullItems
            if (searchQuery.isNotEmpty()) {
                list = list.filter { it.name.lowercase().contains(searchQuery) }
            }
            list = when (sortMode) {
                "Date" -> list.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified }))
                "Size" -> list.sortedWith(compareBy({ !it.isDirectory }, { -it.length }))
                else -> list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
            adapter.submitList(list)
        } catch (e: Exception) {
            android.util.Log.e("FileManager", "applyFilter failed", e)
        }
    }

    private fun updateCrumbsAndStorage(dir: File) {
        try {
            val root = rootFile
            val crumbs = if (root != null) {
                val rel = try {
                    dir.canonicalPath.removePrefix(root.canonicalPath).trim { it == '/' }.split('/').joinToString(" вЂє ")
                } catch (_: Exception) { dir.name }
                if (rel.isEmpty()) root.name.ifEmpty { "/" } else "${root.name.ifEmpty { "/" }} вЂє $rel"
            } else {
                dir.absolutePath
            }
            binding.tvCrumbs.text = crumbs
        } catch (_: Exception) {
            binding.tvCrumbs.text = dir.name
        }
        try {
            val stat = android.os.StatFs(dir.absolutePath)
            val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
            val totalGb = stat.totalBytes / 1024.0 / 1024.0 / 1024.0
            binding.tvStorageInfo.text = String.format(
                java.util.Locale.US, "Free %.1f of %.1f GB", freeGb, totalGb
            )
        } catch (_: Exception) {
            binding.tvStorageInfo.text = "вЂ”"
        }
    }

    private fun loadLocalFiles(dir: File) {
        currentFile = dir
        supportActionBar?.subtitle = dir.name.ifEmpty { dir.absolutePath }
        if (selectMode) {
            binding.btnSelectFolder.text = "вњ” Р’РёР±СЂР°С‚Рё: ${dir.absolutePath}"
        }
        val files = try { dir.listFiles()?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val items = files.map {
            FileItem(
                name = it.name,
                isDirectory = it.isDirectory,
                length = if (it.isFile) it.length() else it.listFiles()?.size?.toLong() ?: 0,
                lastModified = it.lastModified(),
                file = it
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(500)
        fullItems = items
        updateCrumbsAndStorage(dir)
        applyFilter()
        if (items.isEmpty()) Toast.makeText(this, "РџР°РїРєР° РїРѕСЂРѕР¶РЅСЏ", Toast.LENGTH_SHORT).show()
    }

    private fun openItem(item: FileItem) {
        try {
            val text: String? = when {
                item.file != null -> try { item.file.takeIf { it.length() < 2 * 1024 * 1024 }?.readText()?.take(20000) } catch (_: Exception) { null }
                item.docUri != null -> contentResolver.openInputStream(item.docUri)?.bufferedReader()?.readText()?.take(20000)
                else -> null
            }
            if (text != null) {
                AlertDialog.Builder(this)
                    .setTitle(item.name)
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("РџРѕРґС–Р»РёС‚РёСЃСЏ") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_SUBJECT, item.name)
                        }
                        startActivity(Intent.createChooser(intent, "РџРѕРґС–Р»РёС‚РёСЃСЏ"))
                    }
                    .show()
            } else {
                Toast.makeText(this, "РќРµ РІРґР°Р»РѕСЃСЏ РїСЂРѕС‡РёС‚Р°С‚Рё ${item.name} (Р±С–РЅР°СЂРЅРёР№ Р°Р±Рѕ РІРµР»РёРєРёР№?)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "РџРѕРјРёР»РєР°: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileOptions(item: FileItem) {
        val options = arrayOf("Р’РёРґР°Р»РёС‚Рё", "РџРµСЂРµР№РјРµРЅСѓРІР°С‚Рё", "РЎС‚РІРѕСЂРёС‚Рё С„Р°Р№Р»", "РЎС‚РІРѕСЂРёС‚Рё РїР°РїРєСѓ", "РџРѕРґС–Р»РёС‚РёСЃСЏ")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmDelete(item)
                    1 -> renameItem(item)
                    2 -> createFileInCurrent()
                    3 -> createFolderInCurrent()
                    4 -> shareItem(item)
                }
            }.show()
    }

    private fun confirmDelete(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("Р’РёРґР°Р»РёС‚Рё ${item.name}?")
            .setPositiveButton("Р’РёРґР°Р»РёС‚Рё") { _, _ ->
                val ok = if (useSaf && item.docUri != null) {
                    try {
                        (DocumentFile.fromSingleUri(this, item.docUri)?.delete() == true)
                    } catch (_: Exception) { false }
                } else {
                    try { item.file?.deleteRecursively() == true } catch (_: Exception) { false }
                }
                if (ok) {
                    Toast.makeText(this, "Р’РёРґР°Р»РµРЅРѕ", Toast.LENGTH_SHORT).show()
                    refresh()
                } else Toast.makeText(this, "РќРµ РІРґР°Р»РѕСЃСЏ РІРёРґР°Р»РёС‚Рё", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("РЎРєР°СЃСѓРІР°С‚Рё", null).show()
    }

    private fun renameItem(item: FileItem) {
        val input = android.widget.EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle("РџРµСЂРµР№РјРµРЅСѓРІР°С‚Рё")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                if (newName.contains("/") || newName.contains(File.separator) || newName == "." || newName == "..") {
                    Toast.makeText(this, "РќРµРґРѕРїСѓСЃС‚РёРјРµ С–РјКјСЏ", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ok = if (useSaf && item.docUri != null) {
                    try {
                        DocumentFile.fromSingleUri(this, item.docUri)?.renameTo(newName) == true
                    } catch (_: Exception) { false }
                } else {
                    try {
                        val f = item.file ?: return@setPositiveButton
                        f.renameTo(File(f.parent, newName))
                    } catch (_: Exception) { false }
                }
                if (ok) refresh() else Toast.makeText(this, "РќРµ РІРґР°Р»РѕСЃСЏ РїРµСЂРµР№РјРµРЅСѓРІР°С‚Рё", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("РЎРєР°СЃСѓРІР°С‚Рё", null).show()
    }

    private fun createFileInCurrent() {
        val input = android.widget.EditText(this).apply { hint = "С–Рј'СЏ С„Р°Р№Р»Сѓ" }
        AlertDialog.Builder(this)
            .setTitle("РЎС‚РІРѕСЂРёС‚Рё С„Р°Р№Р»")
            .setView(input)
            .setPositiveButton("РЎС‚РІРѕСЂРёС‚Рё") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                if (useSaf) {
                    val dir = currentDocUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return@setPositiveButton
                    dir.createFile("text/plain", name)
                    loadSafFiles(dir.uri)
                } else {
                    val dir = currentFile ?: return@setPositiveButton
                    try { File(dir, name).createNewFile() } catch (_: Exception) {}
                    loadLocalFiles(dir)
                }
            }
            .setNegativeButton("РЎРєР°СЃСѓРІР°С‚Рё", null).show()
    }

    private fun createFolderInCurrent() {
        val input = android.widget.EditText(this).apply { hint = "С–Рј'СЏ РїР°РїРєРё" }
        AlertDialog.Builder(this)
            .setTitle("РЎС‚РІРѕСЂРёС‚Рё РїР°РїРєСѓ")
            .setView(input)
            .setPositiveButton("РЎС‚РІРѕСЂРёС‚Рё") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                if (useSaf) {
                    val dir = currentDocUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return@setPositiveButton
                    dir.createDirectory(name)
                    loadSafFiles(dir.uri)
                } else {
                    val dir = currentFile ?: return@setPositiveButton
                    File(dir, name).mkdirs()
                    loadLocalFiles(dir)
                }
            }
            .setNegativeButton("РЎРєР°СЃСѓРІР°С‚Рё", null).show()
    }

    private fun shareItem(item: FileItem) {
        try {
            // Р“Р°СЂРґ РІС–Рґ OOM / TransactionTooLarge: С€Р°СЂРёРјРѕ Р»РёС€Рµ С‚РµРєСЃС‚РѕРІС– С„Р°Р№Р»Рё РґРѕ 200KB.
            if (item.isDirectory) {
                Toast.makeText(this, "РџР°РїРєСѓ РЅРµ РјРѕР¶РЅР° РїРѕРґС–Р»РёС‚РёСЃСЏ С‚РµРєСЃС‚РѕРј", Toast.LENGTH_SHORT).show()
                return
            }
            if (item.file != null && item.file.length() > 200 * 1024) {
                Toast.makeText(this, "Р¤Р°Р№Р» Р·Р°РІРµР»РёРєРёР№ РґР»СЏ С€Р°СЂРёРЅРіСѓ (${item.file.length() / 1024}KB)", Toast.LENGTH_SHORT).show()
                return
            }
            val text: String = if (item.file != null) {
                item.file.takeIf { it.length() < 200 * 1024 }?.readText()?.take(50000) ?: run {
                    Toast.makeText(this, "Р‘С–РЅР°СЂРЅРёР№ Р°Р±Рѕ РІРµР»РёРєРёР№ С„Р°Р№Р»", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                // H8: Р±РµР· !! вЂ” null-check РґР»СЏ docUri Р· СЂР°РЅРЅС–Рј РІРёС…РѕРґРѕРј.
                val docUri = item.docUri ?: run {
                    Toast.makeText(this, "РќРµРјР° URI РґР»СЏ С€Р°СЂРёРЅРіСѓ", Toast.LENGTH_SHORT).show()
                    return
                }
                contentResolver.openInputStream(docUri)?.bufferedReader()?.readText()?.take(50000) ?: return
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, item.name)
            }
            startActivity(Intent.createChooser(intent, "РџРѕРґС–Р»РёС‚РёСЃСЏ"))
        } catch (e: Exception) {
            Toast.makeText(this, "РџРѕРјРёР»РєР° С€Р°СЂРёРЅРіСѓ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        if (useSaf) currentDocUri?.let { loadSafFiles(it) }
        else currentFile?.let { loadLocalFiles(it) }
    }

    // ---------- SAF legacy mode ----------

    private fun loadSafFiles(uri: Uri) {
        currentDocUri = uri
        val doc = DocumentFile.fromTreeUri(this, uri) ?: DocumentFile.fromSingleUri(this, uri)
        if (doc == null) {
            Toast.makeText(this, "РќРµ РІРґР°Р»РѕСЃСЏ РІС–РґРєСЂРёС‚Рё РїР°РїРєСѓ", Toast.LENGTH_SHORT).show()
            return
        }
        supportActionBar?.subtitle = (doc.name ?: uri.toString()) + " [legacy SAF]"
        binding.tvCrumbs.text = (doc.name ?: uri.toString()) + " [legacy SAF]"
        binding.tvStorageInfo.text = "вЂ”"
        val files = try { doc.listFiles().toList() } catch (_: Exception) { emptyList() }
        val items = files.map {
            FileItem(
                name = it.name ?: "unknown",
                isDirectory = it.isDirectory,
                length = it.length(),
                lastModified = it.lastModified(),
                docUri = it.uri
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(500)
        fullItems = items
        applyFilter()
        if (items.isEmpty()) Toast.makeText(this, "РџР°РїРєР° РїРѕСЂРѕР¶РЅСЏ", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        return handleBack()
    }

    private fun handleBack(): Boolean {
        if (useSaf) {
            val cur = currentDocUri ?: return false
            val root = rootDocUri
            if (root != null && cur.toString() == root.toString()) return false
            try {
                val doc = DocumentFile.fromTreeUri(this, cur) ?: return false
                val parent = doc.parentFile
                if (parent != null) {
                    loadSafFiles(parent.uri)
                    return true
                }
                root?.let { loadSafFiles(it); return true }
            } catch (_: Exception) {}
            return false
        } else {
            val cur = currentFile ?: return false
            val root = rootFile ?: return false
            if (cur.absolutePath == root.absolutePath) return false
            val parent = cur.parentFile
            if (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
                loadLocalFiles(parent)
                return true
            }
            loadLocalFiles(root)
            return true
        }
    }

    data class FileItem(
        val name: String,
        val isDirectory: Boolean,
        val length: Long,
        val lastModified: Long,
        val file: File? = null,
        val docUri: Uri? = null
    )

    private class UnifiedAdapter(
        private val onClick: (FileItem) -> Unit,
        private val onLong: (FileItem) -> Unit
    ) : ListAdapter<FileItem, UnifiedAdapter.VH>(Diff()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
        inner class VH(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: FileItem) {
                binding.fileName.text = item.name
                if (item.isDirectory) {
                    binding.fileIcon.text = "D"
                    binding.fileIcon.setTextColor(
                        androidx.core.content.ContextCompat.getColor(binding.root.context, com.pockethost.app.R.color.secondary)
                    )
                    binding.fileSize.text = if (item.file != null) {
                        "${item.file.listFiles()?.size ?: 0} items"
                    } else "${item.length} items"
                } else {
                    val ext = item.name.substringAfterLast('.', "").uppercase().take(3).ifEmpty { "F" }
                    binding.fileIcon.text = ext
                    binding.fileIcon.setTextColor(
                        androidx.core.content.ContextCompat.getColor(binding.root.context, com.pockethost.app.R.color.tertiary)
                    )
                    binding.fileSize.text = FileUtils.formatFileSize(item.length)
                }
                // Real unix-style perms for local files; SAF has no stat вЂ” show dash.
                binding.filePerms.text = if (item.file != null) {
                    try {
                        val f = item.file
                        val r = if (f.canRead()) "r" else "-"
                        val w = if (f.canWrite()) "w" else "-"
                        val x = if (f.canExecute() || f.isDirectory) "x" else "-"
                        "$r$w$x"
                    } catch (_: Exception) { "вЂ”" }
                } else "вЂ”"
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                binding.fileDate.text = fmt.format(java.util.Date(item.lastModified))
                binding.root.setOnClickListener { onClick(item) }
                binding.root.setOnLongClickListener { onLong(item); true }
            }
        }
        class Diff : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem) =
                oldItem.name == newItem.name && oldItem.file?.absolutePath == newItem.file?.absolutePath && oldItem.docUri == newItem.docUri
            override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem) =
                oldItem.lastModified == newItem.lastModified && oldItem.length == newItem.length && oldItem.name == newItem.name
        }
    }
}
