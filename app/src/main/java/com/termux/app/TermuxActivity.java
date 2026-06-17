package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import android.media.projection.MediaProjectionManager;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.mcp.ScreenCaptureService;
import com.termux.app.autotasks.AutoTaskCoordinator;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.Arrays;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * Activity 是否在前台。供进程内其他组件（如 FloatingStatusService）跨进程边界外的简单查询。
     * 只在主线程读写（onStart/onStop 都在主线程），volatile 保证可见性。
     */
    public static volatile boolean sActivityForeground = false;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;

    private AutoTaskCoordinator mAutoTaskCoordinator;


    private static final int REQUEST_CODE_SCREEN_CAPTURE  = 1001;

    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        mAutoTaskCoordinator = new AutoTaskCoordinator(this);
        mAutoTaskCoordinator.init();

        setupBottomNav();

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;
        sActivityForeground = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();

        if (mAutoTaskCoordinator != null) {
            mAutoTaskCoordinator.onStart();
        }

        // App 回到前台：隐藏悬浮窗
        if (android.provider.Settings.canDrawOverlays(this)) {
            startService(new android.content.Intent(this, FloatingStatusService.class)
                .setAction(FloatingStatusService.ACTION_HIDE));
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        if (mAutoTaskCoordinator != null) {
            mAutoTaskCoordinator.onResume();
        }

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;
        sActivityForeground = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();

        // App 切到后台：显示悬浮窗
        if (android.provider.Settings.canDrawOverlays(this)) {
            startService(new android.content.Intent(this, FloatingStatusService.class)
                .setAction(FloatingStatusService.ACTION_SHOW));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }

        if (mAutoTaskCoordinator != null) {
            mAutoTaskCoordinator.onDestroy();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                        if (mAutoTaskCoordinator != null) {
                            mAutoTaskCoordinator.onSessionReady();
                        }
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);

        if (mAutoTaskCoordinator != null) {
            mAutoTaskCoordinator.onSessionReady();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }


    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else if (handleSimplifiedUiBack()) {
            return;
        } else {
            finishActivityIfNotFinishing();
        }
    }

    private boolean handleSimplifiedUiBack() {
        View container = findViewById(R.id.home_fragment_container);
        if (container == null || container.getVisibility() != View.VISIBLE) return false;

        if (isFragmentVisible("agentserver") || isFragmentVisible("loom")) {
            navigateBackToCollaboration();
            return true;
        }

        if (isFragmentVisible("automation_settings")
                || isFragmentVisible("workspace_access_settings")) {
            navigateBackToSettingsHub();
            return true;
        }

        if (isFragmentVisible("settings_hub")
                || isFragmentVisible("collaboration")
                || isFragmentVisible("apikey")) {
            navigateBackToHomeFromSettings();
            return true;
        }

        if (isFragmentVisible("agent_task_detail")) {
            navigateBackToHomeFromSettings();
            return true;
        }

        return false;
    }

    private boolean isFragmentVisible(String tag) {
        androidx.fragment.app.Fragment fragment =
            getSupportFragmentManager().findFragmentByTag(tag);
        return fragment != null && fragment.isVisible();
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        } else if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.startWithPermission(this, resultCode, data);
            } else {
                Toast.makeText(this, "屏幕截图权限被拒绝", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 弹出系统 MediaProjection 授权对话框，结果回调到 onActivityResult。 */
    public void requestScreenCapturePermission() {
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
    }

    /** 停止当前屏幕截图服务（撤销授权）。 */
    public void stopScreenCapture() {
        ScreenCaptureService.stopCapture(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }

    // =========================================================================
    // 简化 UI / 底部导航栏支持
    // =========================================================================

    /**
     * 初始化底部导航栏：在"主页"和"终端"两个 Tab 之间切换显示模式。
     * 默认选中"终端"Tab，保持原有专业用户体验不变。
     */
    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav == null) return;

        // 默认选中"终端"Tab，保持原有专业用户体验不变
        bottomNav.setSelectedItemId(R.id.nav_terminal);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                showHomeMode();
                return true;
            } else if (id == R.id.nav_collaboration) {
                showCollaborationMode();
                return true;
            } else if (id == R.id.nav_terminal) {
                showTerminalMode();
                return true;
            } else if (id == R.id.nav_apikey) {
                showApiKeyMode();
                return true;
            } else if (id == R.id.nav_settings) {
                showSettingsHubMode();
                return true;
            }
            return false;
        });
    }

    /**
     * 切走终端模式之前，强制清掉当前焦点 + 隐藏软键盘。
     * TerminalView 持有的 IME InputConnection 不会因为 setVisibility(GONE) 自动断开，
     * 否则切到 HomeFragment 后第一次按键会被 TerminalView 偷走（变成 bash 命令）。
     */
    private void clearFocusAndHideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) {
            f.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    public void navigateBackToSettingsHub() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null && bottomNav.getSelectedItemId() != R.id.nav_settings) {
            bottomNav.setSelectedItemId(R.id.nav_settings);
        } else {
            showSettingsHubMode();
        }
    }

    public void navigateBackToCollaboration() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null && bottomNav.getSelectedItemId() != R.id.nav_collaboration) {
            bottomNav.setSelectedItemId(R.id.nav_collaboration);
        } else {
            showCollaborationMode();
        }
    }

    public void navigateBackToHomeFromSettings() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null && bottomNav.getSelectedItemId() != R.id.nav_home) {
            bottomNav.setSelectedItemId(R.id.nav_home);
        } else {
            showHomeMode();
        }
    }

    /** 切换到简化 UI（主页）模式：隐藏终端，显示 HomeFragment。 */
    public void showHomeMode() {
        DrawerLayout drawer = getDrawer();
        ViewPager toolbar = getTerminalToolbarViewPager();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null) return;

        if (drawer.isDrawerOpen(android.view.Gravity.START))
            drawer.closeDrawer(android.view.Gravity.START);

        clearFocusAndHideKeyboard();
        drawer.setVisibility(View.GONE);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF   = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF    = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF  = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF   = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF   = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF = fm.findFragmentByTag("agent_task_detail");
        if (homeF == null) {
            ft.add(R.id.home_fragment_container, new HomeFragment(), "home");
        } else {
            ft.show(homeF);
        }
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF      != null) ft.hide(apiF);
        if (agentF    != null) ft.hide(agentF);
        if (loomF     != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF     != null) ft.hide(autoF);
        if (workspaceF != null) ft.hide(workspaceF);
        if (detailF   != null) ft.remove(detailF);
        ft.commit();
    }

    /** 切换到协作控制台：汇总 AgentServer 与 Loom 的连接入口。 */
    public void showCollaborationMode() {
        DrawerLayout drawer = getDrawer();
        ViewPager toolbar = getTerminalToolbarViewPager();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null) return;

        if (drawer.isDrawerOpen(android.view.Gravity.START))
            drawer.closeDrawer(android.view.Gravity.START);

        clearFocusAndHideKeyboard();
        drawer.setVisibility(View.GONE);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF = fm.findFragmentByTag("agent_task_detail");
        if (collaborationF == null) {
            ft.add(R.id.home_fragment_container, new CollaborationFragment(), "collaboration");
        } else {
            ft.show(collaborationF);
        }
        if (homeF != null) ft.hide(homeF);
        if (apiF != null) ft.hide(apiF);
        if (agentF != null) ft.hide(agentF);
        if (loomF != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF != null) ft.hide(autoF);
        if (workspaceF != null) ft.hide(workspaceF);
        if (detailF != null) ft.remove(detailF);
        ft.commit();
    }

    /** 切换到 API Key 管理页面。 */
    private void showApiKeyMode() {
        DrawerLayout drawer = getDrawer();
        ViewPager toolbar = getTerminalToolbarViewPager();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null) return;

        if (drawer.isDrawerOpen(android.view.Gravity.START))
            drawer.closeDrawer(android.view.Gravity.START);

        clearFocusAndHideKeyboard();
        drawer.setVisibility(View.GONE);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF  = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF   = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF  = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment detailF = fm.findFragmentByTag("agent_task_detail");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF  = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        if (apiF == null) {
            ft.add(R.id.home_fragment_container, new ApiKeyFragment(), "apikey");
        } else {
            ft.show(apiF);
        }
        if (homeF     != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (agentF    != null) ft.hide(agentF);
        if (loomF     != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF     != null) ft.hide(autoF);
        if (detailF   != null) ft.remove(detailF);
        ft.commit();
    }

    /** 切换到 AgentServer 配置页面。 */
    public void showAgentServerMode() {
        DrawerLayout drawer = getDrawer();
        ViewPager toolbar = getTerminalToolbarViewPager();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null) return;

        if (drawer.isDrawerOpen(android.view.Gravity.START))
            drawer.closeDrawer(android.view.Gravity.START);

        clearFocusAndHideKeyboard();
        drawer.setVisibility(View.GONE);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF  = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF   = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF  = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF  = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF = fm.findFragmentByTag("agent_task_detail");
        if (agentF == null) {
            ft.add(R.id.home_fragment_container, new AgentServerFragment(), "agentserver");
        } else {
            ft.show(agentF);
        }
        if (homeF     != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF      != null) ft.hide(apiF);
        if (loomF     != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF     != null) ft.hide(autoF);
        if (workspaceF != null) ft.hide(workspaceF);
        if (detailF   != null) ft.remove(detailF);
        ft.commit();
    }

    /** 切换到 Loom 配置页面。 */
    public void showLoomMode() {
        DrawerLayout drawer = getDrawer();
        ViewPager toolbar = getTerminalToolbarViewPager();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null) return;

        if (drawer.isDrawerOpen(android.view.Gravity.START))
            drawer.closeDrawer(android.view.Gravity.START);

        clearFocusAndHideKeyboard();
        drawer.setVisibility(View.GONE);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF  = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF   = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF  = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF  = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF = fm.findFragmentByTag("agent_task_detail");
        if (loomF == null) {
            ft.add(R.id.home_fragment_container, new LoomFragment(), "loom");
        } else {
            ft.show(loomF);
        }
        if (homeF     != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF      != null) ft.hide(apiF);
        if (agentF    != null) ft.hide(agentF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF     != null) ft.hide(autoF);
        if (workspaceF != null) ft.hide(workspaceF);
        if (detailF   != null) ft.remove(detailF);
        ft.commit();
    }

    /** 切换到设置导航页面（SettingsHubFragment）。 */
    public void showSettingsHubMode() {
        DrawerLayout drawer = getDrawer();
        ViewPager toolbar = getTerminalToolbarViewPager();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null) return;

        if (drawer.isDrawerOpen(android.view.Gravity.START))
            drawer.closeDrawer(android.view.Gravity.START);

        clearFocusAndHideKeyboard();
        drawer.setVisibility(View.GONE);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF    = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF     = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF   = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF    = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF    = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF  = fm.findFragmentByTag("agent_task_detail");
        if (settingsF == null) {
            ft.add(R.id.home_fragment_container, new SettingsHubFragment(), "settings_hub");
        } else {
            ft.show(settingsF);
        }
        if (homeF   != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF    != null) ft.hide(apiF);
        if (agentF  != null) ft.hide(agentF);
        if (loomF   != null) ft.hide(loomF);
        if (autoF   != null) ft.hide(autoF);
        if (workspaceF != null) ft.hide(workspaceF);
        if (detailF != null) ft.remove(detailF);
        ft.commit();
    }

    /** 切换到自动化 Boost 设置页面（从 SettingsHub 进入）。 */
    public void showAutomationSettingsMode() {
        View container = findViewById(R.id.home_fragment_container);
        if (container == null) return;

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF     = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF      = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF    = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF     = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF     = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        if (autoF == null) {
            ft.add(R.id.home_fragment_container, new AutomationSettingsFragment(), "automation_settings");
        } else {
            ft.show(autoF);
        }
        if (homeF     != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF      != null) ft.hide(apiF);
        if (agentF    != null) ft.hide(agentF);
        if (loomF     != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (workspaceF != null) ft.hide(workspaceF);
        ft.commit();
    }

    /** 切换到工作目录限制设置页面。 */
    public void showWorkspaceAccessSettingsMode() {
        View container = findViewById(R.id.home_fragment_container);
        if (container == null) return;

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF     = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF      = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF    = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF     = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF     = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF   = fm.findFragmentByTag("agent_task_detail");
        if (workspaceF == null) {
            ft.add(R.id.home_fragment_container,
                new WorkspaceAccessSettingsFragment(), "workspace_access_settings");
        } else {
            ft.show(workspaceF);
        }
        if (homeF     != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF      != null) ft.hide(apiF);
        if (agentF    != null) ft.hide(agentF);
        if (loomF     != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF     != null) ft.hide(autoF);
        if (detailF   != null) ft.remove(detailF);
        ft.commit();
    }

    /** 切换到 AgentServer 任务详情页（按 task id 加载），通常从 HomeFragment 任务列表点击进入。 */
    public void showAgentTaskDetailMode(String taskId) {
        showAgentTaskDetailMode(AssistantProvider.CLAUDE, taskId);
    }

    /** 切换到当前助手的 AgentServer 任务详情页（按 task id 加载）。 */
    public void showAgentTaskDetailMode(AssistantProvider provider, String taskId) {
        DrawerLayout drawer = getDrawer();
        ViewPager toolbar = getTerminalToolbarViewPager();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null || taskId == null) return;

        if (drawer.isDrawerOpen(android.view.Gravity.START))
            drawer.closeDrawer(android.view.Gravity.START);

        clearFocusAndHideKeyboard();
        drawer.setVisibility(View.GONE);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF   = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF    = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF  = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF   = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF   = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF = fm.findFragmentByTag("agent_task_detail");
        if (homeF   != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF    != null) ft.hide(apiF);
        if (agentF  != null) ft.hide(agentF);
        if (loomF   != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF   != null) ft.hide(autoF);
        if (workspaceF != null) ft.hide(workspaceF);
        if (detailF != null) ft.remove(detailF);
        ft.add(R.id.home_fragment_container,
            AgentTaskDetailFragment.newInstance(provider, taskId), "agent_task_detail");
        ft.commit();
    }

    /** 切换回终端模式：隐藏所有 Fragment，恢复终端视图。 */
    private void showTerminalMode() {
        DrawerLayout drawer = getDrawer();
        View container = findViewById(R.id.home_fragment_container);
        if (drawer == null || container == null) return;

        container.setVisibility(View.GONE);
        drawer.setVisibility(View.VISIBLE);

        // 隐藏所有非终端 Fragment（保留实例，不销毁）
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction ft = fm.beginTransaction();
        androidx.fragment.app.Fragment homeF  = fm.findFragmentByTag("home");
        androidx.fragment.app.Fragment collaborationF = fm.findFragmentByTag("collaboration");
        androidx.fragment.app.Fragment apiF   = fm.findFragmentByTag("apikey");
        androidx.fragment.app.Fragment agentF = fm.findFragmentByTag("agentserver");
        androidx.fragment.app.Fragment loomF  = fm.findFragmentByTag("loom");
        androidx.fragment.app.Fragment settingsF = fm.findFragmentByTag("settings_hub");
        androidx.fragment.app.Fragment autoF  = fm.findFragmentByTag("automation_settings");
        androidx.fragment.app.Fragment workspaceF = fm.findFragmentByTag("workspace_access_settings");
        androidx.fragment.app.Fragment detailF = fm.findFragmentByTag("agent_task_detail");
        if (homeF  != null) ft.hide(homeF);
        if (collaborationF != null) ft.hide(collaborationF);
        if (apiF   != null) ft.hide(apiF);
        if (agentF != null) ft.hide(agentF);
        if (loomF  != null) ft.hide(loomF);
        if (settingsF != null) ft.hide(settingsF);
        if (autoF  != null) ft.hide(autoF);
        if (workspaceF != null) ft.hide(workspaceF);
        if (detailF != null) ft.remove(detailF);
        ft.commit();

        // 按用户设置决定是否恢复 extra keys 工具栏
        ViewPager toolbar = getTerminalToolbarViewPager();
        if (toolbar != null && mPreferences != null && mPreferences.shouldShowTerminalToolbar()) {
            toolbar.setVisibility(View.VISIBLE);
        }
    }

    // ── HomeFragment 调用的公共方法 ──────────────────────────────────────────

    /**
     * 读取当前终端 session 最近 maxLines 行文本（含历史回滚缓冲区）。
     * HomeFragment 通过轮询调用此方法更新输出镜像视图。
     */
    /**
     * 读取当前终端 session 最近 maxLines 行原始输出。
     *
     * 使用 session.getRawOutput()（PTY 原始字节捕获），而非 getScreen().getTranscriptText()。
     * 原因：Claude Code 运行时切换到交替屏幕缓冲区（mAltBuffer），该缓冲区无滚动历史，
     * 只保留当前可见行（约 24 行）。getRawOutput() 在数据进入模拟器前截获，保留完整历史。
     */
    public String getRecentTerminalLines(int maxLines) {
        TerminalSession session = getCurrentSession();
        if (session == null) return "";
        try {
            String raw = session.getRawOutput();
            if (raw == null || raw.isEmpty()) return "";
            String[] lines = raw.split("\n", -1);
            int start = Math.max(0, lines.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]);
                if (i < lines.length - 1) sb.append("\n");
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 向当前终端 session 写入文本（等同于键盘输入）。 */
    public void sendTerminalInput(String text) {
        TerminalSession session = getCurrentSession();
        if (session != null) session.write(text);
    }

    /** 当前是否有正在运行的终端 session。 */
    public boolean hasActiveSession() {
        TerminalSession session = getCurrentSession();
        return session != null && session.isRunning();
    }

    /** 从简化 UI 新建终端 session。 */
    public void addNewSessionFromHome() {
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.addNewSession(false, null);
    }

    /** 获取 session 0（ubuntu bash）对应的 TerminalSession。 */
    private TerminalSession getFirstSession() {
        if (mTermuxService == null) return null;
        java.util.List<com.termux.shared.termux.shell.command.runner.terminal.TermuxSession> sessions =
                mTermuxService.getTermuxSessions();
        if (sessions == null || sessions.isEmpty()) return null;
        return sessions.get(0).getTerminalSession();
    }

    /** 向 session 0（ubuntu bash）写入文本，供 Chat UI 发送 claude -p 命令。 */
    public void sendChatInput(String text) {
        TerminalSession s = getFirstSession();
        if (s != null) s.write(text);
    }

    /** 读取 session 0 最近 maxLines 行的原始 PTY 输出，供 Chat UI 解析 JSONL。 */
    public String getChatTerminalLines(int maxLines) {
        TerminalSession s = getFirstSession();
        if (s == null) return "";
        String raw = s.getRawOutput();
        if (raw == null || raw.isEmpty()) return "";
        String[] lines = raw.split("\n", -1);
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    public void setActiveApiKey(String key, String baseUrl) {
        setActiveApiKey(AssistantProvider.CLAUDE, key, baseUrl);
    }

    public void setActiveApiKey(AssistantProvider provider, String key, String baseUrl) {
        try {
            ProviderEnvironmentWriter.writeActiveKey(this, provider, key, baseUrl);
        } catch (java.io.IOException e) {
            Logger.logError(LOG_TAG, "setActiveApiKey: " + e.getMessage());
        }
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
