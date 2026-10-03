package io.github.th3rumbl3m4t0r.jmail

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Db.init(this)
    }
}
