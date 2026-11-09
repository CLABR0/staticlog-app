package com.demo.zt

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「读取应用列表」权限诊断。
 *
 * 症状：权限引导页这一项恒显示未授权，点「重新检查」跳应用详情页也找不到可授权的开关。
 *
 * 这里把 hasAppListAccess() 用到的每个判据逐个打出来，判断到底卡在哪一步。
 */
@RunWith(AndroidJUnit4::class)
class AppListPermissionProbe {

    private val tag = "AppListProbe"

    @Test
    fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = context.packageName

        // 1. checkSelfPermission(QUERY_ALL_PACKAGES)
        val declared = context.packageManager
            .getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
        val hasQueryAll = declared.contains(Manifest.permission.QUERY_ALL_PACKAGES)
        val selfCheck = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES)
            } catch (t: Throwable) {
                -999
            }
        } else {
            -1
        }

        // 2. appops
        val op = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(
                    "android:query_all_packages", Process.myUid(), pkg
                )
        }.getOrNull()

        // 3. getInstalledApplications 数量
        val apps = runCatching { context.packageManager.getInstalledApplications(0) }.getOrNull()
        val appCount = apps?.size ?: -1
        val appNames = apps?.map { it.packageName }?.take(8)

        // 4. /data/app 目录数
        val dataApp = runCatching {
            val groups = File("/data/app").listFiles()
            if (groups == null) -1 else groups.count { it.isDirectory && !it.name.startsWith(".") }
        }.getOrDefault(-2)

        Log.i(
            tag,
            "requestedPermissions.contains(QUERY_ALL_PACKAGES)=$hasQueryAll | " +
                "checkSelfPermission=$selfCheck (0=GRANTED, -1=DENIED) | " +
                "appops=$op (0=default,1=ignore,2=error,3=allow) | " +
                "getInstalledApplications.size=$appCount | " +
                "/data/app groups=$dataApp | " +
                "SDK=${Build.VERSION.SDK_INT} | " +
                "sample=$appNames"
        )
        Log.i(tag, "hasAppListAccess=${com.demo.zt.ui.NecessaryPermissions.hasAppListAccess(context)}")
    }
}
