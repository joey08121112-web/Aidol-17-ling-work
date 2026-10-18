package com.aidol.demo;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import okhttp3.Call;
import org.json.*;

/** Authenticated official map images, route previews and destination weather; no provider key in APK. */
final class CommuteMapPanel implements AutoCloseable {
    private final Activity activity;
    private final Supplier<ApiClient> client;
    private final BooleanSupplier visible;
    private final Supplier<ScrollView> viewport;
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImageView image;
    private TextView mapStatus, weatherTitle, weatherMain, weatherDetail;
    private LinearLayout mapCard;
    private Bitmap bitmap;
    private Dialog enlarged;
    private Task planTask, mapTask, weatherTask;
    private JSONObject plan;
    private String owner = "", planKey = "", mapKey = "", weatherKey = "";
    private long planAfter, mapAfter, weatherAfter;
    private volatile boolean foreground;
    private boolean closed;
    private static final class Task {
        final ApiClient api; final String token;
        volatile boolean cancelled; volatile Call call;
        Task(ApiClient api) { this.api = api; token = api.vault.token(); }
        void cancel() { cancelled = true; if (call != null) call.cancel(); }
        byte[] bytes(String path, JSONObject body) throws Exception {
            if (cancelled) throw new InterruptedException();
            Call next = api.call("POST", path, body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json");
            call = next; next.timeout().timeout(40, TimeUnit.SECONDS);
            if (cancelled) { next.cancel(); throw new InterruptedException(); }
            return api.execute(next);
        }
        JSONObject json(String path, JSONObject body) throws Exception { return new JSONObject(new String(bytes(path, body), java.nio.charset.StandardCharsets.UTF_8)); }
    }
    CommuteMapPanel(Activity activity, Supplier<ApiClient> client, BooleanSupplier visible, Supplier<ScrollView> viewport) {
        this.activity = activity; this.client = client; this.visible = visible; this.viewport = viewport;
    }
    private int dp(int value) { return EditorUi.dp(activity, value); }
    private TextView label(LinearLayout parent, String text, int size, int color) {
        TextView view = EditorUi.text(activity, text, size, color); parent.addView(view); return view;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = EditorUi.column(activity); card.setPadding(dp(14),dp(14),dp(14),dp(14));
        card.setBackground(EditorUi.shape(activity,0xffffffff,22,EditorUi.LINE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(7),0,dp(7)); parent.addView(card,p); return card;
    }
    void renderMap(LinearLayout parent) {
        mapCard = card(parent);
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL); mapCard.addView(header);
        TextView title = EditorUi.text(activity,"路线地图",17,EditorUi.INK); title.setTypeface(null,android.graphics.Typeface.BOLD); header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView refresh = EditorUi.button(activity,"刷新",false,() -> { planAfter=mapAfter=weatherAfter=0; });
        refresh.setTextSize(12); refresh.setPadding(dp(10),dp(6),dp(10),dp(6)); refresh.setMinHeight(dp(40)); header.addView(refresh);
        image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(EditorUi.shape(activity,0xffedf2f7,16,0xffedf2f7)); image.setClipToOutline(true);
        image.setContentDescription("高德地图：起点、终点与路线全景，点击放大");
        mapCard.addView(image,new LinearLayout.LayoutParams(-1,dp(220))); image.setOnClickListener(v -> enlarge());
        mapStatus=label(mapCard,"正在获取当前位置…",12,EditorUi.MUTED);
        label(mapCard,"高德地图 · 起 / 终标记 · 点击地图放大",11,EditorUi.MUTED);
        if(bitmap!=null)image.setImageBitmap(bitmap);
        planAfter=0; mapAfter=0;
    }
    void renderWeather(LinearLayout parent) {
        LinearLayout card=card(parent);
        weatherTitle=label(card,"目的地天气",17,EditorUi.INK); weatherTitle.setTypeface(null,android.graphics.Typeface.BOLD);
        weatherMain=label(card,"选择目的地后自动显示",24,EditorUi.ACCENT);
        weatherDetail=label(card,"",12,EditorUi.MUTED); weatherAfter=0;
    }
    private void enlarge() {
        if(bitmap==null || enlarged!=null)return;
        LinearLayout body=EditorUi.column(activity), footer=EditorUi.column(activity);
        ImageView full=new ImageView(activity); full.setImageBitmap(bitmap); full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(full,new LinearLayout.LayoutParams(-1,dp(300)));
        TextView note=EditorUi.text(activity,mapStatus.getText().toString(),13,EditorUi.MUTED);body.addView(note);
        Dialog dialog=EditorUi.dialog(activity,"路线全景",body,footer); enlarged=dialog;
        footer.addView(EditorUi.button(activity,"完成",true,dialog::dismiss));
        ScrollView page=viewport.get(); int y=page==null?0:page.getScrollY();
        View pageContent=page==null||page.getChildCount()==0?null:page.getChildAt(0);
        dialog.setOnDismissListener(d -> {
            enlarged=null;
            if(page!=null && page==viewport.get() && page.isAttachedToWindow()) {
                page.clearFocus();
                page.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
                    public boolean onPreDraw(){if(page.getViewTreeObserver().isAlive())page.getViewTreeObserver().removeOnPreDrawListener(this);if(page==viewport.get()&&page.getChildCount()>0&&page.getChildAt(0)==pageContent)page.scrollTo(0,y);return true;}
                });page.requestLayout();
            }
        });dialog.show();
    }
    private boolean current(Task task) { return !closed && !task.cancelled && task.api==client.get() && task.token.equals(task.api.vault.token()); }
    private static void cancel(Task task) { if(task!=null)task.cancel(); }
    private static JSONObject copy(JSONObject object) { try{return object==null?null:new JSONObject(object.toString());}catch(Exception ignored){return null;} }
    private static void put(JSONObject object,String name,Object value) { try{object.put(name,value);}catch(Exception ignored){} }
    private static String pointKey(JSONObject point) { return point==null?"":point.optString("longitude")+","+point.optString("latitude")+":"+point.optString("city")+":"+point.optString("adcode")+":"+point.optString("name"); }
    JSONObject route() { return plan==null?null:plan.optJSONObject("route"); }
    void update(String originMode, JSONObject origin, JSONObject destination, String transport, JSONObject journey) {
        if(closed || mapCard==null)return;
        foreground=visible.getAsBoolean() && mapCard.isAttachedToWindow() && mapCard.isShown();
        if(!foreground){pause();return;}
        ApiClient api=client.get(); if(api==null || api.vault.token().isEmpty())return;
        String session=CommuteService.owner(api);
        if(!session.equals(owner)){reset();owner=session;foreground=true;}
        long now=SystemClock.elapsedRealtime();
        String selected=originMode+"|"+pointKey(origin)+"|"+pointKey(destination)+"|"+transport;
        JSONObject activeRoute=journey.optBoolean("active")?journey.optJSONObject("route"):null;
        boolean active=journey.optBoolean("active");
        String key=selected+"|"+active+(activeRoute==null?"":activeRoute.toString());
        if(!key.equals(planKey)) {
            cancel(planTask);planTask=null;cancel(mapTask);mapTask=null;
            planKey=key;mapKey="";plan=null;bitmap=null;image.setImageDrawable(null);planAfter=0;
            mapStatus.setText("正在更新地图…");
            if(!active && (destination!=null || "fixed".equals(originMode) && origin!=null)) {
                JSONObject pins=new JSONObject();put(pins,"origin","fixed".equals(originMode)?origin:null);put(pins,"destination",destination);requestMap(pins,now);
            }
        }
        if(active) {
            JSONObject next=new JSONObject();
            JSONObject start=activeRoute==null?null:activeRoute.optJSONObject("origin");
            put(next,"origin",start==null && "fixed".equals(originMode)?origin:start);
            put(next,"destination",destination);put(next,"route",activeRoute);plan=next;
            requestMap(next,now);
        } else if(planTask==null && now>=planAfter) {
            requestPlan(originMode,copy(origin),copy(destination),transport);
        } else if(plan!=null)requestMap(plan,now);
        updateWeather(copy(destination),now);
    }
    private void requestPlan(String originMode,JSONObject origin,JSONObject destination,String transport) {
        Task task=new Task(client.get());planTask=task;planAfter=SystemClock.elapsedRealtime()+180_000;
        if(plan==null)mapStatus.setText("gps".equals(originMode)?"正在获取当前位置并规划路线…":"正在规划路线…");
        network.execute(() -> {
            try {
                JSONObject body=new JSONObject().put("origin_mode",originMode).put("transport",transport);
                if(destination!=null)body.put("destination",destination);
                if("fixed".equals(originMode)) { if(origin!=null)body.put("origin",origin); }
                else {
                    JSONObject fix=MeetingStartLocation.capture(activity,task.api,() -> foreground && !task.cancelled);
                    LocationSnapshot snapshot=fix==null?null:LocationSnapshot.parse(fix.toString());
                    if(snapshot!=null && snapshot.usable(System.currentTimeMillis(),120_000))body.put("location",snapshot.json(false));
                }
                JSONObject result=task.json("/v1/commute/preview",body);
                ui.post(() -> {if(!current(task)||planTask!=task)return;planTask=null;plan=result;requestMap(result,SystemClock.elapsedRealtime());});
            } catch(Exception error) { ui.post(() -> {if(!current(task)||planTask!=task)return;planTask=null;planAfter=SystemClock.elapsedRealtime()+30_000;mapStatus.setText("路线预览暂不可用，点击刷新重试");}); }
        });
    }
    private void requestMap(JSONObject value,long now) {
        JSONObject start=value.optJSONObject("origin"),end=value.optJSONObject("destination"),route=value.optJSONObject("route");
        JSONObject body=new JSONObject();put(body,"origin",start);put(body,"destination",end);
        boolean routeReady=route!=null && route.optBoolean("available");
        put(body,"paths",routeReady && route.optJSONArray("geometry")!=null?route.optJSONArray("geometry"):new JSONArray());
        if(start==null && end==null) {mapStatus.setText("尚未取得近期定位，请选择地址或点击刷新");return;}
        String key=body.toString();
        if(!key.equals(mapKey)){cancel(mapTask);mapTask=null;mapKey=key;bitmap=null;image.setImageDrawable(null);mapAfter=0;}
        if(mapTask!=null || now<mapAfter)return;
        Task task=new Task(client.get());mapTask=task;mapAfter=now+180_000;
        String message=routeReady?(route.optBoolean("geometry_partial")?"路线全景 · 部分路段暂时无法在地图上画出":body.optJSONArray("paths").length()==0?"已规划路线，暂时无法在地图上画出":"路线全景 · 优先展示可用方案中较短路线"):
            start==null?"目的地位置 · 等待近期起点定位":end==null?"起点位置 · 请选择目的地":"起终点位置 · "+(route==null?"正在规划路线":route.optString("reason","暂未取得路线"));
        network.execute(() -> {
            try {
                byte[] data=task.bytes("/v1/maps/snapshot",body);
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                if(data.length>4*1024*1024 || bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>2048 || bounds.outHeight>2048)throw new IllegalArgumentException();
                Bitmap result=BitmapFactory.decodeByteArray(data,0,data.length);if(result==null)throw new IllegalArgumentException();
                ui.post(() -> {if(!current(task)||mapTask!=task){result.recycle();return;}mapTask=null;bitmap=result;image.setImageBitmap(result);mapStatus.setText(message);});
            } catch(Exception error) {ui.post(() -> {if(!current(task)||mapTask!=task)return;mapTask=null;mapAfter=SystemClock.elapsedRealtime()+30_000;mapStatus.setText("地图暂不可用，点击刷新重试");});}
        });
    }
    private void updateWeather(JSONObject destination,long now) {
        if(weatherTitle==null)return;
        String key=pointKey(destination);
        if(!key.equals(weatherKey)) {cancel(weatherTask);weatherTask=null;weatherKey=key;weatherAfter=0;weatherMain.setText(destination==null?"选择目的地后自动显示":"正在查询…");weatherDetail.setText("");}
        weatherTitle.setText(destination==null?"目的地天气":destination.optString("name","目的地")+" · 天气");
        if(destination==null || weatherTask!=null || now<weatherAfter)return;
        Task task=new Task(client.get());weatherTask=task;weatherAfter=now+600_000;
        network.execute(() -> {
            try {
                JSONObject value=task.json("/v1/maps/weather-at",destination);
                ui.post(() -> {
                    if(!current(task)||weatherTask!=task)return;weatherTask=null;
                    if(!value.optBoolean("available")){weatherMain.setText("暂无天气信息");weatherDetail.setText(value.optString("reason"));weatherAfter=SystemClock.elapsedRealtime()+60_000;return;}
                    weatherMain.setText(value.optString("weather")+"  "+value.optString("temperature")+"°C");
                    weatherDetail.setText(value.optString("city")+" · "+value.optString("wind_direction")+"风 "+value.optString("wind_power")+"级 · 湿度 "+value.optString("humidity")+"%\n高德实况 · "+value.optString("report_time"));
                });
            } catch(Exception error) {ui.post(() -> {if(!current(task)||weatherTask!=task)return;weatherTask=null;weatherAfter=SystemClock.elapsedRealtime()+30_000;weatherMain.setText("天气暂不可用");weatherDetail.setText("稍后自动重试，也可点击地图旁刷新");});}
        });
    }
    void pause() {foreground=false;cancel(planTask);cancel(mapTask);cancel(weatherTask);planTask=mapTask=weatherTask=null;planAfter=mapAfter=weatherAfter=0;}
    void reset() {pause();owner=planKey=mapKey=weatherKey="";plan=null;bitmap=null;if(image!=null)image.setImageDrawable(null);if(enlarged!=null)enlarged.dismiss();if(weatherTitle!=null){weatherTitle.setText("目的地天气");weatherMain.setText("选择目的地后自动显示");weatherDetail.setText("");}}
    @Override public void close(){closed=true;reset();network.shutdownNow();}
}
