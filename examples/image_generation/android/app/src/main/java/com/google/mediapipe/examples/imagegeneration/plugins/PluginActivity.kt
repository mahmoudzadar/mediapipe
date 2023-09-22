package com.google.mediapipe.examples.imagegeneration.plugins

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mediapipe.examples.imagegeneration.ImageUtils
import com.google.mediapipe.examples.imagegeneration.R
import com.google.mediapipe.examples.imagegeneration.databinding.ActivityPluginBinding
import kotlinx.coroutines.launch
import java.util.*

class PluginActivity : AppCompatActivity() {
    companion object {
        private const val DEFAULT_DISPLAY_ITERATION = 5
        private const val DEFAULT_ITERATION = 20
        private const val DEFAULT_SEED = 0
        private val DEFAULT_PROMPT = R.string.default_prompt_plugin
        private const val DEFAULT_PLUGIN = 0 // FACE
        private val DEFAULT_DISPLAY_OPTIONS = R.id.radio_final // FINAL
    }

    private lateinit var binding: ActivityPluginBinding
    private val viewModel: PluginViewModel by viewModels()
    private val openGalleryResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    val bitmap = ImageUtils.decodeBitmapFromUri(this, uri)
                    if (bitmap != null) {
                        Log.e("Test", "saving bitmap from gallery")
                        viewModel.updateInputBitmap(cropBitmapToSquare(bitmap))
                    }
                }
            }
        }
    private val openCameraResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val bitmap = result.data?.extras?.get("data") as? Bitmap
                if (bitmap != null) {
                    viewModel.updateInputBitmap(cropBitmapToSquare(bitmap))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPluginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel.createImageGenerationHelper(this)

        // Set up spinner
        ArrayAdapter.createFromResource(
            this, R.array.plugins, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerPlugins.adapter = adapter
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Update UI
                viewModel.uiState.collect { uiState ->
                    binding.llInitializeSection.visibility =
                        if (uiState.initialized) android.view.View.GONE else android.view.View.VISIBLE
                    binding.llGenerateSection.visibility =
                        if (uiState.initialized) android.view.View.VISIBLE else android.view.View.GONE
                    binding.llDisplayIteration.visibility =
                        if (uiState.displayOptions == DisplayOptions.ITERATION) android.view.View.VISIBLE else android.view.View.GONE

                    binding.btnInitialize.isEnabled =
                        (uiState.displayOptions == DisplayOptions.FINAL || (uiState.displayOptions == DisplayOptions.ITERATION && uiState.displayIteration != null)) && !uiState.isInitializing

                    if (uiState.isGenerating) {
                        binding.btnGenerate.isEnabled = false
                        binding.btnGenerate.text = uiState.generatingMessage
                    } else {
                        binding.btnGenerate.text = "Generate"
                        if (uiState.initialized) {
                            binding.btnGenerate.isEnabled =
                                uiState.prompt.isNotEmpty() && uiState.iteration != null && uiState.seed != null && uiState.inputBitmap != null
                        } else {
                            binding.btnGenerate.isEnabled = false
                        }
                    }
                    binding.imgOutput.setImageBitmap(uiState.outputBitmap)
                    binding.imgDisplayInput.setImageBitmap(uiState.inputBitmap)
                    binding.imgConditionImage.setImageBitmap(uiState.conditionBitmap)

                    showError(uiState.error)
                    showGenerationTime(uiState.generateTime)
                    showInitializingTime(uiState.initializedTime)
                }
            }
        }

        handleListener()
        setDefaultValue()
    }

    private fun handleListener() {
        binding.btnInitialize.setOnClickListener {
            viewModel.initializeImageGenerator()
            closeSoftKeyboard()
        }
        binding.btnGenerate.setOnClickListener {
            viewModel.generateImage()
            closeSoftKeyboard()
        }
        binding.btnOpenCamera.setOnClickListener {
            openCamera()
            closeSoftKeyboard()
        }
        binding.btnOpenGallery.setOnClickListener {
            openGallery()
            closeSoftKeyboard()
        }
        binding.btnSeedRandom.setOnClickListener {
            randomSeed()
            closeSoftKeyboard()
        }
        binding.radioDisplayOptions.setOnCheckedChangeListener { group, checkedId ->
            when (checkedId) {
                R.id.radio_iteration -> {
                    viewModel.updateDisplayOptions(DisplayOptions.ITERATION)
                }

                R.id.radio_final -> {
                    viewModel.updateDisplayOptions(DisplayOptions.FINAL)
                }
            }
        }
        binding.spinnerPlugins.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    viewModel.updatePlugin(position)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    // do nothing
                }
            }

        binding.edtDisplayIteration.doOnTextChanged { text, _, _, _ ->
            viewModel.updateDisplayIteration(text.toString().toIntOrNull())
        }
        binding.edtPrompt.doOnTextChanged { text, _, _, _ ->
            viewModel.updatePrompt(text.toString())
        }
        binding.edtIterations.doOnTextChanged { text, _, _, _ ->
            viewModel.updateIteration(text.toString().toIntOrNull())
        }
        binding.edtSeed.doOnTextChanged { text, _, _, _ ->
            viewModel.updateSeed(text.toString().toIntOrNull())
        }
    }

    private fun showError(message: String?) {
        if (message.isNullOrEmpty()) return
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            Log.e("Test", message)
        }
        // prevent showing error message twice
        viewModel.clearError()
    }

    private fun showGenerationTime(time: Long?) {
        if (time == null) return
        runOnUiThread {
            Toast.makeText(
                this,
                "Generation time: ${time / 1000.0} seconds",
                Toast.LENGTH_SHORT
            ).show()
        }
        viewModel.clearGenerationTime()
    }

    private fun showInitializingTime(time: Long?) {
        if (time == null) return
        runOnUiThread {
            Toast.makeText(
                this,
                "Initializing time: ${time / 1000.0} seconds",
                Toast.LENGTH_SHORT
            ).show()
        }
        viewModel.clearInitializedTime()
    }


    private fun closeSoftKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        openGalleryResultLauncher.launch(intent)
    }

    private fun openCamera() {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        openCameraResultLauncher.launch(intent)
    }

    private fun setDefaultValue() {
        with(binding) {
            edtPrompt.setText(getString(DEFAULT_PROMPT))
            edtIterations.setText(DEFAULT_ITERATION.toString())
            edtSeed.setText(DEFAULT_SEED.toString())
            spinnerPlugins.setSelection(DEFAULT_PLUGIN)
            radioDisplayOptions.check(DEFAULT_DISPLAY_OPTIONS)
            edtDisplayIteration.setText(DEFAULT_DISPLAY_ITERATION.toString())
        }
    }

    private fun randomSeed() {
        val random = Random()
        // random seed from 0 to 99
        val seed = random.nextInt(100)
        binding.edtSeed.setText(seed.toString())
    }

    private fun cropBitmapToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val cropSize = if (width > height) height else width
        return Bitmap.createBitmap(bitmap, 0, 0, cropSize, cropSize)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.closeGenerator()
    }
}