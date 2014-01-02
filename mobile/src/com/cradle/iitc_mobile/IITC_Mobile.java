package com.cradle.iitc_mobile;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.cradle.iitc_mobile.IITC_NavigationHelper.Pane;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Stack;

public class IITC_Mobile extends Activity implements OnSharedPreferenceChangeListener {

    private static final int REQUEST_LOGIN = 1;
    private static final String mIntelUrl = "https://www.ingress.com/intel";

    private SharedPreferences mSharedPrefs;
    private IITC_FileManager mFileManager;
    private IITC_WebView mIitcWebView;
    private IITC_UserLocation mUserLocation;
    private IITC_NavigationHelper mNavigationHelper;
    private IITC_MapSettings mMapSettings;
    private IITC_DeviceAccountLogin mLogin;
    private boolean mDesktopMode = false;
    private boolean mAdvancedMenu = false;
    private MenuItem mSearchMenuItem;
    private View mImageLoading;
    private ListView mLvDebug;
    private View mViewDebug;
    private ImageButton mBtnToggleMap;
    private EditText mEditCommand;
    private boolean mDebugging = false;
    private boolean mReloadNeeded = false;
    private boolean mIsLoading = true;
    private boolean mShowMapInDebug = false;
    private final Stack<String> mDialogStack = new Stack<String>();

    // Used for custom back stack handling
    private final Stack<Pane> mBackStack = new Stack<IITC_NavigationHelper.Pane>();
    private Pane mCurrentPane = Pane.MAP;
    private boolean mBackButtonPressed = false;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ((IITC_Mobile) context).installIitcUpdate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // enable progress bar above action bar
        requestWindowFeature(Window.FEATURE_PROGRESS);

        setContentView(R.layout.activity_main);
        mImageLoading = findViewById(R.id.imageLoading);
        mIitcWebView = (IITC_WebView) findViewById(R.id.iitc_webview);
        mLvDebug = (ListView) findViewById(R.id.lvDebug);
        mViewDebug = findViewById(R.id.viewDebug);
        mBtnToggleMap = (ImageButton) findViewById(R.id.btnToggleMapVisibility);
        mEditCommand = (EditText) findViewById(R.id.editCommand);
        mEditCommand.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (EditorInfo.IME_ACTION_GO == actionId) {
                    onBtnRunCodeClick(v);

                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);

