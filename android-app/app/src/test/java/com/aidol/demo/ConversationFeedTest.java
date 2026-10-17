package com.aidol.demo;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationFeedTest {
    @Test public void partialSnapshotsUpdateOneTurnAndFinalResultReplacesThem() {
        ConversationFeed feed = new ConversationFeed();
        assertTrue(feed.transcript("今天"));
        assertTrue(feed.transcript("今天怎么样"));
        assertFalse(feed.transcript("今天怎么样"));
        feed.reply("我"); feed.reply("我很好");
        assertEquals("今天怎么样", feed.user());
        assertEquals("我很好", feed.reply());
        String time = "2026-10-17T08:03:05+00:00";
        List<ConversationFeed.Line> lines = feed.finish("今天怎么样？", "我很好。", time, true);
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).assistant); assertTrue(lines.get(1).assistant);
        assertEquals("今天怎么样？", lines.get(0).text);
        assertEquals("我很好。", lines.get(1).text);
        assertEquals(time, lines.get(0).time); assertEquals(time, lines.get(1).time);
        assertEquals("", feed.user()); assertEquals("", feed.reply());
        assertTrue(feed.unsaved().isEmpty());
    }
    @Test public void playbackAfterResultDoesNotCreateAnotherPendingReply() {
        ConversationFeed feed = new ConversationFeed();
        feed.finish("你好", "你好！", "2026-10-17T08:03:05Z", true);
        assertFalse(feed.reply("你好！"));
        assertEquals("", feed.reply());
        feed.transcript("下一句"); feed.reply("下一轮回答");
        assertEquals("下一句", feed.user()); assertEquals("下一轮回答", feed.reply());
    }
    @Test public void threeUnsavedTurnsStayVisibleInOrder() {
        ConversationFeed feed = new ConversationFeed();
        for (int i = 0; i < 3; i++) {
            feed.transcript("问题" + i); feed.reply("回答" + i);
            feed.finish("问题" + i, "回答" + i, "2026-10-17T08:03:0" + i + "Z", false);
        }
        assertEquals(6, feed.unsaved().size());
        assertEquals("问题0", feed.unsaved().get(0).text);
        assertEquals("回答2", feed.unsaved().get(5).text);
        assertEquals("2026-10-17T08:03:02Z", feed.unsaved().get(5).time);
        assertEquals("", feed.user()); assertEquals("", feed.reply());
    }
    @Test public void cancellingPendingTurnPreservesFinalizedUnsavedTurnsButAccountClearRemovesAll() {
        ConversationFeed feed = new ConversationFeed();
        feed.finish("第一句", "收到", "2026-10-17T08:03:05Z", false);
        feed.transcript("未结束的一句"); feed.reply("未完成的回答");
        feed.resetPending();
        assertEquals("", feed.user()); assertEquals("", feed.reply()); assertEquals(2, feed.unsaved().size());
        feed.clear(); assertTrue(feed.unsaved().isEmpty());
    }
    @Test public void unsavedDisplayIsBoundedAndDoesNotInventAnEmptyAssistant() {
        ConversationFeed feed = new ConversationFeed();
        for (int i = 0; i < 32; i++) feed.finish("句子" + i, "", "2026-10-17T08:03:05Z", false);
        assertEquals(30, feed.unsaved().size());
        assertEquals("句子2", feed.unsaved().get(0).text);
        assertFalse(feed.unsaved().get(29).assistant);
    }
    @Test public void followsNewContentWithinOneScreenButLeavesOlderHistoryInPlace() {
        assertTrue(ConversationFeed.shouldFollow(1000, 1500, 500));
        assertTrue(ConversationFeed.shouldFollow(500, 1500, 500));
        assertFalse(ConversationFeed.shouldFollow(499, 1500, 500));
        assertTrue(ConversationFeed.shouldFollow(0, 300, 500));
        assertFalse(ConversationFeed.shouldFollow(0, 300, 0));
    }
}
