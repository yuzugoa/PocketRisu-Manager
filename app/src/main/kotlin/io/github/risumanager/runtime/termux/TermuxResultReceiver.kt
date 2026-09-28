package io.github.risumanager.runtime.termux

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: intent.data?.lastPathSegment ?: return
        val bundle = intent.getBundleExtra(TermuxContract.RESULT_BUNDLE) ?: return
        val result = TermuxCommandResult(
            stdout = bundle.getString(TermuxContract.RESULT_STDOUT).orEmpty(),
            stderr = bundle.getString(TermuxContract.RESULT_STDERR).orEmpty(),
            exitCode = bundle.getInt(TermuxContract.RESULT_EXIT_CODE, -1),
            internalError = bundle.getInt(TermuxContract.RESULT_ERR, Activity.RESULT_OK),
            internalErrorMessage = bundle.getString(TermuxContract.RESULT_ERRMSG),
        )
        TermuxResultRouter.complete(requestId, result)
    }

    companion object {
        const val ACTION_RESULT = "io.github.risumanager.action.TERMUX_RESULT"
        const val EXTRA_REQUEST_ID = "io.github.risumanager.extra.REQUEST_ID"
    }
}
