package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.aidol.demo.hardware.HeadsetController;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.json.*;

/** Mounted conversation regression review; isolated mock settings, real headset output, no capture. */
public final class ModeSwitchInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ApiClient original, fixtureApi;
    private JSONObject originalState, state;
    private MockWebServer server;
    private Context fixture;
    private Object root, page, content;
    private ScrollView scroll;
    private EditText draft;
    private int scrollY, voiceWidth;
    private String liveText;
    private long generation;
    private File output;
    private final AtomicInteger failureCode = new AtomicInteger(), puts = new AtomicInteger(), unexpected = new AtomicInteger();
    private int verifiedRoutes;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        try {
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitUntil(() -> !(Boolean)field(activity, "busy"), 15000, "Initial state load timed out");
            check(!getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(), "Unlock phone for mode review");
            check(field(activity, "session") == null, "Existing audio session is active");
            original = (ApiClient)field(activity, "api"); originalState = (JSONObject)field(activity, "state");
            check(!CommuteService.running(original), "Existing commute is active");
            HeadsetController headset = (HeadsetController)field(activity, "headset");
            waitUntil(() -> headset.snapshot().ready(), 20000, "Rise headset not connected");
            check(!headset.snapshot().inCall, "Phone call is active");
            main(() -> { ((QuickFeedback)field(activity, "quickFeedback")).close(); invoke(activity, "clearVoicePreparation"); });
            fixture = new ContextWrapper(getTargetContext()) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("mode_review_" + name, mode); }
                @Override public Context getApplicationContext() { return this; }
            };
            fixtureApi = new ApiClient(fixture, ServerEndpoint.USB_LOCAL); fixtureApi.vault.save("offline-mode-review", "模式测试");
            JSONArray history = new JSONArray();
            for (int i=0; i<20; i++) history.put(new JSONObject().put("speaker", i%2==0?"user":"assistant").put("text", "第" + (i+1) + "条公开测试对话，切换模式后应保留。"));
            state = new JSONObject().put("settings", new JSONObject().put("mode", "life").put("auto_mode", false))
                .put("agent", new JSONObject().put("mode", "life")).put("capabilities", new JSONObject())
                .put("transcripts", history).put("work_meetings", new JSONArray()).put("tasks", new JSONArray());
            server = new MockWebServer(); server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        if ("PATCH".equals(request.getMethod()) && "/v1/settings".equals(request.getPath())) {
                            puts.incrementAndGet(); int code = failureCode.getAndSet(0);
                            if (code != 0) return json(new JSONObject().put("detail", code==409?ModeChangeRetry.BUSY_DETAIL:"fixture failure")).setResponseCode(code);
                            return json(new JSONObject(request.getBody().readUtf8())).setBodyDelay(300, TimeUnit.MILLISECONDS);
                        }
                        unexpected.incrementAndGet(); return new MockResponse().setResponseCode(500);
                    } catch (Exception error) { unexpected.incrementAndGet(); return new MockResponse().setResponseCode(500); }
                }
            }); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
            set(fixtureApi, "serverUrl", "http://127.0.0.1:" + server.getPort());
            main(() -> {
                ((CommutePanel)field(activity, "commutePanel")).close(); set(activity, "commutePanel", null);
                ((ForegroundLocationMonitor)field(activity, "locationMonitor")).close();
                set(activity, "locationMonitor", new ForegroundLocationMonitor(fixture, new ForegroundLocationMonitor.Listener() { public void status(String text) {} public void updated() {} }));
                set(activity, "api", fixtureApi); set(activity, "state", state); set(activity, "tab", 0);
                set(activity, "lastNotice", ""); set(activity, "startupLocationAttempted", true); invoke(activity, "showApp");
                Handler handler = (Handler)field(activity, "handler");
                handler.removeCallbacks((Runnable)field(activity, "automaticHeadsetTick")); handler.removeCallbacks((Runnable)field(activity, "startupLocationTick"));
            });
            SystemClock.sleep(500);
            root=field(activity,"root"); page=field(activity,"page"); content=field(activity,"content");
            scroll=(ScrollView)field(activity,"scroll"); draft=(EditText)field(activity,"chatInput");
            generation=(Long)field(activity,"sessionGeneration"); liveText=((TextView)field(activity,"liveWords")).getText().toString();
            output=new File(getTargetContext().getExternalFilesDir(null), "mode-switch-review"); output.mkdirs();
            main(() -> { draft.setText("未发送的测试草稿"); draft.setSelection(3); draft.requestFocus(); scroll.setSmoothScrollingEnabled(false); scroll.scrollTo(0,0); });
            SystemClock.sleep(200); scrollY=scroll.getScrollY(); voiceWidth=((View)field(activity,"voiceButton")).getWidth();
            capture("01-before");
            switchAndCheck("work", true); switchAndCheck("commute", false); switchAndCheck("life", false);
            main(() -> scroll.scrollTo(0,420)); SystemClock.sleep(200); scrollY=scroll.getScrollY(); check(scrollY>0,"Fixture did not scroll");
            switchAndCheck("work", false); switchAndCheck("life", false);
            // An explicitly rejected settings request retries without flashing a progress banner.
            int before=puts.get(); failureCode.set(409); switchAndCheck("commute", false); check(puts.get()==before+2,"409 retry was not bounded to the successful retry");
            // Replace a playing cue with another committed mode; stale audio completion must not reset UI.
            begin("work"); waitUntil(() -> !(Boolean)field(activity,"busy"),5000,"First rapid mode did not commit");
            check(((ModeCuePlayer)field(activity,"modeCuePlayer")).active(),"No cue to interrupt"); begin("life");
            waitUntil(() -> !(Boolean)field(activity,"busy") && !((ModeCuePlayer)field(activity,"modeCuePlayer")).active(),10000,"Rapid mode replacement timed out");
            main(() -> stable(true)); check("life".equals(invoke(activity,"mode")),"Stale cue changed the final mode");
            // Failure leaves the selected mode/draft/mounted page intact and displays a real error.
            failureCode.set(500); begin("work"); waitUntil(() -> !(Boolean)field(activity,"busy"),5000,"Failure did not finish");
            check("life".equals(invoke(activity,"mode")),"Failed settings changed mode"); main(() -> stable(false));
            check(((TextView)field(activity,"status")).getText().toString().contains("未能确认"),"Failure feedback missing");
            check(!((ModeCuePlayer)field(activity,"modeCuePlayer")).active(),"Failure played a success cue");
            switchAndCheck("work", false);
            // Actual conversation controls still exist. Allocate and cancel without starting capture/network.
            main(() -> { invoke(activity,"newSession"); check(((View)field(activity,"cancelButton")).getVisibility()==View.VISIBLE,"Real session lost its end control"); invoke(activity,"cancelSession", ""); });
            check(unexpected.get()==0,"Unexpected API request (capture/reply/location): " + unexpected.get());
            check(getTargetContext().getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty(),"Unexpected microphone recording");
            result.putString("stream", "PASS: mounted root/page/history/input retained; draft, selection, focus and scroll stable; no playback/progress/hangup UI; seven full cues verified on Rise output; rapid replacement, 409 retry, 500 failure and real-session end control passed. Settings PATCHes="+puts.get()+", verified_routes="+verifiedRoutes+", unexpected_requests="+unexpected.get());
            outcome=Activity.RESULT_OK;
        } catch(Throwable error) { result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error)); }
        finally {
            if(activity!=null)try{main(() -> { ((ModeCuePlayer)field(activity,"modeCuePlayer")).stop(); if(original!=null){set(activity,"api",original);set(activity,"state",originalState);} activity.finish(); });}catch(Exception ignored){}
            if(fixtureApi!=null)fixtureApi.vault.clear();
            if(server!=null)try{server.shutdown();}catch(Exception ignored){}
        }
        finish(outcome,result);
    }
    private void begin(String mode) throws Exception {
        main(() -> { set(activity,"diagnosticOutput","尚未播放"); int index="life".equals(mode)?0:"work".equals(mode)?1:2; ((LinearLayout)field(activity,"modeChoices")).getChildAt(index).performClick(); activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); });
    }
    private void switchAndCheck(String mode, boolean screenshot) throws Exception {
        begin(mode); main(() -> stable(true));
        // Repeated taps while PATCH is pending remain quiet and do not create duplicate requests.
        begin(mode); main(() -> stable(true));
        long deadline=SystemClock.elapsedRealtime()+12000; boolean seen=false;
        while(SystemClock.elapsedRealtime()<deadline) {
            main(() -> stable(true));
            boolean active=((ModeCuePlayer)field(activity,"modeCuePlayer")).active();
            if(active && !seen) { seen=true; if(screenshot)capture("02-during-cue"); }
            if(!(Boolean)field(activity,"busy") && !active)break;
            SystemClock.sleep(35);
        }
        check(seen && !(Boolean)field(activity,"busy") && !((ModeCuePlayer)field(activity,"modeCuePlayer")).active(),"Cue did not complete: "+mode+", seen="+seen+", busy="+field(activity,"busy")+", visible="+field(activity,"visible")+", selected="+invoke(activity,"mode")+", error="+field(activity,"diagnosticError")+", worn="+((HeadsetController)field(activity,"headset")).snapshot().leftWorn+"/"+((HeadsetController)field(activity,"headset")).snapshot().rightWorn);
        check("无".equals(field(activity,"diagnosticError")),"Cue failure: "+field(activity,"diagnosticError"));
        check(!"尚未播放".equals(field(activity,"diagnosticOutput")),"No verified headset output: "+mode); verifiedRoutes++;
        check(mode.equals(invoke(activity,"mode")),"Mode did not commit: "+mode);
        main(() -> { stable(true); check(((TextView)((LinearLayout)field(activity,"modeChoices")).getChildAt("life".equals(mode)?0:"work".equals(mode)?1:2)).getContentDescription().toString().contains("已选中"),"Selected style missing"); });
        if(screenshot)capture("03-after-cue");
    }
    private void stable(boolean quiet) throws Exception {
        check(field(activity,"root")==root && field(activity,"page")==page && field(activity,"content")==content && field(activity,"scroll")==scroll && field(activity,"chatInput")==draft,"Conversation rebuilt");
        check("未发送的测试草稿".contentEquals(draft.getText()) && draft.getSelectionStart()==3 && draft.hasFocus(),"Draft/cursor/focus changed");
        check(field(activity,"session")==null && !(Boolean)field(activity,"recording"),"Mode cue opened a conversation/capture");
        check((Long)field(activity,"sessionGeneration")==generation,"Mode cue advanced conversation generation");
        check(((View)field(activity,"cancelButton")).getVisibility()==View.GONE,"Mode cue exposed hangup");
        check(((View)field(activity,"voiceButton")).getWidth()==voiceWidth,"Voice button resized");
        check(liveText.contentEquals(((TextView)field(activity,"liveWords")).getText()),"Live conversation text erased");
        if(quiet) {
            check(scroll.getScrollY()==scrollY,"Scroll moved: "+scrollY+" -> "+scroll.getScrollY());
            check(((View)field(activity,"status")).getVisibility()==View.GONE,"Mode cue showed status: "+((TextView)field(activity,"status")).getText());
        }
    }
    private static MockResponse json(JSONObject value) { return new MockResponse().setHeader("Content-Type","application/json").setBody(value.toString()); }
    private interface Checked { void run() throws Exception; } private interface Condition { boolean get() throws Exception; }
    private void main(Checked action) throws Exception { AtomicReference<Throwable> failed=new AtomicReference<>(); runOnMainSync(() -> {try{action.run();}catch(Throwable e){failed.set(e);}}); if(failed.get()!=null)throw new RuntimeException(failed.get()); }
    private void waitUntil(Condition condition,long timeout,String message)throws Exception { long deadline=SystemClock.elapsedRealtime()+timeout; while(SystemClock.elapsedRealtime()<deadline){if(condition.get())return;SystemClock.sleep(50);}throw new AssertionError(message); }
    private static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Object invoke(Object owner,String name,Object... values)throws Exception {Class<?>[] types=new Class<?>[values.length];for(int i=0;i<values.length;i++)types[i]=values[i].getClass();Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,values);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void capture(String name)throws Exception {main(() -> {View view=activity.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();});}
}
