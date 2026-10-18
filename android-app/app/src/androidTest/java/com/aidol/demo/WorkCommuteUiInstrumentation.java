package com.aidol.demo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.json.*;

/** Device UI assertions with isolated preferences and offline server; never changes user records. */
public final class WorkCommuteUiInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ApiClient original, fixtureApi;
    private JSONObject originalState, state;
    private MockWebServer server;
    private CommutePanel panel;
    private CommuteMapPanel map;
    private File output;
    private final AtomicInteger previews=new AtomicInteger(), maps=new AtomicInteger(), weather=new AtomicInteger(), writes=new AtomicInteger();
    private final AtomicReference<String> failure=new AtomicReference<>();
    private byte[] png;
    private static JSONObject point(String name)throws Exception{return new JSONObject().put("name",name).put("address","北京市公开测试路1号").put("city","北京").put("adcode","110101").put("longitude",116.4).put("latitude",39.9);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitUntil(()->!(Boolean)field(activity,"busy"),15000,"Initial load");
            check(!getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(),"Unlock phone for UI review");
            check(field(activity,"session")==null,"Existing recording/session active");
            original=(ApiClient)field(activity,"api");originalState=(JSONObject)field(activity,"state");
            check(!CommuteService.running(original),"Existing commute active");
            Context fixture=new ContextWrapper(getTargetContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("work_commute_ui_review_"+name,mode);}
                @Override public Context getApplicationContext(){return this;}
            };
            fixtureApi=new ApiClient(fixture,ServerEndpoint.USB_LOCAL);fixtureApi.vault.save("offline-ui-review","布局测试");
            JSONArray meetings=new JSONArray(),tasks=new JSONArray();
            for(int i=0;i<10;i++){
                meetings.put(new JSONObject().put("id","fixture-meeting-"+i).put("title","公开测试会议 "+(i+1)).put("status","completed").put("summary_status","ready").put("started_at","2026-10-18T01:00:00Z").put("duration_ms",60000).put("summary","讨论进展、明确行动与下次计划。"));
                tasks.put(new JSONObject().put("id","fixture-task-"+i).put("content","行动事项 "+(i+1)).put("status","open").put("owner","测试用户"));
            }
            state=new JSONObject().put("settings",new JSONObject().put("mode","work").put("auto_mode",false)).put("agent",new JSONObject()).put("capabilities",new JSONObject())
                .put("work_meetings",meetings).put("tasks",tasks).put("transcripts",new JSONArray()).put("memories",new JSONArray()).put("drafts",new JSONArray());
            Bitmap testImage=Bitmap.createBitmap(760,420,Bitmap.Config.ARGB_8888);testImage.eraseColor(0xffe3ece7);
            Canvas canvas=new Canvas(testImage);Paint paint=new Paint();paint.setColor(0xff2468db);paint.setStrokeWidth(9);canvas.drawLine(100,320,660,100,paint);
            paint.setTextSize(28);canvas.drawText("OFFLINE UI FIXTURE",220,220,paint);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();testImage.compress(Bitmap.CompressFormat.PNG,100,bytes);png=bytes.toByteArray();testImage.recycle();
            server=new MockWebServer();server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest r){try{return respond(r);}catch(Throwable e){failure.set(e.toString());return new MockResponse().setResponseCode(500);}}});
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0);set(fixtureApi,"serverUrl","http://127.0.0.1:"+server.getPort());
            panel=(CommutePanel)field(activity,"commutePanel");map=(CommuteMapPanel)field(panel,"mapPanel");
            main(()->{
                panel.reset();((QuickFeedback)field(activity,"quickFeedback")).close();invoke(activity,"clearVoicePreparation");
                ((ForegroundLocationMonitor)field(activity,"locationMonitor")).close();
                set(activity,"locationMonitor",new ForegroundLocationMonitor(fixture,new ForegroundLocationMonitor.Listener(){public void status(String s){}public void updated(){}}));
                set(activity,"api",fixtureApi);set(activity,"state",state);set(activity,"tab",2);set(activity,"workSection",0);set(activity,"startupLocationAttempted",true);set(activity,"lastNotice","");invoke(activity,"showApp");quiet();
            });
            output=new File(getTargetContext().getExternalFilesDir(null),"work-commute-ui-review");output.mkdirs();
            SystemClock.sleep(400);
            LinearLayout[] sections=(LinearLayout[])field(activity,"workSections");Object first=sections[0];ScrollView scroll=(ScrollView)field(activity,"scroll");
            main(()->{onlySection(0);scroll.scrollTo(0,600);});SystemClock.sleep(150);int remembered=scroll.getScrollY();
            main(()->select(1));SystemClock.sleep(250);main(()->onlySection(1));
            main(()->select(2));SystemClock.sleep(250);main(()->{onlySection(2);scroll.scrollTo(0,500);});SystemClock.sleep(150);
            int taskY=scroll.getScrollY();CheckBox check=findCheck(sections[2]);main(check::performClick);
            waitUntil(check::isEnabled,4000,"Task checkbox did not save");SystemClock.sleep(250);
            check(scroll.getScrollY()==taskY,"Checkbox moved scroll");
            capture("02-work-tasks");
            main(check::performClick);
            waitUntil(()->writes.get()==2,4000,"Delayed checkbox request did not reach fixture");
            check(!check.isEnabled(),"Checkbox response was not delayed");
            main(()->select(0));SystemClock.sleep(300);
            check(first==((LinearLayout[])field(activity,"workSections"))[0]&&field(activity,"scroll")==scroll,"Tab rebuilt work content");
            check(scroll.getScrollY()==remembered,"Work tab forgot scroll");
            main(()->{onlySection(0);scroll.scrollTo(0,100);});SystemClock.sleep(150);
            int meetingY=scroll.getScrollY();
            check(meetingY!=taskY&&!check.isEnabled(),"Delayed checkbox scroll fixture was not established");
            waitUntil(check::isEnabled,4000,"Delayed checkbox did not save");SystemClock.sleep(250);
            check(scroll.getScrollY()==meetingY,"Delayed checkbox reply moved another work section");
            main(()->scroll.scrollTo(0,0));capture("01-work-tabs");
            main(panel::resume);waitUntil(()->(Boolean)field(panel,"loaded"),5000,"Commute preferences did not load");
            main(()->{set(panel,"originMode","fixed");set(panel,"origin",point("起点"));set(panel,"destination",point("终点"));invoke(activity,"switchTab",3);quiet();});
            waitUntil(()->map.route()!=null && map.route().optBoolean("available") && field(map,"bitmap")!=null && ((TextView)field(map,"weatherMain")).getText().toString().contains("23"),10000,"Map/preview/weather not rendered");
            main(()->{
                check(((TextView)field(panel,"originButton")).getHeight()==EditorUi.dp(activity,48),"Origin selector not compact");
                check(((View)field(map,"mapCard")).getTop()<((View)field(panel,"originButton")).getTop()+((View)((View)field(panel,"originButton")).getParent()).getTop(),"Map should precede selectors");
                check(find(activity.getWindow().getDecorView(),"附近与天气")==null,"Legacy clutter still visible");
                ((ScrollView)field(activity,"scroll")).scrollTo(0,0);
            });capture("03-commute-map");
            ScrollView mapScroll=(ScrollView)field(activity,"scroll");main(()->mapScroll.scrollTo(0,200));SystemClock.sleep(200);int mapY=mapScroll.getScrollY();
            main(()->((View)field(map,"image")).performClick());SystemClock.sleep(200);check(field(map,"enlarged")!=null,"Map enlargement missing");
            main(()->((Dialog)field(map,"enlarged")).dismiss());SystemClock.sleep(250);check(mapScroll.getScrollY()==mapY,"Map dialog moved scroll");
            int before=previews.get();SystemClock.sleep(2200);check(previews.get()==before,"Preview loops every tick");
            main(()->{set(panel,"destination",point("慢响应目的地"));invoke(panel,"update");});
            waitUntil(()->weather.get()>=2,4000,"Old destination weather not requested");
            main(()->{set(panel,"destination",point("新目的地"));invoke(panel,"update");});
            waitUntil(()->((TextView)field(map,"weatherMain")).getText().toString().contains("26"),5000,"New destination weather missing");SystemClock.sleep(1700);
            check(((TextView)field(map,"weatherMain")).getText().toString().contains("26"),"Stale weather replaced new destination");
            waitUntil(()->map.route()!=null && "新目的地".equals(map.route().optString("destination")),5000,"Stale route replaced selection");
            main(()->{((ScrollView)field(activity,"scroll")).fullScroll(View.FOCUS_DOWN);});SystemClock.sleep(250);capture("04-destination-weather");
            main(()->{invoke(activity,"switchTab",2);quiet();});
            check(field(map,"planTask")==null&&field(map,"mapTask")==null&&field(map,"weatherTask")==null,"Off-page requests not cancelled");
            check(failure.get()==null,"Fixture error: "+failure.get());check(writes.get()==2,"Unexpected non-preview mutation: "+writes.get());
            check(getTargetContext().getSystemService(android.media.AudioManager.class).getActiveRecordingConfigurations().isEmpty(),"Unexpected recording");
            result.putString("stream","PASS: three exclusive mounted work tabs; remembered scroll; checkbox stable; delayed checkbox reply preserves another section; compact selectors; map before controls; preview and auto destination weather; stale responses ignored; tab exit cancels requests; no recording/journey writes. previews="+previews+", maps="+maps+", weather="+weather);code=Activity.RESULT_OK;
        }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));}
        finally{
            if(activity!=null)try{main(()->{if(panel!=null)panel.close();if(original!=null){set(activity,"api",original);set(activity,"state",originalState);}activity.finish();});}catch(Exception ignored){}
            if(fixtureApi!=null)fixtureApi.vault.clear();if(server!=null)try{server.shutdown();}catch(Exception ignored){}
        }
        finish(code,result);
    }
    private MockResponse respond(RecordedRequest r)throws Exception{
        String path=r.getPath();
        if(path.equals("/v1/tasks"))return json(new JSONObject().put("tasks",state.getJSONArray("tasks")));
        if(path.equals("/v1/saved-addresses"))return json(new JSONObject().put("addresses",new JSONArray()));
        if(path.equals("/v1/commute")&&r.getMethod().equals("GET"))return json(new JSONObject());
        if(path.equals("/v1/work-meetings"))return json(new JSONObject().put("meetings",state.getJSONArray("work_meetings")));
        if(path.startsWith("/v1/tasks/")&&r.getMethod().equals("PATCH")){
            int write=writes.incrementAndGet();MockResponse response=json(new JSONObject());
            return write==2?response.setBodyDelay(2000,TimeUnit.MILLISECONDS):response;
        }
        if(path.equals("/v1/commute/preview")){
            previews.incrementAndGet();JSONObject body=new JSONObject(r.getBody().readUtf8()),end=body.optJSONObject("destination");
            JSONObject route=new JSONObject().put("available",true).put("geometry",new JSONArray("[[[116.4,39.9],[116.5,40]]]")).put("distance_meters",3000).put("eta_seconds",900).put("destination",end.optString("name")).put("fetched_at","2026-10-18T01:00:00Z");
            return json(new JSONObject().put("origin",body.optJSONObject("origin")).put("destination",end).put("route",route));
        }
        if(path.equals("/v1/maps/snapshot")){maps.incrementAndGet();return new MockResponse().setHeader("Content-Type","image/png").setBody(new okio.Buffer().write(png));}
        if(path.equals("/v1/maps/weather-at")){
            weather.incrementAndGet();String name=new JSONObject(r.getBody().readUtf8()).optString("name");
            MockResponse response=json(new JSONObject().put("available",true).put("city","北京").put("weather","晴").put("temperature",name.equals("新目的地")?"26":"23").put("humidity","50").put("wind_direction","北").put("wind_power","2").put("report_time","2026-10-18 10:00:00"));
            return name.equals("慢响应目的地")?response.setBodyDelay(1500,TimeUnit.MILLISECONDS):response;
        }
        if(!r.getMethod().equals("GET"))writes.incrementAndGet();failure.set("Unexpected "+r.getMethod()+" "+path);return new MockResponse().setResponseCode(500);
    }
    private void quiet()throws Exception{((ForegroundLocationMonitor)field(activity,"locationMonitor")).close();Handler h=(Handler)field(activity,"handler");for(String name:new String[]{"automaticHeadsetTick","startupLocationTick","workMeetingPoll"})h.removeCallbacks((Runnable)field(activity,name));}
    private void select(int i)throws Exception{((LinearLayout)field(activity,"workSectionTabs")).getChildAt(i).performClick();}
    private void onlySection(int selected)throws Exception{LinearLayout[] s=(LinearLayout[])field(activity,"workSections");for(int i=0;i<3;i++)check(s[i].getVisibility()==(i==selected?View.VISIBLE:View.GONE),"Wrong visible work section");check(((LinearLayout)field(activity,"workSectionTabs")).getChildAt(selected).isSelected(),"Selected tab not emphasized");}
    private static CheckBox findCheck(View v){if(v instanceof CheckBox)return (CheckBox)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){CheckBox c=findCheck(((ViewGroup)v).getChildAt(i));if(c!=null)return c;}return null;}
    private static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=find(((ViewGroup)v).getChildAt(i),text);if(child!=null)return child;}return null;}
    private static MockResponse json(JSONObject v){return new MockResponse().setHeader("Content-Type","application/json").setBody(v.toString());}
    private interface Checked{void run()throws Exception;}private interface Condition{boolean get()throws Exception;}
    private void main(Checked a)throws Exception{AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{a.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new RuntimeException(error.get());}
    private void waitUntil(Condition c,long ms,String msg)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(c.get())return;SystemClock.sleep(50);}throw new AssertionError(msg);}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object invoke(Object o,String name,Object...args)throws Exception{Class<?>[] types=new Class<?>[args.length];for(int i=0;i<types.length;i++)types[i]=args[i] instanceof Integer?int.class:args[i].getClass();Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    private void capture(String name)throws Exception{main(()->{View view=activity.getWindow().getDecorView();Bitmap b=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();});}
}
