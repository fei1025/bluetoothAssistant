package com.zzf.bluetoothsmp.utils;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.zzf.bluetoothsmp.base.AppConstants;
import java.util.Locale;

/** Public per-app locale APIs with migration of the existing i18n preference. */
public final class LanguageUtils {
    public static final String SYSTEM_LANGUAGE_TGA = "systemLanguageTag";
    private static Context application;
    private static final String MIGRATED = "platform_locale_migrated";
    private LanguageUtils() { }

    public static void initialize(Context context) { application = context.getApplicationContext(); }
    private static SharedPreferences preferences() {
        return application.getSharedPreferences(AppConstants.I18N, Context.MODE_PRIVATE);
    }
    private static SharedPreferences migrationPreferences() {
        // This installation marker is excluded from backup; a restored legacy choice
        // must still migrate on the new device without overriding later system changes.
        return application.getSharedPreferences("locale_migration", Context.MODE_PRIVATE);
    }
    public static void applyAppLanguage(@NonNull Activity activity) {
        if (application == null) initialize(activity);
        if (Build.VERSION.SDK_INT >= 33) {
            if (!migrationPreferences().getBoolean(MIGRATED, false)) {
                String saved = preferences().getString(AppConstants.LOCALE_LANGUAGE, "");
                LocaleManager manager = activity.getSystemService(LocaleManager.class);
                if (manager != null && manager.getApplicationLocales().isEmpty()) {
                    AppCompatDelegate.setApplicationLocales(localesFor(saved));
                }
                migrationPreferences().edit().putBoolean(MIGRATED, true).apply();
            }
        } else {
            LocaleListCompat wanted = localesFor(preferences().getString(AppConstants.LOCALE_LANGUAGE, ""));
            if (!wanted.equals(AppCompatDelegate.getApplicationLocales())) {
                AppCompatDelegate.setApplicationLocales(wanted);
            }
        }
    }
    private static LocaleListCompat localesFor(String language) {
        return language == null || language.isEmpty() || SYSTEM_LANGUAGE_TGA.equals(language)
                ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(language);
    }
    public static Locale getSystemLocale() {
        if (Build.VERSION.SDK_INT >= 33 && application != null) {
            LocaleManager manager = application.getSystemService(LocaleManager.class);
            if (manager != null && !manager.getSystemLocales().isEmpty()) {
                return manager.getSystemLocales().get(0);
            }
        }
        if (Build.VERSION.SDK_INT >= 24) return Resources.getSystem().getConfiguration().getLocales().get(0);
        return Resources.getSystem().getConfiguration().locale;
    }
    public static Locale getPrefAppLocale() {
        if (application == null) return null;
        if (Build.VERSION.SDK_INT >= 33 && migrationPreferences().getBoolean(MIGRATED, false)) {
            LocaleManager manager = application.getSystemService(LocaleManager.class);
            if (manager != null) return manager.getApplicationLocales().isEmpty()
                    ? null : manager.getApplicationLocales().get(0);
        }
        LocaleListCompat saved = localesFor(preferences().getString(AppConstants.LOCALE_LANGUAGE, ""));
        return saved.isEmpty() ? null : saved.get(0);
    }
    public static Locale getCurrentAppLocale() {
        Locale locale = getPrefAppLocale();
        return locale == null ? getSystemLocale() : locale;
    }
    public static void saveAppLocaleLanguage(String language) {
        preferences().edit().putString(AppConstants.LOCALE_LANGUAGE, language).apply();
        migrationPreferences().edit().putBoolean(MIGRATED, true).apply();
        AppCompatDelegate.setApplicationLocales(localesFor(language));
    }
    public static Context localizedContext(Context context) {
        if (context == null) return null;
        // API 33+ already supplies per-app locales through the platform context.
        // A fixed override here would mask changes made in Android Settings.
        if (Build.VERSION.SDK_INT >= 33) return context;
        // AppCompat attaches its locale override before onActivityCreated. Prepare
        // the restored preference now, otherwise API 24-32 can replace our localized
        // base with the system locale and miss the first recreation.
        if (application == null) initialize(context);
        LocaleListCompat wanted = localesFor(preferences().getString(AppConstants.LOCALE_LANGUAGE, ""));
        if (!wanted.equals(AppCompatDelegate.getApplicationLocales())) AppCompatDelegate.setApplicationLocales(wanted);
        Locale locale = getCurrentAppLocale();
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
    public static void updateLanguage(Context context, Locale locale) {
        if (application == null) initialize(context);
        saveAppLocaleLanguage(locale == null ? SYSTEM_LANGUAGE_TGA : locale.toLanguageTag());
    }
    public static boolean isSimpleLanguage(Context context, Locale locale) {
        Locale current = Build.VERSION.SDK_INT >= 24 ? context.getResources().getConfiguration().getLocales().get(0)
                : context.getResources().getConfiguration().locale;
        return current.equals(locale);
    }
    public static String getAppLanguage() { return getCurrentAppLocale().toLanguageTag(); }
}
