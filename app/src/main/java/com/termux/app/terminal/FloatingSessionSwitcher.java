package com.termux.app.terminal;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

public class FloatingSessionSwitcher {

    private final TermuxActivity mActivity;

    private FloatingActionButton mButton;
    private PopupWindow mPopupWindow;
    private ListView mSessionListView;
    private MaterialButton mNewSessionButton;
    private MaterialButton mCloseSessionButton;

    private TermuxSessionsListViewController mAdapter;

    private int mTouchSlop;
    private boolean mDragging;
    private boolean mPositionRestored;
    private float mTouchDownRawX;
    private float mTouchDownRawY;
    private float mTouchDownTranslationX;
    private float mTouchDownTranslationY;

    public FloatingSessionSwitcher(TermuxActivity activity) {
        mActivity = activity;
    }

    public void onCreate() {
        mButton = mActivity.findViewById(R.id.session_switcher_button);
        if (mButton == null) return;

        mTouchSlop = ViewConfiguration.get(mActivity).getScaledTouchSlop();

        mButton.setOnClickListener(v -> togglePopup());
        mButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleButtonTouch(v, event);
            }
        });
        mButton.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)
                clampTranslation();
        });

        updateVisibility();
    }

    public void setSessionsAdapter(TermuxSessionsListViewController adapter) {
        mAdapter = adapter;
        if (mPopupWindow != null && mPopupWindow.isShowing() && mSessionListView != null)
            mSessionListView.setAdapter(mAdapter);
    }

    public void onResume() {
        updateVisibility();
        restorePosition();
        updatePosition();
    }

    public void onStop() {
        dismiss();
    }

    public void onReloadProperties() {
        updateVisibility();
        updatePosition();
    }

    public void onSessionsChanged() {
        if (mPopupWindow == null || !mPopupWindow.isShowing() || mSessionListView == null) return;

        int index = getCurrentSessionIndex();
        if (index >= 0) {
            mSessionListView.setItemChecked(index, true);
            mSessionListView.setSelection(index);
        }
        if (mCloseSessionButton != null)
            mCloseSessionButton.setEnabled(mActivity.getCurrentSession() != null);
    }

    public boolean isPopupShowing() {
        return mPopupWindow != null && mPopupWindow.isShowing();
    }

    public void dismiss() {
        if (mPopupWindow != null) {
            mPopupWindow.dismiss();
            mPopupWindow = null;
        }
    }

    private void togglePopup() {
        if (isPopupShowing())
            dismiss();
        else
            showPopup();
    }

    private void updateVisibility() {
        if (mButton == null) return;

        TermuxAppSharedPreferences preferences = mActivity.getPreferences();
        boolean enabled = preferences != null && preferences.isFloatingSessionSwitcherEnabled();

        if (!enabled)
            dismiss();

        mButton.setVisibility(enabled ? View.VISIBLE : View.GONE);

        if (enabled)
            mButton.post(this::updatePosition);
    }

    private void updatePosition() {
        if (mButton == null || mButton.getVisibility() != View.VISIBLE || mDragging) return;

        ViewGroup.LayoutParams params = mButton.getLayoutParams();
        if (!(params instanceof RelativeLayout.LayoutParams)) return;

        RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) params;
        int bottomMargin = dp(16);

        View toolbar = mActivity.getTerminalToolbarViewPager();
        if (toolbar != null && toolbar.getVisibility() == View.VISIBLE) {
            int toolbarHeight = toolbar.getHeight();
            ViewGroup.LayoutParams toolbarParams = toolbar.getLayoutParams();
            if (toolbarParams != null && toolbarParams.height > toolbarHeight)
                toolbarHeight = toolbarParams.height;
            if (toolbarHeight > 0)
                bottomMargin += toolbarHeight;
        }

        layoutParams.bottomMargin = bottomMargin;
        layoutParams.rightMargin = dp(16);
        mButton.setLayoutParams(layoutParams);
    }

    private void restorePosition() {
        if (mButton == null || mPositionRestored) return;

        TermuxAppSharedPreferences preferences = mActivity.getPreferences();
        if (preferences == null) return;

        mButton.setTranslationX(preferences.getFloatingSessionSwitcherOffsetX());
        mButton.setTranslationY(preferences.getFloatingSessionSwitcherOffsetY());
        mPositionRestored = true;
        mButton.post(this::clampTranslation);
    }

    private void savePosition() {
        if (mButton == null) return;

        TermuxAppSharedPreferences preferences = mActivity.getPreferences();
        if (preferences == null) return;

        preferences.setFloatingSessionSwitcherOffset(mButton.getTranslationX(), mButton.getTranslationY());
    }

    private void clampTranslation() {
        if (mButton == null) return;

        View parentView = (View) mButton.getParent();
        if (parentView == null || parentView.getWidth() == 0 || parentView.getHeight() == 0 ||
            mButton.getWidth() == 0 || mButton.getHeight() == 0) return;

        int margin = dp(8);
        float baseLeft = mButton.getLeft() - mButton.getTranslationX();
        float baseTop = mButton.getTop() - mButton.getTranslationY();
        float desiredLeft = baseLeft + mButton.getTranslationX();
        float desiredTop = baseTop + mButton.getTranslationY();

        float minLeft = parentView.getPaddingLeft() + margin;
        float maxLeft = parentView.getWidth() - parentView.getPaddingRight() - margin - mButton.getWidth();
        float minTop = parentView.getPaddingTop() + margin;
        float maxTop = parentView.getHeight() - parentView.getPaddingBottom() - margin - mButton.getHeight();

        if (maxLeft < minLeft) maxLeft = minLeft;
        if (maxTop < minTop) maxTop = minTop;

        float clampedLeft = Math.max(minLeft, Math.min(maxLeft, desiredLeft));
        float clampedTop = Math.max(minTop, Math.min(maxTop, desiredTop));

        mButton.setTranslationX(clampedLeft - baseLeft);
        mButton.setTranslationY(clampedTop - baseTop);
    }

    private boolean handleButtonTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchDownRawX = event.getRawX();
                mTouchDownRawY = event.getRawY();
                mTouchDownTranslationX = view.getTranslationX();
                mTouchDownTranslationY = view.getTranslationY();
                mDragging = false;
                view.setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - mTouchDownRawX;
                float deltaY = event.getRawY() - mTouchDownRawY;

                if (!mDragging && (Math.abs(deltaX) > mTouchSlop || Math.abs(deltaY) > mTouchSlop))
                    mDragging = true;

                if (mDragging) {
                    view.setTranslationX(mTouchDownTranslationX + deltaX);
                    view.setTranslationY(mTouchDownTranslationY + deltaY);
                    clampTranslation();
                }
                return true;
            case MotionEvent.ACTION_UP:
                view.setPressed(false);
                if (mDragging) {
                    savePosition();
                    mDragging = false;
                } else {
                    view.performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                view.setPressed(false);
                if (mDragging) {
                    clampTranslation();
                    savePosition();
                    mDragging = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void showPopup() {
        if (mAdapter == null) return;

        View content = LayoutInflater.from(mActivity).inflate(R.layout.view_session_switcher, null, false);
        mSessionListView = content.findViewById(R.id.session_switcher_list);
        mNewSessionButton = content.findViewById(R.id.session_switcher_new_button);
        mCloseSessionButton = content.findViewById(R.id.session_switcher_close_button);

        if (mSessionListView == null || mNewSessionButton == null || mCloseSessionButton == null) return;

        int width = Math.min(dp(280), mActivity.getResources().getDisplayMetrics().widthPixels - dp(32));
        int[] location = new int[2];
        mButton.getLocationOnScreen(location);

        int availableAbove = location[1] - dp(16);
        int listHeight = Math.min(dp(200), Math.max(dp(72), availableAbove - dp(120)));
        ViewGroup.LayoutParams listParams = mSessionListView.getLayoutParams();
        listParams.height = listHeight;
        mSessionListView.setLayoutParams(listParams);

        mSessionListView.setAdapter(mAdapter);
        mSessionListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mSessionListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                TermuxSession termuxSession = (TermuxSession) parent.getItemAtPosition(position);
                if (termuxSession != null)
                    mActivity.getTermuxTerminalSessionClient().setCurrentSession(termuxSession.getTerminalSession());
                dismiss();
            }
        });

        int currentIndex = getCurrentSessionIndex();
        if (currentIndex >= 0) {
            mSessionListView.setItemChecked(currentIndex, true);
            mSessionListView.setSelection(currentIndex);
        }
        mCloseSessionButton.setEnabled(mActivity.getCurrentSession() != null);

        mNewSessionButton.setOnClickListener(v -> {
            dismiss();
            mActivity.getTermuxTerminalSessionClient().addNewSession(false, null);
        });
        mCloseSessionButton.setOnClickListener(v -> {
            TerminalSession currentSession = mActivity.getCurrentSession();
            dismiss();
            if (currentSession != null)
                mActivity.getTermuxTerminalSessionClient().closeSession(currentSession);
        });

        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = content.getMeasuredHeight();

        mPopupWindow = new PopupWindow(content, width, height, true);
        mPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setFocusable(true);
        mPopupWindow.setElevation(dp(8));
        mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        mPopupWindow.setOnDismissListener(() -> {
            if (mSessionListView != null)
                mSessionListView.setAdapter(null);
            mSessionListView = null;
            mNewSessionButton = null;
            mCloseSessionButton = null;
            mPopupWindow = null;
        });

        int x = location[0] + mButton.getWidth() - width;
        int y = location[1] - height - dp(8);
        int screenWidth = mActivity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = mActivity.getResources().getDisplayMetrics().heightPixels;

        if (y < dp(8))
            y = location[1] + mButton.getHeight() + dp(8);

        x = Math.max(dp(8), Math.min(x, screenWidth - width - dp(8)));
        y = Math.max(dp(8), Math.min(y, screenHeight - height - dp(8)));

        mPopupWindow.showAtLocation(mActivity.getWindow().getDecorView(), Gravity.NO_GRAVITY, x, y);
    }

    private int getCurrentSessionIndex() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        TermuxService service = mActivity.getTermuxService();
        if (currentSession == null || service == null) return -1;
        return service.getIndexOfSession(currentSession);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

}
