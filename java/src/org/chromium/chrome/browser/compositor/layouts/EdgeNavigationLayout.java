/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.chromium.chrome.browser.compositor.layouts;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;

import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.compositor.LayerTitleCache;
import org.chromium.chrome.browser.compositor.layouts.components.LayoutTab;
import org.chromium.chrome.browser.compositor.layouts.content.TabContentManager;
import org.chromium.chrome.browser.compositor.layouts.eventfilter.EdgeSwipeEventFilter;
import org.chromium.chrome.browser.compositor.layouts.eventfilter.EventFilter;
import org.chromium.chrome.browser.compositor.layouts.eventfilter.EventFilterHost;
import org.chromium.chrome.browser.compositor.scene_layer.SceneLayer;
import org.chromium.chrome.browser.compositor.scene_layer.TabListSceneLayer;
import org.chromium.chrome.browser.contextualsearch.SwipeRecognizer;
import org.chromium.chrome.browser.favicon.FaviconHelper;
import org.chromium.chrome.browser.fullscreen.ChromeFullscreenManager;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.EmptyTabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.util.MathUtils;
import org.chromium.content_public.browser.NavigationController;
import org.chromium.content_public.browser.NavigationEntry;
import org.chromium.ui.resources.ResourceManager;

import java.util.List;

