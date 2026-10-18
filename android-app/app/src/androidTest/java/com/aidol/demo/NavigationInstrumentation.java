package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.json.*;

/** Uses only synthetic journeys and an isolated mock account. Never starts a real journey. */
public final class NavigationInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ApiClient original, fixtureApi;
    private JSONObject originalState;
    private CommutePanel panel;
    private CommuteService syntheticService;
    private MockWebServer server;
    private File output;
    private ActivityMonitor monitor;
    private boolean live, rejectNative, rejectBrowser;
    private final List<Intent> intents=new ArrayList<>();
    private final AtomicInteger mutations=new AtomicInteger();
    @Override public void onCreate(Bundle args){super.onCreate(args);live=args!=null&&"true".equals(args.getString("live_amap"));start();}
    private static JSONObject point(boolean start)throws Exception{return new JSONObject().put("name",start?"故宫博物院":"天坛公园").put("address","北京市公开地标").put("city","北京").put("adcode","110101").put("longitude",start?116.397:116.410).put("latitude",start?39.918:39.881);}
    private static JSONObject journey(String transport,boolean fixed)throws Exception{return new JSONObject().put("id","isolated-navigation-fixture").put("active",true).put("origin_mode",fixed?"fixed":"gps").put("origin",fixed?point(true):JSONObject.NULL).put("destination",point(false)).put("transport",transport);}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitUntil(()->!(Boolean)field(activity,"busy"),15000,"Initial state load");
            check(!getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(),"Unlock phone for navigation review");
            check(field(activity,"session")==null,"Existing audio session active");
            original=(ApiClient)field(activity,"api");originalState=(JSONObject)field(activity,"state");
            check(!CommuteService.running(original),"Existing commute active");
            check(staticField(CommuteService.class,"instance")==null,"Existing commute service must remain untouched");
            Context fixture=new ContextWrapper(getTargetContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("navigation_review_"+name,mode);}
                @Override public Context getApplicationContext(){return this;}
            };
            fixtureApi=new ApiClient(fixture,ServerEndpoint.USB_LOCAL);fixtureApi.vault.save("offline-navigation-review","导航测试");
            check(!fixtureApi.vault.token().equals(original.vault.token()),"Fixture must not share the real account vault");
            server=new MockWebServer();server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
                String p=request.getPath();
                if(p.equals("/v1/saved-addresses"))return json("{\"addresses\":[]}");
                if(p.equals("/v1/tasks"))return json("{\"tasks\":[]}");
                if(p.equals("/v1/commute"))return json("{}");
                if(p.startsWith("/v1/maps/")||p.equals("/v1/commute/preview"))return json("{\"available\":false}").setResponseCode(503);
                if(!request.getMethod().equals("GET"))mutations.incrementAndGet();
                return new MockResponse().setResponseCode(404);
            }});server.start(java.net.InetAddress.getByName("127.0.0.1"),0);set(fixtureApi,"serverUrl","http://127.0.0.1:"+server.getPort());
            panel=(CommutePanel)field(activity,"commutePanel");
            main(()->{
                panel.reset();((QuickFeedback)field(activity,"quickFeedback")).close();invoke(activity,"clearVoicePreparation");
                ((ForegroundLocationMonitor)field(activity,"locationMonitor")).close();
                set(activity,"api",fixtureApi);set(activity,"state",new JSONObject().put("settings",new JSONObject().put("mode","commute")).put("capabilities",new JSONObject()).put("agent",new JSONObject()));
                set(activity,"tab",3);set(activity,"lastNotice","");set(activity,"startupLocationAttempted",true);invoke(activity,"showApp");quiet();
            });
            waitUntil(()->(Boolean)field(panel,"loaded"),5000,"Fixture load");
            output=new File(getTargetContext().getExternalFilesDir(null),"navigation-review");output.mkdirs();
            TextView button=(TextView)field(panel,"navigationButton");
            check(button.getVisibility()==View.GONE,"Navigation shown before commute");
            main(()->{set(panel,"journey",journey("driving",false));invoke(panel,"update");});
            check(button.getVisibility()==View.VISIBLE&&!button.isEnabled(),"Navigation allowed before service ready");
            syntheticService=new CommuteService();set(syntheticService,"owner",CommuteService.owner(fixtureApi));setStatic(CommuteService.class,"instance",syntheticService);
            monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
                if(!Intent.ACTION_VIEW.equals(intent.getAction()))return null;
                intents.add(new Intent(intent));
                if(AmapNavigation.PACKAGE.equals(intent.getPackage())&&rejectNative)throw new ActivityNotFoundException("Isolated absent-app fixture");
                if("https".equals(intent.getScheme())&&rejectBrowser)throw new ActivityNotFoundException("Isolated absent-browser fixture");
                return new ActivityResult(Activity.RESULT_CANCELED,null);
            }};addMonitor(monitor);
            String version="";boolean amapInstalled=false;
            try{version=activity.getPackageManager().getPackageInfo(AmapNavigation.PACKAGE,0).versionName;amapInstalled=true;}
            catch(android.content.pm.PackageManager.NameNotFoundException absent){/* Mock handoffs also run without AMap installed. */}
            boolean features=AmapNavigation.supportsFeatureNavigation(version);
            int combinations=0;
            for(boolean fixed:new boolean[]{false,true})for(String mode:new String[]{"driving","walking","bicycling","transit"}){
                JSONObject current=journey(mode,fixed);applyJourney(current);
                // Deliberately stale editor draft: launching must use the immutable active journey.
                main(()->{set(panel,"transport","walking");set(panel,"destination",point(true));});
                String before=current.toString();int count=intents.size();main(button::performClick);
                check(intents.size()==count+1,"Unexpected launch count");Intent sent=intents.get(count);
                check(AmapNavigation.PACKAGE.equals(sent.getPackage())&&sent.getDataString().equals(AmapNavigation.create(current,features).appUri),"Wrong native handoff: "+mode+" "+fixed);
                main(button::performClick);check(intents.size()==count+1,"Duplicate tap opened twice");
                check(before.equals(current.toString())&&CommuteService.running(fixtureApi),"Navigation changed journey or stopped service");combinations++;
            }
            JSONObject current=journey("driving",true);applyJourney(current);
            main(()->{((ScrollView)field(activity,"scroll")).scrollTo(0,500);});SystemClock.sleep(200);capture("01-navigation-button");
            int y=((ScrollView)field(activity,"scroll")).getScrollY();rejectNative=true;main(button::performClick);
            waitUntil(()->field(panel,"navigationFallback")!=null,2000,"Missing fallback dialog");Dialog fallback=(Dialog)field(panel,"navigationFallback");capture("02-navigation-fallback");
            rejectBrowser=true;main(()->find(fallback.getWindow().getDecorView(),"打开网页版路线").performClick());
            check(fallback.isShowing()&&find(fallback.getWindow().getDecorView(),"没有可打开路线的浏览器，请安装高德地图后重试。")!=null,"Browser failure not recoverable");
            rejectBrowser=false;main(()->find(fallback.getWindow().getDecorView(),"打开网页版路线").performClick());SystemClock.sleep(200);
            check(!fallback.isShowing(),"Successful fallback did not close");check(intents.get(intents.size()-1).getDataString().equals(AmapNavigation.create(current,features).webUri),"Web route did not preserve inputs");
            check(((ScrollView)field(activity,"scroll")).getScrollY()==y,"Fallback moved page");
            // Leaving the activity must not stop the separate commute service.
            main(()->callActivityOnPause(activity));check(CommuteService.running(fixtureApi),"Pause ended commute");
            main(()->{callActivityOnResume(activity);quiet();});
            // resume/tick copies service JSON into the panel, so update both fixture sources.
            applyJourney(new JSONObject(current.toString()).put("active",false));
            SystemClock.sleep(1100);
            check(button.getVisibility()==View.GONE,"Navigation shown after end");
            check(mutations.get()==0,"Navigation wrote account data: "+mutations.get());
            String liveStatus=live?"SKIPPED: AMap is not installed; no real handoff performed":"not requested";
            if(live&&amapInstalled){
                removeMonitor(monitor);monitor=null;rejectNative=false;applyJourney(journey("driving",true));
                main(button::performClick);SystemClock.sleep(1800);
                check(field(panel,"navigationFallback")==null,"Installed AMap did not accept live handoff");
                Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null){try(FileOutputStream out=new FileOutputStream(new File(output,"03-live-amap.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
                liveStatus="launch requested; verify external UI separately";
            }
            result.putString("live_amap_status",liveStatus);
            result.putString("stream","PASS: "+combinations+" GPS/fixed mode combinations; active journey wins over stale draft; no duplicate launch; absent native/browser recoverable; web params preserved; fallback scroll stable; button gated by commute/service; lifecycle retains service; zero data writes. Live AMap public-landmark handoff: "+liveStatus+".");code=Activity.RESULT_OK;
        }catch(Throwable error){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error));}
        finally{
            if(monitor!=null)removeMonitor(monitor);
            try{if(syntheticService!=null&&staticField(CommuteService.class,"instance")==syntheticService)setStatic(CommuteService.class,"instance",null);}catch(Exception ignored){}
            if(activity!=null)try{main(()->{if(panel!=null)panel.close();if(original!=null){set(activity,"api",original);set(activity,"state",originalState);}activity.finish();});}catch(Exception ignored){}
            if(fixtureApi!=null)fixtureApi.vault.clear();if(server!=null)try{server.shutdown();}catch(Exception ignored){}
        }
        finish(code,result);
    }
    private void applyJourney(JSONObject value)throws Exception{main(()->{set(syntheticService,"journey",value);set(panel,"journey",value);set(panel,"navigationOpenedAt",-2000L);invoke(panel,"update");});}
    private void quiet()throws Exception{((ForegroundLocationMonitor)field(activity,"locationMonitor")).close();Handler h=(Handler)field(activity,"handler");for(String n:new String[]{"automaticHeadsetTick","startupLocationTick"})h.removeCallbacks((Runnable)field(activity,n));}
    private static MockResponse json(String value){return new MockResponse().setHeader("Content-Type","application/json").setBody(value);}
    private static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private interface Checked{void run()throws Exception;}private interface Condition{boolean get()throws Exception;}
    private void main(Checked action)throws Exception{AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new RuntimeException(failure.get());}
    private void waitUntil(Condition c,long ms,String message)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(c.get())return;SystemClock.sleep(50);}throw new AssertionError(message);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object staticField(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void setStatic(Class<?> type,String name,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    private static Object invoke(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
    private void capture(String name)throws Exception{main(()->{View view=activity.getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();});}
}
