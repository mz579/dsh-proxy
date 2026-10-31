package com.dshproxy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启接收器。
 *
 * 注意：荣耀/华为等 ROM 的「应用启动管理」会拦截第三方 App 的 BOOT_COMPLETED，
 * 此时本接收器根本不会被调用（不是本 App 的问题，是系统策略）。
 * 因此还有两道保险：
 *   1) MainActivity 打开时，若开了自启且代理未运行，自动拉起（见 MainActivity）；
 *   2) App 内提供「去开自启白名单」按钮，引导用户在系统里允许自启。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val store = ProviderStore(context)
        if (store.autoStart) {
            // 真开机是全新状态：清掉「用户手动停止」标记，恢复自启
            ProxyService.userStopped = false
            ProxyService.start(context)
        }
    }
}
