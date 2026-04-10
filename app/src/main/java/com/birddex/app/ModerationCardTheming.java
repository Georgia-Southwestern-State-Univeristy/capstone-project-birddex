package com.birddex.app;

import android.content.Context;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * Applies translucent card tints for moderation history and moderator queue lists so users can
 * scan status and item type at a glance.
 */
public final class ModerationCardTheming {

    private ModerationCardTheming() {
    }

    public static void styleModerationEventCard(CardView card, String status, String actionType) {
        card.setCardBackgroundColor(resolveModerationEventTint(card.getContext(), status, actionType));
    }

    public static void styleUserAppealCard(CardView card, String status) {
        card.setCardBackgroundColor(resolveUserAppealTint(card.getContext(), status));
    }

    public static void styleModeratorQueueCard(CardView card, String queueType) {
        Context ctx = card.getContext();
        String qt = queueType != null ? queueType.trim().toLowerCase(Locale.US) : "";
        if ("report".equals(qt)) {
            card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.moderation_tint_queue_report));
        } else {
            card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.moderation_tint_queue_appeal));
        }
    }

    private static int resolveModerationEventTint(Context ctx, String status, String actionType) {
        String s = status != null ? status.trim().toLowerCase(Locale.US) : "";
        String a = actionType != null ? actionType.trim().toLowerCase(Locale.US) : "";

        if ("reversed".equals(s)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_reversed);
        }
        if ("expired".equals(s)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_expired);
        }

        if ("strike".equals(a) || "forum_ban".equals(a)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_severe);
        }
        if ("forum_suspension".equals(a)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_suspension);
        }
        if ("warning".equals(a)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_warning);
        }
        if ("hide_content".equals(a) || "remove_content".equals(a) || "reject_content".equals(a)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_content);
        }

        return ContextCompat.getColor(ctx, R.color.moderation_tint_neutral);
    }

    private static int resolveUserAppealTint(Context ctx, String status) {
        String s = status != null ? status.trim().toLowerCase(Locale.US) : "";
        if ("approved".equals(s)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_appeal_approved);
        }
        if ("denied".equals(s)) {
            return ContextCompat.getColor(ctx, R.color.moderation_tint_appeal_denied);
        }
        return ContextCompat.getColor(ctx, R.color.moderation_tint_appeal_pending);
    }
}
