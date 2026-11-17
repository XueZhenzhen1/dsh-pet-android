package local.dsh.pet;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * 全屏 WebView 承载 dsh。
 *
 * 这里的四件事，都是「手机上用起来才发现的」：
 * 1. 沉浸但不挡路：状态栏透明、深色图标，页面底色铺到屏幕顶；内容整体下移一个状态栏高度，
 *    侧栏第一行不会顶在时钟底下。
 * 2. 网页里那只不再出现：手机上只留系统悬浮球这一只，App 里常驻注入 CSS 把 .dsh-pet-root 藏掉。
 * 3. 返回键能一层一层退：先收侧边栏抽屉 → 再收右栏 → 再收弹层 → 再走浏览历史 → 最后退到桌面。
 * 4. 下载 / 上传都能用（dsh 里点附件、导出文件不会卡住）。
 */
public class WebActivity extends Activity {

    private static final int REQ_FILE = 0x101;

    /** 页面底色，和 dsh 的浅色主题一致，状态栏那块看起来才是「连着的」。 */
    private static final int PAGE_BG = 0xFFFFFFFF;

    private WebView web;
    private FrameLayout root;
    private FrameLayout host;
    private ValueCallback<Uri[]> pendingFiles;

    private boolean keyboardShown;

    /** 手机上不需要网页里那只：注入一次，永远 display:none。 */
    private static final String JS_HIDE_PET =
            "(function(){var id='__dsh_native_pet_css';"
                    + "var st=document.getElementById(id);"
                    + "if(!st){st=document.createElement('style');st.id=id;"
                    + "st.textContent='.dsh-pet-root{display:none!important}';"
                    + "(document.head||document.documentElement).appendChild(st);}"
                    + "try{document.documentElement.classList.add('dsh-native-pet');}catch(e){}})();";

    /**
     * 返回键的「上一级」：左侧抽屉 → 右侧面板 → 弹层。
     * 顺序不能反：窄屏下侧边栏本身就是 role=dialog，先判弹层会把它当成弹层。
     * 返回 '1' 表示已经吃掉这一下，别再往上退。
     */
    private static final String JS_BACK =
            "(function(){"
                    + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();"
                    + "if(r.width<8||r.height<8)return false;var s=getComputedStyle(el);"
                    + "return s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';}"
                    + "function esc(el){var ev=new KeyboardEvent('keydown',{key:'Escape',code:'Escape',"
                    + "keyCode:27,which:27,bubbles:true,cancelable:true});"
                    + "try{(el||document).dispatchEvent(ev);}catch(e){}"
                    + "try{document.dispatchEvent(ev);}catch(e){}}"
                    + "try{"
                    + "var f=document.querySelector('[data-sidebar-drawer]');"
                    + "if(f&&!f.hasAttribute('data-sidebar-collapsed')){"
                    + "var m=document.querySelector('[data-sidebar-mask]');"
                    + "if(m){m.click();return '1';}}"
                    + "var r=document.querySelector('[data-sidebar-right-open]');"
                    + "if(r){var b=r.querySelector('[data-sidebar-right-toggle]');"
                    + "if(b){b.click();return '1';}}"
                    + "var ds=[].slice.call(document.querySelectorAll('[role=dialog]')).filter(vis);"
                    + "if(ds.length){esc(ds[ds.length-1]);return '1';}"
                    + "}catch(e){}return '0';})()";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupImmersive();

        root = new FrameLayout(this);
        root.setBackgroundColor(PAGE_BG);

