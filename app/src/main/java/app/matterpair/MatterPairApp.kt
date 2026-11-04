package app.matterpair

import android.app.Application
import app.matterpair.ble.BleScanner
import app.matterpair.commission.CommissioningSession
import app.matterpair.commission.EnvironmentCheck
import app.matterpair.data.ConfigRepository
import app.matterpair.ha.HaAuth
import app.matterpair.ha.HaConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MatterPairApp : Application() {
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
