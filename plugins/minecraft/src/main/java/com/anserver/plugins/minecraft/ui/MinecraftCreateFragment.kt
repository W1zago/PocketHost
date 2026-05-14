package com.anserver.plugins.minecraft.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.anserver.plugins.minecraft.MinecraftServerManager
import com.anserver.plugins.minecraft.MinecraftServerSoftware
import com.anserver.plugins.minecraft.databinding.FragmentMinecraftCreateBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MinecraftCreateFragment : Fragment() {

    private var _binding: FragmentMinecraftCreateBinding? = null
    private val binding get() = _binding!!

    private lateinit var minecraftManager: MinecraftServerManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMinecraftCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val baseDir = requireContext().getExternalFilesDir(null)?.absolutePath + "/servers"
        minecraftManager = MinecraftServerManager(baseDir)

        setupViews()
    }

    private fun setupViews() {
        binding.btnCreate.setOnClickListener {
            createMinecraftServer()
        }

        val softwareOptions = MinecraftServerSoftware.values().map { it.name }
        binding.spinnerSoftware.adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            softwareOptions
        )
    }

    private fun createMinecraftServer() {
        val name = binding.editServerName.text.toString()
        val version = binding.editVersion.text.toString()
        val port = binding.editPort.text.toString().toIntOrNull() ?: 25565
        val softwareIndex = binding.spinnerSoftware.selectedItemPosition
        val software = MinecraftServerSoftware.values()[softwareIndex]

        if (name.isEmpty()) {
            Snackbar.make(binding.root, "Server name is required", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (version.isEmpty()) {
            Snackbar.make(binding.root, "Version is required", Snackbar.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCreate.isEnabled = false

        lifecycleScope.launch {
            val server = minecraftManager.createMinecraftServer(name, version, software, port)

            binding.progressBar.visibility = View.GONE
            binding.btnCreate.isEnabled = true

            if (server != null) {
                Snackbar.make(binding.root, "Server created successfully", Snackbar.LENGTH_SHORT).show()
                requireActivity().finish()
            } else {
                Snackbar.make(binding.root, "Failed to create server", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
