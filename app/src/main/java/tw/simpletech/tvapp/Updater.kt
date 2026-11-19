package tw.simpletech.tvapp

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Runs the channel check + TV guide download. Used by the screen and by the background job. */
object Updater {
    private val running = AtomicBoolean(false)
    val isRunning get() = running.get()

    /** Called (on a background thread) when new data has been saved. */
    @Volatile var onUpdated: (() -> Unit)? = null

    private val CHECK_EVERY = TimeUnit.HOURS.toMillis(20)
    private val GUIDE_EVERY = TimeUnit.HOURS.toMillis(20)

    fun checkDue(ctx: Context): Boolean {
        val last = if (vpnOn(ctx) && Store.channels.isNotEmpty()) Store.lastVpnCheck(ctx) else Store.lastCheck(ctx)
        return System.currentTimeMillis() - last > CHECK_EVERY
    }
    fun guideDue(ctx: Context) = System.currentTimeMillis() - Store.lastEpg(ctx) > GUIDE_EVERY

    /** Full check of all links, then the TV guide. Returns false if one is already running. */
    fun full(ctx: Context, status: (String) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            val app = ctx.applicationContext
            val prev = Store.channels.associateBy { it.key }
            val fresh = Builder.build(Store.customUrls(app), status)
            val vpn = vpnOn(app) && prev.isNotEmpty()      // very first check is always a full one
            Checker.run(fresh, prev, vpn = vpn) { done, total ->
                if (done % 10 == 0 || done == total) status("🔄 檢查連結中 Checking links: $done / $total")
            }
            Store.save(app, fresh.filter { it.status != "dead" }, viaVpn = vpn)
            onUpdated?.invoke()
            guideInner(app, status)
            onUpdated?.invoke()
            return true
        } finally {
            running.set(false)
        }
    }

    /** TV guide only. */
    fun guide(ctx: Context, status: (String) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            guideInner(ctx.applicationContext, status)
            onUpdated?.invoke()
            return true
        } finally {
            running.set(false)
        }
    }

    private fun guideInner(app: Context, status: (String) -> Unit) {
        try { Store.saveEpg(app, Epg.download(Store.channels, status)) } catch (_: Exception) { }
    }

    /** True when a VPN (e.g. Surfshark) is on. Checking then would give wrong results. */
    fun vpnOn(ctx: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT < 23) false else {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Exception) { false }

    /** Whatever is due, quietly. With a VPN on, only rugby is re-tested (see Checker). */
    fun quiet(ctx: Context) {
        try {
            when {
                checkDue(ctx) -> full(ctx) {}
                guideDue(ctx) -> guide(ctx) {}
            }
        } catch (_: Exception) { /* try again next time */ }
    }

    /** Asks Android to run [CheckJob] about every 12 hours when online (survives restarts). */
    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, CheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.HOURS.toMillis(12))
                    .setPersisted(true)
                    .build()
            )
        } catch (_: Exception) { }
    }

    private const val JOB_ID = 1001
}

/** Background daily check, even when the app is not open. */
class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            if (Store.channels.isEmpty()) Store.load(applicationContext)
            if (Store.channels.isNotEmpty()) Updater.quiet(applicationContext)  // first full check happens in the app
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
