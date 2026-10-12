package com.gamch1k.lifeos.wear.ui

import android.content.Intent

/** How the app was opened: by the tiles, the complication, the Capture launcher entry, or the recording notice. */
sealed interface Launch {
    data object Record : Launch

    data object Today : Launch

    data class CompleteTask(val taskId: String, val title: String) : Launch

    companion object {
        const val EXTRA_ACTION = "com.gamch1k.lifeos.wear.ACTION"
        const val EXTRA_TASK_ID = "com.gamch1k.lifeos.wear.TASK_ID"
        const val EXTRA_TASK_TITLE = "com.gamch1k.lifeos.wear.TASK_TITLE"
        const val RECORD = "record"
        const val TODAY = "today"
        const val COMPLETE_TASK = "complete-task"

        /** The activity-alias in the manifest that opens straight into recording. */
        const val CAPTURE_ALIAS = "com.gamch1k.lifeos.wear.ui.CaptureLauncher"

        fun from(intent: Intent?): Launch? {
            if (intent == null) return null
            if (intent.component?.className == CAPTURE_ALIAS) return Record
            return when (intent.getStringExtra(EXTRA_ACTION)) {
                RECORD -> Record
                TODAY -> Today
                COMPLETE_TASK -> intent.getStringExtra(EXTRA_TASK_ID)?.let { CompleteTask(it, intent.getStringExtra(EXTRA_TASK_TITLE) ?: "") }
                else -> null
            }
        }
    }
}