                    return true;
                }
                return false;
            }
        });

        mLvDebug.setAdapter(new IITC_LogAdapter(this));

        // do something if user changed something in the settings
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSharedPrefs.registerOnSharedPreferenceChangeListener(this);

        // enable/disable mDesktopMode mode on menu create and url load
        mDesktopMode = mSharedPrefs.getBoolean("pref_force_desktop", false);

        // enable/disable advance menu
        mAdvancedMenu = mSharedPrefs.getBoolean("pref_advanced_menu", false);

        // get fullscreen status from settings
        mIitcWebView.updateFullscreenStatus();

        mFileManager = new IITC_FileManager(this);

        mUserLocation = new IITC_UserLocation(this);
        mUserLocation.setLocationMode(Integer.parseInt(mSharedPrefs.getString("pref_user_location_mode", "0")));

        // pass ActionBar to helper because we deprecated getActionBar
        mNavigationHelper = new IITC_NavigationHelper(this, super.getActionBar());

        mMapSettings = new IITC_MapSettings(this);

        // Clear the back stack
        mBackStack.clear();

        // receive downloadManagers downloadComplete intent
        // afterwards install iitc update
        registerReceiver(mBroadcastReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        handleIntent(getIntent(), true);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("pref_force_desktop")) {
            mDesktopMode = sharedPreferences.getBoolean("pref_force_desktop", false);
            mNavigationHelper.onPrefChanged();
        } else if (key.equals("pref_user_location_mode")) {
            int mode = Integer.parseInt(mSharedPrefs.getString("pref_user_location_mode", "0"));
            if (mUserLocation.setLocationMode(mode))
                mReloadNeeded = true;
            return;
        } else if (key.equals("pref_fullscreen")) {
            mIitcWebView.updateFullscreenStatus();
            mNavigationHelper.onPrefChanged();
            return;
        } else if (key.equals("pref_advanced_menu")) {
            mAdvancedMenu = sharedPreferences.getBoolean("pref_advanced_menu", false);
            mNavigationHelper.setDebugMode(mAdvancedMenu);
            invalidateOptionsMenu();
            // no reload needed
            return;
        } else if (key.equals("pref_fake_user_agent")) {
            mIitcWebView.setUserAgent();
        } else if (key.equals("pref_press_twice_to_exit")
                || key.equals("pref_share_selected_tab")
                || key.equals("pref_messages")
                || key.equals("pref_external_storage")) {
            // no reload needed
            return;
        }

        mReloadNeeded = true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent, false);
    }

    private void handleIntent(Intent intent, boolean onCreate) {
        // load new iitc web view with ingress intel page
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            Log.d("iitcm", "intent received url: " + uri.toString());

            if (uri.getScheme().equals("http") || uri.getScheme().equals("https")) {
                if (uri.getHost() != null
                        && (uri.getHost().equals("ingress.com") || uri.getHost().endsWith(".ingress.com"))) {
                    Log.d("iitcm", "loading url...");
                    this.loadUrl(uri.toString());
                    return;
                }
            }

            if (uri.getScheme().equals("geo")) {
                try {
                    handleGeoUri(uri);
                    return;
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.intent_error)
                            .setMessage(e.getReason())
                            .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            }
        }

        if (Intent.ACTION_SEARCH.equals(action)) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            query = query.replace("'", "''");
            final SearchView searchView =
                    (SearchView) mSearchMenuItem.getActionView();
            searchView.setQuery(query, false);
            searchView.clearFocus();

            switchToPane(Pane.MAP);
            mIitcWebView.loadUrl("javascript:search('" + query + "');");
            return;
        }

        if (onCreate) {
            this.loadUrl(mIntelUrl);
        }
    }

    private void handleGeoUri(Uri uri) throws URISyntaxException {
        String[] parts = uri.getSchemeSpecificPart().split("\\?", 2);
        Double lat, lon;
        Integer z = null;

        // parts[0] may contain an 'uncertainty' parameter, delimited by a semicolon
        String[] pos = parts[0].split(";", 2)[0].split(",", 2);
        if (pos.length != 2) {
            throw new URISyntaxException(uri.toString(), "URI does not contain a valid position");
        }

        try {
            lat = Double.valueOf(pos[0]);
            lon = Double.valueOf(pos[1]);
        } catch (NumberFormatException e) {
            URISyntaxException use = new URISyntaxException(uri.toString(), "position could not be parsed");
            use.initCause(e);
            throw use;
        }

        if (parts.length > 1) { // query string present
            // search for z=
            for (String param : parts[1].split("&")) {
                if (param.startsWith("z=")) {
                    try {
                        z = Integer.valueOf(param.substring(2));
                    } catch (NumberFormatException e) {
                        URISyntaxException use = new URISyntaxException(uri.toString(), "could not parse zoom level");
                        use.initCause(e);
                        throw use;
                    }
                    break;
                }
            }
        }

        String url = "http://www.ingress.com/intel?ll=" + lat + "," + lon;
        if (z != null) {
            url += "&z=" + z;
        }
        this.loadUrl(url);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (mReloadNeeded) {
            Log.d("iitcm", "preference had changed...reload needed");
            reloadIITC();
        } else {
            // iitc is not fully booted...timer will be reset by the script itself
            if (findViewById(R.id.imageLoading).getVisibility() == View.GONE) {
                // enough idle...let's do some work
                Log.d("iitcm", "resuming...reset idleTimer");
                mIitcWebView.loadUrl("javascript: window.idleReset();");
            }
        }

        mUserLocation.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIitcWebView.resumeTimers();
        mIitcWebView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIitcWebView.pauseTimers();
        mIitcWebView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d("iitcm", "stopping iitcm");
        mIitcWebView.loadUrl("javascript: window.idleSet();");
        mUserLocation.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mNavigationHelper.onConfigurationChanged(newConfig);

        Log.d("iitcm", "configuration changed...restoring...reset idleTimer");
        mIitcWebView.loadUrl("javascript: window.idleTime = 0");
        mIitcWebView.loadUrl("javascript: window.renderUpdateStatus()");
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mNavigationHelper.onPostCreate(savedInstanceState);
    }

    // we want a self defined behavior for the back button
    @Override
    public void onBackPressed() {
        // exit fullscreen mode if it is enabled and action bar is disabled or the back stack is empty
        if (mIitcWebView.isInFullscreen() && mBackStack.isEmpty()) {
            mIitcWebView.toggleFullscreen();
            return;
        }

        // close drawer if opened
        if (mNavigationHelper.isDrawerOpened()) {
            mNavigationHelper.closeDrawers();
            return;
        }

        // kill all open iitc dialogs
        if (!mDialogStack.isEmpty()) {
            String id = mDialogStack.pop();
            mIitcWebView.loadUrl("javascript: " +
                    "var selector = $(window.DIALOGS['" + id + "']); " +
                    "selector.dialog('close'); " +
                    "selector.remove();");
            return;
        }

        // Pop last item from backstack and pretend the relevant menu item was clicked
        if (!mBackStack.isEmpty()) {
            backStackPop();
            mBackButtonPressed = true;
            return;
        }

        if (mBackButtonPressed || !mSharedPrefs.getBoolean("pref_press_twice_to_exit", false)) {
            super.onBackPressed();
        } else {
            mBackButtonPressed = true;
            Toast.makeText(this, "Press twice to exit", Toast.LENGTH_SHORT).show();
            // reset back button after 2 seconds
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    mBackButtonPressed = false;
                }
            }, 2000);
        }
    }

    public void backStackPop() {
        // shouldn't be called when back stack is empty
        // catch wrong usage
        if (mBackStack.isEmpty()) {
            mBackStack.push(Pane.MAP);
        }

        Pane pane = mBackStack.pop();
        switchToPane(pane);
    }

    public void setCurrentPane(Pane pane) {
        // ensure no double adds
        if (pane == mCurrentPane) return;

        // map pane is top-lvl. clear stack.
        if (pane == Pane.MAP) mBackStack.clear();
        // don't push current pane to backstack if this method was called via back button
        else if (!mBackButtonPressed) mBackStack.push(mCurrentPane);

        mBackButtonPressed = false;
        mCurrentPane = pane;
        mNavigationHelper.switchTo(pane);
    }

    public void switchToPane(Pane pane) {
        mIitcWebView.loadUrl("javascript: window.show('" + pane.name + "');");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        // Get the SearchView and set the searchable configuration
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        mSearchMenuItem = menu.findItem(R.id.menu_search);
        final SearchView searchView =
                (SearchView) mSearchMenuItem.getActionView();
        // Assumes current activity is the searchable activity
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        searchView.setIconifiedByDefault(false); // Do not iconify the widget; expand it by default
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean visible = false;
        if (mNavigationHelper != null)
            visible = !mNavigationHelper.isDrawerOpened();

        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);

            switch (item.getItemId()) {
                case R.id.action_settings:
                    item.setVisible(true);
                    break;

                case R.id.menu_clear_cookies:
                    item.setVisible(mAdvancedMenu && visible);
                    break;

                case R.id.locate:
                    item.setVisible(visible);
                    item.setIcon(mUserLocation.isFollowing()
                            ? R.drawable.ic_action_location_follow
                            : R.drawable.ic_action_location_found);
                    break;

                case R.id.menu_debug:
                    item.setVisible(mAdvancedMenu && visible);
                    item.setChecked(mDebugging);
                    break;

                default:
                    item.setVisible(visible);
            }
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mNavigationHelper.onOptionsItemSelected(item)) {
            return true;
        }

        // Handle item selection
        final int itemId = item.getItemId();

        switch (itemId) {
            case android.R.id.home:
                switchToPane(Pane.MAP);
                return true;
            case R.id.reload_button:
                reloadIITC();
                return true;
            case R.id.toggle_fullscreen:
                mIitcWebView.toggleFullscreen();
                return true;
            case R.id.layer_chooser:
                mNavigationHelper.openRightDrawer();
                return true;
            case R.id.locate: // get the users current location and focus it on map
                switchToPane(Pane.MAP);

                if (mUserLocation.hasCurrentLocation()) {
                    // if gps location is displayed we can use a better location without any costs
                    mUserLocation.locate();
                } else {
                    // get location from network by default
                    mIitcWebView.loadUrl("javascript: window.map.locate({setView : true});");
                }
                return true;
            case R.id.action_settings: // start settings activity
                Intent intent = new Intent(this, IITC_PreferenceActivity.class);
                try {
                    intent.putExtra("iitc_version", mFileManager.getIITCVersion());
                } catch (IOException e) {
                    e.printStackTrace();
                    return true;
                }
                startActivity(intent);
                return true;
            case R.id.menu_clear_cookies:
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookie();
                return true;
            case R.id.menu_debug:
                mDebugging = !mDebugging;
                updateViews();
                invalidateOptionsMenu();

                // TODO remove debugging stuff from JS?
                return true;
            default:
                return false;
        }
    }

    @Override
    public File getCacheDir() {
        return getApplicationContext().getCacheDir();
    }

    public void reloadIITC() {
        mNavigationHelper.reset();
        mMapSettings.reset();
        mUserLocation.reset();
        mIitcWebView.getWebViewClient().reset();
        mBackStack.clear();
        // iitc starts on map after reload
        mCurrentPane = Pane.MAP;
        loadUrl(mIntelUrl);
        mReloadNeeded = false;
    }

    // vp=f enables mDesktopMode mode...vp=m is the default mobile view
    private String addUrlParam(String url) {
        if (mDesktopMode) {
            return (url + "?vp=f");
        } else {
            return (url + "?vp=m");
        }
    }

    // inject the iitc-script and load the intel url
    // plugins are injected onPageFinished
    public void loadUrl(String url) {
        setLoadingState(true);
        url = addUrlParam(url);
        mIitcWebView.loadUrl(url);
    }

    public IITC_WebView getWebView() {
        return this.mIitcWebView;
    }

    /**
     * It can occur that in order to authenticate, an external activity has to be launched.
     * (This could for example be a confirmation dialog.)
     */
    public void startLoginActivity(Intent launch) {
        startActivityForResult(launch, REQUEST_LOGIN); // REQUEST_LOGIN is to recognize the result
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_LOGIN:
                // authentication activity has returned. mLogin will continue authentication
                mLogin.onActivityResult(resultCode, data);
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * called by IITC_WebViewClient when the Google login form is opened.
     */
    public void onReceivedLoginRequest(IITC_WebViewClient client, WebView view,
            String realm, String account, String args) {
        mLogin = new IITC_DeviceAccountLogin(this, view, client);
        mLogin.startLogin(realm, account, args);
    }

    /**
     * called after successful login
     */
    public void loginSucceeded() {
        // garbage collection
        mLogin = null;
        setLoadingState(true);
    }

    // remove dialog and add it back again
    // to ensure it is the last element of the list
    // focused dialogs should be closed first
    public void setFocusedDialog(String id) {
        Log.d("iitcm", "Dialog " + id + " focused");
        mDialogStack.remove(id);
        mDialogStack.push(id);
    }

    // called by the javascript interface
    public void dialogOpened(String id, boolean open) {
        if (open) {
            Log.d("iitcm", "Dialog " + id + " added");
            mDialogStack.push(id);
        } else {
            Log.d("iitcm", "Dialog " + id + " closed");
            mDialogStack.remove(id);
        }
    }

    public void setLoadingState(boolean isLoading) {
        mIsLoading = isLoading;

        mNavigationHelper.onLoadingStateChanged();

        updateViews();
    }

    private void updateViews() {
        if (!mDebugging) {
            mViewDebug.setVisibility(View.GONE);
            mLvDebug.setVisibility(View.GONE);

            if (mIsLoading && !mSharedPrefs.getBoolean("pref_disable_splash", false)) {
                mIitcWebView.setVisibility(View.GONE);
                mImageLoading.setVisibility(View.VISIBLE);
            } else {
                mIitcWebView.setVisibility(View.VISIBLE);
                mImageLoading.setVisibility(View.GONE);
            }
        } else {
            // if the debug container is invisible (and we are about to show it), select the text box
            boolean select = mViewDebug.getVisibility() != View.VISIBLE;

            mImageLoading.setVisibility(View.GONE); // never show splash screen while debugging
            mViewDebug.setVisibility(View.VISIBLE);

            if (select) {
                mEditCommand.requestFocus();
                mEditCommand.selectAll();
            }

            if (mShowMapInDebug) {
                mBtnToggleMap.setImageResource(R.drawable.ic_action_view_as_list);
                mIitcWebView.setVisibility(View.VISIBLE);
                mLvDebug.setVisibility(View.GONE);
            } else {
                mBtnToggleMap.setImageResource(R.drawable.ic_action_map);
                mIitcWebView.setVisibility(View.GONE);
                mLvDebug.setVisibility(View.VISIBLE);
            }
        }
    }

    public void onBtnRunCodeClick(View v) {
        String code = mEditCommand.getText().toString();
        JSONObject obj = new JSONObject();
        try {
            obj.put("code", code);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        // throwing an exception will be reported by WebView
        String js = "(function(obj){var result;" +
                "console.log('>>> ' + obj.code);" +
                "try{result=eval(obj.code);}catch(e){if(e.stack) console.error(e.stack);throw e;}" +
                "if(result!==undefined) console.log(result.toString());" +
                "})(" + obj.toString() + ");";

        mIitcWebView.loadJS(js);
    }

    /**
     * onClick handler for R.id.btnToggleMapVisibility, assigned in activity_main.xml
     */
    public void onToggleMapVisibility(View v)
    {
        mShowMapInDebug = !mShowMapInDebug;
        updateViews();
    }

    private void deleteUpdateFile() {
        File file = new File(getExternalFilesDir(null).toString() + "/iitcUpdate.apk");
        if (file != null) file.delete();
    }

    public void updateIitc(String url) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setDescription(getString(R.string.download_description));
        request.setTitle("IITCm Update");
        request.allowScanningByMediaScanner();
        Uri fileUri = Uri.parse("file://" + getExternalFilesDir(null).toString() + "/iitcUpdate.apk");
        request.setDestinationUri(fileUri);
        // remove old update file...we don't want to spam the external storage
        deleteUpdateFile();
        // get download service and enqueue file
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        manager.enqueue(request);
    }

    private void installIitcUpdate() {
        String iitcUpdatePath = getExternalFilesDir(null).toString() + "/iitcUpdate.apk";
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(new File(iitcUpdatePath)), "application/vnd.android.package-archive");
        startActivity(intent);
        // finish app, because otherwise it gets killed on update
        finish();
    }

    public boolean isLoading() {
        return mIsLoading;
    }

    /**
     * @see getNavigationHelper()
     * @deprecated ActionBar related stuff should be handled by IITC_NavigationHelper
     */
    @Deprecated
    @Override
    public ActionBar getActionBar() {
        return super.getActionBar();
    }

    public IITC_NavigationHelper getNavigationHelper() {
        return mNavigationHelper;
    }

    public IITC_MapSettings getMapSettings() {
        return mMapSettings;
    }

    public IITC_FileManager getFileManager() {
        return mFileManager;
    }

    public SharedPreferences getPrefs() {
        return mSharedPrefs;
    }

    public IITC_UserLocation getUserLocation() {
        return mUserLocation;
    }
}
