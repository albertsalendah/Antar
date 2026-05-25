package com.richard_salendah.driverantar

import android.app.Application
import com.richard_salendah.driverantar.data.remote.RetrofitClient
import com.richard_salendah.driverantar.utils.SessionManager

class DriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionManager.init(applicationContext)
        RetrofitClient.initWith(applicationContext)
    }
}