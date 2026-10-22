package it.mdo.booth.tablet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Controllo aggiornamenti in background: gira anche ad app chiusa e dopo il riavvio, ogni 3 ore circa (serve la rete). */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        UpdateManager(applicationContext, ASSET, BuildConfig.UPDATE_TOKEN).check { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val ASSET = "booth-tablet.apk"
        private const val JOB_ID = 4243

        fun schedule(ctx: Context) {
            runCatching {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (js.allPendingJobs.any { it.id == JOB_ID }) return
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJobService::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(3 * 60 * 60 * 1000L)
                        .setPersisted(true)
                        .build()
                )
            }
        }
    }
}
