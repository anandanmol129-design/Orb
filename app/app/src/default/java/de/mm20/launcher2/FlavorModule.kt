package de.mm20.launcher2

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import de.mm20.launcher2.ui.launcher.wallpaper.depth.SegmenterNotReadyException
import de.mm20.launcher2.ui.launcher.wallpaper.depth.SubjectSegmenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val flavorModule = module {
    single<SubjectSegmenter> { MlKitSubjectSegmenter(androidContext()) }
}

/** On-device subject cutout via ML Kit; its model is an on-demand Play services module. */
private class MlKitSubjectSegmenter(private val context: Context) : SubjectSegmenter {
    override suspend fun cutOut(bitmap: Bitmap): Bitmap {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        try {
            val modules = ModuleInstall.getClient(context)
            val available = runCatching {
                modules.areModulesAvailable(segmenter).await().areModulesAvailable()
            }.getOrDefault(true)
            if (!available) {
                runCatching {
                    modules.installModules(
                        ModuleInstallRequest.newBuilder().addApi(segmenter).build()
                    ).await()
                }
            }
            // The model may still be downloading: retry for a little while before giving up.
            repeat(15) { attempt ->
                try {
                    val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
                    return result.foregroundBitmap
                        ?: throw IllegalStateException("No subject found")
                } catch (e: MlKitException) {
                    if (e.errorCode != MlKitException.UNAVAILABLE) throw e
                    if (attempt == 14) throw SegmenterNotReadyException(e)
                    delay(2000)
                }
            }
            throw SegmenterNotReadyException()
        } finally {
            segmenter.close()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
