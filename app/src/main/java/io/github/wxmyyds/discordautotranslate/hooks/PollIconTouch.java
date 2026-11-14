package io.github.wxmyyds.discordautotranslate.hooks;

import android.text.Layout;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/** Consume only touches on the injected icon, never normal Poll answer taps. */
final class PollIconTouch implements View.OnTouchListener {
    private ClickableSpan pressed;

    static void attach(TextView view, Spanned text) {
        view.setOnTouchListener(text.getSpans(0, text.length(), InlineIconSpan.class).length == 0
                ? null : new PollIconTouch());
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        TextView textView = (TextView) view;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pressed = hit(textView, event);
            return pressed != null;
        }
        if (pressed == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            ClickableSpan clicked = pressed;
            pressed = null;
            if (clicked == hit(textView, event)) clicked.onClick(view);
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pressed = null;
        }
        return true;
    }

    private static ClickableSpan hit(TextView view, MotionEvent event) {
        Layout layout = view.getLayout();
        CharSequence raw = view.getText();
        if (layout == null || !(raw instanceof Spanned)) return null;
        float x = event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
        float y = event.getY() - view.getTotalPaddingTop() + view.getScrollY();
        if (y < 0 || y >= layout.getHeight()) return null;
        int line = layout.getLineForVertical((int) y);
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
        Spanned text = (Spanned) raw;
        for (InlineIconSpan icon : text.getSpans(layout.getLineStart(line),
                layout.getLineEnd(line), InlineIconSpan.class)) {
            int start = text.getSpanStart(icon);
            int end = text.getSpanEnd(icon);
            float left = layout.getPrimaryHorizontal(start);
            float right = layout.getPrimaryHorizontal(end);
            if (x < Math.min(left, right) || x > Math.max(left, right)) continue;
            ClickableSpan[] clicks = text.getSpans(start, end, ClickableSpan.class);
            return clicks.length == 1 ? clicks[0] : null;
        }
        return null;
    }
}
