package dev.kmcp.samples.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Abstraction over runtime permission checks so the MCP tool handlers can be
 * exercised on the JVM with a fake gate.
 */
interface PermissionGate {
    /** Returns true when [permission] is currently granted. */
    fun isGranted(permission: String): Boolean
}

/** [PermissionGate] backed by the Android package manager. */
class AndroidPermissionGate(private val context: Context) : PermissionGate {
    override fun isGranted(permission: String): Boolean = when {
        // POST_NOTIFICATIONS became a runtime permission in API 33. On
        // API 26-32 checkSelfPermission reports it as denied even though
        // posting notifications requires no runtime grant there, which would
        // permanently disable the notification tool on those devices.
        permission == Manifest.permission.POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> true
        else ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
