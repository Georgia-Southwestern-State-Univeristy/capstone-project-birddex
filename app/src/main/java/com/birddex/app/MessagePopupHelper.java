package com.birddex.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

/**
 * User feedback: modal dialogs ({@link #show}) vs brief non-blocking messages ({@link #showBrief})
 * using Snackbar (~1.5–2s) for interaction flows such as forum posts.
 */
public final class MessagePopupHelper {

    private static final int BRIEF_MS = 1800;

    private MessagePopupHelper() {
    }

    // -------------------------------------------------------------------------
    // Brief (Snackbar, auto-dismiss ~1–2 seconds)
    // -------------------------------------------------------------------------

    public static void showBrief(@NonNull Activity activity, @Nullable CharSequence message) {
        showBrief(activity, message, null);
    }

    /**
     * Shows a Snackbar briefly, then runs {@code afterDismiss} when it is dismissed (timeout, swipe, or action).
     */
    public static void showBrief(@NonNull Activity activity, @Nullable CharSequence message, @Nullable Runnable afterDismiss) {
        if (!canShow(activity)) return;
        if (message == null || message.toString().trim().isEmpty()) {
            if (afterDismiss != null) afterDismiss.run();
            return;
        }
        View root = activity.findViewById(android.R.id.content);
        if (root == null) {
            if (afterDismiss != null) afterDismiss.run();
            return;
        }
        Snackbar sb = Snackbar.make(root, message.toString().trim(), Snackbar.LENGTH_SHORT);
        try {
            sb.setDuration(BRIEF_MS);
        } catch (Throwable ignored) {
            // LENGTH_SHORT used if setDuration unavailable
        }
        if (afterDismiss != null) {
            sb.addCallback(new BaseTransientBottomBar.BaseCallback<Snackbar>() {
                @Override
                public void onDismissed(@NonNull Snackbar transientBottomBar, int event) {
                    afterDismiss.run();
                }
            });
        }
        sb.show();
    }

    public static void showBrief(@Nullable Fragment fragment, @Nullable CharSequence message) {
        if (fragment == null || !fragment.isAdded()) return;
        showBrief(fragment.requireActivity(), message);
    }

    public static void showBrief(@NonNull Context context, @Nullable CharSequence message) {
        Activity a = unwrapActivity(context);
        if (a != null) showBrief(a, message);
    }

    private static Activity unwrapActivity(@Nullable Context context) {
        if (context == null) return null;
        if (context instanceof Activity) return (Activity) context;
        for (Context c = context; c instanceof ContextWrapper; c = ((ContextWrapper) c).getBaseContext()) {
            if (c instanceof Activity) return (Activity) c;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Modal (Material dialog + OK)
    // -------------------------------------------------------------------------

    public static void show(@NonNull Context context, @Nullable CharSequence message) {
        show(context, message, null);
    }

    public static void show(@NonNull Context context, @Nullable CharSequence message, @Nullable Runnable onOk) {
        if (!canShow(context)) return;
        if (message == null) return;
        String msg = message.toString().trim();
        if (msg.isEmpty()) return;

        new MaterialAlertDialogBuilder(context)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    d.dismiss();
                    if (onOk != null) onOk.run();
                })
                .show();
    }

    public static void show(@Nullable Fragment fragment, @Nullable CharSequence message) {
        if (fragment == null || !fragment.isAdded()) return;
        show(fragment.requireContext(), message);
    }

    public static void showThenFinish(@NonNull final Activity activity, @Nullable CharSequence message) {
        if (!canShow(activity)) return;
        if (message == null || message.toString().trim().isEmpty()) {
            activity.finish();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setMessage(message.toString().trim())
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    d.dismiss();
                    if (!activity.isFinishing()) activity.finish();
                })
                .show();
    }

    private static boolean canShow(@Nullable Context context) {
        if (context == null) return false;
        if (context instanceof Activity) {
            Activity a = (Activity) context;
            if (a.isFinishing()) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && a.isDestroyed()) return false;
        }
        return true;
    }
}
