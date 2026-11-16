package app.dsh.running;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 运行在 Shizuku 拉起的 shell 进程里的用户服务。
 * 这里没有隐藏 API 限制，可以直接执行系统命令拿任务栈 / 强停应用。
 *
 * 通信走裸 binder 事务（不依赖 AIDL 生成代码）：
 *   CODE_DUMP -> 返回 `dumpsys activity recents` 的文本
 *   CODE_STOP -> 参数是包名，执行 am force-stop
 */
public class TaskUserService extends Binder {

    public static final String DESCRIPTOR = "app.dsh.running.TaskUserService";
    public static final int CODE_DUMP = 1;
    public static final int CODE_STOP = 2;
    /** Shizuku 用来销毁用户服务的固定事务号。 */
    private static final int CODE_DESTROY = 16777115;

    public TaskUserService() {
    }

    @SuppressWarnings("unused")
    public TaskUserService(Context context) {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == CODE_DUMP || code == CODE_STOP) {
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
            case CODE_DESTROY:
                System.exit(0);
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
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
