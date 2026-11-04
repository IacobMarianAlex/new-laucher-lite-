package ro.lite.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Lite Launcher: ecran principal minimal.
 *  - fara fundal desenat, fara widget-uri, fara animatii, fara biblioteci externe;
 *  - iconitele se incarca doar cand apar pe ecran si se tin putine in memorie;
 *  - bara de favorite jos + lista cu toate aplicatiile (glisezi in sus).
 */
public class MainActivity extends Activity {

    static class App {
        String label;
        String pkg;
        String cls;

        String key() {
            return pkg + "/" + cls;
        }
    }

    // Favorite implicite: prima aplicatie instalata din fiecare grup (varianta Lite are prioritate)
    private static final String[][] DEFAULT_FAVS = {
            {"com.instagram.lite", "com.instagram.android"},
            {"com.whatsapp"},
            {"com.zhiliaoapp.musically.go", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"},
            {"com.facebook.lite", "com.facebook.orca"},
            {"com.samsung.android.dialer", "com.google.android.dialer"},
    };
    private static final int MAX_FAVS = 5;

    private final List<App> apps = new ArrayList<>();
    private final List<App> filtered = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(60);

    private FrameLayout root;
    private View homeView;
    private View drawerView;
    private LinearLayout dock;
    private TextView ramText;
    private EditText search;
    private GridView grid;
    private AppAdapter adapter;
    private boolean drawerOpen = false;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GestureDetector gestures;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
            buildDock();
            applyFilter();
        }
    };

    // ------------------------------------------------------------------ ciclul de viata

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        homeView = buildHome();
        drawerView = buildDrawer();
        root.addView(homeView);
        setContentView(root);

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (vy < -800 && Math.abs(vy) > Math.abs(vx)) {
                    openDrawer();
                    return true;
                }
                if (vy > 800 && Math.abs(vy) > Math.abs(vx)) {
                    expandNotifications();
                    return true;
                }
                return false;
            }
        });
        homeView.setOnTouchListener((v, ev) -> gestures.onTouchEvent(ev));

        loadApps();
        buildDock();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRam();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // butonul Acasa apasat cand launcher-ul e deja deschis
        closeDrawer();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        // pe ecranul principal "Inapoi" nu face nimic, ca la orice launcher
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        icons.evictAll();   // cand iesi din launcher, eliberam iconitele
    }

    // ------------------------------------------------------------------ interfata

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(String color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private View buildHome() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(40), dp(20), dp(16));

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setOnClickListener(v -> launchIntent(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)));
        col.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(Color.parseColor("#BBBBBB"));
        date.setTextSize(16);
        col.addView(date);

        ramText = new TextView(this);
        ramText.setTextColor(Color.parseColor("#777777"));
        ramText.setTextSize(12);
        ramText.setPadding(0, dp(6), 0, 0);
        col.addView(ramText);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(6), dp(10), dp(6), dp(10));
        dock.setBackground(round("#1A1A1A", 24));
        col.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView open = new TextView(this);
        open.setText("︿  Aplicații");
        open.setTextColor(Color.parseColor("#999999"));
        open.setTextSize(13);
        open.setGravity(Gravity.CENTER);
        open.setPadding(0, dp(12), 0, dp(4));
        open.setOnClickListener(v -> openDrawer());
        col.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return col;
    }

    private View buildDrawer() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);
        col.setPadding(dp(10), dp(12), dp(10), 0);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Caută aplicații");
        search.setHintTextColor(Color.GRAY);
        search.setTextColor(Color.WHITE);
        search.setTextSize(15);
        search.setBackground(round("#1E1E1E", 22));
        search.setPadding(dp(16), 0, dp(16), 0);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter();
            }

            public void afterTextChanged(Editable s) {
            }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            if (!filtered.isEmpty()) launch(filtered.get(0));
            return true;
        });
        col.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(14));
        grid.setPadding(0, dp(14), 0, dp(14));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(filtered.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            appMenu(v, filtered.get(pos));
            return true;
        });
        col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        search.setText("");
        applyFilter();
        root.removeAllViews();
        root.addView(drawerView);
        grid.setSelection(0);
    }

    private void closeDrawer() {
        hideKeyboard();
        if (!drawerOpen) return;
        drawerOpen = false;
        root.removeAllViews();
        root.addView(homeView);
        updateRam();
    }

    // ------------------------------------------------------------------ aplicatii

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        apps.clear();
        for (ResolveInfo r : list) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            App a = new App();
            a.pkg = r.activityInfo.packageName;
            a.cls = r.activityInfo.name;
            CharSequence l = r.loadLabel(pm);
            a.label = l != null ? l.toString() : a.pkg;
            apps.add(a);
        }
        final Collator c = Collator.getInstance(new Locale("ro", "RO"));
        apps.sort((x, y) -> c.compare(x.label, y.label));
        icons.evictAll();
    }

    private void applyFilter() {
        filtered.clear();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (App a : apps) {
            if (q.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(q)) filtered.add(a);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private App find(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private Drawable icon(App a) {
        Drawable d = icons.get(a.key());
        if (d == null) {
            try {
                d = getPackageManager().getActivityIcon(new ComponentName(a.pkg, a.cls));
            } catch (Exception e) {
                d = getPackageManager().getDefaultActivityIcon();
            }
            icons.put(a.key(), d);
        }
        return d;
    }

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(a.pkg, a.cls));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        launchIntent(i);
        if (drawerOpen) handler.postDelayed(this::closeDrawer, 300);
    }

    private void launchIntent(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Nu se poate deschide", Toast.LENGTH_SHORT).show();
        }
    }

    private void appMenu(View anchor, final App a) {
        PopupMenu pm = new PopupMenu(this, anchor);
        final boolean fav = favs().contains(a.pkg);
        pm.getMenu().add(0, 1, 0, fav ? "Scoate din favorite" : "Adaugă la favorite");
        pm.getMenu().add(0, 2, 0, "Informații aplicație");
        pm.getMenu().add(0, 3, 0, "Dezinstalează");
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                List<String> f = favs();
                if (fav) {
                    f.remove(a.pkg);
                } else {
                    if (f.size() >= MAX_FAVS) {
                        Toast.makeText(this, "Maxim " + MAX_FAVS + " favorite. Scoate una mai întâi.",
                                Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    f.add(a.pkg);
                }
                saveFavs(f);
                buildDock();
            } else if (id == 2) {
                launchIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.pkg)));
            } else if (id == 3) {
                launchIntent(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
            }
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------------ favorite

    private List<String> favs() {
        String s = prefs.getString("favs", null);
        List<String> out = new ArrayList<>();
        if (s == null) {
            for (String[] group : DEFAULT_FAVS) {
                for (String p : group) {
                    if (find(p) != null) {
                        out.add(p);
                        break;
                    }
                }
            }
            saveFavs(out);
            return out;
        }
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        return out;
    }

    private void saveFavs(List<String> f) {
        prefs.edit().putString("favs", String.join(",", f)).apply();
    }

    private void buildDock() {
        dock.removeAllViews();
        List<String> f = favs();
        int shown = 0;
        for (final String p : f) {
            final App a = find(p);
            if (a == null) continue;   // aplicatie dezinstalata
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon(a));
            iv.setPadding(dp(8), dp(4), dp(8), dp(4));
            iv.setContentDescription(a.label);
            iv.setOnClickListener(v -> launch(a));
            iv.setOnLongClickListener(v -> {
                appMenu(v, a);
                return true;
            });
            dock.addView(iv, new LinearLayout.LayoutParams(0, dp(60), 1f));
            shown++;
        }
        if (shown == 0) {
            TextView hint = new TextView(this);
            hint.setText("Ține apăsat pe o aplicație din listă → Adaugă la favorite");
            hint.setTextColor(Color.GRAY);
            hint.setTextSize(12);
            hint.setGravity(Gravity.CENTER);
            dock.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        }
    }

    // ------------------------------------------------------------------ diverse

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long free = mi.availMem / (1024 * 1024);
        long total = mi.totalMem / (1024 * 1024);
        ramText.setText("RAM liber: " + free + " MB din " + total + " MB");
    }

    @SuppressWarnings({"WrongConstant", "JavaReflectionMemberAccess"})
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Exception ignored) {
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && search != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ grila de aplicatii

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filtered.size();
        }

        @Override
        public Object getItem(int p) {
            return filtered.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell;
            ImageView iv;
            TextView tv;
            if (convert == null) {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                iv = new ImageView(MainActivity.this);
                cell.addView(iv, new LinearLayout.LayoutParams(dp(50), dp(50)));
                tv = new TextView(MainActivity.this);
                tv.setTextColor(Color.parseColor("#DDDDDD"));
                tv.setTextSize(11);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(2), dp(4), dp(2), 0);
                cell.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                cell = (LinearLayout) convert;
                iv = (ImageView) cell.getChildAt(0);
                tv = (TextView) cell.getChildAt(1);
            }
            App a = filtered.get(pos);
            iv.setImageDrawable(icon(a));
            tv.setText(a.label);
            return cell;
        }
    }
}
