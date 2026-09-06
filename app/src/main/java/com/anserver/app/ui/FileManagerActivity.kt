package com.anserver.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anserver.app.databinding.ActivityFileManagerBinding
import com.anserver.app.databinding.ItemFileBinding
import com.anserver.app.embedded.EmbeddedPaths
import com.anserver.core.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Файловий менеджер: працює з внутрішньою папкою (File API, без дозволів).
 * Старі SAF-сервери (content://) відкриваються в режимі читання через DocumentFile.
 *
 * Режим вибору папки (selectMode): гортання спільним сховищем + кнопка
 * "Вибрати цю папку". Використовується екраном створення сервера для
 * довільного розташування (потрібен доступ до всіх файлів).
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectMode = intent.getBooleanExtra(EXTRA_SELECT_MODE, false)

        val uriStr = intent.getStringExtra("workDirUri")
            ?: intent.getStringExtra("working_directory")
            ?: intent.getStringExtra("path")

        if (uriStr == null && !selectMode) {
            Toast.makeText(this, "Невірний шлях", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()

        if (selectMode) {
            openSelectRoot()
            return
        }
        // Тут target точно не null: вище або finish+return, або гілка selectMode з return.
        val target = uriStr!!
        if (target.startsWith("content://")) {
            useSaf = true
            currentDocUri = try { Uri.parse(target) } catch (_: Exception) { null }
            rootDocUri = currentDocUri
            if (currentDocUri == null) {
                Toast.makeText(this, "Невірний SAF шлях", Toast.LENGTH_LONG).show()
                finish(); return
            }
            loadSafFiles(currentDocUri!!)
        } else {
            useSaf = false
            val file = resolveFile(target)
            if (file == null || !file.exists()) {
                Toast.makeText(this, "Папка не знайдена: $target", Toast.LENGTH_LONG).show()
                finish(); return
            }
            currentFile = file
            rootFile = file
            loadLocalFiles(file)
        }
    }

    /** Вхід у режим вибору папки: корінь — спільне сховище. */
    private fun openSelectRoot() {
        useSaf = false
        val startPath = intent.getStringExtra(EXTRA_ROOT_PATH)
            ?.takeIf { it.isNotEmpty() }
            ?: try { android.os.Environment.getExternalStorageDirectory().absolutePath } catch (_: Exception) { "/storage/emulated/0" }
        val root = try { File(startPath) } catch (_: Exception) { null }
        if (root == null || !root.isDirectory || !root.canRead()) {
            Toast.makeText(this, "Сховище недоступне. Надай доступ до всіх файлів.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Відкрий потрібну папку", Toast.LENGTH_SHORT).show()
            return
        }
        if (!EmbeddedPaths.isDirWritable(dir)) {
            Toast.makeText(this, "Папка недоступна для запису: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(RESULT_SELECTED_PATH, dir.absolutePath))
        finish()
    }

    private fun resolveFile(uriStr: String): File? {
        // Важливо: лише lookup без mkdirs() щоб сміттєвий ввід не створював папки.
        return try {
            when {
                uriStr.startsWith("embedded://") -> {
                    val id = uriStr.removePrefix("embedded://")
                    if (id.isEmpty()) return null
                    EmbeddedPaths.lookupServerDir(this, id).let { if (it.exists()) it else null }
                }
                uriStr.startsWith("external://") -> {
                    val id = uriStr.removePrefix("external://")
                    if (id.isEmpty()) return null
                    EmbeddedPaths.lookupServerDir(this, id).let { if (it.exists()) it else null }
                }
                uriStr.startsWith("/") -> File(uriStr).let { if (it.exists()) it else null }
                uriStr.startsWith("file://") -> File(Uri.parse(uriStr).path ?: return null).let {
                    if (it.exists()) it else null
                }
                else -> {
                    // спроба як serverId
                    val dir = EmbeddedPaths.lookupServerDir(this, uriStr)
                    if (dir.exists()) dir else null
                }
            }
        } catch (_: Exception) { null }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (selectMode) "Вибір папки" else "File Manager (вбудований)"
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

    private fun loadLocalFiles(dir: File) {
        currentFile = dir
        supportActionBar?.subtitle = dir.name.ifEmpty { dir.absolutePath }
        if (selectMode) {
            binding.btnSelectFolder.text = "✔ Вибрати: ${dir.absolutePath}"
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
        adapter.submitList(items)
        if (items.isEmpty()) Toast.makeText(this, "Папка порожня", Toast.LENGTH_SHORT).show()
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
                    .setNeutralButton("Поділитися") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_SUBJECT, item.name)
                        }
                        startActivity(Intent.createChooser(intent, "Поділитися"))
                    }
                    .show()
            } else {
                Toast.makeText(this, "Не вдалося прочитати ${item.name} (бінарний або великий?)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileOptions(item: FileItem) {
        val options = arrayOf("Видалити", "Перейменувати", "Створити файл", "Створити папку", "Поділитися")
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
            .setTitle("Видалити ${item.name}?")
            .setPositiveButton("Видалити") { _, _ ->
                val ok = if (useSaf && item.docUri != null) {
                    try {
                        (DocumentFile.fromSingleUri(this, item.docUri)?.delete() == true)
                    } catch (_: Exception) { false }
                } else {
                    try { item.file?.deleteRecursively() == true } catch (_: Exception) { false }
                }
                if (ok) {
                    Toast.makeText(this, "Видалено", Toast.LENGTH_SHORT).show()
                    refresh()
                } else Toast.makeText(this, "Не вдалося видалити", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Скасувати", null).show()
    }

    private fun renameItem(item: FileItem) {
        val input = android.widget.EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle("Перейменувати")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                if (newName.contains("/") || newName.contains(File.separator) || newName == "." || newName == "..") {
                    Toast.makeText(this, "Недопустиме імʼя", Toast.LENGTH_SHORT).show()
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
                if (ok) refresh() else Toast.makeText(this, "Не вдалося перейменувати", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Скасувати", null).show()
    }

    private fun createFileInCurrent() {
        val input = android.widget.EditText(this).apply { hint = "ім'я файлу" }
        AlertDialog.Builder(this)
            .setTitle("Створити файл")
            .setView(input)
            .setPositiveButton("Створити") { _, _ ->
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
            .setNegativeButton("Скасувати", null).show()
    }

    private fun createFolderInCurrent() {
        val input = android.widget.EditText(this).apply { hint = "ім'я папки" }
        AlertDialog.Builder(this)
            .setTitle("Створити папку")
            .setView(input)
            .setPositiveButton("Створити") { _, _ ->
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
            .setNegativeButton("Скасувати", null).show()
    }

    private fun shareItem(item: FileItem) {
        try {
            // Гард від OOM / TransactionTooLarge: шаримо лише текстові файли до 200KB.
            if (item.isDirectory) {
                Toast.makeText(this, "Папку не можна поділитися текстом", Toast.LENGTH_SHORT).show()
                return
            }
            if (item.file != null && item.file.length() > 200 * 1024) {
                Toast.makeText(this, "Файл завеликий для шарингу (${item.file.length() / 1024}KB)", Toast.LENGTH_SHORT).show()
                return
            }
            val text: String = if (item.file != null) {
                item.file.takeIf { it.length() < 200 * 1024 }?.readText()?.take(50000) ?: run {
                    Toast.makeText(this, "Бінарний або великий файл", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                contentResolver.openInputStream(item.docUri!!)?.bufferedReader()?.readText()?.take(50000) ?: return
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, item.name)
            }
            startActivity(Intent.createChooser(intent, "Поділитися"))
        } catch (e: Exception) {
            Toast.makeText(this, "Помилка шарингу: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Не вдалося відкрити папку", Toast.LENGTH_SHORT).show()
            return
        }
        supportActionBar?.subtitle = (doc.name ?: uri.toString()) + " [legacy SAF]"
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
        adapter.submitList(items)
        if (items.isEmpty()) Toast.makeText(this, "Папка порожня", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        return handleBack()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
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
                    binding.fileIcon.text = "📁"
                    binding.fileSize.text = if (item.file != null) {
                        "${item.file.listFiles()?.size ?: 0} items"
                    } else "${item.length} items"
                } else {
                    binding.fileIcon.text = "📄"
                    binding.fileSize.text = FileUtils.formatFileSize(item.length)
                }
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
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
