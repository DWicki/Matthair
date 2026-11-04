package io.github.dwicki.matthair

import android.app.Application
import io.github.dwicki.matthair.ble.BleScanner
import io.github.dwicki.matthair.commission.CommissioningSession
import io.github.dwicki.matthair.commission.EnvironmentCheck
import io.github.dwicki.matthair.data.ConfigRepository
import io.github.dwicki.matthair.ha.HaAuth
import io.github.dwicki.matthair.ha.HaConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MatthairApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Manual dependency container. The app is small enough that a DI framework would add more
 * complexity than it removes. Everything here lives for the whole process.
 */
class AppContainer(app: Application) {
    /** Process-wide scope: commissioning must survive configuration changes and screen-off. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val config = ConfigRepository(app)
    val auth = HaAuth(config)
    val connector = HaConnector(config, auth)
    val scanner = BleScanner(app, scope)
    val session = CommissioningSession(app, scope, connector, scanner)
    val environment = EnvironmentCheck(app, scope, connector, scanner)
}
