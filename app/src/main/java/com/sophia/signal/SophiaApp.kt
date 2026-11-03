package com.sophia.signal

import android.app.Application
import com.sophia.signal.alert.Alerts
import com.sophia.signal.data.Prefs
import com.sophia.signal.data.Repository
import com.sophia.signal.data.SophiaDb
import com.sophia.signal.scan.ScanController

class SophiaApp : Application() {
    lateinit var prefs: Prefs
    lateinit var db: SophiaDb
    lateinit var alerts: Alerts
    lateinit var repo: Repository
    lateinit var scanner: ScanController

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = SophiaDb(this)
        alerts = Alerts(this, prefs)
        repo = Repository(db, prefs, alerts)
        scanner = ScanController(this, repo, prefs)
    }
}
