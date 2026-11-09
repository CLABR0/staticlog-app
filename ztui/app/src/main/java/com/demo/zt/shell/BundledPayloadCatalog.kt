package com.demo.zt.shell

import android.content.Context

/**
 * 内置载荷清单。每一项都必须能真正跑通「推送 + 执行」，
 * 因此这里只提供 [BundledPayload]，实际推送与执行统一走 [PayloadRunner]。
 *
 * `library` 字段直接使用 assets 下的目录名（7-16g / 7-12g / 6-16g / 6-12g），
 * 这样界面选中的条目可以一对一映射回 [com.demo.zt.Variant]，不会再出现
 * 「命令指向 /data/local/tmp 但文件从未被推送」的断链。
 */
object BundledPayloadCatalog {

    private data class Entry(
        val assetKey: String,
        val displayName: String,
        val command: String,
    )

    private val entries = listOf(
        Entry(
            assetKey = "7-16g",
            displayName = "橘子7 · 16G",
            command = "cd /data/local/tmp && ZT_PIPE_DRAIN=80 ZT_PIPE_RECLAIM=40 " +
                "KHDUMP=1 NEO11_DOUBLE_SPRAY=1 KHONESHOT=1 ./zt_run --run",
        ),
        Entry(
            assetKey = "7-12g",
            displayName = "橘子7 · 12G",
            command = "cd /data/local/tmp && ZT_PIPE_DRAIN=80 ZT_PIPE_RECLAIM=40 " +
                "KHDUMP=1 NEO11_DOUBLE_SPRAY=1 KHONESHOT=1 ./zt_run --run",
        ),
        Entry(
            assetKey = "6-16g",
            displayName = "橘子6 · 16G",
            command = "cd /data/local/tmp && ./zt_run --run < zt_cmds.txt",
        ),
        Entry(
            assetKey = "6-12g",
            displayName = "橘子6 · 12G",
            command = "cd /data/local/tmp && ./zt_run --run < zt_cmds.txt",
        ),
    )

    fun load(@Suppress("UNUSED_PARAMETER") context: Context): List<BundledPayload> =
        entries.map { e ->
            BundledPayload(
                library = e.assetKey,
                vendor = "vivo",
                displayName = e.displayName,
                models = listOf("PD2408", "V2408A"),
                kernelVersion = "6.6.127",
                kernelUnknown = false,
                buildId = "",
                command = e.command,
                sha256 = "",
                size = 0L,
                source = "assets/variants/${e.assetKey}",
            )
        }
}
