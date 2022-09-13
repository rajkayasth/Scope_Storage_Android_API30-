package com.example.scopestorageexample

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.scopestorageexample.Adapters.InternalStoragePhotoAdapter
import com.example.scopestorageexample.Adapters.SharedPhotoAdapter
import com.example.scopestorageexample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.Exception
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var internalStoragePhotoAdapter: InternalStoragePhotoAdapter
    /**Setting recycler view for shared storage*/
    private lateinit var externalStoragePhotoAdapter: SharedPhotoAdapter

    private var readPermissionGranted = false
    private var writePermissionGranted = false

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        internalStoragePhotoAdapter = InternalStoragePhotoAdapter {
            val isDeletionSuccess = deletePhotoFromInternalStorage(it.name)
            if (isDeletionSuccess) {
                loadPhotoFromInternalStorageIntoRecyclerView()
                Toast.makeText(this, "Photo deleted successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete Photo", Toast.LENGTH_SHORT).show()

            }
        }
        setupInternalStorageRecyclerView()
        loadPhotoFromInternalStorageIntoRecyclerView()

        externalStoragePhotoAdapter = SharedPhotoAdapter {

        }
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ permission ->
            readPermissionGranted = permission[Manifest.permission.READ_EXTERNAL_STORAGE] ?: readPermissionGranted
            writePermissionGranted = permission[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: writePermissionGranted
        }

        updateAndRequestPermission()

        /**Creating Contract for capturing image*/
        val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {
            val isPrivate = binding.switchPrivate.isChecked
            val isSaveSuccessfully = when {
                isPrivate -> savePhotoToInternalStorage(UUID.randomUUID().toString(), it!!)
                writePermissionGranted -> savePhotoToExternalStorage(
                    UUID.randomUUID().toString(),
                    bmp = it!!
                )
                else -> false
            }
            if (isPrivate){
                loadPhotoFromInternalStorageIntoRecyclerView()
            }
            if (isSaveSuccessfully) {

                Toast.makeText(this, "Photo saved successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save Photo", Toast.LENGTH_SHORT).show()

            }
        }

        binding.btnTakePhoto.setOnClickListener {
            takePhoto.launch()
        }
    }

    /**function for requesting and updating permission*/
    private fun updateAndRequestPermission() {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val hasWritePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val minSdk29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        readPermissionGranted = hasReadPermission
        writePermissionGranted = hasWritePermission || minSdk29

        val permissionToRequest = mutableListOf<String>()
        if (!writePermissionGranted) {
            permissionToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (!readPermissionGranted) {
            permissionToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissionToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionToRequest.toTypedArray())
        }
    }

    /**function for save photo in external storage*/
    private fun savePhotoToExternalStorage(displayName: String, bmp: Bitmap): Boolean {
        val imageCollection = sdk29AndUp {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, bmp.width)
            put(MediaStore.Images.Media.HEIGHT, bmp.height)
        }

        return try {
            contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                contentResolver.openOutputStream(uri).use { outputStream ->
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                        throw IOException("Couldn't save Bitmap")
                    }
                }
            } ?: throw IOException("Couldn't save Bitmap")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**function that load photo from internal storage to RecyclerView */
    private fun loadPhotoFromInternalStorageIntoRecyclerView() {
        lifecycleScope.launch {
            val photo = loadPhotoFromInternalStorage()
            internalStoragePhotoAdapter.submitList(photo)
        }
    }

    private fun setupInternalStorageRecyclerView() = binding.rvPrivatePhotos.apply {
        adapter = internalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager(3, RecyclerView.VERTICAL)
    }

    /**function from delete internal storage*/
    private fun deletePhotoFromInternalStorage(filename: String): Boolean {
        return try {
            deleteFile(filename)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**Function for Load photo from internal storage*/
    private suspend fun loadPhotoFromInternalStorage(): List<InternalStoragePhoto> {
        return withContext(Dispatchers.IO) {
            val files = filesDir.listFiles()
            files?.filter {
                it.canRead() && it.isFile && it.name.endsWith(".jpg")
            }?.map {
                val bytes = it.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                InternalStoragePhoto(it.name, bmp)
            } ?: listOf()
        }
    }


    /**function for save photo to internal storage*/
    private fun savePhotoToInternalStorage(filename: String, bmp: Bitmap): Boolean {
        return try {
            openFileOutput("$filename.jpg", MODE_PRIVATE).use { stream ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw IOException("Couldn't save BitMap")
                }
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }
}