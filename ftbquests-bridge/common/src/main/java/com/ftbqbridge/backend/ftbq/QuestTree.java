package com.ftbqbridge.backend.ftbq;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Pure, Minecraft-free helpers for walking and naming quest objects, kept separate from
 * {@link FtbQuestsBackend} so they can be unit-tested without bootstrapping the game.
 */
public final class QuestTree {
    private QuestTree() {}

    /**
     * Every node of the tree rooted at {@code root}, each node's descendants before the node itself
     * (post-order), ending with {@code root}.
     *
     * <p>Deletion relies on this: {@code Quest.deleteChildren()} clears a quest's tasks and rewards,
     * so those must be visited as objects in their own right before their quest goes away.
     */
    public static <T> List<T> childrenFirst(T root,
            Function<? super T, ? extends Collection<? extends T>> children) {
        List<T> out = new ArrayList<>();
        addChildrenFirst(root, children, out);
        return out;
    }

    private static <T> void addChildrenFirst(T node,
            Function<? super T, ? extends Collection<? extends T>> children, List<T> out) {
        // Copy first: live FTB lists are cleared and repopulated by deleteSelf() while we walk.
        for (T child : List.copyOf(children.apply(node))) {
            addChildrenFirst(child, children, out);
        }
        out.add(node);
    }

    /** The bare chapter filename from FTB's {@code Chapter.getPath()}: {@code chapters/foo.snbt} -> {@code foo}. */
    public static String chapterFilename(String path) {
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return name.endsWith(".snbt") ? name.substring(0, name.length() - ".snbt".length()) : name;
    }
}
