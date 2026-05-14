package com.anserver.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.anserver.app.databinding.ActivityFileManagerBinding
import com.anserver.app.ui.adapter.FileAdapter
import com.anserver.native.NativeBridge
import java.io.File

class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var fileAdapter: FileAdapter
    private var currentPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentPath = intent.getStringExtra("path") ?: getExternalFilesDir(null)?.absolutePath ?: ""

        setupToolbar()
        setupRecyclerView()
        loadFiles(currentPath)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "File Manager"
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(
            onFileClick = { file ->
                if (file.isDirectory) {
                    loadFiles(file.absolutePath)
                } else {
                    openFile(file)
                }
            },
            onFileLongClick = { file ->
                showFileOptions(file)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@FileManagerActivity)
            adapter = fileAdapter
        }
    }

    private fun loadFiles(path: String) {
        currentPath = path
        supportActionBar?.subtitle = path

        val dir = File(path)
        val files = dir.listFiles()?.toList() ?: emptyList()
        fileAdapter.submitList(files.sortedWith(compareBy({ !it.isDirectory }, { it.name })))
    }

    private fun openFile(file: File) {
        // Open file editor or viewer
    }

    private fun showFileOptions(file: File) {
        // Show context menu for file operations
    }

    override fun onSupportNavigateUp(): Boolean {
        val parent = File(currentPath).parentFile
        if (parent != null && parent.canRead()) {
            loadFiles(parent.absolutePath)
        } else {
            onBackPressed()
        }
        return true
    }

    override fun onBackPressed() {
        val parent = File(currentPath).parentFile
        if (parent != null && parent.canRead()) {
            loadFiles(parent.absolutePath)
        } else {
            super.onBackPressed()
        }
    }
}
