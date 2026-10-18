package com.aidol.demo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Names the user gave one meeting's speaker labels ("说话人1" -> "张三"). Saved sentences and minutes
 * keep the labels; this only changes what is shown and exported, so a rename needs no regeneration.
 */
final class SpeakerNames {
    static final SpeakerNames NONE = new SpeakerNames(null);
    // Minutes may write "说话人 2" or the provider's "发言人2"; both mean the stored label "说话人2".
    private static final Pattern LABEL = Pattern.compile("(?:说话人|发言人) ?(\\d+)(?!\\d)");
    private final Map<String, String> names = new HashMap<>();

    SpeakerNames(JSONObject meeting) {
        JSONObject map = meeting == null ? null : meeting.optJSONObject("speaker_names");
        if (map == null) return;
        for (Iterator<String> keys = map.keys(); keys.hasNext(); ) {
            String label = keys.next(), name = map.optString(label).trim();
            if (!name.isEmpty()) names.put(label, name);
        }
    }

    boolean isEmpty() { return names.isEmpty(); }

    /** The user's name for a label, or "" when it has none. */
    String custom(String label) { String name = names.get(label); return name == null ? "" : name; }

    /** What to show for a label: the user's name, else "说话人 1"; unlabelled speech is "发言". */
    String display(String label) { String name = names.get(label); return name != null ? name : SpeakerParagraphs.name(label); }

    /** "说话人 2 建议推迟" -> "李四 建议推迟"; labels without a name stay as written. */
    String apply(String text) {
        if (names.isEmpty() || text == null || text.isEmpty()) return text;
        Matcher matcher = LABEL.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = names.get("说话人" + matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(name == null ? matcher.group() : name));
        }
        return matcher.appendTail(out).toString();
    }

    /** A copy of the minutes with names in every text field; citations and ids stay untouched. */
    JSONObject report(JSONObject report) {
        if (report == null || names.isEmpty()) return report;
        try { return (JSONObject) named(new JSONObject(report.toString()), ""); }
        catch (JSONException e) { return report; }
    }

    private Object named(Object value, String key) throws JSONException {
        if (value instanceof String) return key.equals("source_ids") || key.equals("id") || key.endsWith("_id") ? value : apply((String) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) array.put(i, named(array.get(i), key));
            return array;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String name = keys.next();
                object.put(name, named(object.get(name), name));
            }
            return object;
        }
        return value;
    }
}
