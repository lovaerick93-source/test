package com.remotectl.agent.core

import android.content.Intent
import android.app.Activity

/**
 * Holds the result of the system screen-capture dialog so both [AgentService]
 * (which receives it via onActivityResult) and [FullAccessService] (which turns
 * it into a MediaProjection) can read it.
 */
object CaptureDataHolder {
    @Volatile var data: Intent? = null
    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
}
