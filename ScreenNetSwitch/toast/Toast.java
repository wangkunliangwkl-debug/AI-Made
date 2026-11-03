package com.operit;

import android.content.Context;
import android.os.Looper;
import android.widget.Toast;
import android.os.Handler;

/**
 * 纯 app_process 运行的 Toast 工具
 * 用法: CLASSPATH=/system/framework/framework.jar app_process /system/bin com.operit.Toast "文案"
 *
 * 说明: app_process 启动的进程默认没有 Looper，弹 Toast 必须在主线程有 Looper 的环境下。
 *      这里手动 prepare Looper 并用 Handler 投递，避免主线程未初始化导致崩溃。
 */
public class Toast {
    public static void main(final String[] args) {
        if (args == null || args.length == 0) return;
        final String text = args[0];

        try {
            Looper.prepareMainLooper();
            Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context ctx = getSystemContext();
                        if (ctx != null) {
                            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignored) {}
                }
            });
            // 给 Toast 一点时间挂上去，然后退出
            Thread.sleep(1200);
        } catch (Throwable ignored) {
        }
    }

    /** 通过反射拿到 ActivityThread 的 system context（app_process 下的标准做法） */
    private static Context getSystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cur = at.getMethod("currentActivityThread");
            Object thread = cur.invoke(null);
            if (thread == null) {
                java.lang.reflect.Method sysMain = at.getMethod("systemMain");
                thread = sysMain.invoke(null);
            }
            java.lang.reflect.Method ctx = at.getMethod("getSystemContext");
            return (Context) ctx.invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }
}
