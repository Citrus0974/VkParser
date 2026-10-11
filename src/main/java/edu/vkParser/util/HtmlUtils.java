package edu.vkParser.util;

public class HtmlUtils {
    public static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
//                .replace("\f", "<br>");
    }

    public static String formatDuration(Integer seconds) {
        if (seconds == null || seconds == 0) return "0:00";
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
