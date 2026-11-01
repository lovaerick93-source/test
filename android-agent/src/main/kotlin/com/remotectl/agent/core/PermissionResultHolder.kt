package com.remotectl.agent.core

/**
 * Holds the result of the most recent permission request so [AgentService]
 * (a Service) can receive it after [PermissionActivity] finishes.
 */
object PermissionResultHolder {
    @Volatile var result: Boolean = false
    @Volatile var callback: ((Boolean) -> Unit)? = null
}
