package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;

/** Commute and saved-address views update in place; dialogs never recreate the page behind them. */
final class CommutePanel implements AutoCloseable {
    interface Permission { boolean ensure(boolean notifications); }
    private final Activity activity;
    private final CommuteMapPanel mapPanel;
    private final CommuteSpeaker speaker;
    // Package seam used by offline instrumentation; production always launches the foreground service.
    BiConsumer<Context, JSONObject> journeyLauncher = CommuteService::start;
    private String dialogueJourney = "";
    private long briefingVersion;
    private TextView stationStatus;
    private final Supplier<ApiClient> client;
    private final Supplier<JSONObject> appState;
    private final Supplier<ScrollView> viewport;
    private final BooleanSupplier allowed, visible;
    private final Permission permission;
    private final Runnable started, runningChanged;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final KeyedExecutor network = new KeyedExecutor(3);
    // Slow map searches have separate workers so they cannot occupy commute-control slots.
    private final ExecutorService queries = Executors.newFixedThreadPool(3);
    private final ArrayList<Dialog> dialogs = new ArrayList<>();
    private JSONArray addresses = new JSONArray();
    private JSONObject journey = new JSONObject(), origin, destination;
    private String originMode = "gps", transport = "driving", account = "", notice = "";
    private boolean loading, loaded, mutating, stopping, lastRunning;
    private volatile boolean closed;
    private volatile long generation;
    private long addressesVersion, journeyVersion;
    private int addressWrites;
    private boolean addressesNeedReload, addressRefreshInFlight;
    private String pendingJourneyId = "";
    private Runnable permissionAction;
    private LinearLayout summary, addressRows;
    private TextView originButton, destinationButton, transportButton, startButton, endButton, navigationButton, navigationHint, status;
    /** While a commute runs, its state and the only in-app end control stay above the fold. */
    private LinearLayout activeBar;
    private TextView activeTitle;
    /** Reverse-geocoded phone position shown on the origin row while the origin follows GPS. */
    private JSONObject currentPlace;
    private long originAttemptAt = -600_000;
    private boolean locatingOrigin;
    private String originProblem = "";
    private ScrollView pageScroll;
    private String lastRender = "";
    private Dialog navigationFallback;
    private long navigationOpenedAt = -2000;
    private Boolean navigationPrimary;
    private interface Job { JSONObject run(ApiClient api) throws Exception; }
    private interface Result { void accept(JSONObject result) throws Exception; }
    CommutePanel(Activity activity, Supplier<ApiClient> client, Supplier<JSONObject> state,
                 BooleanSupplier allowed, BooleanSupplier visible, Permission permission, Runnable started, Runnable runningChanged, Supplier<ScrollView> viewport) {
        this.activity = activity; this.client = client; this.speaker = new CommuteSpeaker(activity);
        this.mapPanel = new CommuteMapPanel(activity, client, visible, viewport); this.appState = state; this.allowed = allowed;
        this.viewport = viewport; this.visible = visible; this.permission = permission; this.started = started; this.runningChanged = runningChanged;
    }
    private int dp(int value) { return EditorUi.dp(activity, value); }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception e) { return new JSONObject(); } }
    private static void put(JSONObject value, String key, Object item) { try { value.put(key, item); } catch (JSONException ignored) {} }
    private static String tagName(String tag) { return "home".equals(tag) ? "家" : "work".equals(tag) ? "公司" : "school".equals(tag) ? "学校" : "自定义"; }
    private static String methodName(String method) { return "walking".equals(method) ? "步行" : "transit".equals(method) ? "公共交通" : "bicycling".equals(method) ? "骑行" : "驾车"; }
    private TextView text(LinearLayout parent, String value, int size, int color) { TextView v = EditorUi.text(activity, value, size, color); parent.addView(v); return v; }
    private TextView button(LinearLayout parent, String value, boolean primary, Runnable action) {
        TextView v = EditorUi.button(activity, value, primary, action);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(5), 0, dp(5)); parent.addView(v, p); return v;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout value = EditorUi.column(activity); value.setPadding(dp(16), dp(12), dp(16), dp(16));
        value.setBackground(EditorUi.shape(activity, 0xffffffff, 22, EditorUi.LINE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(7), 0, dp(7)); parent.addView(value, p); return value;
    }
    private Dialog sheet(String title, LinearLayout body, LinearLayout footer) {
        Dialog dialog = EditorUi.dialog(activity, title, body, footer); track(dialog); dialog.show();
        dialog.getWindow().setLayout(activity.getResources().getDisplayMetrics().widthPixels - dp(28), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .86f));
        return dialog;
    }
    private void compactSheet(Dialog dialog, LinearLayout body) {
        if (body.getParent() instanceof ScrollView) {
            ScrollView scroll = (ScrollView) body.getParent();
            scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        }
        dialog.getWindow().setLayout(activity.getResources().getDisplayMetrics().widthPixels - dp(28), -2);
    }
    private void track(Dialog dialog) {
        ScrollView page = viewport.get();
        View pageContent = page == null || page.getChildCount() == 0 ? null : page.getChildAt(0);
        int y = page == null ? 0 : page.getScrollY();
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            dialogs.remove(dialog);
            if (navigationFallback == dialog) navigationFallback = null;
            if (page == null || page != viewport.get() || !page.isAttachedToWindow()) return;
            // Window focus restoration may scroll a previously focused button into view.
            page.clearFocus();
            page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    if (page.getViewTreeObserver().isAlive()) page.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (page == viewport.get() && page.getChildCount() > 0 && page.getChildAt(0) == pageContent) page.scrollTo(0, y);
                    return true;
                }
            });
            page.requestLayout();
        });
    }
    void permissionBlocked(String message) { information("定位服务", message); }
    private void information(String title, String message) {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity); text(body, message, 14, EditorUi.INK);
        Dialog dialog = sheet(title, body, footer); button(footer, "知道了", true, dialog::dismiss);
    }
    private void execute(Job job, Result result, TextView progress) {
        execute(null, job, result, progress, () -> {}, () -> true);
    }
    private void query(Job job, Result result, TextView progress, BooleanSupplier relevant) {
        execute(null, job, result, progress, () -> {}, relevant);
    }
    private void execute(String key, Job job, Result result, TextView progress, Runnable failed) {
        execute(key, job, result, progress, failed, () -> true);
    }
    private void execute(String key, Job job, Result result, TextView progress, Runnable failed, BooleanSupplier relevant) {
        ApiClient api = client.get(); if (api == null) return;
        String token = api.vault.token(); long current = generation;
        if (token.isEmpty() || closed) return;
        if (progress != null) progress.setText("正在处理…");
        Runnable work = () -> {
            if (!current(api, token, current)) return;
            try {
                JSONObject value = job.run(api);
                ui.post(() -> { if (!current(api, token, current) || !relevant.getAsBoolean()) return; try { result.accept(value); } catch (Exception e) { failed.run(); fail("操作未完成，请重试", progress); } });
            } catch (Exception e) { String message = e instanceof ApiClient.ApiException ? e.getMessage() : "连接失败，请检查网络后重试";
                ui.post(() -> { if (current(api, token, current) && relevant.getAsBoolean()) { failed.run(); fail(message, progress); } }); }
        };
        if (key == null) queries.execute(work); else network.execute(CommuteService.owner(api) + ":" + key, work);
    }
    private boolean current(ApiClient api, String token, long current) { return !closed && generation == current && api == client.get() && token.equals(api.vault.token()); }
    private void fail(String message, TextView progress) { if (progress == null || progress == status) notice = message; if (progress != null) progress.setText(message); update(); }
    void resume() {
        if (closed || client.get() == null) return;
        String owner = CommuteService.owner(client.get());
        if (!owner.equals(account)) { resetViews(); account = owner; }
        if (!client.get().vault.token().isEmpty() && !loaded && !loading) load();
        ui.removeCallbacks(tick); ui.post(tick);
    }
    void pause() { ui.removeCallbacks(tick); mapPanel.pause(); speaker.stop(); briefingVersion++; }
    void pauseMap() { mapPanel.pause(); }
    boolean hasPendingMutations() { return mutating || addressWrites > 0; }
    private void load() {
        loading = true;
        long addressRead = addressesVersion, journeyRead = journeyVersion;
        execute("commute", api -> {
            String pending = CommuteService.pendingStop(activity, api);
            if (!pending.isEmpty()) {
                try { api.json("DELETE", "/v1/commute/" + pending, null); }
                catch (ApiClient.ApiException e) { if (e.status != 404) throw e; }
                CommuteService.clearPendingStop(activity, api, pending);
            }
            JSONObject data = api.json("GET", "/v1/saved-addresses", null);
            data.put("journey", api.json("GET", "/v1/commute", null)); return data;
        }, data -> {
            loading = false; loaded = true;
            if (addressRead == addressesVersion && addressWrites == 0) addresses = data.getJSONArray("addresses");
            else addressesNeedReload = true;
            if (journeyRead == journeyVersion) journey = data.optJSONObject("journey");
            if (journey == null) journey = new JSONObject();
            if (journeyRead == journeyVersion && journey.optBoolean("active")) {
                originMode = journey.optString("origin_mode", "gps"); origin = journey.optJSONObject("origin");
                destination = journey.optJSONObject("destination"); transport = journey.optString("transport", "driving");
            } else if (journeyRead == journeyVersion && destination == null) {
                JSONObject settings = appState.get().optJSONObject("settings");
                if (settings != null && !settings.isNull("destination_lon") && settings.has("destination_lon")) {
                    destination = new JSONObject().put("name", settings.optString("destination_name", "目的地")).put("address", settings.optString("destination_name", "目的地"))
                        .put("city", settings.optString("transit_city", "上海")).put("longitude", settings.getDouble("destination_lon")).put("latitude", settings.getDouble("destination_lat"));
                    transport = settings.optString("transport", "driving");
                }
            }
            update(); renderAddressRows(); refreshAddressesIfIdle();
        }, status, () -> loading = false);
    }
    private void refreshAddressesIfIdle() {
        if (!addressesNeedReload || addressRefreshInFlight || addressWrites > 0 || closed) return;
        addressRefreshInFlight = true; long version = addressesVersion;
        execute("addresses-read", api -> api.json("GET", "/v1/saved-addresses", null), data -> {
            addressRefreshInFlight = false;
            if (version == addressesVersion && addressWrites == 0) {
                addresses = data.getJSONArray("addresses"); addressesNeedReload = false; renderAddressRows();
            }
            refreshAddressesIfIdle();
        }, null, () -> addressRefreshInFlight = false);
    }
    private final Runnable tick = new Runnable() { public void run() {
        if (closed || !visible.getAsBoolean()) return;
        locateOrigin(false);
        boolean running = CommuteService.running(client.get());
        JSONObject active = CommuteService.current(client.get());
        if (active != null) journey = active;
        if (lastRunning != running) { lastRunning = running; runningChanged.run(); if (!running) { loaded = false; if (!loading) load(); } }
        update(); ui.postDelayed(this, 1000);
    }};
    void render(LinearLayout parent, ScrollView scroll) {
        pageScroll = scroll; resume();
        activeBar = card(parent); activeBar.setOrientation(LinearLayout.HORIZONTAL); activeBar.setGravity(Gravity.CENTER_VERTICAL);
        activeBar.setPadding(dp(16), dp(10), dp(10), dp(10));
        activeBar.setBackground(EditorUi.shape(activity, 0xfff3f7ff, 22, 0xffd0dfff));
        activeTitle = EditorUi.text(activity, "", 15, EditorUi.INK);
        activeBar.addView(activeTitle, new LinearLayout.LayoutParams(0, -2, 1));
        endButton = EditorUi.button(activity, "结束通勤", false, this::stop);
        endButton.setTextColor(EditorUi.RED); endButton.setBackground(EditorUi.shape(activity, 0xffffffff, 16, EditorUi.RED));
        endButton.setMinWidth(dp(112)); endButton.setMinHeight(dp(48)); endButton.setContentDescription("结束本次通勤");
        activeBar.addView(endButton, new LinearLayout.LayoutParams(-2, -2));
        mapPanel.renderMap(parent);
        LinearLayout plan = card(parent);
        originButton = compactButton(plan, "", () -> {
            if (!editable()) return;
            if ("gps".equals(originMode) && !locationAllowed()) { withPermission(false, () -> locateOrigin(true)); return; }
            choosePlace(true, p -> { journeyVersion++; origin = p; originMode = p == null ? "gps" : "fixed"; update(); if (p == null) locateOrigin(true); });
        });
        destinationButton = compactButton(plan, "", () -> { if (editable()) choosePlace(false, p -> { journeyVersion++; destination = p; update(); }); });
        transportButton = compactButton(plan, "", () -> {
            if (!editable()) return;
            String[] keys = {"driving", "walking", "transit", "bicycling"};
            long editorGeneration = generation;
            RoundedChoiceDialog dialog = new RoundedChoiceDialog(activity, "出行方式", "选择此次路线的计算方式。", new String[]{"驾车", "步行", "公共交通", "骑行"}, Arrays.asList(keys).indexOf(transport), "确定", at -> { if (editorGeneration == generation && editable()) { journeyVersion++; transport = keys[at]; update(); } }); track(dialog); dialog.show();
        });
        navigationButton = button(plan, "打开高德导航", true, this::openNavigation);
        navigationHint = text(plan, "", 12, EditorUi.MUTED);
        startButton = button(plan, "开始通勤", true, this::start);
        navigationPrimary = null;
        status = text(plan, "", 12, EditorUi.MUTED);
        text(plan, "开始后每 3 分钟更新路线，锁屏也会继续。随时可以在本页结束通勤；部分手机需要长按通知才会出现操作按钮。", 12, EditorUi.MUTED);
        LinearLayout stations = card(parent);
        text(stations, "站点提醒", 18, EditorUi.INK);
        stationStatus = text(stations, CommuteService.reminderStatus(client.get()), 13, EditorUi.MUTED);
        text(stations, "根据定位识别上车；定位不足时点“我已上车”校准。每 3 分钟语音播报进度，换乘或下车前一站发送 App 通知并播报。地下定位不足时使用计划时间估算，以车内报站为准。", 12, EditorUi.MUTED);
        button(stations, "我已上车", false, () -> { stationStatus.setText(CommuteService.boarded(client.get())); });
        button(stations, "提醒设置", false, this::reminderSettings);
        button(stations, "测试站点提醒", false, this::testStationReminder);
        summary = card(parent);
        mapPanel.renderWeather(parent);
        lastRender = ""; update();
    }
    private TextView compactButton(LinearLayout parent, String value, Runnable action) {
        TextView button = EditorUi.button(activity, value, false, action);
        button.setTextSize(14); button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setMinHeight(dp(46));
        button.setSingleLine(true); button.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.setMargins(0, dp(3), 0, dp(3));
        parent.addView(button, p); return button;
    }
    private String gpsOrigin() {
        if (currentPlace != null) {
            String address = currentPlace.optString("address"), city = currentPlace.optString("city");
            // The city is implied by the route; keep the row to district and street.
            if (!city.isEmpty() && address.startsWith(city) && address.length() > city.length()) address = address.substring(city.length());
            return "当前位置 · " + address;
        }
        if (locatingOrigin) return "正在定位当前位置…";
        if (!locationAllowed()) return "当前位置（点此开启定位）";
        return originProblem.isEmpty() ? "当前位置" : "当前位置（" + originProblem + "）";
    }
    private boolean locationAllowed() {
        return activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            || activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    /** Resolves the phone's position to an address: every 3 minutes while visible, or 1 minute after a failure. */
    private void locateOrigin(boolean force) {
        if (closed || locatingOrigin || !"gps".equals(originMode) || !locationAllowed()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - originAttemptAt < (currentPlace == null ? 60_000 : 180_000)) return;
        ApiClient api = client.get(); if (api == null || api.vault.token().isEmpty()) return;
        String token = api.vault.token(); long current = generation;
        originAttemptAt = now; locatingOrigin = true; update();
        queries.execute(() -> {
            JSONObject place = null; String problem = "";
            try {
                JSONObject fix = MeetingStartLocation.capture(activity, api, visible);
                LocationSnapshot snap = fix == null ? null : LocationSnapshot.parse(fix.toString());
                if (snap == null || !snap.usable(System.currentTimeMillis(), 120_000)) problem = "暂未获得定位";
                else {
                    JSONObject data = api.json("POST", "/v1/maps/reverse", fix);
                    if (data.optBoolean("available")) place = data; else problem = data.optString("reason", "无法识别当前位置");
                }
            } catch (Exception e) { problem = "定位失败"; }
            JSONObject found = place; String reason = problem;
            ui.post(() -> {
                locatingOrigin = false;
                if (!current(api, token, current)) return;
                if (found != null) currentPlace = found;
                originProblem = reason; update();
            });
        });
    }
    private boolean editable() { if (journey.optBoolean("active")) { notice = "请先结束本次通勤，再修改起终点"; update(); return false; } return !mutating; }
    private void update() {
        if (stationStatus != null) stationStatus.setText(CommuteService.reminderStatus(client.get()));
        if (originButton == null) return;
        originButton.setText("起点  ·  " + ("gps".equals(originMode) ? gpsOrigin() : origin == null ? "选择自定义起点" : origin.optString("name")));
        destinationButton.setText("终点  ·  " + (destination == null ? "选择目的地" : destination.optString("name")));
        transportButton.setText("出行方式  ·  " + methodName(transport));
        boolean running = CommuteService.running(client.get()), active = journey.optBoolean("active");
        startButton.setText(running ? "立即更新路线" : active ? "继续通勤" : "开始通勤"); startButton.setEnabled(!mutating);
        activeBar.setVisibility(active || running || !pendingJourneyId.isEmpty() ? View.VISIBLE : View.GONE);
        endButton.setEnabled(!stopping); endButton.setText(stopping ? "正在结束…" : "结束通勤");
        JSONObject activeRoute = journey.optJSONObject("route");
        activeTitle.setText(!running && active ? "通勤已暂停\n可继续或结束"
            : activeRoute != null && activeRoute.optBoolean("available")
            ? "通勤进行中\n预计还需 " + Math.max(1, Math.round(activeRoute.optDouble("eta_seconds") / 60)) + " 分钟"
            : "通勤进行中\n正在获取路线…");
        updateNavigation(active, running);
        status.setText(!notice.isEmpty() ? notice : running ? CommuteService.status(client.get()) : active ? "后台定位已暂停，请点继续通勤" : "选择起终点后开始通勤");
        mapPanel.update(originMode, origin, destination, transport, journey);
        JSONObject route = active ? journey.optJSONObject("route") : mapPanel.route();
        String render = journey.toString() + running + (route == null ? "" : route.toString()) + active;
        if (summary == null || render.equals(lastRender)) return;
        lastRender = render; int y = pageScroll == null ? 0 : pageScroll.getScrollY();
        summary.removeAllViews(); text(summary, active ? "本次路线" : "路线预览", 19, EditorUi.INK).setTypeface(null, Typeface.BOLD);
        if (route != null && route.optBoolean("available")) {
            text(summary, (!active || "fixed".equals(journey.optString("origin_mode")) ? "路线耗时约 " : "预计还需 ") + Math.max(1, Math.round(route.optDouble("eta_seconds") / 60)) + " 分钟", 24, EditorUi.ACCENT);
            text(summary, String.format(Locale.CHINA, "%.1f 公里 · %s", route.optDouble("distance_meters") / 1000, route.optString("destination")), 15, EditorUi.INK);
            text(summary, "更新于 " + TimeFormat.format(active ? journey.optString("updated_at") : route.optString("fetched_at")) + (journey.optString("error").isEmpty() ? "" : "\n更新失败，以上为上次估算"), 12, EditorUi.MUTED);
            button(summary, "查看路线详情", false, () -> routeDetails(route));
        } else text(summary, active ? "等待定位与路线结果…" : "选择起终点后自动显示路线，点击开始通勤持续更新。", 14, EditorUi.MUTED);
        if (pageScroll != null && summary.isAttachedToWindow() && summary.isShown()) {
            ScrollView viewport = pageScroll; LinearLayout routeSummary = summary;
            viewport.post(() -> {
                if (viewport == pageScroll && routeSummary == summary && routeSummary.isAttachedToWindow() && routeSummary.isShown())
                    viewport.scrollTo(0, y);
            });
        }
    }
    private void updateNavigation(boolean active, boolean running) {
        navigationButton.setVisibility(active ? View.VISIBLE : View.GONE);
        navigationHint.setVisibility(active ? View.VISIBLE : View.GONE);
        if (navigationPrimary == null || navigationPrimary != active) {
            navigationPrimary = active;
            startButton.setBackground(EditorUi.shape(activity, active ? 0xffffffff : EditorUi.ACCENT, 16, active ? EditorUi.LINE : EditorUi.ACCENT));
            startButton.setTextColor(active ? EditorUi.ACCENT : 0xffffffff);
        }
        if (!active) return;
        navigationButton.setText("高德导航 · " + methodName(journey.optString("transport")));
        boolean valid;
        try { AmapNavigation.create(journey, true); valid = true; } catch (IllegalArgumentException invalid) { valid = false; }
        boolean enabled = valid && running && !mutating && allowed.getAsBoolean();
        navigationButton.setEnabled(enabled); navigationButton.setAlpha(enabled ? 1f : .5f);
        navigationHint.setText(!valid ? "本次通勤地址无效，请结束后重新选择。" : !running ? "先继续通勤，再打开高德导航。"
            : "fixed".equals(journey.optString("origin_mode")) ? "带入所选起终点，在高德确认路线并开始导航。"
            : "transit".equals(journey.optString("transport")) ? "带入目的地，在高德选择公交或地铁乘车方案。"
            : "从当前位置出发，其他偏好沿用高德设置。");
    }
    void openNavigation() {
        if (closed || mutating || !visible.getAsBoolean() || !allowed.getAsBoolean()) return;
        ApiClient api = client.get();
        JSONObject active = CommuteService.current(api);
        if (active == null || !active.optBoolean("active") || !account.equals(CommuteService.owner(api))) { update(); return; }
        long now = SystemClock.elapsedRealtime();
        if (now - navigationOpenedAt < 1500 || navigationFallback != null) return;
        AmapNavigation.Plan plan;
        try {
            String version = "";
            try { version = activity.getPackageManager().getPackageInfo(AmapNavigation.PACKAGE, 0).versionName; }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* Launch handles an absent app. */ }
            plan = AmapNavigation.create(active, AmapNavigation.supportsFeatureNavigation(version));
        } catch (IllegalArgumentException invalid) { notice = "本次通勤地址无效，请结束后重新选择。"; update(); return; }
        navigationOpenedAt = now;
        if (launchNavigationUri(plan.appUri, true)) return;
        if (!plan.appUri.equals(plan.routeUri) && launchNavigationUri(plan.routeUri, true)) return;
        showNavigationFallback(plan, api, api.vault.token(), active.optString("id"));
    }
    private boolean launchNavigationUri(String uri, boolean nativeApp) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (nativeApp) intent.setPackage(AmapNavigation.PACKAGE);
        else intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try { activity.startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }
    private void showNavigationFallback(AmapNavigation.Plan plan, ApiClient api, String token, String journeyId) {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        text(body, "未能打开高德地图。可以先用网页版查看已选路线，或安装、更新高德后重试。", 14, EditorUi.INK);
        TextView error = text(body, "", 12, EditorUi.MUTED);
        Dialog dialog = sheet("打开导航", body, footer); navigationFallback = dialog;
        button(footer, "打开网页版路线", true, () -> {
            JSONObject active = CommuteService.current(client.get());
            if (closed || !visible.getAsBoolean() || api != client.get() || !token.equals(api.vault.token())
                    || active == null || !active.optBoolean("active") || !journeyId.equals(active.optString("id"))) { dialog.dismiss(); return; }
            if (launchNavigationUri(plan.webUri, false)) dialog.dismiss();
            else error.setText("没有可打开路线的浏览器，请安装高德地图后重试。");
        });
        button(footer, "取消", false, dialog::dismiss);
    }
    private void withPermission(boolean notifications, Runnable action) { if (permission.ensure(notifications)) action.run(); else permissionAction = action; }
    void permissionResult(boolean granted) { Runnable action = permissionAction; permissionAction = null; if (granted && action != null && visible.getAsBoolean()) action.run(); else { notice = "请允许定位权限"; update(); } }
    private void start() {
        if (CommuteService.running(client.get())) { notice = ""; CommuteService.refresh(client.get()); return; }
        if (!allowed.getAsBoolean() || mutating) return;
        if (destination == null || "fixed".equals(originMode) && origin == null) { notice = "请先选择起点和目的地"; update(); return; }
        withPermission(true, () -> {
            if (!visible.getAsBoolean()) return;
            if (journey.optBoolean("active")) { launch(journey); return; }
            if (mutating) return;
            mutating = true; long version = ++journeyVersion;
            pendingJourneyId = dialogueJourney.isEmpty() ? UUID.randomUUID().toString() : dialogueJourney;
            JSONObject body = new JSONObject(); put(body, "journey_id", pendingJourneyId); put(body, "origin_mode", originMode);
            put(body, "origin", "fixed".equals(originMode) ? copy(origin) : JSONObject.NULL); put(body, "destination", copy(destination)); put(body, "transport", transport); update();
            execute("commute", api -> {
                String pending = CommuteService.pendingStop(activity, api);
                if (!pending.isEmpty()) {
                    try { api.json("DELETE", "/v1/commute/" + pending, null); }
                    catch (ApiClient.ApiException missing) { if (missing.status != 404) throw missing; }
                    CommuteService.clearPendingStop(activity, api, pending);
                }
                return api.json("POST", "/v1/commute", body);
            }, value -> { mutating = false; pendingJourneyId = ""; journeyVersion++; journey = value; started.run(); launch(value); }, status,
                () -> { mutating = false; pendingJourneyId = ""; journeyVersion++; }, () -> version == journeyVersion);
        });
    }
    void startFromConversation(JSONObject plan) {
        if (closed || !visible.getAsBoolean() || !allowed.getAsBoolean() || mutating) return;
        resume();
        ApiClient api = client.get();
        String id = plan.optString("journey_id");
        if (!id.matches("[a-zA-Z0-9-]{16,64}") || plan.optJSONObject("destination") == null) return;
        android.content.SharedPreferences consumed = activity.getSharedPreferences("commute_dialogue_actions", Context.MODE_PRIVATE);
        String key = CommuteService.owner(api);
        if (id.equals(consumed.getString(key, ""))) return;
        if (CommuteService.running(api) || journey.optBoolean("active")) { information("通勤正在进行", "请先结束当前通勤，再开始新的行程。"); return; }
        String method = plan.optString("transport"), from = plan.optString("origin_mode");
        if (!Arrays.asList("driving", "walking", "transit", "bicycling").contains(method)
                || !Arrays.asList("gps", "fixed").contains(from) || "fixed".equals(from) && plan.optJSONObject("origin") == null) return;
        consumed.edit().putString(key, id).apply();
        journeyVersion++; originMode = from; origin = plan.optJSONObject("origin"); destination = copy(plan.optJSONObject("destination")); transport = method;
        dialogueJourney = id; update(); start();
    }
    private boolean briefingCurrent(ApiClient api, String token, String id, long version) {
        JSONObject live = CommuteService.current(api);
        return !closed && visible.getAsBoolean() && version == briefingVersion && client.get() == api && token.equals(api.vault.token())
            && live != null && id.equals(live.optString("id")) && live.optBoolean("active");
    }
    private void prepareBriefing(String id, long version, long deadline) {
        ApiClient api = client.get(); String token = api.vault.token();
        if (!briefingCurrent(api, token, id, version)) return;
        JSONObject live = CommuteService.current(api), route = live.optJSONObject("route");
        if ((route == null || !route.optBoolean("available")) && SystemClock.elapsedRealtime() < deadline) {
            ui.postDelayed(() -> prepareBriefing(id, version, deadline), 1000); return;
        }
        BooleanSupplier relevant = () -> briefingCurrent(api, token, id, version);
        query(c -> c.json("GET", "/v1/commute/" + id + "/briefing", null), result -> {
            String announcement = result.optString("text");
            if (announcement.isEmpty()) { information("通勤已开启", "语音信息暂未取得，可在通勤页打开高德导航。"); return; }
            speaker.speak(api, announcement, relevant, played -> {
                if (!relevant.getAsBoolean()) return;
                if (played) openNavigation();
                else information("通勤已开启", "语音未能播放，可能音频正忙或网络不可用。可在通勤页点击高德导航。\n\n" + announcement);
            });
        }, null, relevant);
    }
    private void reminderSettings() {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        android.widget.Switch enabled = new android.widget.Switch(activity); enabled.setText("语音和 App 站点提醒"); enabled.setChecked(CommuteService.remindersEnabled(activity, client.get())); body.addView(enabled);
        text(body, "提醒发到本机通知栏，不使用手机号和短信。通知权限被关闭时，可在系统设置中开启。", 14, EditorUi.MUTED);
        Dialog dialog = sheet("站点提醒设置", body, footer); compactSheet(dialog, body);
        button(footer, "保存", true, () -> { CommuteService.setReminders(activity, client.get(), enabled.isChecked()); dialog.dismiss(); });
        button(footer, "打开系统通知设置", false, () -> { activity.startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.getPackageName())); });
        button(footer, "取消", false, dialog::dismiss);
    }
    private void testStationReminder() {
        if (!visible.getAsBoolean() || !allowed.getAsBoolean() || speaker.busy()) return;
        String message = TransitProgress.testMessage();
        boolean notified = CommuteService.showReminder(activity, message);
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        text(body, message, 15, EditorUi.INK);
        TextView result = text(body, notified ? "测试通知已提交 · 正在准备语音" : "通知未开启，请在提醒设置中允许通知 · 正在准备语音", 13, EditorUi.MUTED);
        Dialog dialog = sheet("测试站点提醒", body, footer); compactSheet(dialog, body); button(footer, "完成", true, dialog::dismiss);
        ApiClient api = client.get(); String token = api.vault.token();
        speaker.speak(api, message, () -> !closed && visible.getAsBoolean() && client.get() == api && token.equals(api.vault.token()),
            success -> result.setText((notified ? "测试通知已提交" : "通知未开启") + (success ? " · 语音播放完成" : " · 语音未播放，请检查网络或等待其他音频结束")));
    }
    private void launch(JSONObject value) {
        if (!visible.getAsBoolean()) { notice = "请回到 App 后点击继续通勤"; update(); return; }
        ApiClient api = client.get(); String token = api.vault.token(); long current = generation, version = journeyVersion;
        try { journeyLauncher.accept(activity, value); notice = "正在开启通勤服务…"; ui.postDelayed(() -> { if (!current(api, token, current) || version != journeyVersion) return; notice = CommuteService.running(client.get()) ? "" : "请检查系统定位开关和权限，再继续通勤"; update(); runningChanged.run(); if (CommuteService.running(client.get()) && value.optString("id").equals(dialogueJourney)) { dialogueJourney = ""; prepareBriefing(value.optString("id"), ++briefingVersion, SystemClock.elapsedRealtime() + 45000); } }, 1000); }
        catch (RuntimeException error) { notice = "通勤服务未能启动，请检查定位权限后重试"; update(); }
    }
    void stop() {
        if (stopping || closed || client.get() == null) return;
        if (navigationFallback != null) navigationFallback.dismiss();
        speaker.stop(); briefingVersion++; dialogueJourney = "";
        notice = "通勤已结束";
        if (CommuteService.running(client.get())) CommuteService.stop(activity);
        String id = pendingJourneyId.isEmpty() ? journey.optString("id") : pendingJourneyId;
        long version = ++journeyVersion; pendingJourneyId = ""; stopping = mutating = !id.isEmpty();
        put(journey, "active", false); update();
        if (!id.isEmpty()) {
            CommuteService.queueStop(activity, client.get(), id);
            execute("commute", api -> {
                JSONObject value;
                try { value = api.json("DELETE", "/v1/commute/" + id, null); }
                catch (ApiClient.ApiException missing) { if (missing.status != 404) throw missing; value = new JSONObject(); }
                CommuteService.clearPendingStop(activity, api, id); return value;
            }, value -> { stopping = mutating = false; journeyVersion++; journey = value; notice = "通勤已结束"; update(); }, status,
                () -> { stopping = mutating = false; journeyVersion++; }, () -> version == journeyVersion);
        }
        runningChanged.run();
    }
    boolean inProgress() { return journey.optBoolean("active") || !pendingJourneyId.isEmpty() || CommuteService.running(client.get()); }
    /** Leaving commute mode ends the journey; returns whether one was running. */
    boolean modeChanged(String mode) {
        if ("commute".equals(mode)) return false;
        speaker.stop(); briefingVersion++; dialogueJourney = "";
        if (!inProgress()) return false;
        stop(); return true;
    }
    void addressBook() {
        resume(); LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        text(body, "家、公司、学校和自定义地点。地址定位一次后保存坐标，下次直接选用。", 13, EditorUi.MUTED);
        addressRows = EditorUi.column(activity); body.addView(addressRows);
        Dialog dialog = sheet("常用地址", body, footer);
        button(footer, "添加常用地址", true, () -> editAddress(null)); button(footer, "完成", false, dialog::dismiss); renderAddressRows();
    }
    private void renderAddressRows() {
        if (addressRows == null) return; addressRows.removeAllViews();
        if (addresses.length() == 0) text(addressRows, loading ? "正在读取常用地址…" : "还没有保存地址，点击下方添加。", 14, EditorUi.MUTED);
        for (int i = 0; i < addresses.length(); i++) {
            JSONObject address = addresses.optJSONObject(i); if (address == null) continue;
            LinearLayout row = card(addressRows); text(row, tagName(address.optString("tag")) + " · " + address.optString("name"), 16, EditorUi.INK);
            text(row, address.optString("address"), 13, EditorUi.MUTED); button(row, "编辑地址", false, () -> editAddress(address));
        }
    }
    private void editAddress(JSONObject existing) {
        if (!allowed.getAsBoolean()) return;
        ApiClient editorApi = client.get(); String editorToken = editorApi.vault.token(); long editorGeneration = generation;
        BooleanSupplier editorCurrent = () -> current(editorApi, editorToken, editorGeneration);
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        Dialog[] editorRef = new Dialog[1]; long[] searchVersion = {0};
        String addressId = existing == null ? UUID.randomUUID().toString() : existing.optString("id");
        String[] tag = {existing == null ? "home" : existing.optString("tag", "custom")};
        if (existing == null) { for (int i = 0; i < addresses.length(); i++) if ("home".equals(addresses.optJSONObject(i).optString("tag"))) tag[0] = "custom"; }
        EditText name = EditorUi.input(activity, body, "名称", existing == null ? tagName(tag[0]) : existing.optString("name"), false);
        TextView tagButton = button(body, "标签 · " + tagName(tag[0]), false, () -> {});
        tagButton.setOnClickListener(v -> {
            String[] tags = {"home", "work", "school", "custom"};
            RoundedChoiceDialog dialog = new RoundedChoiceDialog(activity, "地址标签", "家、公司和学校各保存一个；自定义可保存多个。", new String[]{"家", "公司", "学校", "自定义"}, Arrays.asList(tags).indexOf(tag[0]), "确定", at -> { tag[0] = tags[at]; tagButton.setText("标签 · " + tagName(tag[0])); if (!"custom".equals(tag[0])) name.setText(tagName(tag[0])); }); track(dialog); dialog.show();
        });
        EditText city = EditorUi.input(activity, body, "城市", existing == null ? "" : existing.optString("city"), false);
        EditText address = EditorUi.input(activity, body, "详细地址", existing == null ? "" : existing.optString("address"), true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)}); city.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)}); address.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
        JSONObject[] resolved = {existing == null ? null : copy(existing)};
        TextView progress = text(body, existing == null ? "填写后点击定位地址，再确认保存。" : "已保存坐标；不修改地址即可直接保存。", 12, EditorUi.MUTED);
        TextWatcher changed = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { searchVersion[0]++; resolved[0] = null; progress.setText("地址已修改，请重新定位后保存"); } public void afterTextChanged(Editable e) {}
        }; city.addTextChangedListener(changed); address.addTextChangedListener(changed);
        Consumer<JSONObject> selected = point -> { city.setText(point.optString("city")); address.setText(point.optString("address")); resolved[0] = copy(point); progress.setText("已定位：" + point.optString("address") + "\n确认无误后保存。"); };
        button(body, "定位地址", false, () -> {
            String cityValue = city.getText().toString().trim(), value = address.getText().toString().trim();
            if (value.isEmpty() || cityValue.isEmpty()) { progress.setText("请填写城市和详细地址"); return; }
            long request = ++searchVersion[0];
            BooleanSupplier relevant = () -> editorRef[0] != null && editorRef[0].isShowing() && request == searchVersion[0];
            query(api -> api.json("GET", "/v1/maps/geocode?address=" + Uri.encode(value) + "&city=" + Uri.encode(cityValue), null), data -> {
                JSONArray values = data.optJSONArray("places"); if (values == null || values.length() == 0) { progress.setText(data.optString("reason", "未找到地址，请补充具体位置")); return; }
                if (values.length() == 1) selected.accept(values.getJSONObject(0)); else pointResults("确认地址", values, p -> { if (relevant.getAsBoolean()) selected.accept(p); });
            }, progress, relevant);
        });
        button(body, "使用当前位置", false, () -> withPermission(false, () -> {
            if (editorRef[0] == null || !editorRef[0].isShowing()) return;
            long request = ++searchVersion[0];
            query(api -> {
                JSONObject fix = MeetingStartLocation.capture(activity, api, visible);
                LocationSnapshot snap = fix == null ? null : LocationSnapshot.parse(fix.toString());
                if (snap == null || !snap.usable(System.currentTimeMillis(), 120_000)) throw new ApiClient.ApiException(422, "暂未获得近期定位，请检查系统定位开关后重试");
                return api.json("POST", "/v1/maps/reverse", fix);
            }, data -> { if (data.optBoolean("available")) selected.accept(data); else progress.setText(data.optString("reason", "未能识别当前位置")); }, progress,
                () -> editorRef[0].isShowing() && request == searchVersion[0]);
        }));
        Dialog editor = sheet(existing == null ? "添加常用地址" : "编辑常用地址", body, footer);
        editorRef[0] = editor;
        TextView saveAddress = button(footer, "保存地址", true, () -> {});
        saveAddress.setOnClickListener(v -> {
            if (!saveAddress.isEnabled() || !editorCurrent.getAsBoolean()) return;
            if (resolved[0] == null) { progress.setText("请先定位地址并确认位置"); return; }
            String title = name.getText().toString().trim(); if (title.isEmpty()) { name.setError("请输入名称"); return; }
            JSONObject payload = copy(resolved[0]); put(payload, "name", title); put(payload, "tag", tag[0]);
            saveAddress.setEnabled(false); addressesVersion++; addressWrites++;
            execute("address:" + addressId, api -> api.json("PUT", "/v1/saved-addresses/" + addressId, payload), saved -> {
                addressesVersion++; JSONArray next = new JSONArray(); boolean replaced = false;
                for (int i = 0; i < addresses.length(); i++) { JSONObject old = addresses.getJSONObject(i); boolean match = addressId.equals(old.optString("id")); next.put(match ? saved : old); replaced |= match; }
                if (!replaced) next.put(saved); addresses = next; editor.dismiss(); renderAddressRows(); addressWrites--; refreshAddressesIfIdle();
            }, progress, () -> { addressesVersion++; addressWrites--; saveAddress.setEnabled(true); refreshAddressesIfIdle(); });
        });
        if (existing != null) button(footer, "删除地址", false, () -> {
            LinearLayout confirmBody = EditorUi.column(activity), confirmFooter = EditorUi.column(activity); text(confirmBody, "删除“" + existing.optString("name") + "”？已开始的通勤会保留本次地址。", 14, EditorUi.INK);
            Dialog confirm = sheet("删除常用地址", confirmBody, confirmFooter);
            TextView deleteAddress = button(confirmFooter, "删除", true, () -> {});
            deleteAddress.setOnClickListener(v -> {
                if (!deleteAddress.isEnabled() || !editorCurrent.getAsBoolean()) return;
                deleteAddress.setEnabled(false); addressesVersion++; addressWrites++;
                execute("address:" + addressId, api -> api.json("DELETE", "/v1/saved-addresses/" + addressId, null), saved -> {
                addressesVersion++;
                JSONArray next = new JSONArray(); for (int i = 0; i < addresses.length(); i++) if (!existing.optString("id").equals(addresses.getJSONObject(i).optString("id"))) next.put(addresses.getJSONObject(i));
                addresses = next; confirm.dismiss(); editor.dismiss(); renderAddressRows(); addressWrites--; refreshAddressesIfIdle();
                }, progress, () -> { addressesVersion++; addressWrites--; deleteAddress.setEnabled(true); refreshAddressesIfIdle(); });
            }); button(confirmFooter, "取消", false, confirm::dismiss);
        });
        button(footer, "取消", false, editor::dismiss);
    }
    private void choosePlace(boolean start, Consumer<JSONObject> chosen) {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        Dialog dialog = sheet(start ? "选择起点" : "选择目的地", body, footer);
        ApiClient editorApi = client.get(); String editorToken = editorApi.vault.token(); long editorGeneration = generation;
        BooleanSupplier editorCurrent = () -> dialog.isShowing() && current(editorApi, editorToken, editorGeneration);
        Consumer<JSONObject> select = point -> { if (editorCurrent.getAsBoolean() && editable()) { chosen.accept(point); dialog.dismiss(); } };
        if (start) option(body, "当前位置", "随手机位置自动更新", "gps".equals(originMode), () -> select.accept(null));
        JSONObject current = start ? origin : destination;
        for (int i = 0; i < addresses.length(); i++) {
            JSONObject p = addresses.optJSONObject(i); if (p == null) continue;
            option(body, tagName(p.optString("tag")) + " · " + p.optString("name"), p.optString("address"), samePoint(current, p), () -> select.accept(copy(p)));
        }
        text(body, "搜索其他地址", 16, EditorUi.INK);
        EditText city = EditorUi.input(activity, body, "城市", current == null ? "" : current.optString("city"), false);
        EditText query = EditorUi.input(activity, body, "地址或地点名称", "", false);
        long[] searchVersion = {0};
        TextWatcher changed = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchVersion[0]++; }
            public void afterTextChanged(Editable e) {}
        };
        city.addTextChangedListener(changed); query.addTextChangedListener(changed);
        TextView progress = text(body, addresses.length() > 0 ? "点上方常用地址可直接选择" : "", 12, EditorUi.MUTED);
        button(body, "查找地址", false, () -> {
            String value = query.getText().toString().trim(), cityValue = city.getText().toString().trim();
            city.setBackground(EditorUi.shape(activity, 0xffffffff, 14, cityValue.isEmpty() ? EditorUi.RED : EditorUi.LINE));
            query.setBackground(EditorUi.shape(activity, 0xffffffff, 14, value.isEmpty() ? EditorUi.RED : EditorUi.LINE));
            if (value.isEmpty() || cityValue.isEmpty()) {
                progress.setTextColor(EditorUi.RED); progress.setText(cityValue.isEmpty() && value.isEmpty() ? "请填写城市和地址" : cityValue.isEmpty() ? "请填写城市" : "请填写地址或地点名称"); return;
            }
            progress.setTextColor(EditorUi.MUTED);
            long request = ++searchVersion[0];
            BooleanSupplier relevant = () -> editorCurrent.getAsBoolean() && request == searchVersion[0];
            query(api -> api.json("GET", "/v1/maps/geocode?address=" + Uri.encode(value) + "&city=" + Uri.encode(cityValue), null), data -> {
                JSONArray results = data.optJSONArray("places");
                if (results == null || results.length() == 0) { progress.setTextColor(EditorUi.RED); progress.setText(data.optString("reason", "未找到地址，换个说法试试")); return; }
                pointResults("选择地址", results, point -> { if (relevant.getAsBoolean()) { put(point, "name", value); select.accept(copy(point)); } });
            }, progress, relevant);
        });
        button(footer, "取消", false, dialog::dismiss);
    }
    private static boolean samePoint(JSONObject a, JSONObject b) {
        if (a == null) return false;
        if (a.has("id") && b.has("id")) return a.optString("id").equals(b.optString("id"));
        return a.optString("name").equals(b.optString("name")) && a.optDouble("longitude") == b.optDouble("longitude") && a.optDouble("latitude") == b.optDouble("latitude");
    }
    private void option(LinearLayout parent, String title, String subtitle, boolean selected, Runnable action) {
        LinearLayout row = card(parent); row.setBackground(EditorUi.shape(activity, selected ? 0xffeaf1ff : 0xffffffff, 18, selected ? EditorUi.ACCENT : EditorUi.LINE));
        text(row, (selected ? "✓  " : "") + title, 15, selected ? EditorUi.ACCENT : EditorUi.INK); text(row, subtitle, 12, EditorUi.MUTED);
        row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> { row.animate().alpha(.65f).setDuration(80).withEndAction(action).start(); });
    }
    private void pointResults(String title, JSONArray results, Consumer<JSONObject> chosen) {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity); Dialog dialog = sheet(title, body, footer);
        for (int i = 0; i < results.length(); i++) { JSONObject point = results.optJSONObject(i); if (point == null) continue;
            option(body, point.optString("name", point.optString("address")), point.optString("city") + " · " + point.optString("address"), false, () -> { chosen.accept(copy(point)); dialog.dismiss(); }); }
        button(footer, "取消", false, dialog::dismiss);
    }
    private void routeDetails(JSONObject route) {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity); text(body, route.optString("note"), 12, EditorUi.MUTED);
        JSONArray steps = route.optJSONArray("steps"); if (steps != null) for (int i = 0; i < steps.length(); i++) { JSONObject step = steps.optJSONObject(i); if (step != null) text(card(body), (i + 1) + "  " + step.optString("instruction"), 15, EditorUi.INK); }
        Dialog dialog = sheet("路线详情", body, footer); button(footer, "完成", true, dialog::dismiss);
    }
    private void nearby(boolean metro) {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity);
        EditText keyword = metro ? null : EditorUi.input(activity, body, "查找什么", "咖啡", false);
        TextView progress = text(body, metro ? "查询当前位置附近 2 公里的地铁站。" : "查询当前位置附近 3 公里的地点。", 13, EditorUi.MUTED);
        Dialog dialog = sheet(metro ? "附近地铁站" : "搜索周边", body, footer);
        button(footer, "搜索", true, () -> {
            String search = metro ? "地铁" : keyword.getText().toString().trim();
            execute(api -> {
            LocationSnapshot point = MeetingLocationCache.read(activity, api, api.vault.token());
            if (point == null || !point.usable(System.currentTimeMillis(), 300_000)) throw new ApiClient.ApiException(422, "暂无近期定位，请开启系统定位或开始通勤后再试");
            JSONObject payload = point.json(true).put("metro_only", metro).put("radius", metro ? 2000 : 3000).put("keyword", search);
            return api.json("POST", "/v1/maps/nearby", payload);
        }, value -> { JSONArray results = value.optJSONArray("pois"); if (results == null || results.length() == 0) progress.setText(value.optString("reason", "附近暂无结果")); else pointResults("附近地点 · 选择可设为目的地", results, p -> { if (editable()) { destination = p; update(); dialog.dismiss(); } }); }, progress); });
        button(footer, "取消", false, dialog::dismiss);
    }
    private void weather(boolean forecast) {
        if (destination == null) { information("选择目的地", "先选择目的地，再查询当地天气。"); return; }
        JSONObject point = copy(destination);
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity); TextView progress = text(body, "正在查询天气…", 14, EditorUi.MUTED);
        Dialog dialog = sheet(forecast ? "目的地天气预报" : "目的地天气", body, footer); button(footer, "完成", true, dialog::dismiss);
        execute(api -> api.json("POST", "/v1/maps/weather-at?forecast=" + forecast, point), value -> {
            if (!value.optBoolean("available")) { progress.setText(value.optString("reason", "天气暂不可用")); return; }
            progress.setText(value.optString("city") + " · " + value.optString("report_time"));
            JSONArray days = value.optJSONArray("forecast");
            if (forecast && days != null) for (int i = 0; i < days.length(); i++) { JSONObject day = days.getJSONObject(i); text(card(body), day.optString("date") + "\n" + day.optString("dayweather") + " / " + day.optString("nightweather") + "\n" + day.optString("nighttemp") + "–" + day.optString("daytemp") + "°C", 15, EditorUi.INK); }
            else text(card(body), value.optString("weather") + "  " + value.optString("temperature") + "°C\n" + value.optString("wind_direction") + "风 " + value.optString("wind_power") + "级\n湿度 " + value.optString("humidity") + "%", 16, EditorUi.INK);
        }, progress);
    }
    private void arrival() {
        LinearLayout body = EditorUi.column(activity), footer = EditorUi.column(activity); JSONObject settings = appState.get().optJSONObject("settings"); if (settings == null) settings = new JSONObject();
        EditText contact = EditorUi.input(activity, body, "联系人称呼", settings.optString("contact", "同事"), false);
        EditText minutes = EditorUi.input(activity, body, "预计到达前几分钟生成草稿", String.valueOf(settings.optInt("arrival_notice_minutes", 5)), false); minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        TextView progress = text(body, "到达时间由路线估算；草稿需要你复制发送，不会自动联系他人。", 12, EditorUi.MUTED);
        LinearLayout rows = EditorUi.column(activity); body.addView(rows);
        renderDrafts(rows, appState.get().optJSONArray("drafts"), progress);
        execute(api -> api.json("GET", "/v1/drafts", null), value -> { JSONArray drafts = value.optJSONArray("drafts"); put(appState.get(), "drafts", drafts); renderDrafts(rows, drafts, progress); }, null);
        Dialog dialog = sheet("到达提醒与草稿", body, footer);
        TextView save = button(footer, "保存偏好", true, () -> {});
        save.setOnClickListener(view -> {
            if (!save.isEnabled()) return;
            int m; try { m = Integer.parseInt(minutes.getText().toString()); if (m < 1 || m > 60) throw new NumberFormatException(); } catch (Exception e) { minutes.setError("请输入1–60"); return; }
            JSONObject payload = new JSONObject(); put(payload, "contact", contact.getText().toString().trim()); put(payload, "arrival_notice_minutes", m);
            save.setEnabled(false);
            execute("settings", api -> api.patchSettings(payload), value -> { put(appState.get(), "settings", value); dialog.dismiss(); }, progress, () -> save.setEnabled(true));
        }); button(footer, "取消", false, dialog::dismiss);
    }
    private void renderDrafts(LinearLayout rows, JSONArray drafts, TextView progress) {
        rows.removeAllViews();
        if (drafts != null) for (int i = 0; i < drafts.length(); i++) { JSONObject draft = drafts.optJSONObject(i); if (draft == null || !"pending".equals(draft.optString("status"))) continue;
            LinearLayout row = card(rows); text(row, "给 " + draft.optString("contact"), 15, EditorUi.INK); text(row, draft.optString("text"), 14, EditorUi.INK);
            boolean[] pending = {false};
            button(row, "复制草稿", false, () -> { if (pending[0]) return; pending[0] = true; ((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("到达提醒", draft.optString("text"))); execute("draft:" + draft.optString("id"), api -> api.json("POST", "/v1/drafts/" + draft.optString("id") + "/copied", new JSONObject()), v -> { row.setVisibility(View.GONE); put(draft, "status", "copied"); }, progress, () -> pending[0] = false); });
            button(row, "忽略", false, () -> { if (pending[0]) return; pending[0] = true; execute("draft:" + draft.optString("id"), api -> api.json("POST", "/v1/drafts/" + draft.optString("id") + "/dismissed", new JSONObject()), v -> { row.setVisibility(View.GONE); put(draft, "status", "dismissed"); }, progress, () -> pending[0] = false); });
        }
    }
    private void resetViews() {
        speaker.stop(); briefingVersion++; dialogueJourney = ""; mapPanel.reset(); navigationOpenedAt = -2000;
        generation++; addressesVersion++; journeyVersion++; addressWrites = 0; addressesNeedReload = addressRefreshInFlight = false; pendingJourneyId = ""; loaded = loading = mutating = stopping = false; addresses = new JSONArray(); journey = new JSONObject(); origin = destination = null; originMode = "gps";
        currentPlace = null; originAttemptAt = -600_000; locatingOrigin = false; originProblem = ""; transport = "driving"; notice = ""; permissionAction = null; lastRender = "";
        for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); addressRows = null;
    }
    void reset() { CommuteService.stop(activity); resetViews(); account = ""; }
    @Override public void close() { closed = true; pause(); speaker.close(); mapPanel.close(); for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); network.shutdownNow(); queries.shutdownNow(); }
}
