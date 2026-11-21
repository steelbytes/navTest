package com.example.navtest

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager

/** Bound by the Navigation SDK to deliver turn-by-turn NavInfo updates. */
class NavInfoReceivingService : Service() {

    private val turnByTurnManager = TurnByTurnManager.createInstance()
    private var lastNavState: Int? = null

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != TurnByTurnManager.MSG_NAV_INFO) return
            val navInfo = turnByTurnManager.readNavInfoFromBundle(msg.data)
            if (navInfo.navState == lastNavState) return
            lastNavState = navInfo.navState
            AppLog.d(
                "NavInfo state=${navInfo.navState} " +
                    "step=${navInfo.currentStep?.fullInstructionText} " +
                    "distToStep=${navInfo.distanceToCurrentStepMeters}m " +
                    "remainingSteps=${navInfo.remainingSteps.size}"
            )
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
