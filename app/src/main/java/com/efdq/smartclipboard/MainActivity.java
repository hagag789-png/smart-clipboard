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

public class MainActivity extends AppCompatActivity {
    WebView web;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript(
            "(function(){ var ov=document.getElementById('overlay'); " +
            "if(ov && ov.classList.contains('open')){ ov.classList.remove('open'); return true; } " +
            "var co=document.getElementById('confirm-overlay'); " +
            "if(co && co.classList.contains('open')){ co.classList.remove('open'); return true; } " +
            "return false; })()",
            result -> { if (!"true".equals(result)) super.onBackPressed(); });
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
            } catch (Exception e) { return ""; }
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
                    if (lightBars) { flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; }
                    else { flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; }
                    decor.setSystemUiVisibility(flags);
                } catch (Exception ignored) {}
            });
        }
    }
}
