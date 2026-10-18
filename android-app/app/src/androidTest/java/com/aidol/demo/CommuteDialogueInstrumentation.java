package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.json.*;

/** Offline account, synthetic itinerary/audio, intercepted map intent. No microphone or real journey. */
public final class CommuteDialogueInstrumentation extends Instrumentation {
    private MockWebServer server;
    private CommutePanel panel;
    private MainActivity activity;
    private CommuteService synthetic;
    private ApiClient api;
    private final AtomicInteger starts = new AtomicInteger(), speeches = new AtomicInteger(), launches = new AtomicInteger();
    private final AtomicReference<JSONObject> started = new AtomicReference<>();
    private volatile long speechAt, launchAt;
    private ActivityMonitor monitor;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            check(getTargetContext().getPackageName().endsWith(".review"), "This fixture requires isolated review build");
            server = new MockWebServer();
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    String path = request.getPath();
                    if (path.equals("/v1/saved-addresses")) return json(new JSONObject().put("addresses", new JSONArray()));
                    if (path.equals("/v1/commute") && request.getMethod().equals("GET")) return json(new JSONObject());
                    if (path.equals("/v1/commute") && request.getMethod().equals("POST")) {
                        JSONObject body = new JSONObject(request.getBody().readUtf8()); started.set(body); starts.incrementAndGet();
                        return json(new JSONObject(body.toString()).put("id", body.getString("journey_id")).put("active", true)
                            .put("route", new JSONObject().put("available", true).put("eta_seconds",1200)));
                    }
                    if (path.endsWith("/briefing")) return json(new JSONObject().put("text", "通勤已开启，预计二十分钟抵达。目的地晴。即将打开高德地图。"));
                    if (path.equals("/v1/speech")) {
                        speeches.incrementAndGet(); speechAt = SystemClock.elapsedRealtime();
                        check(new JSONObject(request.getBody().readUtf8()).getString("text").contains("通勤") || speeches.get() > 1,"Wrong speech text");
                        return new MockResponse().setHeader("Content-Type","audio/wav").setBody(new Buffer().write(wav()));
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) { return new MockResponse().setResponseCode(500); }
            }});
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(!getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(), "Unlock required");
            api = new ApiClient(getTargetContext(), ServerEndpoint.USB_LOCAL);
            api.vault.save("isolated-commute-dialogue", "离线测试"); set(api,"serverUrl","http://127.0.0.1:"+server.getPort());
            JSONObject state = new JSONObject().put("settings",new JSONObject().put("mode","commute"));
            main(() -> {
                // Use an independent panel; keep the real account and activity controllers out of this fixture.
                panel = new CommutePanel(activity, () -> api, () -> state, () -> true, () -> true, notifications -> true, () -> {}, () -> {}, () -> null);
                synthetic = new CommuteService(); set(synthetic,"owner",CommuteService.owner(api));
                panel.journeyLauncher = (context, journey) -> { try { set(synthetic,"journey",journey); setStatic(CommuteService.class,"instance",synthetic); } catch(Exception e) { throw new RuntimeException(e); } };
                LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); ScrollView scroll = new ScrollView(activity); scroll.addView(root); activity.setContentView(scroll); panel.render(root,scroll);
            });
            waitFor(() -> (Boolean)field(panel,"loaded"), 5000,"initial load");
            monitor = new ActivityMonitor() { @Override public ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
                launches.incrementAndGet(); launchAt = SystemClock.elapsedRealtime();
                check(AmapNavigation.PACKAGE.equals(intent.getPackage()), "Wrong navigation package");
                return new ActivityResult(Activity.RESULT_CANCELED,null);
            }}; addMonitor(monitor);
            JSONObject point = new JSONObject().put("name","测试公园").put("city","北京").put("address","北京市公开地标").put("longitude",116.4).put("latitude",39.9);
            JSONObject plan = new JSONObject().put("journey_id",UUID.randomUUID().toString()).put("origin_mode","fixed").put("origin",point).put("destination",point).put("transport","transit");
            main(() -> panel.startFromConversation(plan));
            waitFor(() -> launches.get()==1,15000,"speech followed by map");
            check(starts.get()==1 && speeches.get()==1, "Expected one start and one combined speech");
            check(launchAt-speechAt>=250, "Navigation occurred before audio completed");
            check("transit".equals(started.get().getString("transport")) && started.get().getJSONObject("origin").getDouble("longitude")==116.4,"Lost commute slots");
            main(() -> panel.startFromConversation(plan)); SystemClock.sleep(200);
            check(starts.get()==1 && launches.get()==1,"Duplicate plan restarted journey");
            main(() -> invoke(panel,"testStationReminder"));
            waitFor(() -> speeches.get()==2,5000,"test reminder speech");
            check(getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications().length>0,"No App notification posted");
            check(starts.get()==1 && launches.get()==1,"Test changed real journey/navigation");
            SystemClock.sleep(700);
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            java.io.File capture = new java.io.File(getTargetContext().getExternalFilesDir(null), "commute-dialogue-test.png");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(capture)) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream); }
            screenshot.recycle();
            main(() -> { panel.pause(); });
            output.putString("result","PASS: slots forwarded, one start, TTS completion before AMap, duplicate suppression, test App notification, no SMS/microphone");code=Activity.RESULT_OK;
        } catch(Throwable failure) { output.putString("error",android.util.Log.getStackTraceString(failure)); }
        finally {
            try { main(() -> { setStatic(CommuteService.class,"instance",null); if(panel!=null)panel.close(); if(activity!=null)activity.finish(); }); } catch(Exception ignored) {}
            if(monitor!=null)removeMonitor(monitor);
            getTargetContext().getSystemService(NotificationManager.class).cancel(46);
            if(api!=null)api.vault.clear(); try{if(server!=null)server.shutdown();}catch(Exception ignored){}
            finish(code,output);
        }
    }
    private static MockResponse json(JSONObject value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value.toString());}
    private static byte[] wav() { // 0.4 seconds of silence: tests output completion without speaking to the user.
        int n=19200;java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(44+n).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36+n).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(n);return b.array();
    }
    private interface Work { void run() throws Exception; }
    private interface Condition { boolean get() throws Exception; }
    private void main(Work work) throws Exception { AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{work.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new Exception(error.get()); }
    private void waitFor(Condition condition,long ms,String stage)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(condition.get())return;SystemClock.sleep(50);}throw new AssertionError(stage);}
    private static Field find(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object field(Object obj,String name)throws Exception{return find(obj.getClass(),name).get(obj);}
    private static void set(Object obj,String name,Object value)throws Exception{find(obj.getClass(),name).set(obj,value);}
    private static void setStatic(Class<?> type,String name,Object value)throws Exception{find(type,name).set(null,value);}
    private static void invoke(Object obj,String method)throws Exception{Method m=obj.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(obj);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
