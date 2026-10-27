package com.zzf.bluetoothsmp;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.Navigation;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.zzf.bluetoothsmp.base.AppConstants;
import com.zzf.bluetoothsmp.utils.LanguageUtils;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public locale behavior on API 23+; platform-only assertions are guarded for API 33+. */
@RunWith(AndroidJUnit4.class)
public class LanguagePreferencesInstrumentedTest {
    private static final String MIGRATED = "platform_locale_migrated";
    private Instrumentation instrumentation;
    private Application application;
    private SharedPreferences preferences;
    private SharedPreferences migrationPreferences;
    private Map<String, ?> originalPreferences;
    private Map<String, ?> originalMigrationPreferences;
    private String originalPlatformTags;
    private String originalCompatTags;
    private volatile MainActivity currentMain;
    private final List<Activity> createdActivities = new ArrayList<>();
    private Instrumentation.ActivityMonitor discoveryPrompt;

    private final Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            if (activity instanceof MainActivity) {
                createdActivities.add(activity);
                currentMain = (MainActivity) activity;
            }
        }
        @Override public void onActivityResumed(Activity activity) {
            if (activity instanceof MainActivity) currentMain = (MainActivity) activity;
        }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    };

    @Before
    public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        application = (Application) instrumentation.getTargetContext().getApplicationContext();
        preferences = application.getSharedPreferences(AppConstants.I18N, Context.MODE_PRIVATE);
        migrationPreferences = application.getSharedPreferences("locale_migration", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(preferences.getAll());
        originalMigrationPreferences = new HashMap<>(migrationPreferences.getAll());
        instrumentation.runOnMainSync(() -> {
            originalCompatTags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
            if (Build.VERSION.SDK_INT >= 33) originalPlatformTags = platformTags();
            application.registerActivityLifecycleCallbacks(callbacks);
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grant(Manifest.permission.BLUETOOTH_CONNECT);
            grant(Manifest.permission.BLUETOOTH_SCAN);
            grant(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            grant(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        discoveryPrompt = instrumentation.addMonitor(new IntentFilter(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        assertTrue(preferences.edit().putString(AppConstants.LOCALE_LANGUAGE, "en-US").commit());
        assertTrue(migrationPreferences.edit().putBoolean(MIGRATED, true).commit());
        instrumentation.runOnMainSync(() -> {
            if (Build.VERSION.SDK_INT >= 33) setPlatformTags("en-US");
            else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en-US"));
        });
        launchMain();
        await(() -> isReady("en"));
    }

    @After
    public void cleanup() {
        if (instrumentation == null) return;
        try {
            finishMain();
        } finally {
            instrumentation.runOnMainSync(() -> application.unregisterActivityLifecycleCallbacks(callbacks));
            SharedPreferences.Editor edit = preferences.edit();
            restoreKey(edit, originalPreferences, AppConstants.LOCALE_LANGUAGE);
            restoreKey(edit, originalPreferences, MIGRATED);
            assertTrue(edit.commit());
            SharedPreferences.Editor migrationEdit = migrationPreferences.edit();
            restoreKey(migrationEdit, originalMigrationPreferences, MIGRATED);
            assertTrue(migrationEdit.commit());
            instrumentation.runOnMainSync(() -> {
                if (Build.VERSION.SDK_INT >= 33) setPlatformTags(originalPlatformTags == null ? "" : originalPlatformTags);
                else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(originalCompatTags == null ? "" : originalCompatTags));
            });
            if (discoveryPrompt != null) instrumentation.removeMonitor(discoveryPrompt);
        }
    }

    @Test
    public void chineseEnglishSwitchRecreatesTheCurrentActivityAndRetainsNavigation() {
        navigateToService();
        MainActivity original = currentMain;
        int task = original.getTaskId();
        MainActivity chinese = selectLocale(2, "zh", true);
        assertNotSame(original, chinese);
        instrumentation.runOnMainSync(() -> {
            assertEquals("历史记录", chinese.getString(R.string.history));
            assertServicePage(chinese, task);
        });
        MainActivity english = selectLocale(1, "en", true);
        assertNotSame(chinese, english);
        instrumentation.runOnMainSync(() -> {
            assertEquals("History", english.getString(R.string.history));
            assertServicePage(english, task);
        });
    }

    @Test
    public void existingLegacyPreferenceMigratesWithoutLosingItsValue() {
        finishMain();
        // A restored backup can contain the old marker; only the non-backed-up
        // local migration marker may suppress the first platform migration.
        assertTrue(preferences.edit().putString(AppConstants.LOCALE_LANGUAGE, "zh-CN")
                .putBoolean(MIGRATED, true).commit());
        assertTrue(migrationPreferences.edit().remove(MIGRATED).commit());
        instrumentation.runOnMainSync(() -> {
            if (Build.VERSION.SDK_INT >= 33) setPlatformTags("");
            else AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        });
        launchMain();
        await(() -> isReady("zh"));
        assertEquals("zh-CN", preferences.getString(AppConstants.LOCALE_LANGUAGE, ""));
        assertEquals("zh", LanguageUtils.getPrefAppLocale().getLanguage());
        instrumentation.runOnMainSync(() -> {
            assertEquals("历史记录", currentMain.getString(R.string.history));
            if (Build.VERSION.SDK_INT >= 33) {
                assertTrue(migrationPreferences.getBoolean(MIGRATED, false));
                assertEquals("zh", application.getSystemService(LocaleManager.class).getApplicationLocales().get(0).getLanguage());
            } else {
                assertEquals("zh", AppCompatDelegate.getApplicationLocales().get(0).getLanguage());
            }
        });
    }

    @Test
    public void externalPlatformLocaleChangesTakePriorityOverMigratedLegacyPreference() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 33);
        navigateToService();
        MainActivity previous = currentMain;
        int task = previous.getTaskId();
        instrumentation.runOnMainSync(() -> setPlatformTags("zh-CN"));
        await(() -> currentMain != previous && isReady("zh"), previous);
        assertEquals("en-US", preferences.getString(AppConstants.LOCALE_LANGUAGE, ""));
        assertEquals("zh", LanguageUtils.getPrefAppLocale().getLanguage());
        instrumentation.runOnMainSync(() -> assertServicePage(currentMain, task));

        MainActivity chinese = currentMain;
        String systemLanguage = LanguageUtils.getSystemLocale().getLanguage();
        instrumentation.runOnMainSync(() -> setPlatformTags(""));
        await(() -> currentMain != chinese && isReady(systemLanguage), chinese);
        assertNull(LanguageUtils.getPrefAppLocale());
        assertEquals("en-US", preferences.getString(AppConstants.LOCALE_LANGUAGE, ""));
        instrumentation.runOnMainSync(() -> {
            assertEquals("", platformTags());
            assertServicePage(currentMain, task);
        });
    }

    @Test
    public void followSystemFromAppPickerRetainsTheExistingNavigationTask() {
        navigateToService();
        String systemLanguage = LanguageUtils.getSystemLocale().getLanguage();
        String explicitLanguage = "zh".equals(systemLanguage) ? "en" : "zh";
        int type = "zh".equals(explicitLanguage) ? 2 : 1;
        selectLocale(type, explicitLanguage, !isCurrentLanguage(explicitLanguage));
        MainActivity explicit = currentMain;
        int task = explicit.getTaskId();
        MainActivity following = selectLocale(0, systemLanguage, true);
        assertNotSame(explicit, following);
        assertEquals(LanguageUtils.SYSTEM_LANGUAGE_TGA, preferences.getString(AppConstants.LOCALE_LANGUAGE, ""));
        assertNull(LanguageUtils.getPrefAppLocale());
        instrumentation.runOnMainSync(() -> {
            if (Build.VERSION.SDK_INT >= 33) assertEquals("", platformTags());
            else assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty());
            assertServicePage(following, task);
        });
    }

    private void launchMain() {
        // Lifecycle callbacks track the replacement, including locale migration during first launch.
        instrumentation.startActivitySync(new Intent(application, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
    }

    private MainActivity selectLocale(int type, String expectedLanguage, boolean requireReplacement) {
        MainActivity previous = currentMain;
        instrumentation.runOnMainSync(() -> previous.toSetLanguage(type));
        await(() -> (!requireReplacement || currentMain != previous) && isReady(expectedLanguage), previous);
        return currentMain;
    }

    private boolean isReady(String language) {
        return currentMain != null && !currentMain.isDestroyed()
                && currentMain.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED
                && resourceLanguage(currentMain).equals(language);
    }

    private String resourceLanguage(Activity activity) {
        return Build.VERSION.SDK_INT >= 24
                ? activity.getResources().getConfiguration().getLocales().get(0).getLanguage()
                : activity.getResources().getConfiguration().locale.getLanguage();
    }

    private boolean isCurrentLanguage(String language) {
        AtomicBoolean same = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> same.set(resourceLanguage(currentMain).equals(language)));
        return same.get();
    }

    private void navigateToService() {
        instrumentation.runOnMainSync(() -> Navigation.findNavController(currentMain,
                R.id.nav_host_fragment_activity_home).navigate(R.id.navigation_service));
        instrumentation.waitForIdleSync();
    }

    private void assertServicePage(MainActivity activity, int task) {
        assertEquals(task, activity.getTaskId());
        assertEquals(R.id.navigation_service, Navigation.findNavController(activity,
                R.id.nav_host_fragment_activity_home).getCurrentDestination().getId());
    }

    private void finishMain() {
        if (currentMain == null) return;
        instrumentation.runOnMainSync(() -> {
            for (Activity activity : new ArrayList<>(createdActivities)) {
                if (!activity.isDestroyed() && !activity.isFinishing()) activity.finishAndRemoveTask();
            }
        });
        await(() -> {
            for (Activity activity : createdActivities) if (!activity.isDestroyed()) return false;
            return true;
        });
        instrumentation.waitForIdleSync();
    }

    @RequiresApi(33)
    private String platformTags() {
        LocaleManager manager = application.getSystemService(LocaleManager.class);
        assertNotNull(manager);
        return manager.getApplicationLocales().toLanguageTags();
    }

    @RequiresApi(33)
    private void setPlatformTags(String tags) {
        LocaleManager manager = application.getSystemService(LocaleManager.class);
        assertNotNull(manager);
        manager.setApplicationLocales(LocaleList.forLanguageTags(tags));
    }

    @SuppressWarnings("unchecked")
    private void restoreKey(SharedPreferences.Editor edit, Map<String, ?> original, String key) {
        if (!original.containsKey(key)) { edit.remove(key); return; }
        Object value = original.get(key);
        if (value instanceof String) edit.putString(key, (String) value);
        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) edit.putInt(key, (Integer) value);
        else if (value instanceof Long) edit.putLong(key, (Long) value);
        else if (value instanceof Float) edit.putFloat(key, (Float) value);
        else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
    }

    private void await(Check condition) {
        await(condition, currentMain);
    }

    private void await(Check condition, MainActivity baseline) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 10000L;
        do {
            instrumentation.runOnMainSync(() -> ready.set(condition.ready()));
            if (ready.get()) return;
            SystemClock.sleep(20L);
        } while (SystemClock.uptimeMillis() < deadline);
        final String[] diagnostic = new String[1];
        instrumentation.runOnMainSync(() -> {
            MainActivity observed = currentMain;
            String state = observed == null ? "none" : observed.getLifecycle().getCurrentState().name();
            String language = observed == null ? "none" : resourceLanguage(observed);
            String appTags = Build.VERSION.SDK_INT >= 33 ? platformTags()
                    : AppCompatDelegate.getApplicationLocales().toLanguageTags();
            diagnostic[0] = "Locale/activity state did not settle: baseline="
                    + System.identityHashCode(baseline) + ", current=" + System.identityHashCode(observed)
                    + ", replaced=" + (observed != baseline) + ", language=" + language
                    + ", lifecycle=" + state + ", destroyed=" + (observed != null && observed.isDestroyed())
                    + ", platform/compat locales=" + appTags
                    + ", system locale=" + LanguageUtils.getSystemLocale().toLanguageTag()
                    + ", legacy preference=" + preferences.getString(AppConstants.LOCALE_LANGUAGE, "")
                    + ", migrated=" + migrationPreferences.getBoolean(MIGRATED, false);
        });
        assertTrue(diagnostic[0], ready.get());
    }

    private interface Check { boolean ready(); }

    private void grant(String permission) throws Exception {
        ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(
                "pm grant " + application.getPackageName() + " " + permission);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (input.read() != -1) { }
        }
    }
}
