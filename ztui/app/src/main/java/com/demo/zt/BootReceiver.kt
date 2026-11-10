package com.demo.zt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.demo.zt.adb.AdbKeyMigration

/**
 * 开机自启入口。
 *
 * 只做两件事：
 * 1. 恢复「无线调试」系统开关（免 root，写 `adb_wifi_enabled`）
 * 2. 若「开机自动提权」开着且未超 30 次，启动 [AutoBootService] 去跑载荷
 *
 * ### 为什么不再 startActivity
 * Android 10+ 限制后台启动 Activity，开机广播里 `startActivity()` 会被
 * **静默拒绝**，用户看到的现象就是「开关是开的，但什么都没发生」。
 * 所以改成前台服务在后台直接执行，不需要界面。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        if (!isBoot) return

        Log.i("BootReceiver", "收到开机广播：$action")
        BootTrace.log(context, "收到广播：$action")

        // 0) 升级用户：把开关从 CE 存储搬到 DE 存储。
        //    不做这步，解锁前读到的是空值，会被判成「未启用」。
        runCatching { AutoBoot.migrateToDeviceProtected(context) }
        // CE→DE 复制必须解锁后做，锁屏进来时这里会自己跳过。
        runCatching { AdbKeyMigration.migrate(context) }

        // 1) 先恢复无线调试开关——必须在跑载荷之前，否则没有端口可连
        runCatching { WirelessAdbAutoEnabler.onBoot(context) }
            .onSuccess { r ->
                if (r != null) Log.i("BootReceiver", "自动恢复无线调试：${r.message}")
            }
            .onFailure { Log.e("BootReceiver", "恢复无线调试失败", it) }

        // 2) 自启动提权
        //    解锁前（LOCKED_BOOT_COMPLETED）和解锁后（BOOT_COMPLETED）各会来一次，
        //    只允许起一次服务：否则计数 +2、两个服务抢同一个 /data/local/tmp。
        val firstInThisBoot = AutoBoot.beginSessionIfNeeded(context)
        if (!firstInThisBoot) {
            Log.i("BootReceiver", "本次开机已启动过自动提权，跳过：$action")
            BootTrace.log(context, "本次开机已启动过，跳过：$action")
            return
        }

        if (!AutoBoot.shouldRunOnBootAndConsume(context)) {
            Log.i("BootReceiver", "开机自动提权未启用或已达上限，跳过")
            BootTrace.log(context, "开关未开或已达上限，跳过")
            return
        }

        Log.i("BootReceiver", "启动 AutoBootService 执行提权（解锁与否由服务内部等待）")
        AutoBootService.start(context)
    }
}
