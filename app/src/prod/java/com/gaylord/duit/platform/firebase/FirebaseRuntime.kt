package com.gaylord.duit.platform.firebase

import android.content.Context

object FirebaseRuntimeFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): FirebaseRuntime = ProductionFirebaseRuntime
}

private val ProductionFirebaseRuntime: FirebaseRuntime =
    UnavailableFirebaseRuntime(
        FirebaseRuntimeUnavailableReason.ProductionConfigurationUnavailable,
    )
