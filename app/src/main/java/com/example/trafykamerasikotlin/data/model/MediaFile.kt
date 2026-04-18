package com.example.trafykamerasikotlin.data.model

/**
 * Represents a single file (video or photo) on the dashcam's SD card.
 *
 * @param path        SD-card path as returned by getfilelist.cgi, e.g. "/sd/Video/normal/2024_01_15_120000.mp4"
 * @param httpUrl     Full HTTP URL for streaming/download, e.g. "http://192.168.0.1/sd/Video/normal/..."
 * @param thumbnailUrl HTTP URL of the .thm thumbnail for videos, or same as httpUrl for photos
 * @param name        Bare filename, e.g. "2024_01_15_120000.mp4"
 * @param isPhoto     True if this is a still image rather than a video
 */
data class MediaFile(
    val path: String,
    val httpUrl: String,
    val thumbnailUrl: String,
    val name: String,
    val isPhoto: Boolean,
    // Known file size in bytes when the protocol reports it (Allwinner getvideos).
    // Null for chipsets that don't expose size in their listing; UI falls back to
    // indeterminate progress.
    val sizeBytes: Long? = null,
)
