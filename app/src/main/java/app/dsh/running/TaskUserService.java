package app.dsh.running;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 运行在 Shizuku 拉起的 shell 进程里的用户服务。
 * 这里没有隐藏 API 限制，可以直接执行系统命令拿任务栈 / 强停应用。
 *
 * 通信走裸 binder 事务（不依赖 AIDL 生成代码）：
 *   CODE_DUMP     -> 返回 `dumpsys activity recents` 的文本
 *   CODE_STOP     -> 参数是包名 + taskId，执行 am force-stop / am stack remove
 *   CODE_SLEEP    -> 注入 KEYCODE_SLEEP，让系统熄屏休眠
 *   CODE_SNAPSHOT -> 参数是 taskId，返回该任务缩略图的 JPEG 字节（失败返回 null）
 */
public class TaskUserService extends Binder {

    public static final String DESCRIPTOR = "app.dsh.running.TaskUserService";
    public static final int CODE_DUMP = 1;
    public static final int CODE_STOP = 2;
    public static final int CODE_SLEEP = 3;
    public static final int CODE_SNAPSHOT = 4;
    /** Shizuku 用来销毁用户服务的固定事务号。 */
    private static final int CODE_DESTROY = 16777115;

    // IActivityTaskManager 的反射句柄（只初始化一次）
    private static Object atm;
    private static Method snapMethod;

    public TaskUserService() {
    }

    @SuppressWarnings("unused")
    public TaskUserService(Context context) {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == CODE_DUMP || code == CODE_STOP || code == CODE_SLEEP
                || code == CODE_SNAPSHOT) {
            data.enforceInterface(DESCRIPTOR);
        }
        switch (code) {
            case CODE_DUMP: {
                String dump = exec("/system/bin/dumpsys", "activity", "recents");
                reply.writeNoException();
                reply.writeString(dump == null ? "" : dump);
                return true;
            }
            case CODE_STOP: {
                String pkg = data.readString();
                int taskId = data.readInt();
                // 1) 杀进程  2) 把任务从「最近任务」里删掉，否则系统面板里还会留一张卡片
                String out = pkg == null ? null : exec("/system/bin/am", "force-stop", pkg);
                if (taskId > 0) {
                    exec("/system/bin/am", "stack", "remove", String.valueOf(taskId));
                }
                Log.i("TaskSvc", "stop " + pkg + " task=" + taskId + " -> " + out);
                reply.writeNoException();
                reply.writeString(out == null ? "" : out);
                return true;
            }
            case CODE_SLEEP: {
                // KEYCODE_SLEEP(223) 直接让系统进休眠；这是电源键的「只关不唤醒」版本，
                // 普通应用没有 DEVICE_POWER 权限，只有这里（uid shell 2000）能注入
                String out = exec("/system/bin/input", "keyevent", "223");
                Log.i("TaskSvc", "sleep -> " + out);
                reply.writeNoException();
                reply.writeString(out == null ? "" : out);
                return true;
            }
            case CODE_SNAPSHOT: {
                int taskId = data.readInt();
                byte[] jpg = snapshot(taskId);
                reply.writeNoException();
                reply.writeByteArray(jpg);
                return true;
            }
            case CODE_DESTROY:
                System.exit(0);
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    /**
     * 取某个任务的系统缩略图。
     *
     * 系统本来就有每任务的 snapshot（TaskSnapshotController 存盘），只是
     * IActivityTaskManager.getTaskSnapshot() 要 READ_FRAME_BUFFER 权限，普通应用没有；
     * 而 com.android.shell 在 AOSP 里就是这项权限的持有者，所以 shell 身份（本进程）能拿到。
     *
     * 返回给主进程的是缩小并压过的 JPEG，binder 单次事务有 1MB 上限，别直接塞原图。
     */
    private static byte[] snapshot(int taskId) {
        if (taskId <= 0 || Build.VERSION.SDK_INT < 29) {
            return null;
        }
        Bitmap sw = null;
        try {
            if (snapMethod == null) {
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "activity_task");
                if (binder == null) {
                    return null;
                }
                Class<?> stub = Class.forName("android.app.IActivityTaskManager$Stub");
                atm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
                Class<?> itf = Class.forName("android.app.IActivityTaskManager");
                // 不同版本签名不一样：13 是 (int, boolean)，14/15 是 (int, boolean, boolean)
                for (Method m : itf.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("getTaskSnapshot") && p.length >= 2 && p[0] == int.class) {
                        snapMethod = m;
                        break;
                    }
                }
                if (snapMethod == null) {
                    Log.w("TaskSvc", "getTaskSnapshot not found");
                    return null;
                }
            }
            Class<?>[] ps = snapMethod.getParameterTypes();
            Object[] call = new Object[ps.length];
            call[0] = taskId;
            for (int i = 1; i < ps.length; i++) {
                call[i] = Boolean.TRUE;
            }
            Object snap = snapMethod.invoke(atm, call);
            if (snap == null) {
                return null;
            }
            Class<?> sc = snap.getClass();
            HardwareBuffer buffer = null;
            try {
                buffer = (HardwareBuffer) sc.getMethod("getHardwareBuffer").invoke(snap);
            } catch (Throwable t) {
                for (Field f : sc.getDeclaredFields()) {
                    if (f.getType() == HardwareBuffer.class) {
                        f.setAccessible(true);
                        buffer = (HardwareBuffer) f.get(snap);
                        break;
                    }
                }
            }
            if (buffer == null) {
                return null;
            }
            ColorSpace cs = ColorSpace.get(ColorSpace.Named.SRGB);
            try {
                Object o = sc.getMethod("getColorSpace").invoke(snap);
                if (o instanceof ColorSpace) {
                    cs = (ColorSpace) o;
                }
            } catch (Throwable ignored) {
            }
            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, cs);
            if (hw == null) {
                return null;
            }
            // 硬件位图不能缩放，先拷一份到软件位图
            sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            if (sw == null) {
                return null;
            }
            int w = sw.getWidth();
            int h = sw.getHeight();
            if (w > 480) {
                Bitmap small = Bitmap.createScaledBitmap(sw, 480, Math.max(1, h * 480 / w), true);
                sw.recycle();
                sw = small;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            sw.compress(Bitmap.CompressFormat.JPEG, 72, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w("TaskSvc", "snapshot " + taskId + " -> " + t);
            return null;
        } finally {
            if (sw != null) {
                sw.recycle();
            }
        }
    }

    private static String exec(String... cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
