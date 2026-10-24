package de.velwebsolutions.oasis.network.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import de.velwebsolutions.oasis.network.R
import java.io.File

/** Shares photos taken for an upload with the camera app (a class of its own, so it never clashes with other modules). */
class CameraFileProvider : FileProvider(R.xml.network_camera_paths)

/** Opens the system photo picker and, where the device has one, the camera. */
class ImagePicker(
    /** Opens the Android photo picker (no storage permission needed). */
    val pick: () -> Unit,
    /** Takes a photo with the camera app; null without a camera. */
    val takePhoto: (() -> Unit)?
)

/**
 * The Android photo picker (`PickVisualMedia`, images only, up to [maxItems]) and the camera (`TakePicture` into the
 * cache). [onPicked] gets the chosen images in order; the caller converts them with [de.velwebsolutions.oasis.network.ui.ImageConverter].
 */
@Composable
fun rememberImagePicker(maxItems: Int = 1, onPicked: (List<Uri>) -> Unit): ImagePicker {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) currentOnPicked(listOf(uri))
    }
    val multipleContract = remember(maxItems) { ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, maxItems)) }
    val multiple = rememberLauncherForActivityResult(multipleContract) { uris ->
        if (uris.isNotEmpty()) currentOnPicked(uris.take(maxItems))
    }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = photoUri
        if (saved && uri != null) currentOnPicked(listOf(Uri.parse(uri)))
        photoUri = null
    }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    return ImagePicker(
        pick = { runCatching { if (maxItems > 1) multiple.launch(request) else single.launch(request) } },
        takePhoto = if (hasCamera) {
            {
                runCatching {
                    val uri = cameraUri(context)
                    photoUri = uri.toString()
                    camera.launch(uri)
                }
            }
        } else {
            null
        }
    )
}

/** A fresh file in the cache for the camera app to write to. */
private fun cameraUri(context: Context): Uri {
    val folder = File(context.cacheDir, "network-camera").apply { mkdirs() }
    folder.listFiles()?.forEach { it.delete() }
    val file = File(folder, "foto-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.network.camera", file)
}
