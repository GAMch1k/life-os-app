package com.gamch1k.lifeos.core.config

/** Where Life OS lives on the internet (behind Cloudflare and Cloudflare Access). */
object LifeOsEndpoints {
    const val HOST = "lifeos.gamch1k.com"

    /** Served by Cloudflare's edge itself, without an Access login. */
    const val TRACE_URL = "https://$HOST/cdn-cgi/trace"

    /** A cheap authenticated read: one task. */
    const val TASKS_PROBE_URL = "https://$HOST/api/tasks/v1/tasks?limit=1"
}
