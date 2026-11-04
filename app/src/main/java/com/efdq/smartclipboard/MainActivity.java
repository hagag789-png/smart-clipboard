package com.efdq.smartclipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import com.google.mlkit.nl.entityextraction.Entity;
import com.google.mlkit.nl.entityextraction.EntityAnnotation;
import com.google.mlkit.nl.entityextraction.EntityExtraction;
import com.google.mlkit.nl.entityextraction.EntityExtractionParams;
import com.google.mlkit.nl.entityextraction.EntityExtractor;
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    WebView web;

    // ── ML Kit: فهم النص على الجهاز ──
    EntityExtractor mlAr;          // النموذج العربي (يتعامل مع الأرقام والنصوص المختلطة)
    EntityExtractor mlEn;          // النموذج الإنجليزي (للنصوص اللاتينية الصرفة)
    volatile String mlStatus = "downloading";   // downloading | ready | failed

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { pushMlStatus(); }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        initMl();
    }

    // تحميل النماذج مرة واحدة (بضعة ميجابايت) ثم تعمل بدون إنترنت
    private void initMl() {
        try {
            mlAr = EntityExtraction.getClient(new EntityExtractorOptions.Builder(EntityExtractorOptions.ARABIC).build());
            mlEn = EntityExtraction.getClient(new EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build());
            mlAr.downloadModelIfNeeded()
                .addOnSuccessListener(u -> { mlStatus = "ready"; pushMlStatus();
                    // الإنجليزي اختياري: لو فشل نكمل بالعربي فقط
                    mlEn.downloadModelIfNeeded().addOnFailureListener(e -> mlEn = null); })
                .addOnFailureListener(e -> { mlStatus = "failed"; pushMlStatus(); });
        } catch (Throwable t) {
            mlStatus = "failed"; pushMlStatus();
        }
    }

    private void pushMlStatus() {
        runOnUiThread(() -> web.evaluateJavascript(
            "window.onMlStatus && window.onMlStatus(" + JSONObject.quote(mlStatus) + ")", null));
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (mlAr != null) mlAr.close(); if (mlEn != null) mlEn.close(); } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
            "(function(){ var ov=document.getElementById('overlay'); " +
            "if(ov && ov.classList.contains('open')){ ov.classList.remove('open'); return true; } " +
            "var co=document.getElementById('confirm-overlay'); " +
            "if(co && co.classList.contains('open')){ co.classList.remove('open'); return true; } " +
            "return false; })()",
            result -> {
                if (!"true".equals(result)) {
                    super.onBackPressed();
                }
            }
        );
    }

    class Bridge {

        @JavascriptInterface
        public String getClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return "";
                ClipData data = cm.getPrimaryClip();
                if (data == null || data.getItemCount() == 0) return "";
                CharSequence text = data.getItem(0).coerceToText(MainActivity.this);
                return text != null ? text.toString() : "";
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String mlStatus() { return mlStatus; }

        // تحليل النص بالذكاء الاصطناعي ثم إرجاع النتيجة إلى JS عبر onMlEntities(reqId, [...])
        @JavascriptInterface
        public void extractEntities(String text, String reqId) {
            if (text == null || text.isEmpty() || !"ready".equals(mlStatus)) return;
            EntityExtractor ex = (!hasArabic(text) && mlEn != null) ? mlEn : mlAr;
            if (ex == null) return;
            EntityExtractionParams params = new EntityExtractionParams.Builder(text).build();
            ex.annotate(params)
              .addOnSuccessListener(list -> sendEntities(reqId, text, list))
              .addOnFailureListener(e -> sendEntities(reqId, text, null));
        }

        private void sendEntities(String reqId, String text, List<EntityAnnotation> list) {
            JSONArray arr = new JSONArray();
            try {
                if (list != null) for (EntityAnnotation a : list) {
                    String t = a.getAnnotatedText();
                    if (t == null || t.isEmpty()) t = text.substring(a.getStart(), a.getEnd());
                    for (Entity en : a.getEntities()) {
                        JSONObject o = new JSONObject();
                        o.put("type", en.getType());
                        o.put("text", t);
                        o.put("start", a.getStart());
                        o.put("end", a.getEnd());
                        arr.put(o);
                    }
                }
            } catch (Throwable ignored) {}
            final String js = "window.onMlEntities && window.onMlEntities(" + JSONObject.quote(reqId) + "," + arr.toString() + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        }

        @JavascriptInterface
        public void setBars(String bg, boolean lightBars) {
            runOnUiThread(() -> {
                try {
                    int color = android.graphics.Color.parseColor(bg);
                    getWindow().setStatusBarColor(color);
                    getWindow().setNavigationBarColor(color);
                    View decor = getWindow().getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    if (lightBars) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                    decor.setSystemUiVisibility(flags);
                } catch (Exception ignored) {}
            });
        }
    }
}
