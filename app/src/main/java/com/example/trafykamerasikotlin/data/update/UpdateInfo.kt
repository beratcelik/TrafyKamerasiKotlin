package com.example.trafykamerasikotlin.data.update

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val fileSize: Long,
    val mandatory: Boolean,
    val releaseNotesEn: String,
    val releaseNotesTr: String,
    val signedAt: String,
)
