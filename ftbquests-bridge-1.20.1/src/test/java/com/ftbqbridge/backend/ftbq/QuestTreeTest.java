package com.ftbqbridge.backend.ftbq;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link QuestTree}. The delete adapter in {@code FtbQuestsBackend} feeds it
 * FTB's {@code QuestObject.getChildren()}; here the tree is plain strings.
 */
class QuestTreeTest {

    @Test void leafIsJustItself() {
        assertEquals(List.of("task"), QuestTree.childrenFirst("task", n -> List.of()));
    }

    @Test void descendantsComeBeforeTheirParent() {
        // group -> {chapterA -> {quest1 -> {task, reward}}, chapterB}
        Map<String, List<String>> tree = Map.of(
                "group", List.of("chapterA", "chapterB"),
                "chapterA", List.of("quest1"),
                "quest1", List.of("task", "reward"));
        List<String> order = QuestTree.childrenFirst("group", n -> tree.getOrDefault(n, List.of()));
        assertEquals(List.of("task", "reward", "quest1", "chapterA", "chapterB", "group"), order);
    }

    @Test void snapshotsChildrenSoTheCallerMayMutateLiveLists() {
        // FTB's getChildren() is a live view; mutating it mid-walk must not throw or skip nodes.
        List<String> live = new ArrayList<>(List.of("a", "b"));
        List<String> order = QuestTree.childrenFirst("root", n -> {
            if (n.equals("root")) return live;
            live.clear();
            return List.of();
        });
        assertEquals(List.of("a", "b", "root"), order);
    }

    @Test void chapterFilenameStripsFolderAndExtension() {
        // 1.20.1 exposes Chapter.getPath() as a String, not a java.nio.file.Path.
        assertEquals("getting_started", QuestTree.chapterFilename("chapters/getting_started.snbt"));
    }

    @Test void chapterFilenameHandlesWindowsSeparators() {
        assertEquals("getting_started", QuestTree.chapterFilename("chapters\\getting_started.snbt"));
    }

    @Test void chapterFilenameKeepsNamesWithoutSnbtExtension() {
        assertEquals("odd.name", QuestTree.chapterFilename("chapters/odd.name"));
    }

    @Test void chapterFilenameHandlesBareName() {
        assertEquals("lonely", QuestTree.chapterFilename("lonely"));
    }
}
