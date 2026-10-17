package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionListTest {
    private static SpeakerParagraphs.Sentence s(String speaker, long at, String text) {
        return new SpeakerParagraphs.Sentence(speaker + at, speaker, at, text);
    }

    @Test public void firstFinalsAppendParagraphViewsInsteadOfRebuilding() {
        CaptionList list = new CaptionList(200);
        CaptionList.Diff diff = list.append(List.of(s("说话人1", 0, "大家好。"), s("说话人1", 2000, "今天讨论发布。")));
        assertFalse(diff.rebuild);
        assertEquals(0, diff.dropLeading);
        assertEquals(1, diff.appended.size());
        assertEquals("说话人 1  00:00:00", diff.appended.get(0).header);
        assertEquals("大家好。今天讨论发布。", diff.appended.get(0).text);
        assertEquals("大家好。今天讨论发布。", diff.lastText);
    }

    @Test public void sameSpeakerContinuationOnlyExtendsTheLastParagraph() {
        CaptionList list = new CaptionList(200);
        list.append(List.of(s("说话人1", 0, "大家好。")));
        CaptionList.Diff diff = list.append(List.of(s("说话人1", 2000, "今天讨论发布。")));
        assertFalse(diff.rebuild);
        assertTrue(diff.appended.isEmpty());
        assertEquals(0, diff.dropLeading);
        assertEquals("今天讨论发布。", diff.appendToLast);
        assertEquals("大家好。今天讨论发布。", diff.lastText);
    }

    @Test public void speakerChangeAndLongMonologueStartNewParagraphs() {
        String chunk = "很长的一段话".repeat(30);  // 180 chars, as in SpeakerParagraphsTest
        CaptionList list = new CaptionList(200);
        CaptionList.Diff change = list.append(List.of(s("说话人1", 0, "大家好。"), s("说话人2", 4000, "我建议推迟。")));
        assertEquals(2, change.appended.size());
        assertEquals("说话人 2  00:00:04", change.appended.get(1).header);

        list.append(List.of(s("说话人2", 6000, chunk)));  // joins the speaker-2 paragraph: 186 chars
        CaptionList.Diff overflow = list.append(List.of(s("说话人2", 9000, chunk), s("说话人2", 12000, "句尾。")));
        assertEquals(1, overflow.appended.size());
        assertEquals(chunk + "句尾。", overflow.appended.get(0).text);
        assertEquals("", overflow.appendToLast);
    }

    @Test public void partialUpdatesOnlyTheInterimTextOfTheLastParagraph() {
        CaptionList list = new CaptionList(200);
        list.append(List.of(s("说话人1", 0, "大家好。")));
        CaptionList.Diff diff = list.partial("正在识别的话");
        assertFalse(diff.rebuild);
        assertTrue(diff.appended.isEmpty());
        assertEquals("", diff.appendToLast);
        assertEquals("大家好。", diff.lastText);
        assertEquals("正在识别的话", diff.partial);
        assertFalse(diff.partialParagraph);
        CaptionList.Diff gone = list.partial("");
        assertEquals("", gone.partial);
        assertFalse(gone.partialParagraph);
    }

    @Test public void partialBeforeAnyFinalRendersAsItsOwnParagraph() {
        CaptionList list = new CaptionList(200);
        CaptionList.Diff diff = list.partial("开头几个字");
        assertTrue(diff.partialParagraph);
        assertEquals("", diff.lastText);
    }

    @Test public void emptyFinalsChangeNothing() {
        CaptionList list = new CaptionList(200);
        list.append(List.of(s("说话人1", 0, "大家好。")));
        CaptionList.Diff diff = list.append(List.of(s("说话人1", 1000, "")));
        assertFalse(diff.rebuild);
        assertTrue(diff.appended.isEmpty());
        assertEquals("", diff.appendToLast);
        assertEquals(1, list.totalSentences());
    }

    @Test public void slidingWindowDropsLeadingParagraphsAndKeepsTheNewest() {
        CaptionList list = new CaptionList(4);
        CaptionList.Diff seed = list.append(List.of(
            s("a", 0, "一"), s("b", 1000, "二"), s("a", 2000, "三"), s("b", 3000, "四")));
        assertEquals(4, seed.appended.size());
        CaptionList.Diff slide = list.append(List.of(s("a", 4000, "五")));
        assertFalse(slide.rebuild);
        assertEquals(1, slide.dropLeading);
        assertEquals(1, slide.appended.size());
        assertEquals("五", slide.appended.get(0).text);
        assertEquals(4, list.visibleSentences());
        assertEquals(1, list.earlierSentences());
        assertTrue(list.hasEarlier());
    }

    @Test public void windowSlidesOnParagraphBoundariesSoRemainingViewsStayValid() {
        String chunk = "很长的一段话".repeat(30);  // 180 chars per sentence
        CaptionList list = new CaptionList(3);
        list.append(List.of(s("a", 0, chunk)));
        list.append(List.of(s("a", 1000, chunk)));      // 360 > 320: a second paragraph
        CaptionList.Diff join = list.append(List.of(s("a", 2000, "尾巴。")));
        assertTrue(join.appended.isEmpty());
        assertEquals("尾巴。", join.appendToLast);
        assertEquals(chunk + "尾巴。", join.lastText);
        assertEquals(0, join.dropLeading);

        CaptionList.Diff slide = list.append(List.of(s("b", 3000, "新话")));
        assertEquals(1, slide.dropLeading);              // whole leading paragraph, never a partial one
        assertEquals(3, list.visibleSentences());
        assertEquals(1, list.earlierSentences());
        // The surviving paragraph kept its committed text: the suffix view needs no rewrite.
        CaptionList.Diff after = list.partial("还在说");
        assertEquals("新话", after.lastText);
        CaptionList.Diff whole = list.snapshot();
        assertEquals(chunk + "尾巴。", whole.blocks.get(0).text);
    }

    @Test public void appendingMoreThanAWindowAtOnceFallsBackToOneRebuild() {
        CaptionList list = new CaptionList(3);
        CaptionList.Diff diff = list.append(List.of(
            s("a", 0, "一"), s("b", 1000, "二"), s("a", 2000, "三"), s("b", 3000, "四"), s("a", 4000, "五")));
        assertTrue(diff.rebuild);
        assertTrue(list.visibleSentences() <= 3);
        assertEquals(2, list.earlierSentences());
    }

    @Test public void resetAndSnapshotRebuildEverythingVisible() {
        CaptionList list = new CaptionList(200);
        list.append(List.of(s("a", 0, "第一句。")));
        list.partial("识别中");
        CaptionList.Diff reset = list.reset();
        assertTrue(reset.rebuild);
        assertTrue(reset.blocks.isEmpty());
        assertEquals("", reset.partial);
        assertEquals(0, list.visibleSentences());
        assertEquals(0, list.totalSentences());

        list.append(List.of(s("a", 0, "新的一句。")));
        CaptionList.Diff snapshot = list.snapshot();
        assertTrue(snapshot.rebuild);
        assertEquals(1, snapshot.blocks.size());
        assertEquals("新的一句。", snapshot.blocks.get(0).text);
        assertEquals("新的一句。", snapshot.lastText);
    }

    @Test public void loadEarlierGrowsTheWindowBackwards() {
        CaptionList list = new CaptionList(3);
        list.append(List.of(s("a", 0, "一"), s("b", 1000, "二"), s("a", 2000, "三"), s("b", 3000, "四"), s("a", 4000, "五")));
        assertTrue(list.hasEarlier());
        CaptionList.Diff earlier = list.loadEarlier();
        assertTrue(earlier.rebuild);
        assertEquals(5, earlier.blocks.size());
        assertEquals(0, list.earlierSentences());
        assertFalse(list.hasEarlier());
    }

    @Test public void longRecordingStaysIncrementalAndTheWindowStaysBounded() {
        CaptionList list = new CaptionList(200);
        int createdViews = 0, droppedViews = 0;
        for (int i = 0; i < 1000; i++) {
            String speaker = i % 3 == 0 ? "说话人2" : "说话人1";
            CaptionList.Diff diff = list.append(List.of(s(speaker, i * 3000L, "第" + i + "句话的内容，凑够一点长度。")));
            assertFalse("live appends must never rebuild", diff.rebuild);
            createdViews += diff.appended.size();
            droppedViews += diff.dropLeading;
            assertTrue(list.visibleSentences() <= 200);
        }
        assertTrue(list.hasEarlier());
        // A full rebuild per event would create ~200 TextViews × 1000 events; incremental rendering
        // creates one view per paragraph over the whole session and prunes the window instead.
        assertTrue(createdViews < 1000);
        CaptionList.Diff snapshot = list.snapshot();
        assertTrue(snapshot.blocks.size() <= 200);
        assertTrue(droppedViews > 0);
    }

    @Test public void incrementalBlocksAlwaysMatchSpeakerParagraphsOverTheVisibleRange() {
        CaptionList list = new CaptionList(37);
        java.util.Random random = new java.util.Random(7);
        List<SpeakerParagraphs.Sentence> everything = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String speaker = random.nextBoolean() ? "说话人1" : "说话人2";
            SpeakerParagraphs.Sentence sentence = s(speaker, i * 2600L,
                random.nextInt(40) == 0 ? "很长的一段话".repeat(24) : "普通句子第" + i + "号，凑一点长度。");
            everything.add(sentence);
            list.append(List.of(sentence));
            if (random.nextInt(5) == 0) list.partial(random.nextInt(2) == 0 ? "临时识别文字" : "");
        }
        CaptionList.Diff snapshot = list.snapshot();
        List<SpeakerParagraphs.Paragraph> expected = SpeakerParagraphs.build(
            everything.subList(list.earlierSentences(), everything.size()));
        assertEquals(expected.size(), snapshot.blocks.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).header(), snapshot.blocks.get(i).header);
            StringBuilder text = new StringBuilder();
            for (SpeakerParagraphs.Sentence sentence : expected.get(i).sentences) text.append(sentence.text);
            assertEquals(text.toString(), snapshot.blocks.get(i).text);
        }
    }
}