public class EdgeNavigationLayout extends Layout
        implements ChromeAnimation.Animatable<EdgeNavigationLayout.Property> {

    public enum Property {
        OFFSET,
    }

    private static final int   FINGER_WIDTH_MARGIN = 40;
    private static final float INTERPOLATOR_SPEED = 0.8f;
    private static final float MIN_SWIPE = 0.1f;
    private static final float ANIMATION_SPEED_SCREEN = 500.0f;
    private static final float STATIONARY_VIEW_MIN_ALPHA = 0.5f;
    private static final float STATIONARY_VIEW_MAX_ALPHA = 1.0f;
    private static final float SHADOW_ALPHA = 0.5f;
    private static final float SHADOW_WIDTH = 0.3f;
    private static final int   FLING_MIN_VELOCITY = 100;
    private static final int   MIN_LOAD_PROGRESS = 70;
    private static final int   NAV_BACK = -1;
    private static final int   NAV_FWD = 1;
    private static final int   INVALID_NAV_VIEW_ID = Integer.MAX_VALUE;

    /*                        SNAPSHOT ID STRUCTURE (31 bits)
                 ---------------------------------------------------------
                 | NUM_NAV_ID_BITS | NUM_RESERVED_BITS | NUM_TAB_ID_BITS |
                 ---------------------------------------------------------
     */
    private static final int   NUM_NAV_ID_BITS = 14;
    private static final int   NUM_RESERVED_BITS = 1;
    private static final int   NUM_TAB_ID_BITS = 31 - NUM_NAV_ID_BITS - NUM_RESERVED_BITS;
    private static final int   TAB_ID_MASK = (int) (Math.pow(2, NUM_TAB_ID_BITS) - 1);
    private static final int   RESERVED_BIT_VAL =
            (int) (Math.pow(2, NUM_RESERVED_BITS) - 1) << NUM_TAB_ID_BITS;

    private final TabListSceneLayer mTabListSceneLayer;

    private EdgeNavigationLayoutTab mNextView;
    private EdgeNavigationLayoutTab mCurrentView;

    private boolean mbSettlingViews;
    private boolean mbNavigationPossible;
    private boolean mbNavigateBack;
    private boolean mbNavigateForward;

    private float mOffsetStart;
    private float mOffset;
    private float mOffsetTarget;
    private float mOffsetBounds[];

    private LayoutManager mLayoutManager;
    private SwipeRecognizer mSwipeRecognizer;

    private EventFilter mEventFilter;

    private EdgeSwipeEventFilter.ScrollDirection mSwipeDirection;
    private EdgeSwipeEventFilter.ScrollDirection mLastSwipeMovement;

    private FaviconHelper mFaviconHelper;

    private Layout mLiveLayout;

    private final float mToolbarHeight;

    private EdgeNavigationTabObserver mTabObserver;

    private int mCurrHistoryIndex;
    private int mNextHistoryIndex;

    private class EdgeNavigationTabObserver extends EmptyTabObserver {
        private EdgeNavigationLayout mLayout;

        public EdgeNavigationTabObserver(EdgeNavigationLayout mainLayout) {
            mLayout = mainLayout;
            if (mLayout.mTabModelSelector != null
                    && mLayout.mTabModelSelector.getCurrentTab() != null) {
                mLayout.mTabModelSelector.getCurrentTab().addObserver(this);
            }
        }

        public void observe(Tab tab) {
            mLayout.mCurrHistoryIndex = tab.getWebContents().getNavigationController()
                    .getLastCommittedEntryIndex();
            mLayout.mNextHistoryIndex = mLayout.mCurrHistoryIndex;
            tab.addObserver(this);
        }

        @Override
        public void onShown(Tab tab) {
            observe(tab);
        }

        @Override
        public void onHidden(Tab tab) {
            tab.removeObserver(this);
        }

        @Override
        public void onClosingStateChanged(Tab tab, boolean closing) {
            if (closing) {
                tab.removeObserver(this);
                mLayout.mTabContentManager.removeThumbnailWithID(tab.getId(), TAB_ID_MASK);
            }
        }

        @Override
        public void onDestroyed(Tab tab) {
            tab.removeObserver(this);
            mLayout.mTabContentManager.removeThumbnailWithID(tab.getId(), TAB_ID_MASK);
        }

        @Override
        public void onLoadProgressChanged(Tab tab, int progress) {
            if (progress > MIN_LOAD_PROGRESS) {
                showLiveView(tab);
            }
        }

        @Override
        public void onPageLoadFinished(Tab tab) {
            mLayout.mCurrHistoryIndex = tab.getWebContents().getNavigationController()
                    .getLastCommittedEntryIndex();
            showLiveView(tab);
            mLayout.mNextHistoryIndex = mLayout.mCurrHistoryIndex;
        }

        private void showLiveView(Tab tab) {
            if (mLayout.mSwipeDirection == EdgeSwipeEventFilter.ScrollDirection.UNKNOWN &&
                    mLayoutManager.isActiveLayout(EdgeNavigationLayout.this)) {
                int index = tab.getWebContents().getNavigationController()
                        .getLastCommittedEntryIndex();

                Log.e("EdgeNav", "Show live view: index "
                        + index + " expected " + mNextHistoryIndex);

                if (index == mNextHistoryIndex) {
                    mLayout.goLive(mLayout.mLiveLayout);
                }
            }
        }
    }

    private class EdgeNavigationTabModelObserver extends EmptyTabModelObserver {
        @Override
        public void didSelectTab(Tab tab, TabModel.TabSelectionType type, int lastId) {
            tab.addObserver(mTabObserver);
        }
    }

    private class EdgeNavigationSwipeHandler extends EdgeSwipeHandlerLayoutDelegate {
        public EdgeNavigationSwipeHandler(LayoutProvider provider) {
            super(provider);
        }

        @Override
        public void swipeStarted(EdgeSwipeEventFilter.ScrollDirection direction, float x, float y) {
            if (direction == EdgeSwipeEventFilter.ScrollDirection.LEFT ||
                    direction == EdgeSwipeEventFilter.ScrollDirection.RIGHT) {
                if (mLiveLayout != null) {
                    mLiveLayout = mLayoutManager.getActiveLayout();
                }
                mLayoutManager.startShowing(EdgeNavigationLayout.this, false);
                mLayoutManager.setNextLayout(mLiveLayout);
            }
            super.swipeStarted(direction, x, y);
        }


        @Override
        public boolean isSwipeEnabled(EdgeSwipeEventFilter.ScrollDirection direction) {
            if (direction == EdgeSwipeEventFilter.ScrollDirection.LEFT ||
                    direction == EdgeSwipeEventFilter.ScrollDirection.RIGHT) {
                return true;
            }
            return false;
        }

        @Override
        public void swipeUpdated(float x, float y, float dx, float dy, float tx, float ty) {
            super.swipeUpdated(x, y, dx, dy, tx, ty);
        }

        @Override
        public void swipeFinished() {
            super.swipeFinished();
        }
    }

    private static class EdgeNavigationEventFilter extends EventFilter {
        private EdgeNavigationLayout mLayout;

        public EdgeNavigationEventFilter(Context context, EventFilterHost host) {
            super(context, host);
        }

        @Override
        protected boolean onInterceptTouchEventInternal(MotionEvent event,
                                                        boolean isKeyboardShowing) {
            if (mLayout == null) {
                return false;
            }

            float x = event.getX();
            float width = mLayout.mLayoutManager.getViewportWidth();
            if (x <= FINGER_WIDTH_MARGIN || x >= (width - FINGER_WIDTH_MARGIN)) {
                mLayout.mSwipeRecognizer.onTouchEvent(event);
                return true;
            }

            return false;
        }

        @Override
        protected boolean onTouchEventInternal(MotionEvent event) {
            if (mLayout == null) {
                return false;
            }

            mLayout.mSwipeRecognizer.onTouchEvent(event);
            return true;
        }

        public void init(EdgeNavigationLayout layout) {
            mLayout = layout;
        }

    }

    private class EdgeNavigationLayoutTab extends LayoutTab {
        private int mColor;
        private boolean mbBitmapView;
        public EdgeNavigationLayoutTab(int tabId,
                                       float maxContentTextureWidth,
                                       float maxContentTextureHeight,
                                       boolean showCloseButton, boolean isTitleNeeded) {
            super(tabId, true, maxContentTextureWidth, maxContentTextureHeight,
                    showCloseButton, isTitleNeeded);
            mColor = super.getBackgroundColor();
            mbBitmapView = false;
        }

        @Override
        public int getBackgroundColor() {
            return mColor;
        }

        @Override
        public boolean canUseLiveTexture() {
            return !mbBitmapView;
        }

        public void setBackgroundColor(int color) {
            mColor = color;
        }

        public void setBackgroundColorRes(int colorRes) {
            int color = EdgeNavigationLayout.this.getContext().getResources().getColor(colorRes);
            mColor = color;
        }

        public void setBitmapView() {
            mbBitmapView = true;
        }
    }

    public static EdgeNavigationLayout getNewLayout(Context context,
                                                    LayoutManager manager,
                                                    LayoutRenderHost renderHost) {
        return new EdgeNavigationLayout(context, manager, renderHost,
                              new EdgeNavigationEventFilter(context, manager));

    }

    public EventFilter getEventFilter() {
        return mEventFilter;
    }

    private EdgeNavigationLayout(Context context,
                                 LayoutManager manager,
                                 LayoutRenderHost renderHost,
                                 EventFilter filter) {
        super(context, manager, renderHost, filter);

        mSwipeRecognizer = new SwipeRecognizer(context);
        mSwipeRecognizer.setSwipeHandler(new EdgeNavigationSwipeHandler(manager));

        assert (filter instanceof EdgeNavigationEventFilter);
        ((EdgeNavigationEventFilter) filter).init(this);

        mEventFilter = filter;
        mTabListSceneLayer = new TabListSceneLayer();
        mLayoutManager = manager;

        Resources res = context.getResources();
        mToolbarHeight = res.getDimension(R.dimen.toolbar_height_no_shadow);

        mTabObserver = new EdgeNavigationTabObserver(this);
        mFaviconHelper = new FaviconHelper();
    }

    @Override
    public void setTabModelSelector(TabModelSelector modelSelector, TabContentManager manager) {
        super.setTabModelSelector(modelSelector, manager);
        if (modelSelector == null)
            return;

        TabModelObserver modelObserver = new EdgeNavigationTabModelObserver();
        List<TabModel> list = mTabModelSelector.getModels();
        for (TabModel model : list) {
            model.addObserver(modelObserver);
        }

        if (modelSelector.getCurrentTab() != null)
            modelSelector.getCurrentTab().addObserver(mTabObserver);

/*
        Bitmap image = createBitmapFromText("No more navigation entries");
        manager.cacheThumbnailWithID(INVALID_NAV_VIEW_ID, image);
*/
    }

    @Override
    public void show(long time, boolean animate) {
        super.show(time, animate);

        if (mTabModelSelector == null
                || mTabModelSelector.getCurrentModel() == null
                || mTabModelSelector.getCurrentTabId() == TabModel.INVALID_TAB_INDEX) {
            return;
        }

        Tab tab = mTabModelSelector.getCurrentTab();
        if (tab != null && tab.isNativePage()) {
            mTabContentManager.cacheTabThumbnail(tab);
        }

        mCurrentView = newLayoutView(mTabModelSelector.getCurrentTabId());
        resetState();
    }

    @Override
    protected void updateLayout(long time, long dt) {
        super.updateLayout(time, dt);

        mOffset = MathUtils.clamp(mOffset,
                mOffsetTarget - FINGER_WIDTH_MARGIN / 2,
                mOffsetTarget + FINGER_WIDTH_MARGIN / 2);

        mOffset = MathUtils.interpolate(mOffset, mOffsetTarget, INTERPOLATOR_SPEED);

        if (Math.abs(mOffset - mOffsetTarget) >= MIN_SWIPE) {
            mOffset = MathUtils.clamp(mOffset, mOffsetBounds[0], mOffsetBounds[1]);
            LayoutTab slidingView = mCurrentView;
            LayoutTab stationaryView = mNextView;

            if (mSwipeDirection == EdgeSwipeEventFilter.ScrollDirection.LEFT) {
                slidingView = mNextView;
                stationaryView = mCurrentView;
            }

            if (slidingView != null) {
                slidingView.setX(mOffset);
                slidingView.updateSnap(dt);

                if (stationaryView != null) {
                    float visibility = mOffset / getWidth();
                    float alpha = MathUtils.clamp(visibility, STATIONARY_VIEW_MIN_ALPHA,
                            STATIONARY_VIEW_MAX_ALPHA);
                    stationaryView.setAlpha(alpha);
                }

                requestUpdate();
            }
        }
    }

    @Override
    public void setProperty(Property prop, float value) {
        if (prop == Property.OFFSET) {
            mOffsetTarget = value;
        }
    }

    @Override
    public void swipeStarted(long time, EdgeSwipeEventFilter.ScrollDirection direction,
                             float x, float y) {
        if (mTabModelSelector == null || mNextView != null ||
                (direction != EdgeSwipeEventFilter.ScrollDirection.LEFT &&
                        direction != EdgeSwipeEventFilter.ScrollDirection.RIGHT)
                || mTabModelSelector.getCurrentModel() == null) {
            return;
        }

        Tab tab = mTabModelSelector.getCurrentTab();

        if (tab == null) {
            return;
        }

        resetState();

        mSwipeDirection = direction;

        if (mSwipeDirection == EdgeSwipeEventFilter.ScrollDirection.LEFT) {
            if (!tab.canGoForward()) {
                mOffsetBounds[0] = 2 * getWidth() / 3;
                setupViewForNavigation(NAV_FWD + 1);
            } else {
                setupViewForNavigation(NAV_FWD);
            }
            mCurrentView.setY(mToolbarHeight);
            mOffsetStart = getWidth();
            mLayoutTabs = new LayoutTab[]{mCurrentView, mNextView};
        } else {
            if (!tab.canGoBack()) {
                mOffsetBounds[1] = getWidth() / 3;
                setupViewForNavigation(NAV_BACK - 1);
            } else {
                setupViewForNavigation(NAV_BACK);
            }
            mNextView.setX(0);
            mOffsetStart = 0;
            mLayoutTabs = new LayoutTab[]{mNextView, mCurrentView};
        }

        requestUpdate();
    }

    @Override
    public void swipeUpdated(long time, float x, float y, float dx, float dy, float tx, float ty) {
        mOffsetTarget = MathUtils.clamp(mOffsetStart + tx, 0, getWidth());
        mLastSwipeMovement = (tx > 0) ? EdgeSwipeEventFilter.ScrollDirection.RIGHT :
                EdgeSwipeEventFilter.ScrollDirection.LEFT;
        requestUpdate();
    }

    protected void onAnimationFinished() {
        if (mbSettlingViews) {
            mSwipeDirection = EdgeSwipeEventFilter.ScrollDirection.UNKNOWN;
            Tab tab = mTabModelSelector.getCurrentTab();
            if ((mbNavigateForward || mbNavigateBack) && tab != null) {
                if (mbNavigateForward && tab.canGoForward()) {
                    updateHistoryIndex(NAV_FWD);
                } else if (mbNavigateBack && tab.canGoBack()) {
                    updateHistoryIndex(NAV_BACK);
                } else {
                    goLive(mLiveLayout);
                }
            } else {
                goLive(mLiveLayout);
            }
            mbSettlingViews = false;
        }
    }

    @Override
    public void swipeFlingOccurred(long time, float x, float y, float tx,
                                   float ty, float vx, float vy) {
        if (mbNavigationPossible) {
            settleSlidingView(vx < -FLING_MIN_VELOCITY, vx > FLING_MIN_VELOCITY);
        }
    }

    @Override
    public void swipeFinished(long time) {
        float commitDistance = getWidth() / 2;
        settleSlidingView(mOffset < commitDistance, mOffset > commitDistance);
        requestRender();
    }

    @Override
    public void swipeCancelled(long time) {
        swipeFinished(time);
    }


    @Override
    protected SceneLayer getSceneLayer() {
        return mTabListSceneLayer;
    }

    @Override
    protected void updateSceneLayer(Rect viewport, Rect contentViewport,
                                    LayerTitleCache layerTitleCache,
                                    TabContentManager tabContentManager,
                                    ResourceManager resourceManager,
                                    ChromeFullscreenManager fullscreenManager) {
        super.updateSceneLayer(viewport, contentViewport, layerTitleCache, tabContentManager,
                resourceManager, fullscreenManager);
        if (mTabListSceneLayer != null) {
            mTabListSceneLayer.pushLayers(getContext(), viewport, contentViewport,
                    this, layerTitleCache, tabContentManager, resourceManager);
        }
    }

    private void setupViewForNavigation(int nav_offset) {
        Tab tab = mTabModelSelector.getCurrentTab();
        int nav_idx = mNextHistoryIndex + nav_offset;
        NavigationEntry entry = tab.getWebContents().getNavigationController()
                .getEntryAtIndex(nav_idx);

        if (entry == null) {
            mNextView = newLayoutView(INVALID_NAV_VIEW_ID);
            mNextView.setBackgroundColorRes(R.color.tab_switcher_background);
            mbNavigationPossible = false;
        } else {
            mNextView = newLayoutView(generateThumbnailID(tab, nav_idx));

            if (entry.getFavicon() == null) {
                mNextView.setBackgroundColorRes(R.color.light_active_color);
            } else {
                mNextView.setBackgroundColor(
                        FaviconHelper.getDominantColorForBitmap(entry.getFavicon()));
            }
        }
        mNextView.setBitmapView();
    }

    private EdgeNavigationLayoutTab newLayoutView(int viewId) {
        EdgeNavigationLayoutTab view = new EdgeNavigationLayoutTab(viewId,
                getWidth(), getHeight(), NO_CLOSE_BUTTON, NO_TITLE);

        if (view.shouldStall()) {
            view.setSaturation(0.0f);
        }

        view.setBorderScale(SHADOW_WIDTH);
        view.setDecorationAlpha(SHADOW_ALPHA);
        view.setDrawDecoration(true);

        return view;
    }

    private void resetState() {
        mLayoutTabs = null;
        mNextView = null;
        mOffsetStart = 0;
        mOffset = 0;
        mOffsetTarget = 0;
        mSwipeDirection = EdgeSwipeEventFilter.ScrollDirection.UNKNOWN;
        mbSettlingViews = false;
        mOffsetBounds = new float[]{0.f, getWidth()};
        mbNavigationPossible = true;
        mbNavigateBack = false;
        mbNavigateForward = false;
    }

    private void animateSwipe(final float start, final float end) {
        long duration = (long) (ANIMATION_SPEED_SCREEN * Math.abs(start - end) / getWidth());
        forceAnimationToFinish();
        if (duration > 0) {
            addToAnimation(this, Property.OFFSET, start, end, duration, 0);
        }
    }

    private void settleSlidingView(boolean leftCondition, boolean rightCondition) {
        if (mLastSwipeMovement == mSwipeDirection) {
            mLastSwipeMovement = EdgeSwipeEventFilter.ScrollDirection.UNKNOWN;
            switch (mSwipeDirection) {
                case LEFT:
                    mbSettlingViews = true;
                    if (leftCondition) {
                        animateSwipe(mOffset, 0);
                        mbNavigateForward = true;
                    } else {
                        animateSwipe(mOffset, getWidth());
                    }
                    break;
                case RIGHT:
                    mbSettlingViews = true;
                    if (rightCondition) {
                        animateSwipe(mOffset, getWidth());
                        mbNavigateBack = true;
                    } else {
                        animateSwipe(mOffset, 0);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void goLive(final Layout liveLayout) {
        if (liveLayout != null) {
            mLayoutManager.setNextLayout(liveLayout);
        }
        doneHiding();
    }

    private void updateHistoryIndex(int nextIndexOffset) {
        Tab tab = mTabModelSelector.getCurrentTab();
        NavigationController controller = tab.getWebContents().getNavigationController();

        mCurrHistoryIndex = controller.getLastCommittedEntryIndex();
        mNextHistoryIndex += nextIndexOffset;

        Log.e("EdgeNav", "Update index: index " + mCurrHistoryIndex +
                " next expected " + mNextHistoryIndex);

        controller.goToNavigationIndex(mNextHistoryIndex);
    }

    public static int generateThumbnailID(Tab tab, int navID) {
        return tab.getId() | RESERVED_BIT_VAL | navID << (NUM_TAB_ID_BITS + NUM_RESERVED_BITS);
    }

    public static void captureBeforeNavigation(int index, Tab tab, TabContentManager manager) {
        if (manager != null && tab != null) {
            int snapshotID = generateThumbnailID(tab, index);

            if (!(tab.getProgress() < MIN_LOAD_PROGRESS
                    && manager.hasFullCachedThumbnail(snapshotID))) {
                manager.cacheTabThumbnailWithID(tab, snapshotID);
            }
        }
    }

    public Bitmap createBitmapFromText(String text) {
        Paint paint = new Paint();
        paint.setTextSize(30.f);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);

        float baseline = -paint.ascent();
        int width = (int) paint.measureText(text);
        int height = (int) (baseline + paint.descent());
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_4444);

        Canvas canvas = new Canvas(image);
        canvas.drawText(text, 0, baseline, paint);

        Matrix matrix = new Matrix();
        matrix.postRotate(90);

        return Bitmap.createBitmap(image, 0, 0, width, height, matrix, true);
    }
}
