package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.view.WindowManager;
import com.aidol.demo.hardware.HeadsetController;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;

/** Explicit physical playback of public, nonverbal assets. No microphone or settings mutation. */
public final class EarconInstrumentation extends Instrumentation {
    private MainActivity activity;
    private HostedSession session;
    private MockWebServer server;
    private final AtomicInteger captureStarts = new AtomicInteger(), routes = new AtomicInteger();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        try {
            activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            require(!getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(), "Unlock phone for earcon review");
            require(field(activity, "session") == null, "Existing session is active");
            require(!CommuteService.running((ApiClient)field(activity, "api")), "An existing commute is active");
            // Isolate measured playback from delayed startup/wear feedback in this review Activity.
            runOnMainSync(() -> { try { ((QuickFeedback)field(activity, "quickFeedback")).close(); } catch (Exception e) { throw new RuntimeException(e); } });
            HeadsetController headset = (HeadsetController)field(activity, "headset");
            for(int i=0; i<100 && !headset.snapshot().ready(); i++) SystemClock.sleep(200);
            require(headset.snapshot().ready(), "Rise headset is not ready");
            require(!headset.snapshot().inCall && getTargetContext().getSystemService(AudioManager.class).getMode() == AudioManager.MODE_NORMAL, "A call is active");
            server = new MockWebServer(); server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(500); }
            }); server.start(java.net.InetAddress.getByName("127.0.0.1"),0);
            Context fixture = new ContextWrapper(getTargetContext()) {
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) { return super.getSharedPreferences("earcon_review_"+name,mode); }
                @Override public Context getApplicationContext() { return this; }
            };
            ApiClient api = new ApiClient(fixture, ServerEndpoint.USB_LOCAL);
            Field url=ApiClient.class.getDeclaredField("serverUrl");url.setAccessible(true);url.set(api,"http://127.0.0.1:"+server.getPort());
            for(String mode:new String[]{"life","work","commute"}) {
                CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error=new AtomicReference<>();
                CaptureSource forbidden = new CaptureSource() {
                    public void start(Listener listener) { captureStarts.incrementAndGet(); listener.error("Earcons must not open the microphone"); }
                    public void stop(StopCallback callback) { callback.ended(); }
                    public void cancel() {}
                };
                session=new HostedSession(getTargetContext(),api,AudioMode.RISE_SDK,forbidden,headset::matchesOutput,new HostedSession.Listener() {
                    public void status(String text) {}
                    public void failure(String text) { error.set(text); }
                    public void output(String text) { routes.incrementAndGet(); }
                    public void result(JSONObject value) { error.set("Unexpected business result"); }
                    public void recording() { captureStarts.incrementAndGet(); }
                    public void meter(long count,double rms) { captureStarts.incrementAndGet(); }
                    public void done() { done.countDown(); }
                });
                session.announceMode(mode);
                require(done.await(12,TimeUnit.SECONDS),mode+" playback timed out");
                require(error.get()==null,mode+": "+error.get());
                SystemClock.sleep(350);
            }
            ReplyPlayer general = new ReplyPlayer(getTargetContext(), AudioMode.RISE_SDK, headset::matchesOutput);
            try { general.play(LocalCue.READY.pcm(getTargetContext()), ignored -> {}, ignored -> routes.incrementAndGet()); }
            finally { general.cancel(); }
            require(routes.get()==4,"Not every cue verified the connected headset output");
            require(captureStarts.get()==0 && server.getRequestCount()==0,"Earcons attempted capture or a backend request");
            result.putString("stream","PASS: life/work/commute + general earcon completed on verified Rise output; zero microphone starts and zero fixture API requests; mode settings and meetings unchanged. Human listening quality remains user-reviewed.");
            outcome=Activity.RESULT_OK;
        } catch(Throwable error) { result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error)); }
        finally {
            if(session!=null)session.cancel();
            if(server!=null)try{server.shutdown();}catch(Exception ignored){}
            if(activity!=null)runOnMainSync(activity::finish);
        }
        finish(outcome,result);
    }
    private static Object field(Object owner,String name)throws Exception { Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner); }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
