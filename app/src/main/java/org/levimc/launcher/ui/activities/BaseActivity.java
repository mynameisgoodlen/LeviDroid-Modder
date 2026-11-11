package org.levimc.launcher.ui.activities;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.news.NewsFeed;
import org.levimc.launcher.core.news.NewsRepository;
import org.levimc.launcher.core.news.NewsState;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.AccountTextUtils;
import org.levimc.launcher.util.AmoledContext;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.LauncherBackgroundController;
import org.levimc.launcher.util.ThemeManager;
import org.levimc.launcher.util.ThemePalette;
import org.levimc.launcher.util.UiSettings;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BaseActivity extends AppCompatActivity {
    private int appliedThemeGeneration = -1;
    private int appliedPersonalizationGeneration = -1;
    private int appliedBackgroundEffectGeneration = -1;
    private int appliedLayoutGeneration = -1;
    private boolean navBarInjected = false;
    private TextView[] railLabels;
    private int railCollapsedWidth;
    private int railExpandedWidth;
    private SpringAnimation railSpring;
    private boolean railHovered;

    // Rail width animation reflows the wrapper every frame; syncing label
    // alpha here keeps the text fade locked to the spring motion.
    private final FloatPropertyCompat<View> railWidthProperty = new FloatPropertyCompat<View>("railWidth") {
        @Override
        public float getValue(View view) {
            return view.getLayoutParams().width;
        }

        @Override
        public void setValue(View view, float value) {
            view.getLayoutParams().width = Math.round(value);
            view.requestLayout();
            if (railLabels != null && railExpandedWidth > railCollapsedWidth) {
                float progress = (value - railCollapsedWidth) / (railExpandedWidth - railCollapsedWidth);
                for (TextView label : railLabels) {
                    if (label != null) label.setAlpha(progress);
                }
            }
        }
    };

    private int activeNavTabId = View.NO_ID;
    private boolean finishingByBack;
    private static final ArrayDeque<Intent> sForwardHistory = new ArrayDeque<>();
    private static final Set<Class<?>> sLivePages = new HashSet<>();
    private static final String EXTRA_FROM_FORWARD = "org.levimc.launcher.extra.FROM_FORWARD";
    private final OkHttpClient navAvatarClient = new OkHttpClient();
    private final ExecutorService navAccountExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<Intent> navAccountLoginLauncher;
    private boolean newsReceiverRegistered;
    private final BroadcastReceiver newsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshNewsBadge();
            onNewsChanged();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        String languageCode = LanguageManager.getSavedLanguage(newBase);
        Locale locale = Locale.forLanguageTag(languageCode);
        Locale.setDefault(locale);
        Resources res = newBase.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        Context localizedContext = newBase.createConfigurationContext(config);
        if (ThemeManager.isAmoledMode(newBase)) {
            localizedContext = new AmoledContext(localizedContext);
        }
        super.attachBaseContext(localizedContext);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager themeManager = new ThemeManager(this);
        themeManager.applyTheme();
        appliedThemeGeneration = ThemeManager.getThemeChangeGeneration();
        appliedPersonalizationGeneration = PersonalizationManager.getChangeGeneration();
        appliedBackgroundEffectGeneration = PersonalizationManager.getBackgroundEffectGeneration();
        appliedLayoutGeneration = UiSettings.getLayoutGeneration();
        UiSettings.refreshAnimations(this);
        super.onCreate(savedInstanceState);
        sLivePages.add(getClass());
        if (savedInstanceState == null && !getIntent().getBooleanExtra(EXTRA_FROM_FORWARD, false)) {
            sForwardHistory.clear();
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishingByBack = true;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
                if (!isFinishing()) finishingByBack = false;
            }
        });
        navAccountLoginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleNavAccountLoginResult(result.getResultCode(), result.getData()));
        hideSystemUI();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                visibility -> getWindow().getDecorView().post(this::hideSystemUI));
    }

    @Override
    public void setContentView(int layoutResID) {
        View contentView = LayoutInflater.from(this).inflate(layoutResID, null);
        wrapWithNavBar(contentView);
    }

    @Override
    public void setContentView(View view) {
        wrapWithNavBar(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        wrapWithNavBar(view);
    }

    private void wrapWithNavBar(View contentView) {
        if (shouldSkipNavBar()) {
            super.setContentView(contentView);
            if (avoidsCutoutWithoutShell()) org.levimc.launcher.ui.widget.CutoutAvoider.attachToPage(contentView);
            applyPersonalization();
            return;
        }

        String navPos = new PersonalizationManager(this).getNavBarPosition();
        boolean sideRail = "left".equals(navPos) || "right".equals(navPos);
        boolean navFirst = "top".equals(navPos) || "left".equals(navPos);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(sideRail ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        View navBar = LayoutInflater.from(this).inflate(
                sideRail ? R.layout.nav_bar_side : R.layout.nav_bar, wrapper, false);
        if (sideRail) setupSideRail(navBar);

        LinearLayout.LayoutParams contentParams = sideRail
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentView.setLayoutParams(contentParams);

        if (navFirst) {
            wrapper.addView(navBar);
            wrapper.addView(contentView);
        } else {
            wrapper.addView(contentView);
            wrapper.addView(navBar);
        }

        contentView.setAlpha(0f);
        contentView.setTranslationY(8f * getResources().getDisplayMetrics().density);

        super.setContentView(wrapper);
        navBarInjected = true;
        setupBaseNavBar();

        applyPersonalization();

        if (!UiSettings.animationsDisabled()) {
            contentView.post(() -> {
                DynamicAnim.springAlphaTo(contentView, 1f).start();
                DynamicAnim.springTranslationYTo(contentView, 0f).start();
            });
        }
    }

    private void setupSideRail(View navBar) {
        float density = getResources().getDisplayMetrics().density;
        railCollapsedWidth = (int) (56 * density);
        railExpandedWidth = navBar.getLayoutParams().width;

        railLabels = new TextView[]{
                findViewById(R.id.nav_tab_launch),
                findViewById(R.id.nav_tab_instances),
                findViewById(R.id.nav_tab_about),
                findViewById(R.id.nav_tab_settings)
        };
        for (TextView label : railLabels) {
            if (label != null) label.setAlpha(0f);
        }
        navBar.getLayoutParams().width = railCollapsedWidth;

        railSpring = new SpringAnimation(navBar, railWidthProperty);
        railSpring.setSpring(new SpringForce(railCollapsedWidth)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        railSpring.setStartValue(railCollapsedWidth);

        navBar.setOnHoverListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER -> {
                    railHovered = true;
                    expandRail();
                }
                case MotionEvent.ACTION_HOVER_EXIT -> {
                    railHovered = false;
                    collapseRail();
                }
            }
            return false;
        });

        // Touch peek: expand while the finger is down and collapse on release.
        // Events are never consumed, so taps on the tab wrappers still fire.
        navBar.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> expandRail();
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> collapseRail();
            }
            return false;
        });
    }

    private void expandRail() {
        if (railSpring != null) railSpring.animateToFinalPosition(railExpandedWidth);
    }

    private void collapseRail() {
        // A mouse click inside a hovered rail fires ACTION_UP; keep it open
        // until the pointer actually leaves.
        if (railSpring != null && !railHovered) railSpring.animateToFinalPosition(railCollapsedWidth);
    }

    // In the side-rail layout each label sits in its own wrapper that owns the
    // click, so taps on the icon register too; in the bar layout the parent is
    // the shared tabs row, so keep binding the TextView itself.
    private void bindNavTab(int tabId, Runnable action) {
        TextView tab = findViewById(tabId);
        if (tab == null) return;
        View parent = tab.getParent() instanceof View ? (View) tab.getParent() : null;
        View hit = parent != null && parent.getId() != View.NO_ID ? parent : tab;
        hit.setOnClickListener(v -> action.run());
    }

    private void tintRailIcon(int tabId, int color) {
        int iconId = railIconId(tabId);
        if (iconId == 0) return;
        ImageView icon = findViewById(iconId);
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(color));
    }

    private int railIconId(int tabId) {
        if (tabId == R.id.nav_tab_launch) return R.id.nav_tab_icon_launch;
        if (tabId == R.id.nav_tab_instances) return R.id.nav_tab_icon_instances;
        if (tabId == R.id.nav_tab_about) return R.id.nav_tab_icon_about;
        if (tabId == R.id.nav_tab_settings) return R.id.nav_tab_icon_settings;
        return 0;
    }

    private void applyPersonalization() {
        PersonalizationManager pm = new PersonalizationManager(this);
        pm.applyToActivity(this);
    }

    protected boolean shouldSkipNavBar() {
        return false;
    }

    protected boolean avoidsCutoutWithoutShell() {
        return !(this instanceof SplashActivity);
    }

    private static final int[] NAV_TAB_IDS = {
            R.id.nav_tab_launch, R.id.nav_tab_download, R.id.nav_tab_instances,
            R.id.nav_tab_about, R.id.nav_tab_settings
    };

    private void setupBaseNavBar() {
        PersonalizationManager pm = new PersonalizationManager(this);
        if (pm.hasBackgroundEffect()) {
            View navRoot = findViewById(R.id.nav_bar_root);
            if (navRoot != null) {
                boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                navRoot.setBackgroundColor(isDark
                        ? android.graphics.Color.argb(90, 25, 25, 25)
                        : android.graphics.Color.argb(110, 255, 255, 255));
            }
            View navDivider = findViewById(R.id.nav_divider);
            if (navDivider != null) {
                boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                navDivider.setBackgroundColor(isDark
                        ? android.graphics.Color.argb(40, 255, 255, 255)
                        : android.graphics.Color.argb(40, 0, 0, 0));
            }
        }

        for (int id : NAV_TAB_IDS) {
            styleNavTab(findViewById(id), false);
            tintRailIcon(id, ContextCompat.getColor(this, R.color.ui_muted));
        }

        View backButton = findViewById(R.id.nav_back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
        View forwardButton = findViewById(R.id.nav_forward_button);
        if (forwardButton != null) {
            forwardButton.setOnClickListener(v -> {
                Intent next = sForwardHistory.pollFirst();
                if (next == null) return;
                next.putExtra(EXTRA_FROM_FORWARD, true);
                startActivity(next);
            });
        }
        refreshHistoryButtons();

        View signIn = findViewById(R.id.nav_sign_in_button);
        if (signIn != null) {
            signIn.setOnClickListener(v -> navAccountLoginLauncher.launch(new Intent(this, MsftLoginActivity.class)));
        }

        View avatarContainer = findViewById(R.id.nav_account_avatar_container);
        if (avatarContainer != null) {
            avatarContainer.setOnClickListener(v -> {
                if (!(this instanceof AccountsActivity)) startActivity(new Intent(this, AccountsActivity.class));
            });
            DynamicAnim.applyPressScale(avatarContainer);
        }

        View news = findViewById(R.id.nav_news_container);
        if (news != null) {
            news.setOnClickListener(v -> {
                if (!(this instanceof NewsActivity)) {
                    startActivity(new Intent(this, NewsActivity.class));
                }
            });
        }

        bindNavTab(R.id.nav_tab_launch, () -> {
            if (!(this instanceof MainActivity)) {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        });
        bindNavTab(R.id.nav_tab_download, () -> {
            if (!(this instanceof GameDownloadActivity)) {
                startActivity(new Intent(this, GameDownloadActivity.class));
            }
        });
        bindNavTab(R.id.nav_tab_instances, () -> {
            if (!(this instanceof InstancesActivity)) {
                startActivity(new Intent(this, InstancesActivity.class));
            }
        });
        bindNavTab(R.id.nav_tab_about, () -> {
            if (!(this instanceof AboutActivity)) {
                startActivity(new Intent(this, AboutActivity.class));
            }
        });
        bindNavTab(R.id.nav_tab_settings, () -> {
            if (!(this instanceof SettingsActivity)) {
                startActivity(new Intent(this, SettingsActivity.class));
            }
        });

        View railScroll = findViewById(R.id.nav_rail_scroll);
        if (railScroll != null) {
            railScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> placeRailIndicator());
        }

        refreshBreadcrumbs();
        refreshNavAccountUI();
        refreshNewsBadge();
    }

    private void refreshHistoryButtons() {
        View back = findViewById(R.id.nav_back_button);
        if (back != null) {
            boolean canBack = !isTaskRoot();
            back.setEnabled(canBack);
            back.setAlpha(canBack ? 1f : 0.35f);
        }
        View forward = findViewById(R.id.nav_forward_button);
        if (forward != null) {
            boolean canForward = !sForwardHistory.isEmpty();
            forward.setEnabled(canForward);
            forward.setAlpha(canForward ? 1f : 0.35f);
        }
    }

    @Nullable
    protected CharSequence getBreadcrumbTitle() {
        NavRoutes.Route route = NavRoutes.get(getClass());
        return route != null ? getString(route.title) : getTitle();
    }

    protected void refreshBreadcrumbs() {
        LinearLayout crumbs = findViewById(R.id.nav_breadcrumbs);
        if (crumbs == null) return;
        TextView appName = findViewById(R.id.nav_app_name);
        for (int i = crumbs.getChildCount() - 1; i >= 0; i--) {
            if (crumbs.getChildAt(i) != appName) crumbs.removeViewAt(i);
        }
        if (this instanceof MainActivity) {
            if (appName != null) appName.setVisibility(View.VISIBLE);
            return;
        }
        if (appName != null) appName.setVisibility(View.GONE);

        java.util.List<Class<? extends android.app.Activity>> chain = new java.util.ArrayList<>();
        NavRoutes.Route route = NavRoutes.get(getClass());
        Class<? extends android.app.Activity> parent = route != null ? route.parent : MainActivity.class;
        while (parent != null && chain.size() < 6) {
            chain.add(0, parent);
            NavRoutes.Route r = NavRoutes.get(parent);
            parent = r != null ? r.parent : null;
        }
        float density = getResources().getDisplayMetrics().density;
        int muted = ContextCompat.getColor(this, R.color.text_secondary);
        for (Class<? extends android.app.Activity> page : chain) {
            NavRoutes.Route r = NavRoutes.get(page);
            TextView crumb = makeCrumb(r != null ? getString(r.title) : page.getSimpleName(), false);
            boolean reachable = r != null && (r.standalone || sLivePages.contains(page));
            if (reachable) {
                crumb.setBackgroundResource(R.drawable.bg_shell_icon_button);
                crumb.setOnClickListener(v -> {
                    Intent intent = new Intent(this, page);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                });
            }
            crumbs.addView(crumb);
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_ui_chevron_right);
            chevron.setImageTintList(ColorStateList.valueOf(muted));
            int size = Math.round(16 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(Math.round(2 * density));
            lp.setMarginEnd(Math.round(2 * density));
            crumbs.addView(chevron, lp);
        }
        CharSequence title = getBreadcrumbTitle();
        crumbs.addView(makeCrumb(title != null ? title : "", true));
    }

    private TextView makeCrumb(CharSequence text, boolean current) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setSingleLine(true);
        t.setTextSize(14);
        t.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.misans),
                current ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextColor(ContextCompat.getColor(this, current ? R.color.text_primary : R.color.text_secondary));
        float density = getResources().getDisplayMetrics().density;
        int padH = Math.round(6 * density);
        t.setPadding(padH, 0, padH, 0);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setMinHeight(Math.round(40 * density));
        return t;
    }

    private void styleNavTab(@Nullable View view, boolean active) {
        if (!(view instanceof TextView)) return;
        TextView tab = (TextView) view;
        boolean navbar = UiSettings.useTopNavbar(this);
        PersonalizationManager pm = new PersonalizationManager(this);
        boolean dark = pm.isDarkMode(this);
        int accent = pm.getAccentColor(dark);
        float density = getResources().getDisplayMetrics().density;
        if (navbar) {
            int iconSize = getResources().getDimensionPixelSize(R.dimen.levi_navbar_icon_size);
            android.graphics.drawable.Drawable[] icons = tab.getCompoundDrawablesRelative();
            for (android.graphics.drawable.Drawable icon : icons) {
                if (icon != null) icon.setBounds(0, 0, iconSize, iconSize);
            }
            tab.setCompoundDrawablesRelative(icons[0], icons[1], icons[2], icons[3]);
            // As in v1.6.1: no pill behind the active tab; it is bold in the primary text color and
            // the other tabs use the secondary text color.
            int text = ContextCompat.getColor(this, active ? R.color.text_primary : R.color.text_secondary);
            tab.setTextColor(text);
            tab.setTypeface(Typeface.create(tab.getTypeface(), active ? Typeface.BOLD : Typeface.NORMAL));
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(text));
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            tab.setBackgroundResource(ripple.resourceId);
        } else {
            int icon = active ? ThemePalette.foreground(accent, dark) : ContextCompat.getColor(this, R.color.ui_muted);
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(icon));
            if (active) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(12 * density);
                bg.setColor(ThemePalette.withAlpha(accent, 0.10f));
                tab.setBackground(bg);
            } else {
                tab.setBackgroundResource(R.drawable.bg_shell_icon_button);
            }
        }
    }

    private void placeRailIndicator() {
        View indicator = findViewById(R.id.nav_rail_indicator);
        if (indicator == null) return;
        View tab = activeNavTabId != View.NO_ID ? findViewById(activeNavTabId) : null;
        if (tab == null) {
            indicator.setVisibility(View.INVISIBLE);
            return;
        }
        indicator.setBackgroundTintList(ColorStateList.valueOf(new PersonalizationManager(this).getAccentColor(
                new PersonalizationManager(this).isDarkMode(this))));
        View rail = findViewById(R.id.nav_rail);
        tab.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                tab.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] tabLoc = new int[2];
                int[] railLoc = new int[2];
                tab.getLocationInWindow(tabLoc);
                (rail != null ? rail : (View) indicator.getParent()).getLocationInWindow(railLoc);
                float y = tabLoc[1] - railLoc[1] + (tab.getHeight() - indicator.getHeight()) / 2f;
                indicator.setTranslationY(y);
                indicator.setVisibility(View.VISIBLE);
                return true;
            }
        });
        tab.invalidate();
    }

    private void refreshNewsBadge() {
        if (!navBarInjected) return;
        NewsRepository.loadCached(this, (feed, error) -> applyNewsBadge(feed));
        NewsRepository.refreshIfStale(this, (feed, error) -> applyNewsBadge(feed));
    }

    private void applyNewsBadge(NewsFeed feed) {
        if (isFinishing() || isDestroyed()) return;
        int unread = NewsState.getUnreadCount(this, feed);
        View badge = findViewById(R.id.nav_news_badge);
        View container = findViewById(R.id.nav_news_container);
        if (badge != null) badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        if (container != null) {
            container.setContentDescription(unread > 0
                    ? getString(R.string.news_unread_description, unread)
                    : getString(R.string.news_title));
        }
    }

    public void markPersonalizationApplied() {
        appliedPersonalizationGeneration = PersonalizationManager.getChangeGeneration();
    }

    protected void onNewsChanged() {
    }

    protected void refreshNavAccountUI() {
        if (!navBarInjected) return;
        java.util.List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        MsftAccountStore.MsftAccount active = null;
        for (MsftAccountStore.MsftAccount a : list) if (a.active) { active = a; break; }
        View signIn = findViewById(R.id.nav_sign_in_button);
        View avatarContainer = findViewById(R.id.nav_account_avatar_container);
        if (active == null) {
            if (signIn != null) signIn.setVisibility(View.VISIBLE);
            if (avatarContainer != null) avatarContainer.setVisibility(View.GONE);
            clearNavAvatar();
        } else {
            if (signIn != null) signIn.setVisibility(View.GONE);
            if (avatarContainer != null) avatarContainer.setVisibility(View.VISIBLE);
            loadNavXboxAvatar(active);
        }
    }

    private void clearNavAvatar() {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.nav_account_avatar);
        ProgressBar progress = findViewById(R.id.nav_avatar_progress);
        if (avatar != null) avatar.setImageResource(R.drawable.ic_minecraft_cube);
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void loadNavXboxAvatar(MsftAccountStore.MsftAccount active) {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.nav_account_avatar);
        ProgressBar progress = findViewById(R.id.nav_avatar_progress);
        if (avatar == null) return;

        String url = AccountTextUtils.sanitizeUrl(active != null ? active.xboxAvatarUrl : null);
        if (url == null) {
            avatar.setImageResource(R.drawable.ic_minecraft_cube);
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        Object currentUrl = avatar.getTag(R.id.nav_account_avatar);
        if (url.equals(currentUrl) && avatar.getDrawable() != null) {
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        Bitmap cached = AccountTextUtils.getCachedAvatar(url);
        if (cached != null) {
            avatar.setTag(R.id.nav_account_avatar, url);
            avatar.setImageBitmap(cached);
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        avatar.setTag(R.id.nav_account_avatar, url);
        avatar.setImageResource(R.drawable.ic_minecraft_cube);
        if (progress != null) progress.setVisibility(View.VISIBLE);
        navAccountExecutor.execute(() -> {
            Bitmap bmp = null;
            try (Response imgResp = navAvatarClient.newCall(new Request.Builder().url(url).build()).execute()) {
                if (imgResp.isSuccessful() && imgResp.body() != null) {
                    bmp = android.graphics.BitmapFactory.decodeStream(imgResp.body().byteStream());
                }
            } catch (Exception ignored) {
            }

            final Bitmap loaded = bmp;
            runOnUiThread(() -> {
                if (!url.equals(avatar.getTag(R.id.nav_account_avatar))) return;
                if (loaded != null) {
                    AccountTextUtils.cacheAvatar(url, loaded);
                    avatar.setImageBitmap(loaded);
                }
                if (progress != null) progress.setVisibility(View.GONE);
            });
        });
    }

    private void handleNavAccountLoginResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null
                && data.getBooleanExtra(MsftLoginActivity.EXTRA_LOGIN_COMPLETED, false)) {
            String name = data.getStringExtra(MsftLoginActivity.EXTRA_LOGIN_NAME);
            String statusName = name != null ? name : getString(R.string.not_signed_in);
            Toast.makeText(this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
            refreshNavAccountUI();
            onNavAccountChanged();
            return;
        }
        refreshNavAccountUI();
    }

    protected void onNavAccountChanged() {
    }

    protected void setActiveNavTab(int activeTabId) {
        if (!navBarInjected) return;
        activeNavTabId = activeTabId;
        PersonalizationManager pm = new PersonalizationManager(this);
        boolean dark = pm.isDarkMode(this);
        int accent = pm.getAccentColor(dark);
        for (int id : NAV_TAB_IDS) {
            boolean active = id == activeTabId;
            styleNavTab(findViewById(id), active);
            tintRailIcon(id, active ? ThemePalette.foreground(accent, dark)
                    : ContextCompat.getColor(this, R.color.ui_muted));
        }
        View activeTab = findViewById(activeTabId);
        if (activeTab != null && UiSettings.useTopNavbar(this)) {
            androidx.core.view.OneShotPreDrawListener.add(activeTab, () -> activeTab.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, activeTab.getWidth(), activeTab.getHeight()), true));
        }
        placeRailIndicator();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!newsReceiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    newsReceiver,
                    new IntentFilter(NewsState.ACTION_NEWS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            newsReceiverRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int currentGen = ThemeManager.getThemeChangeGeneration();
        int currentPGen = PersonalizationManager.getChangeGeneration();
        int currentLayout = UiSettings.getLayoutGeneration();
        if (appliedThemeGeneration != currentGen || appliedPersonalizationGeneration != currentPGen
                || appliedLayoutGeneration != currentLayout) {
            appliedThemeGeneration = currentGen;
            appliedPersonalizationGeneration = currentPGen;
            appliedLayoutGeneration = currentLayout;
            recreate();
            return;
        }
        UiSettings.refreshAnimations(this);
        refreshHistoryButtons();
        getDelegate().applyDayNight();
        hideSystemUI();
        int currentEffects = PersonalizationManager.getBackgroundEffectGeneration();
        if (appliedBackgroundEffectGeneration != currentEffects) {
            appliedBackgroundEffectGeneration = currentEffects;
            new PersonalizationManager(this).refreshBackgroundEffects(this);
        }
        LauncherBackgroundController.resume(this);
        refreshNavAccountUI();
        refreshNewsBadge();
    }

    @Override
    protected void onStop() {
        LauncherBackgroundController.stopped(this);
        if (newsReceiverRegistered) {
            unregisterReceiver(newsReceiver);
            newsReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        hideSystemUI();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        navAccountExecutor.shutdownNow();
        if (isFinishing()) sLivePages.remove(getClass());
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        getDelegate().applyDayNight();
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    protected void hideSystemUI() {
        View decorView = getWindow().getDecorView();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }

        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private boolean shouldSuppressTransition(Intent intent) {
        return intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0;
    }

    private boolean hasLauncherBackground() {
        return getClass().getName().startsWith("org.levimc.launcher.ui.activities.")
                && !(this instanceof SplashActivity)
                && !LauncherBackgroundController.isMinecraftBlocked()
                && new PersonalizationManager(this).hasBackgroundEffect();
    }

    private boolean isLauncherNavigation(Intent intent) {
        return intent != null && intent.getComponent() != null
                && getPackageName().equals(intent.getComponent().getPackageName())
                && intent.getComponent().getClassName().startsWith("org.levimc.launcher.ui.")
                && !SplashActivity.class.getName().equals(intent.getComponent().getClassName());
    }

    private void applyExitTransition(boolean keepBackground) {
        if (keepBackground) overridePendingTransition(0, 0);
        else overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    public void finish() {
        if (finishingByBack && navBarInjected && !isTaskRoot()) {
            Intent again = new Intent(getIntent());
            again.removeExtra(EXTRA_FROM_FORWARD);
            again.setFlags(0);
            sForwardHistory.addFirst(again);
            while (sForwardHistory.size() > 20) sForwardHistory.pollLast();
        }
        boolean keepBackground = !isTaskRoot() && (navBarInjected || hasLauncherBackground());
        if (!isTaskRoot()) LauncherBackgroundController.navigating(this);
        super.finish();
        applyExitTransition(keepBackground);
    }

    @Override
    public void startActivity(Intent intent) {
        prepareBackgroundNavigation(intent);
        try {
            super.startActivity(intent);
        } catch (RuntimeException e) {
            LauncherBackgroundController.navigationFailed(this);
            throw e;
        }
        if (!shouldSuppressTransition(intent)) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else overridePendingTransition(0, 0);
    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        prepareBackgroundNavigation(intent);
        try {
            super.startActivity(intent, options);
        } catch (RuntimeException e) {
            LauncherBackgroundController.navigationFailed(this);
            throw e;
        }
        if (!shouldSuppressTransition(intent)) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else overridePendingTransition(0, 0);
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        prepareBackgroundNavigation(intent);
        try {
            super.startActivityForResult(intent, requestCode, options);
        } catch (RuntimeException e) {
            LauncherBackgroundController.navigationFailed(this);
            throw e;
        }
        if (shouldSuppressTransition(intent)) overridePendingTransition(0, 0);
    }

    @Override
    public void finishAfterTransition() {
        boolean keepBackground = !isTaskRoot() && (navBarInjected || hasLauncherBackground());
        if (!isTaskRoot()) LauncherBackgroundController.navigating(this);
        super.finishAfterTransition();
        applyExitTransition(keepBackground);
    }

    @Override
    public void recreate() {
        LauncherBackgroundController.navigating(this);
        super.recreate();
    }

    private void prepareBackgroundNavigation(Intent intent) {
        if (isLauncherNavigation(intent)) {
            LauncherBackgroundController.navigating(this);
            if (navBarInjected || hasLauncherBackground()) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
    }
}
