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
}
