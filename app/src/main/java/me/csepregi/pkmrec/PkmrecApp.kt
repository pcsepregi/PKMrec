package me.csepregi.pkmrec

import android.app.Application
import me.csepregi.pkmrec.diagnostics.ErrorLog

class PkmrecApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
    }
}