        web = new WebView(this);
        web.setBackgroundColor(PAGE_BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setTextZoom(100);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return handleUrl(req.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                // 页面一开始就塞，别等加载完闪一下那只网页宠物。
                hideWebPet();
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                hideWebPet();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                hideWebPet();
                syncPageTheme();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    Toast.makeText(WebActivity.this,
                            "连不上。先确认电脑上 dsh 在跑，手机也在 Tailscale 同一账号下。",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (pendingFiles != null) {
                    pendingFiles.onReceiveValue(null);
                }
                pendingFiles = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    pendingFiles = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception ignored) {
                    }
                });
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> download(url, cd, mime));

        // WebView 自己不吃 padding（它按整块 surface 排版），所以外面套一层会吃 padding 的容器，
        // 让页面整体从状态栏下面开始——侧栏第一行才点得到。
        host = new FrameLayout(this);
        host.setBackgroundColor(PAGE_BG);
        host.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(host, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 先用资源里的高度垫一下，避免首帧文字跑到状态栏底下；insets 回来后按真实值再改。
        host.setPadding(0, barHeight("status_bar_height") + dp(4),
                0, barHeight("navigation_bar_height"));

        // 键盘弹起时交给 adjustResize 缩窗口，这里再按 IME 高度加 padding 会扣两次。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                keyboardShown = insets.isVisible(WindowInsets.Type.ime());
                bottom = keyboardShown ? 0 : bars.bottom;
            } else {
                int navH = barHeight("navigation_bar_height");
                int sysBottom = insets.getSystemWindowInsetBottom();
                top = insets.getSystemWindowInsetTop();
                keyboardShown = sysBottom > navH + dp(40);
                bottom = Math.min(sysBottom, navH);
            }
            host.setPadding(0, Math.max(top + dp(4), dp(4)), 0, Math.max(0, bottom));
            return insets;
        });

        web.loadUrl(new Prefs(this).webUrl());
    }

    /* ------------------------- 沉浸式状态栏 ------------------------- */

    private void setupImmersive() {
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setBackgroundDrawableResource(android.R.color.white);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /**
     * 状态栏那条底色跟着页面走：页面是浅色就是白底深色图标，换成深色主题就自动翻过来。
     * 这样状态栏和页面是连成一片的，不会突兀地多一条灰带。
     */
    private void syncPageTheme() {
        if (web == null) return;
        String js = "(function(){try{var b=getComputedStyle(document.body).backgroundColor;"
                + "if(!b||b==='rgba(0, 0, 0, 0)'||b==='transparent')"
                + "b=getComputedStyle(document.documentElement).backgroundColor;"
                + "return b||'';}catch(e){return '';}})()";
        try {
            web.evaluateJavascript(js, value -> {
                Integer color = parseCssColor(value);
                if (color == null) return;
                root.setBackgroundColor(color);
                if (host != null) host.setBackgroundColor(color);
                double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                        + 0.114 * Color.blue(color)) / 255.0;
                setBarIconsDark(lum > 0.55);
            });
        } catch (Exception ignored) {
        }
    }

    /** 深色图标（浅底）／浅色图标（深底）。 */
    private void setBarIconsDark(boolean darkIcons) {
        try {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (darkIcons) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
    }

    /** 解析 WebView 返回的 "rgb(r, g, b)" / "rgba(...)" / "#rrggbb"，认不出就返回 null。 */
    private static Integer parseCssColor(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        while (v.startsWith("\"") || v.startsWith("[")) v = v.substring(1);
        while (v.endsWith("\"") || v.endsWith("]")) v = v.substring(0, v.length() - 1);
        v = v.trim();
        try {
            if (v.startsWith("#") && (v.length() == 7 || v.length() == 4)) {
                int r, g, b;
                if (v.length() == 7) {
                    r = Integer.parseInt(v.substring(1, 3), 16);
                    g = Integer.parseInt(v.substring(3, 5), 16);
                    b = Integer.parseInt(v.substring(5, 7), 16);
                } else {
                    r = Integer.parseInt(v.substring(1, 2), 16) * 17;
                    g = Integer.parseInt(v.substring(2, 3), 16) * 17;
                    b = Integer.parseInt(v.substring(3, 4), 16) * 17;
                }
                return Color.rgb(r, g, b);
            }
            int i = v.indexOf('(');
            int j = v.lastIndexOf(')');
            if (i > 0 && j > i) {
                String[] parts = v.substring(i + 1, j).split(",");
                if (parts.length >= 4) {
                    float a = Float.parseFloat(parts[3].trim());
                    if (a <= 0.01f) return null;
                }
                if (parts.length >= 3) {
                    int r = Math.round(Float.parseFloat(parts[0].trim()));
                    int g = Math.round(Float.parseFloat(parts[1].trim()));
                    int b = Math.round(Float.parseFloat(parts[2].trim()));
                    return Color.rgb(clamp255(r), clamp255(g), clamp255(b));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /* ------------------------- 手机上只留一只 ------------------------- */
    /**
     * 网页里那只常驻隐藏。注意不能做成「悬浮球开着才隐藏」——用户要的是手机端彻底没有网页宠物，
     * 只留系统悬浮球；电脑浏览器里的那只不受影响（这段 CSS 只注入到这个 WebView）。
     */
    private void hideWebPet() {
        if (web == null) return;
        try {
            web.evaluateJavascript(JS_HIDE_PET, null);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideWebPet();
    }

    /* ------------------------- 返回键 ------------------------- */

    @Override
    public void onBackPressed() {
        if (keyboardShown) {
            hideKeyboard();
            return;
        }
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript(JS_BACK, value -> {
            String v = value == null ? "" : value.trim();
            if (v.contains("1")) return;
            if (web != null && web.canGoBack()) {
                web.goBack();
                return;
            }
            moveTaskToBack(true);
        });
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && web != null) {
                imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            }
            if (web != null) {
                web.clearFocus();
                web.evaluateJavascript(
                        "(function(){var a=document.activeElement;if(a&&a.blur)a.blur();})();", null);
            }
        } catch (Exception ignored) {
        }
    }

    /* ------------------------- 下载 ------------------------- */

    private void download(String url, String contentDisposition, String mime) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mime);
            req.setTitle(name);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.allowScanningByMediaScanner();
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(this, "开始下载：" + name, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "这个文件没法下载，试试在电脑上拿", Toast.LENGTH_SHORT).show();
        }
    }

    /* ------------------------- 其它 ------------------------- */

    private boolean handleUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (pendingFiles == null) return;
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            pendingFiles.onReceiveValue(out);
            pendingFiles = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                if (host != null) host.removeView(web);
                else root.removeView(web);
            } catch (Exception ignored) {
            }
            web.stopLoading();
            web.loadUrl("about:blank");
            web.removeAllViews();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int barHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
