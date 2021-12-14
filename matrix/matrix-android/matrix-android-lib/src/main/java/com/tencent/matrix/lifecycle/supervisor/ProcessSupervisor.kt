package com.tencent.matrix.lifecycle.supervisor

import android.app.Application
import android.content.ComponentName
import android.content.Context.BIND_ABOVE_CLIENT
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.tencent.matrix.lifecycle.*
import com.tencent.matrix.lifecycle.owners.*
import com.tencent.matrix.util.MatrixLog
import com.tencent.matrix.util.MatrixUtil
import com.tencent.matrix.util.safeApply

/**
 * Created by Yves on 2021/9/26
 */
const val LRU_KILL_SUCCESS = 1
const val LRU_KILL_RESCUED = 2
const val LRU_KILL_CANCELED = 3
const val LRU_KILL_NOT_FOUND = 4

/**
 * supervisorProcess:
 *
 * Disabled by default.
 *
 * usage: TODO
 *
 * Created by Yves on 2021/10/22
 */
@Deprecated("TODO: 2021/11/16 we're going to move the config to Manifest next version by configuring matrix extension")
data class SupervisorConfig(
    @Deprecated("")
    val enable: Boolean = false,
    /**
     * If you specify an existing process as Supervisor but don't want to modify the boot order
     * pls set [autoCreate] to false and than it would be init manually by startService when
     * Matrix init in the Supervisor process
     */
    @Deprecated("")
    val autoCreate: Boolean = false,
    @Deprecated("")
    val lruKillerWhiteList: List<String> = emptyList()
)

object ProcessSupervisor : IProcessListener by DispatchReceiver {

    private const val TAG = "Matrix.ProcessSupervisor"

    internal val tag by lazy { "${TAG}_${suffix()}" }

    private fun suffix(): String {
        return if (MatrixUtil.isInMainProcess(application)) {
            "Main"
        } else {
            val split = MatrixUtil.getProcessName(application).split(":").toTypedArray()
            if (split.size > 1) {
                split[1]
            } else {
                "unknown"
            }
        }
    }

    private var application: Application? = null
    internal var config: SupervisorConfig? = null

    val isAppUIForeground: Boolean
        get() = appUIForegroundOwner.active()

    val isAppExplicitBackground: Boolean
        get() = appExplicitBackgroundOwner.active()

    val isAppStagedBackground: Boolean
        get() = appStagedBackgroundOwner.active()

    val isAppDeepBackground: Boolean
        get() = appDeepBackgroundOwner.active()

    val isSupervisor: Boolean by lazy {
        if (application == null) {
            throw IllegalStateException("Matrix NOT initialized yet !!!")
        }

        val serviceInfo =
            application!!.packageManager.getPackageInfo(
                application!!.packageName, PackageManager.GET_SERVICES
            ).services.find {
                it.name == SupervisorService::class.java.name
            }

        return@lazy MatrixUtil.getProcessName(application!!) == serviceInfo?.processName
    }

    @Volatile
    internal var supervisorProxy: ISupervisorProxy? = null

    // @formatter:off
    val appUIForegroundOwner: StatefulOwner = DispatcherStateOwner(ReduceOperators.OR, MatrixProcessLifecycleOwner.startedStateOwner, "StartedStateOwner")
    val appExplicitBackgroundOwner: IBackgroundStatefulOwner = object : DispatcherStateOwner(ReduceOperators.AND, ExplicitBackgroundOwner, "ExplicitBackgroundOwner"), IBackgroundStatefulOwner {}
    val appDeepBackgroundOwner: IBackgroundStatefulOwner = object : DispatcherStateOwner(ReduceOperators.AND, DeepBackgroundOwner, "DeepBackgroundOwner"), IBackgroundStatefulOwner {}
    // @formatter:on

    private class AppStagedBackgroundOwner(
        private val delegate: MultiSourceStatefulOwner = MultiSourceStatefulOwner(
            ReduceOperators.AND,
            appExplicitBackgroundOwner,
            appDeepBackgroundOwner.reverse()
        )
    ) : IBackgroundStatefulOwner, IStatefulOwner by delegate

    val appStagedBackgroundOwner: IBackgroundStatefulOwner = AppStagedBackgroundOwner()

    fun init(app: Application, config: SupervisorConfig?): Boolean {
        if (config == null || !config.enable) {
            MatrixLog.i(TAG, "Supervisor is disabled")
            return false
        }
        this.config = config
        application = app
        if (isSupervisor) {
            initSupervisor(app)
        }
        inCharge(config.autoCreate, app)
        return isSupervisor
    }

    fun backgroundLruKill(killedResult: (result: Int, process: String?, pid: Int) -> Unit) =
        SupervisorService.instance?.backgroundLruKill(killedResult)

    private fun initSupervisor(app: Application) {
        SupervisorService.start(app)
        MatrixLog.i(tag, "initSupervisor")
    }

    /**
     * call by all processes
     */
    private fun inCharge(autoCreate: Boolean, app: Application) {

        val intent = Intent(app, SupervisorService::class.java)

        Log.i(tag, "bind to Supervisor")

        DispatchReceiver.install(app)

        app.bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                DispatchReceiver.uninstallPacemaker()
                supervisorProxy = ISupervisorProxy.Stub.asInterface(service)
                MatrixLog.i(TAG, "on Supervisor Connected $supervisorProxy")
                supervisorProxy?.safeApply(tag) {
                    stateRegister(
                        DispatcherStateOwner.ownersToProcessTokens(
                            app
                        )
                    )
                }
                DispatcherStateOwner.attach(supervisorProxy, application!!)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                MatrixLog.e(tag, "onServiceDisconnected $name")
            }
        }, if (autoCreate) (BIND_AUTO_CREATE.or(BIND_ABOVE_CLIENT)) else BIND_ABOVE_CLIENT)

        MatrixLog.i(tag, "inCharge")
    }
}