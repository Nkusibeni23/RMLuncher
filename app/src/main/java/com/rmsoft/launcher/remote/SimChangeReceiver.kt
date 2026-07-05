package com.rmsoft.launcher.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rmsoft.launcher.utils.SimGuard

/**
 * Fires on any SIM state change. Re-evaluates the SIM against the enrollment baseline via [SimGuard];
 * on a swap it locks the phone and queues an alert, then makes sure the agent is running so that
 * alert reaches the server. Registered in the manifest for android.intent.action.SIM_STATE_CHANGED —
 * a protected broadcast that a privileged system app receives.
 */
class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SimChangeReceiver", "SIM state changed — checking against baseline")
        val swapped = SimGuard.check(context)
        if (swapped) {
            // Ensure the agent is up to deliver the queued SIM_SWAP alert + a location fix.
            AgentService.start(context)
        }
    }
}
