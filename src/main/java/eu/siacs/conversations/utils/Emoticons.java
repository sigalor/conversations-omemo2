package eu.siacs.conversations.utils;

import net.fellbaum.jemoji.EmojiManager;

public class Emoticons {
    public static boolean isEmoji(String input) {
        return EmojiManager.isEmoji(input);
    }

    public static boolean isOnlyEmoji(String input) {
        if (input.trim().length() == 0) return false; // Vaccuously true but not useful

        return EmojiManager.removeAllEmojis(input).replaceAll("\uFE0F", "").trim().length() == 0;
    }
}
