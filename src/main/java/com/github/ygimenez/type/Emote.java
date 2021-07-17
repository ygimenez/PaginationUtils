package com.github.ygimenez.type;

import com.github.ygimenez.model.Page;
import net.dv8tion.jda.api.entities.Message;

import java.util.List;
import java.util.function.Consumer;

import static com.github.ygimenez.method.Pages.clearReactions;
import static com.github.ygimenez.method.Pages.updatePage;

/**
 * {@link EmoteWorker} specifies methods that should be overridden by the {@link Emote}'s
 */
interface EmoteWorker {
    default int doWork(Message message, int pageIndex, int lastPageIndex, List<Page> pageList) { return 0; }
    default int doWork(Message message, int pageIndex, int lastPageIndex, int skipAmount, List<Page> pageList) { return 0; }
    default void doWork(Message message, Consumer<Void> successConsumer) {
        clearReactions(message, successConsumer);
    }
}

/**
 * Enumerator representing values required by non-dynamic buttons.
 */
public enum Emote implements EmoteWorker {
    /**
     * {@link Emote} representing the "next" button (default: ▶).
     */
    NEXT {
        @Override
        public int doWork(Message message, int pageIndex, int lastPageIndex, List<Page> pageList) {
            if (pageIndex < lastPageIndex) {
                pageIndex++;
                nowUpdatePage(message, pageList, pageIndex);
            }
            return pageIndex;
        }
    },

    /**
     * {@link Emote} representing the "previous" button (default: ◀).
     */
    PREVIOUS {
        @Override
        public int doWork(Message message, int pageIndex, int lastPageIndex, List<Page> pageList) {
            if (pageIndex > 0) {
                pageIndex--;
                nowUpdatePage(message, pageList, pageIndex);
            }
            return pageIndex;
        }
    },

    /**
     * {@link Emote} representing the "accept" button (default: ✅).
     */
    ACCEPT { },

    /**
     * {@link Emote} representing the "cancel" button (default: ❎).
     */
    CANCEL { },

    /**
     * {@link Emote} representing the "skip forward" button (default: ⏩).
     */
    SKIP_FORWARD {
        @Override
        public int doWork(Message message, int pageIndex, int lastPageIndex, int skipAmount, List<Page> pageList) {
            if (pageIndex < lastPageIndex) {
                pageIndex += (pageIndex + skipAmount > lastPageIndex ? lastPageIndex - pageIndex : skipAmount);
                nowUpdatePage(message, pageList, pageIndex);
            }
            return pageIndex;
        }
    },

    /**
     * {@link Emote} representing the "skip backward" button (default: ⏪).
     */
    SKIP_BACKWARD {
        @Override
        public int doWork(Message message, int pageIndex, int lastPageIndex, int skipAmount, List<Page> pageList) {
            if (pageIndex > 0) {
                pageIndex -= (pageIndex - skipAmount < 0 ? pageIndex : skipAmount);
                nowUpdatePage(message, pageList, pageIndex);
            }
            return pageIndex;
        }
    },

    /**
     * {@link Emote} representing the "go to first" button (default: ⏮).
     */
    GOTO_FIRST {
        @Override
        public int doWork(Message message, int pageIndex, int lastPageIndex, List<Page> pageList) {
            if (pageIndex > 0) {
                pageIndex = 0;
                nowUpdatePage(message, pageList, pageIndex);
            }
            return pageIndex;
        }
    },

    /**
     * {@link Emote} representing the "go to last" button (default: ⏭).
     */
    GOTO_LAST {
        @Override
        public int doWork(Message message, int pageIndex, int lastPageIndex, List<Page> pageList) {
            if (pageIndex < lastPageIndex) {
                pageIndex = lastPageIndex;
                nowUpdatePage(message, pageList, pageIndex);
            }
            return pageIndex;
        }
    };

    void nowUpdatePage(Message message, List<Page> pageList, int pageIndex) {
        Page page = pageList.get(pageIndex);
        updatePage(message, page);
    }
}
