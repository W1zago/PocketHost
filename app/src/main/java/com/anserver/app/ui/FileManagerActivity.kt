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
import com.anserver.core.util.FileUtils
import java.text.SimpleDateFormat
import java.util.Locale

class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var adapter: DocAdapter
    private var currentUri: Uri? = null
    private var rootUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra("workDirUri")
            ?: intent.getStringExtra("working_directory")
            ?: intent.getStringExtra("path")
        currentUri = uriStr?.let { try { Uri.parse(it) } catch (_: Exception) { null } }
        rootUri = currentUri

        if (currentUri == null) {
            Toast.makeText(this, "Невірний шлях SAF", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupToolbar()
        setupRecyclerView()
        loadFiles(currentUri!!)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "File Manager"
    }

    private fun setupRecyclerView() {
        adapter = DocAdapter(
            onClick = { doc ->
                if (doc.isDirectory) {
                    loadFiles(doc.uri)
                } else {
                    openFile(doc)
                }
            },
            onLong = { doc -> showFileOptions(doc) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadFiles(uri: Uri) {
        currentUri = uri
        val doc = DocumentFile.fromTreeUri(this, uri) ?: DocumentFile.fromSingleUri(this, uri)
        if (doc == null) {
            Toast.makeText(this, "Не вдалося відкрити папку", Toast.LENGTH_SHORT).show()
            return
        }
        supportActionBar?.subtitle = doc.name ?: uri.toString()
        val files = try {
            doc.listFiles().toList()
        } catch (_: Exception) { emptyList() }
        val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() })).take(500)
        adapter.submitList(sorted)
        if (sorted.isEmpty()) {
            Toast.makeText(this, "Папка порожня", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(file: DocumentFile) {
        try {
            val text = contentResolver.openInputStream(file.uri)?.bufferedReader()?.readText()?.take(20000)
            if (text != null) {
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Поділитися") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_SUBJECT, file.name)
                        }
                        startActivity(Intent.createChooser(intent, "Поділитися"))
                    }
                    .show()
            } else {
                Toast.makeText(this, "Не вдалося прочитати ${file.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileOptions(file: DocumentFile) {
        val options = arrayOf("Видалити", "Перейменувати", "Створити файл", "Створити папку", "Поділитися")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmDelete(file)
                    1 -> renameFile(file)
                    2 -> createFileInCurrent()
                    3 -> createFolderInCurrent()
                    4 -> shareFile(file)
                }
            }.show()
    }

    private fun confirmDelete(file: DocumentFile) {
        AlertDialog.Builder(this)
            .setTitle("Видалити ${file.name}?")
            .setPositiveButton("Видалити") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Видалено", Toast.LENGTH_SHORT).show()
                    currentUri?.let { loadFiles(it) }
                } else Toast.makeText(this, "Не вдалося видалити", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Скасувати", null).show()
    }

    private fun renameFile(file: DocumentFile) {
        val input = android.widget.EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this)
            .setTitle("Перейменувати")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && file.renameTo(newName)) {
                    currentUri?.let { loadFiles(it) }
                } else Toast.makeText(this, "Не вдалося перейменувати", Toast.LENGTH_SHORT).show()
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
                val dir = currentUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return@setPositiveButton
                dir.createFile("text/plain", name)
                loadFiles(dir.uri)
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
                val dir = currentUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return@setPositiveButton
                dir.createDirectory(name)
                loadFiles(dir.uri)
            }
            .setNegativeButton("Скасувати", null).show()
    }

    private fun shareFile(file: DocumentFile) {
        try {
            val text = contentResolver.openInputStream(file.uri)?.bufferedReader()?.readText() ?: return
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
            }
            startActivity(Intent.createChooser(intent, "Поділитися"))
        } catch (_: Exception) {}
    }

    override fun onSupportNavigateUp(): Boolean {
        return handleBack()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    private fun handleBack(): Boolean {
        val cur = currentUri ?: return false
        val root = rootUri
        if (root != null && cur.toString() == root.toString()) return false
        // Try parent
        try {
            val doc = DocumentFile.fromTreeUri(this, cur) ?: return false
            val parent = doc.parentFile
            if (parent != null) {
                loadFiles(parent.uri)
                return true
            }
            // Fallback to root
            root?.let { loadFiles(it); return true }
        } catch (_: Exception) {}
        return false
    }

    private class DocAdapter(
        private val onClick: (DocumentFile) -> Unit,
        private val onLong: (DocumentFile) -> Unit
    ) : ListAdapter<DocumentFile, DocAdapter.VH>(Diff()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
        inner class VH(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(file: DocumentFile) {
                binding.fileName.text = file.name ?: "unknown"
                if (file.isDirectory) {
                    binding.fileIcon.text = "📁"
                    binding.fileSize.text = "${file.listFiles().size} items"
                } else {
                    binding.fileIcon.text = "📄"
                    binding.fileSize.text = FileUtils.formatFileSize(file.length())
                }
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                binding.fileDate.text = fmt.format(java.util.Date(file.lastModified()))
                binding.root.setOnClickListener { onClick(file) }
                binding.root.setOnLongClickListener { onLong(file); true }
            }
        }
        class Diff : DiffUtil.ItemCallback<DocumentFile>() {
            override fun areItemsTheSame(oldItem: DocumentFile, newItem: DocumentFile) = oldItem.uri == newItem.uri
            override fun areContentsTheSame(oldItem: DocumentFile, newItem: DocumentFile) =
                oldItem.lastModified() == newItem.lastModified() && oldItem.length() == newItem.length() && oldItem.name == newItem.name
        }
    }
}
