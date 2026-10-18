package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.json.*;

/** Real device UI/location-service review with a local mock server and isolated preferences. */
public final class CommuteInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ApiClient original, fixtureApi;
    private JSONObject originalState, state, journey = new JSONObject();
    private JSONArray addresses = new JSONArray();
    private CommutePanel panel;
    private MockWebServer server;
    private Context fixture;
    private File output;
    private CommuteService service;
    private ServiceConnection connection;
    private final AtomicInteger geocodes = new AtomicInteger(), fixedRoutes = new AtomicInteger(), gpsRoutes = new AtomicInteger(), records = new AtomicInteger(), stops = new AtomicInteger();
    private final AtomicReference<String> gpsTime = new AtomicReference<>("");
    private final AtomicReference<String> failure = new AtomicReference<>();
    private static JSONObject point(String name) throws Exception { return new JSONObject().put("name", name).put("address", "北京市测试路1号").put("city", "北京").put("longitude", 116.4).put("latitude", 39.9).put("adcode", "110101"); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitUntil(() -> !(Boolean) field(activity, "busy"), 15000, "App load did not finish");
            check(!getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(), "Unlock phone for commute review");
            check(field(activity, "session") == null, "Existing audio session is active");
            original = (ApiClient) field(activity, "api"); originalState = (JSONObject) field(activity, "state");
            check(!CommuteService.running(original), "Existing commute is active");
            check(getTargetContext().getSystemService(NotificationManager.class).areNotificationsEnabled(), "Enable notifications before review");
            output = new File(getTargetContext().getExternalFilesDir(null), "commute-review"); output.mkdirs();
            fixture = new ContextWrapper(getTargetContext()) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("commute_review_" + name, mode); }
                @Override public Context getApplicationContext() { return this; }
            };
            fixtureApi = new ApiClient(fixture, ServerEndpoint.USB_LOCAL); fixtureApi.vault.save("offline-commute-review", "通勤测试");
            state = new JSONObject().put("settings", new JSONObject().put("mode", "life").put("auto_mode", false).put("transport", "driving").put("contact", "同事").put("arrival_notice_minutes", 5))
                .put("capabilities", new JSONObject()).put("agent", new JSONObject()).put("tasks", new JSONArray()).put("memories", new JSONArray()).put("drafts", new JSONArray()).put("transcripts", new JSONArray()).put("work_meetings", new JSONArray());
            server = new MockWebServer(); server.setDispatcher(new Dispatcher() {
                @Override public synchronized MockResponse dispatch(RecordedRequest request) {
                    try { return respond(request); } catch (Throwable e) { failure.set(e.toString()); return new MockResponse().setResponseCode(500); }
                }
            }); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
            set(fixtureApi, "serverUrl", "http://127.0.0.1:" + server.getPort());
            panel = (CommutePanel) field(activity, "commutePanel");
            main(() -> { panel.reset();
                ((ForegroundLocationMonitor)field(activity, "locationMonitor")).close();
                set(activity, "locationMonitor", new ForegroundLocationMonitor(fixture, new ForegroundLocationMonitor.Listener() { public void status(String value) {} public void updated() {} }));
                set(activity, "api", fixtureApi); set(activity, "state", state); set(activity, "tab", 4); invoke(activity, "showApp"); quietMaintenance(); panel.resume(); });
            waitUntil(() -> (Boolean) field(panel, "loaded"), 10000, "Address list did not load");
            main(() -> find(activity.getWindow().getDecorView(), "账号与隐私").performClick());
            ScrollView scroll = (ScrollView) field(activity, "scroll"); main(() -> { scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN); }); SystemClock.sleep(400);
            int before = scroll.getScrollY();
            main(() -> find(activity.getWindow().getDecorView(), "常用地址").performClick());
            addAddress("家", "home", "01-home-editor");
            check(geocodes.get() == 1 && addresses.length() == 1, "Home save did not resolve exactly once");
            main(() -> find(top().getWindow().getDecorView(), "编辑地址").performClick());
            Dialog edit = top(); main(() -> edits(edit).get(0).setText("我的家"));
            main(() -> find(edit.getWindow().getDecorView(), "保存地址").performClick()); waitUntil(() -> !edit.isShowing(), 5000, "Save without coordinate edit failed");
            check(geocodes.get() == 1, "Unchanged address re-geocoded");
            addAddress("公司", "work", "02-work-editor");
            check(geocodes.get() == 2 && addresses.length() == 2, "Work save failed");
            capture(top().getWindow().getDecorView(), "03-address-book");
            main(() -> find(top().getWindow().getDecorView(), "完成").performClick()); SystemClock.sleep(200);
            check(scroll.getScrollY() == before, "Address dialog moved profile scroll: " + before + " -> " + scroll.getScrollY() + ", same=" + (scroll == field(activity, "scroll")));
            main(() -> { invoke(activity, "switchTab", 3); quietMaintenance(); });
            waitUntil(() -> (Boolean) field(panel, "loaded"), 5000, "Commute did not load");
            main(() -> ((TextView)field(panel, "destinationButton")).performClick());
            main(() -> find(top().getWindow().getDecorView(), "公司 · 公司").getParent().requestDisallowInterceptTouchEvent(true));
            clickOption("公司 · 公司");
            main(() -> ((TextView)field(panel, "originButton")).performClick()); clickOption("家 · 我的家");
            main(() -> ((TextView)field(panel, "transportButton")).performClick());
            Dialog methods = top(); check(find(methods.getWindow().getDecorView(), "骑行") != null, "Transport choices missing");
            capture(methods.getWindow().getDecorView(), "04-transport-options");
            main(() -> find(methods.getWindow().getDecorView(), "取消").performClick());
            bindFixtureService();
            main(() -> ((TextView)field(panel, "startButton")).performClick());
            waitUntil(() -> fixedRoutes.get() >= 1, 12000, "Fixed-origin route not fetched");
            check(geocodes.get() == 2, "Starting saved route performed address lookup");
            SystemClock.sleep(1300); capture(activity.getWindow().getDecorView(), "05-fixed-route");
            main(panel::stop); waitUntil(() -> !CommuteService.running(fixtureApi) && stops.get() > 0, 5000, "Fixed commute did not stop");
            unbind(); SystemClock.sleep(500);
            main(() -> ((TextView)field(panel, "originButton")).performClick()); clickOption("当前位置");
            bindFixtureService();
            main(() -> ((TextView)field(panel, "startButton")).performClick());
            waitUntil(() -> gpsRoutes.get() >= 1 && records.get() >= 1, 40000, "Phone did not provide a recent location");
            String first = gpsTime.get(); int count = gpsRoutes.get();
            SystemClock.sleep(1200); capture(activity.getWindow().getDecorView(), "06-current-location-route");
            // One full production interval, without changing the device's location or the service's clock.
            getUiAutomation().executeShellCommand("input keyevent 223").close();
            waitUntil(() -> !getTargetContext().getSystemService(PowerManager.class).isInteractive(), 5000, "Phone did not turn screen off");
            Bundle progress = new Bundle(); progress.putString("stream", "UI PASS; waiting for the real 180-second lock-screen location interval"); sendStatus(1, progress);
            waitUntil(() -> gpsRoutes.get() > count && !gpsTime.get().equals(first), 215000, "No new GPS route during lock-screen interval");
            check(CommuteService.running(fixtureApi), "Service stopped on screen off");
            check(field(activity, "session") == null, "Commute unexpectedly opened audio");
            android.service.notification.StatusBarNotification note = null;
            for (android.service.notification.StatusBarNotification item : getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()) if (item.getId() == 43) note = item;
            check(note != null, "Persistent commute notification missing");
            note.getNotification().actions[0].actionIntent.send();
            waitUntil(() -> !CommuteService.running(fixtureApi) && !journey.optBoolean("active"), 10000, "Notification stop did not finish commute");
            check(!(Boolean)field(field(service, "locations"), "active"), "Location listener survived stop");
            int last = gpsRoutes.get(); SystemClock.sleep(2500); check(last == gpsRoutes.get(), "Route refreshed after stop");
            check(failure.get() == null, "Mock API failure: " + failure.get());
            result.putString("stream", "PASS: address resolve once/save/edit/cache reuse; rounded choices and profile scroll; fixed/GPS route starts; real phone location refreshed with screen off after 180 seconds; stop notification removed listener; no microphone session. fixed_routes=" + fixedRoutes.get() + " gps_routes=" + gpsRoutes.get() + " location_records=" + records.get());
            code = Activity.RESULT_OK;
        } catch (Throwable e) { result.putString("failure", e.toString()); }
        finally {
            try { if (activity != null) main(() -> { CommuteService.stop(activity); if (panel != null) panel.close(); ((ForegroundLocationMonitor)field(activity, "locationMonitor")).stop(); }); } catch (Exception ignored) {}
            try { unbind(); } catch (Exception ignored) {}
            try { getUiAutomation().executeShellCommand("input keyevent 224").close(); } catch (Exception ignored) {}
            try { if (fixture != null && fixtureApi != null) { MeetingLocationCache.clear(fixture); CommuteService.clearPendingStop(getTargetContext(), fixtureApi); fixtureApi.vault.clear(); } } catch (Exception ignored) {}
            try { if (server != null) server.shutdown(); } catch (Exception ignored) {}
            if (activity != null) try { main(() -> { if (original != null) { set(activity, "api", original); set(activity, "state", originalState); } activity.finish(); }); } catch (Exception ignored) {}
        }
        finish(code, result);
    }
    private MockResponse respond(RecordedRequest request) throws Exception {
        String path = request.getPath(), method = request.getMethod(); JSONObject body = request.getBodySize() == 0 ? new JSONObject() : new JSONObject(request.getBody().readUtf8());
        if (path.equals("/v1/saved-addresses")) return json(new JSONObject().put("addresses", addresses));
        if (path.startsWith("/v1/saved-addresses/")) {
            String id = path.substring(path.lastIndexOf('/') + 1); JSONArray next = new JSONArray();
            for (int i = 0; i < addresses.length(); i++) if (!addresses.getJSONObject(i).optString("id").equals(id)) next.put(addresses.getJSONObject(i));
            if (method.equals("PUT")) next.put(body.put("id", id)); addresses = next; return json(body);
        }
        if (path.startsWith("/v1/maps/geocode")) { geocodes.incrementAndGet(); return json(new JSONObject().put("available", true).put("places", new JSONArray().put(point("测试地点")))); }
        if (path.equals("/v1/commute")) {
            if (method.equals("POST")) { journey = body.put("id", body.getString("journey_id")).put("active", true).put("route", new JSONObject()); }
            return json(journey);
        }
        if (path.startsWith("/v1/commute/")) {
            if (method.equals("DELETE")) { journey.put("active", false); stops.incrementAndGet(); return json(journey); }
            if (path.endsWith("/refresh")) {
                check(journey.optBoolean("active"), "Refresh after end");
                if (journey.optString("origin_mode").equals("fixed")) { check(!body.has("location"), "Fixed origin followed GPS"); fixedRoutes.incrementAndGet(); }
                else { JSONObject fix = body.getJSONObject("location"); check(fix.getDouble("accuracy") <= 200, "Inaccurate GPS accepted"); gpsTime.set(fix.getString("observed_at")); gpsRoutes.incrementAndGet(); }
                JSONObject route = new JSONObject().put("available", true).put("eta_seconds", 600).put("distance_meters", 4200).put("destination", "测试公司").put("note", "公开测试路线估算")
                    .put("steps", new JSONArray().put(new JSONObject().put("instruction", "沿测试道路前往目的地")));
                journey.put("route", route).put("error", "").put("updated_at", Instant.now().toString()); return json(journey);
            }
        }
        if (path.equals("/v1/location-records")) { records.incrementAndGet(); return json(new JSONObject().put("ok", true)); }
        if (path.equals("/v1/drafts")) return json(new JSONObject().put("drafts", new JSONArray()));
        if (path.equals("/v1/state")) return json(state);
        return new MockResponse().setResponseCode(404);
    }
    private MockResponse json(JSONObject value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString()); }
    private void addAddress(String name, String tag, String captureName) throws Exception {
        main(() -> find(top().getWindow().getDecorView(), "添加常用地址").performClick()); Dialog edit = top();
        main(() -> { ArrayList<EditText> fields = edits(edit); fields.get(0).setText(name); fields.get(1).setText("北京"); fields.get(2).setText("北京市测试路1号"); });
        if (!tag.equals("home")) {
            main(() -> find(edit.getWindow().getDecorView(), "标签 · ").performClick()); Dialog tags = top();
            check(find(tags.getWindow().getDecorView(), "学校") != null && find(tags.getWindow().getDecorView(), "自定义") != null, "Address tags missing");
            main(() -> ((View)find(tags.getWindow().getDecorView(), name).getParent()).performClick());
            main(() -> find(tags.getWindow().getDecorView(), "确定").performClick());
        }
        main(() -> find(edit.getWindow().getDecorView(), "定位地址").performClick());
        waitUntil(() -> findOrNull(edit.getWindow().getDecorView(), "已定位：") != null, 8000, "Address resolution failed");
        capture(edit.getWindow().getDecorView(), captureName);
        main(() -> find(edit.getWindow().getDecorView(), "保存地址").performClick()); waitUntil(() -> !edit.isShowing(), 5000, "Address did not save");
    }
    private void bindFixtureService() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((CommuteService.LocalBinder)binder).service();
                try {
                    ((ForegroundLocationMonitor)field(service, "locations")).close();
                    set(service, "api", fixtureApi); set(service, "token", fixtureApi.vault.token()); set(service, "owner", CommuteService.owner(fixtureApi));
                    ForegroundLocationMonitor monitor = new ForegroundLocationMonitor(fixture, new ForegroundLocationMonitor.Listener() {
                        public void status(String message) {} public void updated() {}
                        public void fixed(LocationSnapshot value) { try { set(service, "fix", value); invoke(service, "refreshRoute"); } catch (Exception e) { failure.set(e.toString()); } }
                    }); set(service, "locations", monitor);
                } catch (Exception e) { failure.set(e.toString()); }
                ready.countDown();
            }
            public void onServiceDisconnected(ComponentName name) {}
        };
        main(() -> check(activity.bindService(new Intent(activity, CommuteService.class), connection, Context.BIND_AUTO_CREATE), "Service bind failed"));
        check(ready.await(5, TimeUnit.SECONDS), "Service bind timed out");
    }
    private void unbind() throws Exception { if (connection != null) { ServiceConnection old = connection; connection = null; main(() -> activity.unbindService(old)); } }
    private void quietMaintenance() throws Exception { ((ForegroundLocationMonitor)field(activity, "locationMonitor")).stop(); set(activity, "startupLocationAttempted", true); }
    private Dialog top() throws Exception { ArrayList<Dialog> list = (ArrayList<Dialog>)field(panel, "dialogs"); return list.get(list.size() - 1); }
    private void clickOption(String prefix) throws Exception { Dialog dialog = top(); main(() -> ((View)find(dialog.getWindow().getDecorView(), prefix).getParent()).performClick()); SystemClock.sleep(250); }
    private static ArrayList<EditText> edits(Dialog dialog) { ArrayList<EditText> values = new ArrayList<>(); collect(dialog.getWindow().getDecorView(), values); return values; }
    private static void collect(View view, ArrayList<EditText> values) { if (view instanceof EditText) values.add((EditText)view); if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) collect(((ViewGroup)view).getChildAt(i), values); }
    private static TextView findOrNull(View root, String prefix) { if (root instanceof TextView && ((TextView)root).getText().toString().startsWith(prefix)) return (TextView)root; if (root instanceof ViewGroup) for (int i=0; i<((ViewGroup)root).getChildCount(); i++) { TextView v=findOrNull(((ViewGroup)root).getChildAt(i),prefix); if(v!=null)return v; } return null; }
    private static TextView find(View root,String prefix) { TextView found=findOrNull(root,prefix); if(found==null)throw new AssertionError("Missing UI: "+prefix); return found; }
    private interface Checked { void run() throws Exception; } private interface Condition { boolean get() throws Exception; }
    private void main(Checked job) throws Exception { AtomicReference<Throwable> failed = new AtomicReference<>(); runOnMainSync(() -> { try { job.run(); } catch(Throwable e){failed.set(e);} }); if(failed.get()!=null)throw new RuntimeException(failed.get()); }
    private void waitUntil(Condition condition, long timeout, String message) throws Exception { long end=SystemClock.elapsedRealtime()+timeout; while(SystemClock.elapsedRealtime()<end){if(condition.get())return; SystemClock.sleep(100);} throw new AssertionError(message); }
    private static Object field(Object owner,String name) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private static void set(Object owner,String name,Object value) throws Exception { Field f=owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner,value); }
    private static Object invoke(Object owner,String name,Object... values) throws Exception { Class<?>[] types=new Class<?>[values.length]; for(int i=0;i<values.length;i++)types[i]=values[i] instanceof Integer?int.class:values[i].getClass(); Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,values); }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void capture(View view,String name) throws Exception { SystemClock.sleep(250); main(() -> { Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle(); }); }
}
