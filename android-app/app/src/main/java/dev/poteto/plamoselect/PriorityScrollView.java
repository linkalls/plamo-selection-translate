package dev.poteto.plamoselect;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * A touch-first result scroller for the floating translation popup.
 *
 * The popup has an outer ScrollView for small displays and an inner ScrollView
 * for long translations. Without explicit arbitration, the outer view can
 * steal a vertical swipe before the blue result panel begins scrolling.
 *
 * This implementation follows Android's requestDisallowInterceptTouchEvent
 * contract and the platform SystemUI NonInterceptingScrollView approach:
 * the result card owns the gesture while it can scroll in that direction;
 * only at the very end of its content can the parent take over.
 */
public final class PriorityScrollView extends ScrollView {
    private final int touchSlop;
    private float lastY;

    public PriorityScrollView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setVerticalScrollBarEnabled(true);
        setScrollbarFadingEnabled(false);
        setSmoothScrollingEnabled(true);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    private void giveScrollPriority(float deltaY) {
        ViewParent parent = getParent();
        if (parent == null) return;
        // Finger moving up = content moves down (canScrollVertically(1)).
        // Finger moving down = content moves up (canScrollVertically(-1)).
        boolean canScrollInDirection = deltaY < 0
                ? canScrollVertically(1)
                : deltaY > 0 ? canScrollVertically(-1)
                    : canScrollVertically(1) || canScrollVertically(-1);
        parent.requestDisallowInterceptTouchEvent(canScrollInDirection);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastY = event.getY();
            giveScrollPriority(0);
        } else if (action == MotionEvent.ACTION_MOVE) {
            float y = event.getY();
            float delta = y - lastY;
            if (Math.abs(delta) > touchSlop) {
                giveScrollPriority(delta);
                lastY = y;
            }
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            // A subsequent gesture should not inherit the prior ownership.
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastY = event.getY();
            giveScrollPriority(0);
        } else if (action == MotionEvent.ACTION_MOVE) {
            float y = event.getY();
            float delta = y - lastY;
            if (Math.abs(delta) > touchSlop) {
                giveScrollPriority(delta);
                lastY = y;
            }
        }
        boolean handled = super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }
}
