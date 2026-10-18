package com.aidol.demo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeakerNamesTest {
    private static SpeakerNames names() throws Exception {
        return new SpeakerNames(new JSONObject().put("speaker_names",
            new JSONObject().put("说话人1", "张三").put("说话人12", "王五").put("说话人3", "  ")));
    }

    @Test public void displayFallsBackToTheNumberedLabel() throws Exception {
        SpeakerNames names = names();
        assertEquals("张三", names.display("说话人1"));
        assertEquals("说话人 2", names.display("说话人2"));
        assertEquals("说话人 3", names.display("说话人3")); // A blank name is no name.
        assertEquals("发言", names.display(""));
        assertEquals("", names.custom("说话人2"));
        assertTrue(new SpeakerNames(new JSONObject()).isEmpty());
        assertTrue(SpeakerNames.NONE.isEmpty());
    }

    @Test public void applyMatchesWholeNumbersInEitherSpelling() throws Exception {
        SpeakerNames names = names();
        assertEquals("张三和王五都同意，说话人 2反对", names.apply("说话人 1和说话人12都同意，说话人 2反对"));
        assertEquals("张三负责", names.apply("发言人1负责"));
        assertEquals("说话人10没有名字", names.apply("说话人10没有名字"));
        assertEquals("$钱 张三", new SpeakerNames(new JSONObject().put("speaker_names",
            new JSONObject().put("说话人1", "张三"))).apply("$钱 说话人1"));
    }

    @Test public void reportCopyNamesTextButKeepsCitations() throws Exception {
        JSONObject report = new JSONObject().put("summary", "说话人1提出方案")
            .put("viewpoints", new JSONArray().put(new JSONObject().put("person", "说话人1").put("text", "支持")
                .put("source_ids", new JSONArray().put("说话人1"))))
            .put("meeting_id", "说话人1");
        JSONObject named = names().report(report);
        assertEquals("张三提出方案", named.getString("summary"));
        assertEquals("张三", named.getJSONArray("viewpoints").getJSONObject(0).getString("person"));
        assertEquals("说话人1", named.getJSONArray("viewpoints").getJSONObject(0).getJSONArray("source_ids").getString(0));
        assertEquals("说话人1", named.getString("meeting_id"));
        assertEquals("说话人1提出方案", report.getString("summary"));
        assertSame(report, SpeakerNames.NONE.report(report));
    }

    @Test public void paragraphHeaderUsesTheName() throws Exception {
        assertEquals("张三  00:00:41", new SpeakerParagraphs.Paragraph("说话人1", 41000).header(names()));
        assertEquals("说话人 1  00:00:41", new SpeakerParagraphs.Paragraph("说话人1", 41000).header());
    }
}
