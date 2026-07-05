package com.rmsoft.launcher.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rmsoft.launcher.ui.EnrollmentActivity

/**
 * RMLauncher runs invisibly (no launcher icon, no Home role). An admin opens the enrollment / status
 * screen by dialing the secret code *#*#767638#*#* (767638 = RMSOFT). The dialer broadcasts
 * SECRET_CODE and we launch [EnrollmentActivity] so the operator can sign in with their @rmsoft.rw
 * account. Mirrors the proven hidden-entry pattern from the old RmsoftMdm agent.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val open = Intent(context, EnrollmentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(open) }
    }
}
