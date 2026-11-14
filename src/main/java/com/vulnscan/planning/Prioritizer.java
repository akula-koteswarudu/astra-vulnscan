package com.vulnscan.planning;

import com.vulnscan.ingest.AppModel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rule-based priority of a file from built-in Android security API signals (MASVS-oriented, not app-specific).
 * Used to order units and, when planning.max_units is set, to decide which units are skipped (skips are reported).
 */
public class Prioritizer {

    static final Map<String, Pattern> SIGNALS = new LinkedHashMap<>();

    static {
        SIGNALS.put("webview_load", Pattern.compile("\\.loadUrl\\(|\\.loadData(WithBaseURL)?\\("));
        SIGNALS.put("webview_settings", Pattern.compile("setJavaScriptEnabled\\(|addJavascriptInterface\\(|setAllowFileAccess|setAllowUniversalAccessFromFileURLs|setAllowContentAccess"));
        SIGNALS.put("tls_override", Pattern.compile("onReceivedSslError|X509TrustManager|HostnameVerifier|checkServerTrusted"));
        SIGNALS.put("intent_input", Pattern.compile("getStringExtra\\(|getParcelableExtra\\(|getExtras\\(|getData\\(\\)|getDataString\\(|getQueryParameter\\("));
        SIGNALS.put("component_launch", Pattern.compile("startActivity\\(|startService\\(|sendBroadcast\\(|setResult\\(|registerReceiver\\("));
        SIGNALS.put("sql", Pattern.compile("rawQuery\\(|execSQL\\("));
        SIGNALS.put("world_mode", Pattern.compile("MODE_WORLD_(READABLE|WRITEABLE)"));
        SIGNALS.put("file_storage", Pattern.compile("openFileOutput\\(|getExternalStorage|getExternalFilesDir|Environment\\.getExternal"));
        SIGNALS.put("code_loading", Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|createPackageContext\\(|DexClassLoader|loadClass\\("));
        SIGNALS.put("crypto", Pattern.compile("Cipher\\.getInstance\\(|SecretKeySpec\\(|/ECB/|MessageDigest\\.getInstance\\(\"(MD5|SHA-?1)\""));
        SIGNALS.put("logging", Pattern.compile("\\bLog\\.[vdiwe]\\("));
        SIGNALS.put("prefs_write", Pattern.compile("SharedPreferences|\\.putString\\("));
        SIGNALS.put("provider", Pattern.compile("extends ContentProvider|openFile\\(|query\\(Uri"));
    }

    public int score(AppModel.SourceFile file, String text, AppModel.Manifest manifest) {
        int score = 0;
        for (Pattern p : SIGNALS.values()) {
            if (p.matcher(text).find()) {
                score += 1;
            }
        }
        if (file.component() != null) {
            AppModel.Component c = manifest.components().stream().filter(x -> x.name().equals(file.component())).findFirst().orElse(null);
            if (c != null && c.effectiveExported()) {
                score += 3;
            }
            if (c != null && c.isDeepLink()) {
                score += 2;
            }
        }
        return score;
    }
}
