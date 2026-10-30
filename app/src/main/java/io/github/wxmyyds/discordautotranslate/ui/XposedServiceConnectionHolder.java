package io.github.wxmyyds.discordautotranslate.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 进程级 LSPosed service 连接持有者。
 *
 * <p>{@link XposedServiceHelper} 只支持一个强引用 listener，且没有注销接口，
 * 所以 helper 只注册本持有者一次。Activity 通过弱引用临时绑定；当前 service
 * 保留在本进程中，使重新打开设置页时可以立即收到已经建立的连接。</p>
 */
final class XposedServiceConnectionHolder implements XposedServiceHelper.OnServiceListener {

    interface Listener {
        void onServiceConnected(@NonNull XposedService service);

        void onServiceDied(@NonNull XposedService service);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final XposedServiceConnectionHolder INSTANCE =
            new XposedServiceConnectionHolder();

    private static boolean registered;

    @Nullable
    private XposedService currentService;
    @Nullable
    private WeakReference<Listener> boundListener;

    private XposedServiceConnectionHolder() {
    }

    /** 将当前页面绑定到进程级连接；调用方应在主线程调用。 */
    static void bind(@NonNull Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> bind(listener));
            return;
        }
        ensureRegistered();
        INSTANCE.boundListener = new WeakReference<>(listener);
        XposedService service = INSTANCE.currentService;
        if (service != null) {
            listener.onServiceConnected(service);
        }
    }

    /** 解除当前页面绑定，不影响进程级 service 监听和缓存。 */
    static void unbind(@NonNull Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> unbind(listener));
            return;
        }
        Listener bound = INSTANCE.boundListener == null
                ? null : INSTANCE.boundListener.get();
        if (bound == listener) {
            INSTANCE.boundListener = null;
        }
    }

    private static void ensureRegistered() {
        if (registered) {
            return;
        }
        synchronized (XposedServiceConnectionHolder.class) {
            if (registered) {
                return;
            }
            registered = true;
            // 这里是进程唯一一次注册。helper 本身没有 unregister 接口。
            XposedServiceHelper.registerListener(INSTANCE);
        }
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        dispatchToMain(() -> handleServiceBind(service));
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        dispatchToMain(() -> handleServiceDied(service));
    }

    private static void dispatchToMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN.post(action);
        }
    }

    private void handleServiceBind(@NonNull XposedService service) {
        currentService = service;
        Listener listener = boundListener == null ? null : boundListener.get();
        if (listener != null) {
            listener.onServiceConnected(service);
        }
    }

    private void handleServiceDied(@NonNull XposedService service) {
        // 旧 service 的 binder death 不能清掉后来绑定的新 service。
        if (currentService != service) {
            return;
        }
        currentService = null;
        Listener listener = boundListener == null ? null : boundListener.get();
        if (listener != null) {
            listener.onServiceDied(service);
        }
    }
}
