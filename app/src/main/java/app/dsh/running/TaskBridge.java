package app.dsh.running;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 用户服务的连接管理。
 *
 * 关键点：在 Application.onCreate 里就开始 bind，而不是等界面起来再绑。
 * 用户服务是一个独立的 app_process 进程，冷启动要 0.5~2 秒，
 * 这段时间和界面构建、首帧绘制重叠掉，能显著缩短「连接中」的窗口。
 */
final class TaskBridge {

    private static final String TAG = "TaskBridge";
    private static final int REQ_PERMISSION = 2001;

    private static Context app;
    private static IBinder binder;
    private static boolean binding;
    private static boolean requesting;
    private static Runnable onReady;

    private TaskBridge() {
    }

    static void init(Context c) {
        if (app != null) {
            return;
        }
        app = c.getApplicationContext();
        try {
            Shizuku.addBinderReceivedListenerSticky(TaskBridge::connect);
            Shizuku.addRequestPermissionResultListener((code, result) -> {
                requesting = false;
                connect();
            });
        } catch (Throwable t) {
            Log.e(TAG, "init", t);
        }
        connect();
    }

    static void setOnReady(Runnable r) {
        onReady = r;
        if (binder != null && r != null) {
            r.run();
        }
    }

    static boolean ready() {
        return binder != null;
    }

    /** Shizuku 本体在，但用户服务还没连上。 */
    static boolean connecting() {
        try {
            return binder == null && Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean permissionDialogShowing() {
        return requesting;
    }

    private static void connect() {
        if (app == null || binder != null || binding) {
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (!Shizuku.shouldShowRequestPermissionRationale()) {
                    requesting = true;
                    Shizuku.requestPermission(REQ_PERMISSION);
                }
                return;
            }
            binding = true;
            ComponentName cn = new ComponentName(app.getPackageName(),
                    TaskUserService.class.getName());
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(cn)
                    .processNameSuffix("task")
                    .debuggable(false)
                    .version(1)
                    .daemon(true);
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    binder = service;
                    binding = false;
                    Log.i(TAG, "user service connected");
                    Runnable r = onReady;
                    if (r != null) {
                        r.run();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    binder = null;
                    binding = false;
                }
            });
        } catch (Throwable t) {
            binding = false;
            Log.e(TAG, "connect", t);
        }
    }

    /** dumpsys activity recents 的原文；失败返回 null。 */
    static String dumpRecents() {
        IBinder b = binder;
        if (b == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskUserService.DESCRIPTOR);
            if (!b.transact(TaskUserService.CODE_DUMP, data, reply, 0)) {
                return null;
            }
            reply.readException();
            return reply.readString();
        } catch (Throwable t) {
            Log.e(TAG, "dump", t);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 杀进程 + 从最近任务里移除。 */
    static boolean stop(String pkg, int taskId) {
        IBinder b = binder;
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskUserService.DESCRIPTOR);
            data.writeString(pkg);
            data.writeInt(taskId);
            b.transact(TaskUserService.CODE_STOP, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "stop", t);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
