package com.aidol.demo;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Activity dispatch and HTTP concurrency checks, using only isolated offline records. */
public final class OperationConcurrencyInstrumentation extends Instrumentation {
    private MainActivity activity;
    private ApiClient original, fixture, otherAccount;
    private ActionTaskBoard originalTaskBoard;
    private JSONObject originalState;
    private MockWebServer server;
    private final AtomicReference<String> phase = new AtomicReference<>("parallel");
    private final AtomicReference<String> fixtureState = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger stateReads = new AtomicInteger(), writes = new AtomicInteger();
    private final CopyOnWriteArrayList<Integer> writeOrder = new CopyOnWriteArrayList<>();
    private final CountDownLatch historyEntered = new CountDownLatch(1), historyRelease = new CountDownLatch(1);
    private final CountDownLatch firstWriteEntered = new CountDownLatch(1), firstWriteRelease = new CountDownLatch(1), secondWriteEntered = new CountDownLatch(1);
    private final CountDownLatch staleReadEntered = new CountDownLatch(1), staleReadRelease = new CountDownLatch(1);
    private final CountDownLatch freshReadEntered = new CountDownLatch(1), freshReadRelease = new CountDownLatch(1);
    private final CountDownLatch accountEntered = new CountDownLatch(1), accountRelease = new CountDownLatch(1);
    private final CountDownLatch settingsEntered = new CountDownLatch(1), settingsRelease = new CountDownLatch(1);
    private final CopyOnWriteArrayList<String> settingsRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> settingsAuthorization = new AtomicReference<>();

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitUntil(() -> !(Boolean) field(activity, "busy") && ((Map<?, ?>) field(activity, "pendingOperations")).isEmpty(), 15000, "Initial requests did not settle");
            check(!getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked(), "Unlock phone before review");
            check(field(activity, "session") == null, "Existing audio session active");
            original = (ApiClient) field(activity, "api"); originalState = (JSONObject) field(activity, "state");
            check(!CommuteService.running(original), "Existing commute active");
            Context isolated = isolatedContext("operation_concurrency_review_");
            fixture = new ApiClient(isolated, ServerEndpoint.USB_LOCAL);
            fixture.vault.save("offline-concurrency-account-one", "并发测试");
            otherAccount = new ApiClient(isolatedContext("operation_concurrency_review_other_"), ServerEndpoint.USB_LOCAL);
            otherAccount.vault.save("offline-concurrency-account-two", "第二测试账号");
            fixtureState.set(state(1).toString());
            server = new MockWebServer();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try { return respond(request); }
                    catch (Throwable e) { failure.compareAndSet(null, e); return new MockResponse().setResponseCode(500); }
                }
            });
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
            set(fixture, "serverUrl", "http://127.0.0.1:" + server.getPort());
            set(otherAccount, "serverUrl", "http://127.0.0.1:" + server.getPort());
            main(() -> {
                ((CommutePanel) field(activity, "commutePanel")).reset();
                ((QuickFeedback) field(activity, "quickFeedback")).close();
                invoke(activity, "clearVoicePreparation");
                ((ForegroundLocationMonitor) field(activity, "locationMonitor")).close();
                set(activity, "locationMonitor", new ForegroundLocationMonitor(isolated, new ForegroundLocationMonitor.Listener() {
                    public void status(String message) {} public void updated() {}
                }));
                set(activity, "api", fixture); set(activity, "state", state(1));
                // ActionTaskBoard schedules reminders through the real Activity context.
                // These dispatch tests avoid that board so even local notification data stays untouched.
                originalTaskBoard = (ActionTaskBoard) field(activity, "taskBoard"); set(activity, "taskBoard", null);
                set(activity, "tab", 1);
                set(activity, "startupLocationAttempted", true); set(activity, "lastNotice", "");
                invoke(activity, "showApp"); quiet();
            });
            independentRequestsAndPageChange();
            sameResourcePreservesOrder();
            staleStateCannotUndoMutation();
            pageChangeKeepsUnstartedAudioSession();
            oldAccountCannotDeliverOrStartQueuedWork();
            settingsCompatibilityKeepsIndependentRequestsAvailable();
            check(failure.get() == null, "Fixture failure: " + failure.get());
            check(getTargetContext().getSystemService(android.media.AudioManager.class).getActiveRecordingConfigurations().isEmpty(), "Unexpected recording");
            result.putString("stream", "PASS: blocked history request allows task PATCH, state refresh and page navigation; ordinary actions remain enabled; original history completes without cancellation or page jump; same-resource writes remain FIFO while another resource progresses; stale state cannot overwrite completed edits; page changes retain an unstarted audio session while ordinary actions remain available and a second audio action stays blocked; old-account running callbacks and queued jobs are ignored; real HTTP settings PATCH stays partial and allows independent tasks, legacy 405 falls back to fresh GET/merged PUT preserving preferences and retention with fixed renewed credentials; isolated offline fixtures only, no recording, reminder or commute writes.");
            code = Activity.RESULT_OK;
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(e));
        } finally {
            historyRelease.countDown(); firstWriteRelease.countDown(); staleReadRelease.countDown(); freshReadRelease.countDown(); accountRelease.countDown();
            settingsRelease.countDown();
            if (activity != null) try {
                main(() -> {
                    quiet(); ((CommutePanel) field(activity, "commutePanel")).close();
                    if (field(activity, "session") != null) invoke(activity, "cancelSession", "");
                    if (originalTaskBoard != null) set(activity, "taskBoard", originalTaskBoard);
                    if (original != null) { set(activity, "api", original); set(activity, "state", originalState); }
                    activity.finish();
                });
            } catch (Exception ignored) {}
            if (fixture != null) fixture.vault.clear();
            if (otherAccount != null) otherAccount.vault.clear();
            if (server != null) try { server.shutdown(); } catch (Exception ignored) {}
        }
        finish(code, result);
    }

    private void independentRequestsAndPageChange() throws Exception {
        AtomicInteger historyDone = new AtomicInteger(), patchDone = new AtomicInteger();
        main(() -> request("history", client -> client.json("POST", "/v1/meetings", new JSONObject().put("hours", 24)), value -> {
            invoke(activity, "applyHistoryMeetingReport", value); historyDone.incrementAndGet();
        }));
        await(historyEntered, "History request did not start");
        main(() -> {
            check((Boolean) invoke(activity, "canAct"), "Slow request blocks unrelated actions");
            request("task:parallel", client -> client.json("PATCH", "/v1/tasks/parallel", new JSONObject().put("status", "done")), value -> patchDone.incrementAndGet());
            invoke(activity, "refresh", false);
            invoke(activity, "switchTab", 4); quiet();
        });
        waitUntil(() -> patchDone.get() == 1 && stateReads.get() > 0 && !(Boolean) field(activity, "stateRefreshing"), 5000, "Independent task or refresh blocked behind history");
        check(historyDone.get() == 0, "History fixture was not held open");
        check((Integer) field(activity, "tab") == 4, "Navigation blocked behind history");
        Object profile = field(activity, "page"), viewport = field(activity, "scroll");
        historyRelease.countDown();
        waitUntil(() -> historyDone.get() == 1, 5000, "Original history request was cancelled or never completed");
        main(() -> {
            check((Integer) field(activity, "tab") == 4 && field(activity, "page") == profile && field(activity, "scroll") == viewport, "History completion moved or rebuilt another page");
            check(((JSONObject) field(activity, "state")).optJSONObject("meeting_report") != null, "Completed history result was lost");
            quiet();
        });
        settle();
    }

    private void sameResourcePreservesOrder() throws Exception {
        phase.set("fifo"); AtomicInteger done = new AtomicInteger(), otherDone = new AtomicInteger();
        main(() -> request("task:ordered", client -> client.json("PATCH", "/v1/tasks/ordered", new JSONObject().put("sequence", 1)), value -> done.incrementAndGet()));
        await(firstWriteEntered, "First ordered write did not start");
        main(() -> {
            request("task:ordered", client -> client.json("PATCH", "/v1/tasks/ordered", new JSONObject().put("sequence", 2)), value -> done.incrementAndGet());
            request("task:other", client -> client.json("PATCH", "/v1/tasks/other", new JSONObject().put("status", "done")), value -> otherDone.incrementAndGet());
        });
        waitUntil(() -> otherDone.get() == 1, 5000, "Same-resource queue blocked a separate task");
        check(!secondWriteEntered.await(200, TimeUnit.MILLISECONDS), "Second write overtook the first write");
        check(done.get() == 0, "Ordered first response was not held open");
        firstWriteRelease.countDown();
        waitUntil(() -> done.get() == 2, 5000, "Ordered writes did not both finish");
        check(writeOrder.size() == 2 && writeOrder.get(0) == 1 && writeOrder.get(1) == 2, "Writes reached server out of order");
        settle();
    }

    private void staleStateCannotUndoMutation() throws Exception {
        phase.set("stale"); stateReads.set(0); fixtureState.set(state(1).toString());
        main(() -> { set(activity, "state", state(1)); invoke(activity, "refresh", false); });
        await(staleReadEntered, "Old state read did not start");
        AtomicInteger changed = new AtomicInteger();
        main(() -> request("task:edited", client -> client.json("PATCH", "/v1/tasks/edited", new JSONObject().put("content", "修改后的内容")), value -> {
            set(activity, "state", state(2)); changed.incrementAndGet();
        }));
        waitUntil(() -> changed.get() == 1, 5000, "Edit did not complete while old state was pending");
        staleReadRelease.countDown();
        await(freshReadEntered, "Stale read was not replaced by a fresh snapshot");
        main(() -> check(((JSONObject) field(activity, "state")).optInt("fixture_revision") == 2, "Late state response undid the completed edit"));
        freshReadRelease.countDown();
        waitUntil(() -> !(Boolean) field(activity, "stateRefreshing"), 5000, "Fresh state did not settle");
        main(() -> { check(((JSONObject) field(activity, "state")).optInt("fixture_revision") == 2, "Fresh state did not preserve edit"); quiet(); });
        settle();
    }

    private void oldAccountCannotDeliverOrStartQueuedWork() throws Exception {
        phase.set("account"); AtomicInteger callbacks = new AtomicInteger(), queuedStarts = new AtomicInteger();
        CountDownLatch oldHttpReturned = new CountDownLatch(1);
        main(() -> request("account-read", client -> {
            JSONObject value = client.json("GET", "/v1/fixture-account", null); oldHttpReturned.countDown(); return value;
        }, value -> callbacks.incrementAndGet()));
        await(accountEntered, "Old account request did not start");
        main(() -> {
            request("account-read", client -> { queuedStarts.incrementAndGet(); return client.json("GET", "/v1/fixture-account-queued", null); }, value -> callbacks.incrementAndGet());
            set(activity, "api", otherAccount);
            ((Map<?, ?>) field(activity, "pendingOperations")).clear();
            set(activity, "stateRefreshing", false); set(activity, "stateRefreshAgain", false);
            set(activity, "state", state(3)); quiet();
        });
        accountRelease.countDown(); await(oldHttpReturned, "Already-started old account HTTP request was interrupted");
        AtomicInteger currentDone = new AtomicInteger();
        // Same lane also acts as a fence: the old callback and queued request have run before this one completes.
        main(() -> request("account-read", client -> client.json("GET", "/v1/fixture-current-account", null), value -> currentDone.incrementAndGet()));
        waitUntil(() -> currentDone.get() == 1, 5000, "New account could not use the request lane");
        main(() -> {
            check(callbacks.get() == 0, "Previous account response reached current page");
            check(queuedStarts.get() == 0, "Previous account queued work executed after switch");
            check(((JSONObject) field(activity, "state")).optInt("fixture_revision") == 3, "Previous account overwrote current state");
            quiet();
        });
    }

    private void pageChangeKeepsUnstartedAudioSession() throws Exception {
        main(() -> {
            invoke(activity, "newSession"); Object session = field(activity, "session");
            check(session != null, "Unstarted audio session was not created");
            check((Boolean) invoke(activity, "canAct"), "Audio session blocks independent ordinary actions");
            check(!(Boolean) invoke(activity, "canUseAudio"), "A second audio operation was allowed over the existing session");
            invoke(activity, "switchTab", 1); quiet();
            check(field(activity, "session") == session, "Page change interrupted the original audio session");
            invoke(activity, "switchTab", 4); quiet();
            check(field(activity, "session") == session, "Returning page interrupted the original audio session");
            check(getTargetContext().getSystemService(android.media.AudioManager.class).getActiveRecordingConfigurations().isEmpty(), "Unstarted session opened the microphone");
            invoke(activity, "cancelSession", ""); quiet();
        });
        settle();
    }

    private void settingsCompatibilityKeepsIndependentRequestsAvailable() throws Exception {
        settle(); phase.set("settings"); settingsRequests.clear();
        AtomicInteger saved = new AtomicInteger(), independent = new AtomicInteger();
        main(() -> {
            otherAccount.vault.save("offline-settings-renewed-session", "第二测试账号");
            settingsAuthorization.set("Bearer " + otherAccount.vault.token());
            request("settings", client -> client.patchSettings(new JSONObject().put("mode", "work")), value -> {
                check("work".equals(value.getString("mode")), "Partial settings response was not returned"); saved.incrementAndGet();
            });
        });
        await(settingsEntered, "Real settings PATCH did not reach fixture");
        main(() -> request("task:settings-independent", client -> client.json("PATCH", "/v1/tasks/settings-independent", new JSONObject().put("status", "done")), value -> independent.incrementAndGet()));
        waitUntil(() -> independent.get() == 1, 5000, "Settings lock blocked independent ApiClient HTTP request");
        check(saved.get() == 0, "Settings response was not held open");
        check(settingsRequests.size() == 1 && "PATCH /v1/settings".equals(settingsRequests.get(0)), "Supported server did not receive exactly one settings PATCH");
        settingsRelease.countDown();
        waitUntil(() -> saved.get() == 1, 5000, "Settings PATCH did not finish after release");
        settle();
        check(settingsRequests.size() == 1, "Successful PATCH triggered an unnecessary fallback request");

        phase.set("legacy-settings"); settingsRequests.clear(); AtomicInteger legacySaved = new AtomicInteger();
        main(() -> request("settings", client -> client.patchSettings(new JSONObject().put("mode", "commute")), value -> {
            verifyPreservedSettings(value); legacySaved.incrementAndGet();
        }));
        waitUntil(() -> legacySaved.get() == 1, 5000, "Legacy settings GET/merge/PUT did not finish");
        settle();
        check(settingsRequests.size() == 3
            && "PATCH /v1/settings".equals(settingsRequests.get(0))
            && "GET /v1/state".equals(settingsRequests.get(1))
            && "PUT /v1/settings".equals(settingsRequests.get(2)), "Legacy settings fallback did not use exactly PATCH, GET, PUT in order");
    }

    private static JSONObject legacySettings() throws Exception {
        return new JSONObject().put("mode", "life").put("preferences", new JSONObject().put("language", "zh-CN").put("keep", true))
            .put("work_audio_retention_days", 90);
    }
    private static void verifyPreservedSettings(JSONObject value) throws Exception {
        check(value.length() == 3 && "commute".equals(value.getString("mode")), "Legacy PUT changed more than the requested mode");
        check(value.getInt("work_audio_retention_days") == 90, "Legacy PUT reset recording retention");
        JSONObject preferences = value.getJSONObject("preferences");
        check(preferences.length() == 2 && preferences.getBoolean("keep") && "zh-CN".equals(preferences.getString("language")), "Legacy PUT reset existing preferences");
    }

    private MockResponse respond(RecordedRequest request) throws Exception {
        String path = request.getPath(), method = request.getMethod();
        String currentPhase = phase.get();
        if ("settings".equals(currentPhase) || "legacy-settings".equals(currentPhase)) {
            check(settingsAuthorization.get().equals(request.getHeader("Authorization")), "Settings request did not retain renewed credentials");
            if ("/v1/settings".equals(path)) {
                settingsRequests.add(method + " " + path);
                JSONObject body = new JSONObject(request.getBody().readUtf8());
                if ("PATCH".equals(method)) {
                    check(body.length() == 1 && body.has("mode"), "Settings PATCH sent untouched fields");
                    if ("legacy-settings".equals(currentPhase)) {
                        check("commute".equals(body.getString("mode")), "Legacy PATCH used wrong requested mode");
                        return json(new JSONObject().put("detail", "Method Not Allowed")).setResponseCode(405);
                    }
                    check("work".equals(body.getString("mode")), "Supported PATCH used wrong requested mode");
                    settingsEntered.countDown(); hold(settingsRelease); return json(body);
                }
                check("legacy-settings".equals(currentPhase) && "PUT".equals(method), "Unexpected settings method");
                verifyPreservedSettings(body); return json(body);
            }
            if ("/v1/state".equals(path) && "legacy-settings".equals(currentPhase)) {
                settingsRequests.add(method + " " + path);
                check("GET".equals(method), "Legacy settings snapshot was not a GET");
                return json(new JSONObject().put("settings", legacySettings()));
            }
        }
        if ("/v1/meetings".equals(path) && "POST".equals(method)) {
            historyEntered.countDown(); hold(historyRelease);
            return json(new JSONObject().put("summary", "公开并发测试纪要").put("created_at", "2026-10-18T01:00:00Z"));
        }
        if ("/v1/tasks/ordered".equals(path) && "PATCH".equals(method)) {
            int sequence = new JSONObject(request.getBody().readUtf8()).getInt("sequence"); writeOrder.add(sequence);
            if (sequence == 1) { firstWriteEntered.countDown(); hold(firstWriteRelease); } else secondWriteEntered.countDown();
            return json(new JSONObject().put("sequence", sequence));
        }
        if (path.startsWith("/v1/tasks/") && "PATCH".equals(method)) {
            writes.incrementAndGet();
            if ("/v1/tasks/edited".equals(path)) fixtureState.set(state(2).toString());
            return json(new JSONObject().put("status", "done"));
        }
        if ("/v1/state".equals(path)) {
            int index = stateReads.incrementAndGet(); String captured = fixtureState.get();
            if ("stale".equals(phase.get())) {
                if (index == 1) { staleReadEntered.countDown(); hold(staleReadRelease); }
                else { freshReadEntered.countDown(); hold(freshReadRelease); }
            }
            return json(new JSONObject(captured));
        }
        if ("/v1/fixture-account".equals(path)) { accountEntered.countDown(); hold(accountRelease); return json(new JSONObject()); }
        if ("/v1/fixture-current-account".equals(path)) return json(new JSONObject());
        if ("/v1/tasks".equals(path)) return json(new JSONObject().put("tasks", new JSONArray()));
        if ("/v1/work-meetings".equals(path)) return json(new JSONObject().put("meetings", new JSONArray()));
        if ("/v1/saved-addresses".equals(path)) return json(new JSONObject().put("addresses", new JSONArray()));
        if ("/v1/commute".equals(path) && "GET".equals(method)) return json(new JSONObject());
        throw new AssertionError("Unexpected fixture request " + method + " " + path);
    }

    private Context isolatedContext(String prefix) {
        return new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences(prefix + name, mode); }
            @Override public Context getApplicationContext() { return this; }
        };
    }
    private static JSONObject state(int revision) throws Exception {
        return new JSONObject().put("fixture_revision", revision).put("settings", new JSONObject().put("mode", "work").put("auto_mode", false))
            .put("agent", new JSONObject()).put("capabilities", new JSONObject()).put("work_meetings", new JSONArray()).put("tasks", new JSONArray())
            .put("transcripts", new JSONArray()).put("memories", new JSONArray()).put("drafts", new JSONArray());
    }
    private void request(String key, Job job, Result result) throws Exception {
        Class<?> jobType = Class.forName(MainActivity.class.getName() + "$Job"), resultType = Class.forName(MainActivity.class.getName() + "$Result");
        Object jobProxy = Proxy.newProxyInstance(jobType.getClassLoader(), new Class<?>[]{jobType}, (proxy, method, args) -> job.run((ApiClient) args[0]));
        Object resultProxy = Proxy.newProxyInstance(resultType.getClassLoader(), new Class<?>[]{resultType}, (proxy, method, args) -> {
            try { result.accept((JSONObject) args[0]); } catch (Throwable e) { failure.compareAndSet(null, e); throw e; }
            return null;
        });
        Method dispatch = MainActivity.class.getDeclaredMethod("request", String.class, jobType, resultType); dispatch.setAccessible(true); dispatch.invoke(activity, key, jobProxy, resultProxy);
    }
    private void settle() throws Exception {
        waitUntil(() -> ((Map<?, ?>) field(activity, "pendingOperations")).isEmpty(), 5000, "Request bookkeeping did not settle");
        main(this::quiet); check(failure.get() == null, "Async fixture failure: " + failure.get());
    }
    private void quiet() throws Exception {
        ((ForegroundLocationMonitor) field(activity, "locationMonitor")).close();
        Handler handler = (Handler) field(activity, "handler");
        for (String name : new String[]{"automaticHeadsetTick", "startupLocationTick", "workMeetingPoll"}) handler.removeCallbacks((Runnable) field(activity, name));
    }
    private static MockResponse json(JSONObject value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString()); }
    private static void hold(CountDownLatch latch) throws Exception { check(latch.await(15, TimeUnit.SECONDS), "Fixture response release timed out"); }
    private static void await(CountDownLatch latch, String message) throws Exception { check(latch.await(5, TimeUnit.SECONDS), message); }
    private interface Job { JSONObject run(ApiClient client) throws Exception; }
    private interface Result { void accept(JSONObject value) throws Exception; }
    private interface Checked { void run() throws Exception; }
    private interface Condition { boolean get() throws Exception; }
    private void main(Checked operation) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { operation.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new RuntimeException(error.get());
    }
    private void waitUntil(Condition condition, long ms, String message) throws Exception {
        long until = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < until) { if (condition.get()) return; SystemClock.sleep(40); }
        throw new AssertionError(message);
    }
    private static Object field(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static Object invoke(Object object, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < types.length; i++) types[i] = args[i] instanceof Integer ? int.class : args[i] instanceof Boolean ? boolean.class : args[i].getClass();
        Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
