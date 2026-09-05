// HistoryEntry.kt
package com.shafi.deepfakedetector

/**
 * A single entry in the scan history. The media is copied into the app's
 * internal storage so history survives process restarts; [filePath] points
 * at that local copy.
 */
data class HistoryEntry(
    val filePath: String,
    val mode: String,        // "PHOTO" or "VIDEO"
    val label: String,       // "REAL" or "FAKE"
    val confidence: Float,   // 0..100
    val rawScore: Float,     // 0..1 model output
    val message: String,
    val timestamp: Long
)