package com.richard_salendah.driverantar

import android.app.Application
import com.richard_salendah.driverantar.data.remote.RetrofitClient
import com.richard_salendah.driverantar.utils.SessionManager
import org.conscrypt.Conscrypt
import java.security.Security

class DriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        SessionManager.init(applicationContext)
        RetrofitClient.initWith(applicationContext)
    }
}