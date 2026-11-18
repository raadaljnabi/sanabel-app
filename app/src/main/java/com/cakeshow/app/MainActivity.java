package com.cakeshow.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.io.OutputStream;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final int PICK_REQ = 1001;
    // رابط التطبيق المدموج على Firebase Hosting (الكيك + المخبز والمستودع)
    static final String APP_URL = "https://sanabelaltahoona-3bec5.web.app/";
    static final String APP_HOST = "sanabelaltahoona-3bec5.web.app";
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private WebView printView; // kept alive until printing finishes
    private WebView shareView; // for converting the receipt to PDF
    private FrameLayout root;
    // Public key for verifying activation codes (the private key stays with Meras Tech only)
    private static final String LIC_PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+v5k/+QnvNE39OVDW+xkwIpJx0dc5MGXjT3bHg7wzWjuiYWjIkklSpmCwFGVZQSEXjlJ+YQDKVT5wo8uYZyo5w==";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Keep the screen on (display mode in the shop)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Android 13+: ask once for permission to show notifications (orders, maintenance...)
        if (Build.VERSION.SDK_INT >= 33) { try { if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Exception e) {} }

        // Needed to capture the whole receipt as an image
        if (Build.VERSION.SDK_INT >= 21) WebView.enableSlowWholeDocumentDraw();
        root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFFF8EBCF);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage to save the data
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        // Allow the local page to reach Firebase
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);             // ignore the device font size so the layout stays the same
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        web.setWebViewClient(new WebViewClient() {
            // Links like WhatsApp open outside the app; the app's own pages stay inside
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                return openExternal(req.getUrl());
            }
            // No internet on the very first run (nothing cached yet): show the offline page
            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame() && req.getUrl() != null && APP_HOST.equals(req.getUrl().getHost())) view.loadUrl("file:///android_asset/offline.html");
            }
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String url) {
                if (url != null && url.startsWith(APP_URL)) view.loadUrl("file:///android_asset/offline.html");
            }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternal(Uri.parse(url));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                // Images by default, any file for restoring a backup
                boolean any = false;
                String[] acc = params.getAcceptTypes();
                if (acc != null) for (String t : acc) if (t != null && (t.contains("json") || t.equals("*/*"))) any = true;
                i.setType(any ? "*/*" : "image/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, "اختر الصور"), PICK_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        // Print the receipt from inside the page: Android.printHtml(html)
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void printHtml(final String html) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { printReceipt(html); }
                });
            }

            // Reload the app (from the offline page or after collecting old data)
            @JavascriptInterface
            public void retry() {
                runOnUiThread(new Runnable() {
                    @Override public void run() { web.loadUrl(APP_URL); }
                });
            }

            // Open the page that collects the old cake app's data saved on this device
            @JavascriptInterface
            public void openLegacy() {
                runOnUiThread(new Runnable() {
                    @Override public void run() { web.loadUrl("file:///android_asset/migrate.html"); }
                });
            }

            // Device code for activation
            @JavascriptInterface
            public String deviceId() {
                String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                return id == null ? "" : id;
            }

            // Verify the activation code signature
            @JavascriptInterface
            public boolean verifyLic(String msg, String sig) {
                try {
                    PublicKey k = KeyFactory.getInstance("EC").generatePublic(
                            new X509EncodedKeySpec(Base64.decode(LIC_PUB, Base64.DEFAULT)));
                    Signature v = Signature.getInstance("SHA256withECDSA");
                    v.initVerify(k);
                    v.update(msg.getBytes("UTF-8"));
                    return v.verify(rawToDer(Base64.decode(sig, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP)));
                } catch (Exception e) {
                    return false;
                }
            }

            // Save the backup in Downloads or share it
            @JavascriptInterface
            public void saveFile(final String data, final String name, final boolean share) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { saveBackup(data, name, share); }
                });
            }

            // System notification + vibration for important work (new order, delivery, maintenance)
            @JavascriptInterface
            public void notify(final String title, final String body, final boolean urgent) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { showNote(title, body, urgent); }
                });
            }

            // Customer photo at full resolution: save to the gallery (Pictures/Sanabel) or share to a printer app
            @JavascriptInterface
            public void saveImage(final String dataUrl, final String name, final boolean share) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { saveImg(dataUrl, name, share); }
                });
            }

            // Send the receipt as a PDF straight to the customer's WhatsApp
            @JavascriptInterface
            public void sharePdf(final String html, final String phone, final String no, final String caption) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { makePdf(html, phone, no, caption, 400, 320, 0f); }
                });
            }

            // PDF at invoice size (A4/A5): page width and CSS width
            @JavascriptInterface
            public void sharePdf2(final String html, final String phone, final String no, final String caption, final int cssW, final int pageW, final float minRatio) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { makePdf(html, phone, no, caption, cssW, pageW, minRatio); }
                });
            }

            // Print window with a default paper size: A4 / A5 / R80 / R58
            @JavascriptInterface
            public void printHtml2(final String html, final String media) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { printWithMedia(html, media); }
                });
            }

            // Direct printing to a network receipt printer (ESC/POS, port 9100)
            @JavascriptInterface
            public void printEscPos(final String html, final String ip, final int port, final int dots, final int cssW, final int copies) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { escPosPrint(html, ip, port, dots, cssW, copies); }
                });
            }

            // Direct printing to an office printer on the network (HP etc.): PDF over port 9100
            @JavascriptInterface
            public void printPdfNet(final String html, final String ip, final int port, final int cssW, final int pageW, final int pageH, final int contentW, final int copies) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { pdfNetPrint(html, ip, port, cssW, pageW, pageH, contentW, copies); }
                });
            }

            // Search the network for printers (port 9100)
            @JavascriptInterface
            public void findPrinters() { scanPrinters(); }

            // Test the connection to a printer
            @JavascriptInterface
            public void testPrinter(final String ip, final int port) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        String why = "";
                        try { Socket sk = openPrinter(ip, port); sk.close(); } catch (Exception e) { why = reason(e); }
                        jsCall("__prnTest(" + why.isEmpty() + ",'" + why.replace("'", "") + "')");
                    }
                }).start();
            }
        }, "Android");

        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/start.html");

        hideBars();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_REQ || fileCb == null) return;
        Uri[] result = null;
        if (res == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int k = 0; k < clip.getItemCount(); k++) result[k] = clip.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCb.onReceiveValue(result);
        fileCb = null;
    }

    private void jsCall(final String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                String fn = js.substring(0, js.indexOf('('));
                web.evaluateJavascript("window." + fn + "&&window." + js, null);
            }
        });
    }

    private void toastUi(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    private void printWithMedia(String html, final String media) {
        final WebView pv = new WebView(this);
        printView = pv;
        pv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView view, String url) {
                // Short wait for the app font to load before printing
                view.postDelayed(new Runnable() {
                    @Override public void run() { startPrint(view, media); }
                }, 700);
            }
        });
        pv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    private void startPrint(WebView view, String media) {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) return;
        PrintAttributes.Builder b = new PrintAttributes.Builder();
        if ("A4".equals(media)) b.setMediaSize(PrintAttributes.MediaSize.ISO_A4);
        else if ("A5".equals(media)) b.setMediaSize(PrintAttributes.MediaSize.ISO_A5);
        else if ("R58".equals(media)) b.setMediaSize(new PrintAttributes.MediaSize("R58", "إيصال 58مم", 2283, 11690));
        else if ("R80".equals(media)) b.setMediaSize(new PrintAttributes.MediaSize("R80", "إيصال 80مم", 3150, 11690));
        PrintDocumentAdapter ad = view.createPrintDocumentAdapter("receipt");
        pm.print("سنابل الطاحونة", ad, b.build());
    }

    /** Render a hidden HTML page as an image at the given width */
    interface BmCb { void done(Bitmap bm); }

    private void renderBitmap(String html, final int cssW, final int pxW, final BmCb cb) {
        if (shareView != null) { root.removeView(shareView); shareView.destroy(); shareView = null; }
        final WebView sv = new WebView(this);
        shareView = sv;
        sv.setBackgroundColor(Color.WHITE);
        WebSettings ss = sv.getSettings();
        ss.setUseWideViewPort(true);
        ss.setLoadWithOverviewMode(true);
        ss.setTextZoom(100);
        final float scale = pxW / (float) cssW;
        // Placed behind the main screen so it stays hidden
        root.addView(sv, 0, new FrameLayout.LayoutParams(pxW, (int) (pxW * 1.5f)));
        sv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView view, String url) {
                view.postDelayed(new Runnable() {
                    @Override public void run() {
                        int h = (int) (view.getContentHeight() * scale);
                        if (h < 200) h = (int) (pxW * 1.5f);
                        final int fh = h;
                        view.setLayoutParams(new FrameLayout.LayoutParams(pxW, fh));
                        view.measure(View.MeasureSpec.makeMeasureSpec(pxW, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY));
                        view.layout(0, 0, pxW, fh);
                        view.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    Bitmap bm = Bitmap.createBitmap(pxW, fh, Bitmap.Config.ARGB_8888);
                                    Canvas c = new Canvas(bm);
                                    c.drawColor(Color.WHITE);
                                    view.draw(c);
                                    root.removeView(view);
                                    view.destroy();
                                    shareView = null;
                                    cb.done(bm);
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, "تعذر تجهيز الصفحة", Toast.LENGTH_LONG).show();
                                }
                            }
                        }, 450);
                    }
                }, 800);
            }
        });
        sv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    /** Convert the image to ESC/POS raster commands (black and white) */
    private static byte[] escPosBytes(Bitmap bm, boolean cut) {
        int w = bm.getWidth(), h = bm.getHeight(), bw = (w + 7) / 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x1B); o.write(0x40); // printer init
        int[] px = new int[w];
        for (int y0 = 0; y0 < h; y0 += 120) {
            int bh = Math.min(120, h - y0);
            o.write(0x1D); o.write(0x76); o.write(0x30); o.write(0x00);
            o.write(bw & 0xFF); o.write((bw >> 8) & 0xFF); o.write(bh & 0xFF); o.write((bh >> 8) & 0xFF);
            for (int y = y0; y < y0 + bh; y++) {
                bm.getPixels(px, 0, w, 0, y, w, 1);
                for (int xb = 0; xb < bw; xb++) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = xb * 8 + bit;
                        if (x >= w) continue;
                        int c = px[x];
                        int lum = (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
                        if (((c >>> 24) > 100) && lum < 170) b |= (0x80 >> bit);
                    }
                    o.write(b);
                }
            }
        }
        o.write(0x1B); o.write(0x64); o.write(5); // feed 5 lines
        if (cut) { o.write(0x1D); o.write(0x56); o.write(0x42); o.write(0x00); } // cut
        return o.toByteArray();
    }

    private void escPosPrint(String html, final String ip, final int port, int dots, int cssW, final int copies) {
        Toast.makeText(this, "جاري الطباعة...", Toast.LENGTH_SHORT).show();
        renderBitmap(html, cssW, dots, new BmCb() {
            @Override public void done(final Bitmap bm) {
                final byte[] data = escPosBytes(bm, true);
                bm.recycle();
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Socket sk = openPrinter(ip, port);
                            OutputStream os = sk.getOutputStream();
                            for (int i = 0; i < Math.max(1, copies); i++) os.write(data);
                            os.flush();
                            Thread.sleep(300);
                            sk.close();
                            toastUi("تمت الطباعة ✓");
                        } catch (Exception e) {
                            toastUi("ما گدر يوصل للطابعة " + ip + "\nالسبب: " + reason(e));
                        }
                    }
                }).start();
            }
        });
    }

    /** The local network (Wi-Fi/Ethernet) even if it has no internet */
    private Network lanNet() {
        if (Build.VERSION.SDK_INT < 23) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return n;
            }
        } catch (Exception e) { }
        return null;
    }

    /** Connect to the printer: over the Wi-Fi, with 3 tries */
    private Socket openPrinter(String ip, int port) throws Exception {
        Exception last = null;
        for (int t = 0; t < 3; t++) {
            Socket sk = new Socket();
            try {
                Network n = lanNet();
                if (n != null && Build.VERSION.SDK_INT >= 23) { try { n.bindSocket(sk); } catch (Exception x) { } }
                sk.connect(new InetSocketAddress(ip.trim(), port), 5000);
                return sk;
            } catch (Exception e) {
                last = e;
                try { sk.close(); } catch (Exception x) { }
                Thread.sleep(700);
            }
        }
        throw last;
    }

    private static String reason(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("ECONNREFUSED")) return "الطابعة رفضت الاتصال (يمكن مشغولة بجهاز ثاني، أو المنفذ مو 9100)";
        if (m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH")) return "الجهاز مو على نفس شبكة الطابعة";
        if (e instanceof java.net.SocketTimeoutException || m.contains("timed out") || m.contains("ETIMEDOUT")) return "ماكو رد من الطابعة (تأكد إنها شغالة والعنوان صح)";
        return m;
    }

    private void pdfNetPrint(String html, final String ip, final int port, int cssW, final int pageW, final int pageH, final int contentW, final int copies) {
        Toast.makeText(this, "جاري الطباعة...", Toast.LENGTH_SHORT).show();
        int pxW = Math.min(1800, Math.max(cssW, contentW * 200 / 72));
        renderBitmap(html, cssW, pxW, new BmCb() {
            @Override public void done(Bitmap bm) {
                final byte[] pdf;
                try {
                    int w = bm.getWidth(), h = bm.getHeight();
                    float sc = contentW / (float) w;
                    if (h * sc > pageH) sc = pageH / (float) h;
                    float dx = (pageW - w * sc) / 2f;
                    PdfDocument doc = new PdfDocument();
                    PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pageW, pageH, 1).create());
                    Canvas pc = pg.getCanvas();
                    pc.drawColor(Color.WHITE);
                    pc.translate(dx, 0);
                    pc.scale(sc, sc);
                    pc.drawBitmap(bm, 0, 0, null);
                    doc.finishPage(pg);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    doc.writeTo(bo);
                    doc.close();
                    bm.recycle();
                    pdf = bo.toByteArray();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر تجهيز الفاتورة", Toast.LENGTH_LONG).show();
                    return;
                }
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Socket sk = openPrinter(ip, port);
                            OutputStream os = sk.getOutputStream();
                            String uel = "\u001B%-12345X";
                            os.write((uel + "@PJL\r\n@PJL JOB NAME=\"Sanabel\"\r\n@PJL SET COPIES=" + Math.max(1, copies) + "\r\n@PJL ENTER LANGUAGE=PDF\r\n").getBytes("US-ASCII"));
                            os.write(pdf);
                            os.write((uel + "@PJL EOJ\r\n" + uel).getBytes("US-ASCII"));
                            os.flush();
                            Thread.sleep(800);
                            sk.close();
                            toastUi("تم إرسال الفاتورة للطابعة ✓");
                        } catch (Exception e) {
                            toastUi("ما گدر يوصل للطابعة " + ip + "\nالسبب: " + reason(e));
                        }
                    }
                }).start();
            }
        });
    }

    private void scanPrinters() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> found = Collections.synchronizedList(new ArrayList<String>());
                List<String> bases = new ArrayList<String>();
                try {
                    Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
                    while (ifs.hasMoreElements()) {
                        NetworkInterface ni = ifs.nextElement();
                        if (!ni.isUp() || ni.isLoopback()) continue;
                        Enumeration<InetAddress> as = ni.getInetAddresses();
                        while (as.hasMoreElements()) {
                            InetAddress a = as.nextElement();
                            if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                                String ipS = a.getHostAddress();
                                String base = ipS.substring(0, ipS.lastIndexOf('.') + 1);
                                if (!bases.contains(base)) bases.add(base);
                            }
                        }
                    }
                } catch (Exception e) { }
                ExecutorService ex = Executors.newFixedThreadPool(48);
                for (final String base : bases) {
                    for (int i = 1; i < 255; i++) {
                        final String ip = base + i;
                        ex.execute(new Runnable() {
                            @Override public void run() {
                                try { Socket sk = new Socket(); Network wn = lanNet(); if (wn != null) try { wn.bindSocket(sk); } catch (Exception x) { } sk.connect(new InetSocketAddress(ip, 9100), 600); sk.close(); found.add(ip); } catch (Exception e) { }
                            }
                        });
                    }
                }
                ex.shutdown();
                try { ex.awaitTermination(20, TimeUnit.SECONDS); } catch (Exception e) { }
                StringBuilder sb = new StringBuilder("[");
                synchronized (found) {
                    for (int i = 0; i < found.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(found.get(i)).append('"'); }
                }
                sb.append(']');
                jsCall("__prnFound(" + sb + "," + bases.size() + ")");
            }
        }).start();
    }

    private void printReceipt(String html) {
        final WebView pv = new WebView(this);
        printView = pv;
        pv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                if (pm == null) return;
                PrintDocumentAdapter ad = view.createPrintDocumentAdapter("receipt");
                pm.print("وصل سنابل الطاحونة", ad, new PrintAttributes.Builder().build());
            }
        });
        pv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    private static byte[] trimInt(byte[] b) {
        int i = 0;
        while (i < b.length - 1 && b[i] == 0 && (b[i + 1] & 0x80) == 0) i++;
        byte[] t = Arrays.copyOfRange(b, i, b.length);
        if ((t[0] & 0x80) != 0) {
            byte[] u = new byte[t.length + 1];
            System.arraycopy(t, 0, u, 1, t.length);
            return u;
        }
        return t;
    }

    private static byte[] rawToDer(byte[] raw) {
        if (raw.length != 64) return raw;
        byte[] r = trimInt(Arrays.copyOfRange(raw, 0, 32)), s = trimInt(Arrays.copyOfRange(raw, 32, 64));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x30); o.write(4 + r.length + s.length);
        o.write(0x02); o.write(r.length); o.write(r, 0, r.length);
        o.write(0x02); o.write(s.length); o.write(s, 0, s.length);
        return o.toByteArray();
    }

    private void saveBackup(String data, String name, boolean share) {
        try {
            byte[] bytes = data.getBytes("UTF-8");
            if (!share && Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SanabelBackup");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u != null) {
                    OutputStream os = getContentResolver().openOutputStream(u);
                    os.write(bytes);
                    os.close();
                    Toast.makeText(this, "تم الحفظ بمجلد التنزيلات / SanabelBackup", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            // Share (Drive, Telegram, WhatsApp...) or older Android versions
            File dir = new File(getCacheDir(), "receipts");
            dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream out = new FileOutputStream(f);
            out.write(bytes);
            out.close();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://" + ReceiptProvider.AUTH + "/" + name));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "حفظ النسخة الاحتياطية"));
        } catch (Exception e) {
            Toast.makeText(this, "تعذر حفظ النسخة الاحتياطية", Toast.LENGTH_LONG).show();
        }
    }

    private int noteId = 100;
    private void showNote(String title, String body, boolean urgent) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String ch = urgent ? "sanabel_urgent" : "sanabel_work";
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel c = new android.app.NotificationChannel(ch, urgent ? "تنبيهات عاجلة" : "تنبيهات الشغل",
                        urgent ? android.app.NotificationManager.IMPORTANCE_HIGH : android.app.NotificationManager.IMPORTANCE_DEFAULT);
                c.enableVibration(true); c.setVibrationPattern(new long[]{0, 400, 200, 400});
                nm.createNotificationChannel(c);
            }
            Intent it = new Intent(this, MainActivity.class); it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int pf = 0; if (Build.VERSION.SDK_INT >= 23) pf = android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, it, pf);
            android.app.Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new android.app.Notification.Builder(this, ch); else b = new android.app.Notification.Builder(this);
            b.setSmallIcon(getApplicationInfo().icon).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pi)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(body));
            if (Build.VERSION.SDK_INT < 26) { b.setDefaults(android.app.Notification.DEFAULT_ALL); if (urgent) b.setPriority(android.app.Notification.PRIORITY_HIGH); }
            nm.notify(noteId++, b.build());
            if (noteId > 150) noteId = 100;
        } catch (Exception e) { }
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) { if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 300, 150, 300}, -1)); else v.vibrate(new long[]{0, 300, 150, 300}, -1); }
        } catch (Exception e) { }
    }

    private void saveImg(String dataUrl, String name, boolean share) {
        try {
            String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            String fn = name.endsWith(".jpg") ? name : name + ".jpg";
            if (!share && Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, fn);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sanabel");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (u != null) {
                    OutputStream os = getContentResolver().openOutputStream(u);
                    os.write(bytes);
                    os.close();
                    Toast.makeText(this, "انحفظت الصورة بالمعرض / Sanabel", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            // Share to a printer app (Canon PRINT, Epson...) or older Android versions
            File dir = new File(getCacheDir(), "receipts");
            dir.mkdirs();
            File f = new File(dir, fn);
            FileOutputStream out = new FileOutputStream(f);
            out.write(bytes);
            out.close();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/jpeg");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://" + ReceiptProvider.AUTH + "/" + fn));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, share ? "افتح الصورة ببرنامج الطابعة" : "حفظ الصورة"));
        } catch (Exception e) {
            Toast.makeText(this, "تعذر حفظ الصورة", Toast.LENGTH_LONG).show();
        }
    }

    private void makePdf(String html, final String phone, final String no, final String caption, int cssW, final int pageW, final float minRatio) {
        Toast.makeText(this, "جاري تجهيز الملف...", Toast.LENGTH_SHORT).show();
        final float dens = getResources().getDisplayMetrics().density;
        int pxW = Math.min(1400, Math.max((int) (cssW * dens), cssW * 2));
        renderBitmap(html, cssW, pxW, new BmCb() {
            @Override public void done(Bitmap bm) {
                try {
                    int w = bm.getWidth(), h = bm.getHeight();
                    float sc = pageW / (float) w;
                    int ph = (int) (h * sc);
                    if (minRatio > 0) ph = Math.max(ph, (int) (pageW * minRatio));
                    PdfDocument doc = new PdfDocument();
                    PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pageW, ph, 1).create());
                    Canvas pc = pg.getCanvas();
                    pc.drawColor(Color.WHITE);
                    pc.scale(sc, sc);
                    pc.drawBitmap(bm, 0, 0, null);
                    doc.finishPage(pg);
                    File dir = new File(getCacheDir(), "receipts");
                    dir.mkdirs();
                    String name = "invoice-" + no.replaceAll("[^0-9]", "") + ".pdf";
                    File f = new File(dir, name);
                    FileOutputStream out = new FileOutputStream(f);
                    doc.writeTo(out);
                    out.close();
                    doc.close();
                    bm.recycle();
                    sendPdf(Uri.parse("content://" + ReceiptProvider.AUTH + "/" + name), phone, caption);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر إنشاء ملف PDF", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private Intent pdfIntent(Uri u, String caption) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, u);
        if (caption != null && caption.length() > 0) i.putExtra(Intent.EXTRA_TEXT, caption);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    private void sendPdf(Uri u, String phone, String caption) {
        // Straight to the customer's chat on WhatsApp, then WhatsApp Business, otherwise the share list
        String[] apps = {"com.whatsapp", "com.whatsapp.w4b"};
        for (String app : apps) {
            try {
                Intent i = pdfIntent(u, caption);
                i.setPackage(app);
                if (phone != null && phone.length() > 6) i.putExtra("jid", phone + "@s.whatsapp.net");
                startActivity(i);
                return;
            } catch (Exception e) { /* try the next one */ }
        }
        try {
            startActivity(Intent.createChooser(pdfIntent(u, caption), "إرسال الوصل"));
        } catch (Exception e) {
            Toast.makeText(this, "ما لكيت تطبيق للإرسال", Toast.LENGTH_LONG).show();
        }
    }

    private boolean openExternal(Uri u) {
        if (u == null || "file".equals(u.getScheme())) return false;
        String h = u.getHost(), sc = u.getScheme();
        if (("https".equals(sc) || "http".equals(sc)) && h != null && (h.equals(APP_HOST) || h.endsWith(".web.app") || h.endsWith(".firebaseapp.com"))) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
        return true;
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
