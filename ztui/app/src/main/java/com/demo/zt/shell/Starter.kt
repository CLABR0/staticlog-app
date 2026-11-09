package com.demo.zt.shell

/** 原版 Starter 的命令常量。 */
object Starter {
    /** root 身份下直接跑的启动命令。 */
    const val internalCommand = "/data/local/tmp/zt_run --run"

    /** adb shell 身份下的启动命令。 */
    const val userCommand = "/data/local/tmp/zt_run --run"

    /**
     * 配对页「连上之后」执行的命令。
     *
     * **必须只读、绝不能是载荷启动器。**
     * 曾经直接沿用 `userCommand`（= `/data/local/tmp/zt_run --run`），
     * 后果就是「一配对就自动开始提权」，用户根本没点过「执行」。
     *
     * 配对只负责建立连接；是否跑载荷由主界面的「执行」按钮决定，
     * 或由「开机自动提权」在开机广播的明确条件下触发。
     */
    const val wirelessDebuggingCommand = "id"
}
