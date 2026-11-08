package com.oracole.readit.util

import android.content.Context
import android.widget.Toast

/**
 * 全局轻提示
 *
 * 为什么不用 Snackbar：Material3 的 ModalBottomSheet 跑在独立窗口里
 * （ModalBottomSheetWindow），页面内的 SnackbarHost 永远盖不到它上面
 * 系统 Toast 由 WindowManager 直接绘制，能盖住弹层、对话框、键盘
 * 这是唯一能保证任何状态下都看得见的做法
 */
object Notify {
    private var app: Context? = null
    private var current: Toast? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun show(msg: String) {
        val c = app ?: return
        current?.cancel()
        current = Toast.makeText(c, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

}
