package com.example.trafykamerasikotlin.data.vision.detectors

import com.example.trafykamerasikotlin.data.vision.Detection
import com.example.trafykamerasikotlin.data.vision.Frame

/**
 * Detects road agents (cars, trucks, motorcycles, pedestrians, …) in a single
 * frame. Implementation has ownership of heavy native resources — call
 * [initialize] before first use and [release] on teardown.
 *
 * Serial access only. The VisionPipeline serializes calls via a single
 * inference coroutine.
 */
interface VehicleDetector {
    /** Load model + warm up GPU. Throws on unrecoverable init failure. */
    suspend fun initialize()

    /** Run one inference pass. Empty list if nothing above threshold. */
    suspend fun detect(frame: Frame): List<Detection>

    /** Free native + GPU resources. After this call [detect] must not be invoked. */
    fun release()
}
