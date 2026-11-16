package app.dsh.running;

import android.app.Application;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.install(this);
        TaskBridge.init(this);
    }
}
