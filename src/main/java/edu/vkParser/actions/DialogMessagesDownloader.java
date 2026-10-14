package edu.vkParser.actions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.objects.audio.Audio;
import com.vk.api.sdk.objects.base.Sticker;
import com.vk.api.sdk.objects.calls.Call;
import com.vk.api.sdk.objects.docs.Doc;
import com.vk.api.sdk.objects.gifts.Layout;
import com.vk.api.sdk.objects.groups.GroupFull;
import com.vk.api.sdk.objects.market.MarketItem;
import com.vk.api.sdk.objects.messages.*;
import com.vk.api.sdk.objects.messages.responses.GetHistoryExtendedResponse;
import com.vk.api.sdk.objects.photos.PhotoSizes;
import com.vk.api.sdk.objects.polls.Poll;
import com.vk.api.sdk.objects.stories.Story;
import com.vk.api.sdk.objects.users.Fields;
import com.vk.api.sdk.objects.users.UserFull;
import com.vk.api.sdk.objects.users.responses.GetResponse;
import com.vk.api.sdk.objects.wall.WallComment;
import edu.vkParser.exception.DownloadingException;
import edu.vkParser.exception.SetupException;
import edu.vkParser.util.DownloadUtils;
import edu.vkParser.util.HtmlUtils;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DialogMessagesDownloader {

    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final VkApiClient vk;
    private final UserActor actor;

    private String peerName;
    private String peerAvatarUrl;
    private final long peerId;
    private String selfName;
    private String selfAvatarUrl;
    private final long selfId;

    private String outputDir;
    private String imagesDir;
    private String documentsDir;
    private String audioDir;

    public DialogMessagesDownloader(long userId, String apiKey, VkApiClient vkApiClient, long peerId) {
        this.vk = vkApiClient;
        this.peerId = peerId;
        this.selfId = userId;
        this.actor = new UserActor(userId, apiKey);
    }

    public void setupSettings() throws SetupException {
        try {
            List<GetResponse> self = vk.users().get(actor).fields(Fields.PHOTO_400).execute();
            selfName = self.get(0).getFirstName() + " " + self.get(0).getLastName();
            selfAvatarUrl = self.get(0).getPhoto400();
            List<GetResponse> peer = vk.users().get(actor).userIds(String.valueOf(peerId)).fields(Fields.PHOTO_400).execute();
            peerName = peer.get(0).getFirstName() + " " + peer.get(0).getLastName();
            peerAvatarUrl = peer.get(0).getPhoto400();
            System.out.println("Dialog of " + selfName + " (id" + selfId + ") and " + peerName + " (id" + peerId + ")");

            String trimmedName = peerName.replaceAll("[^\\p{L}\\p{Nd}]", "");
            outputDir = "./vk_backup/" + trimmedName + "/";
            imagesDir = outputDir + "images/";
            documentsDir = outputDir + "documents/";
            audioDir = outputDir + "audio/";
            Files.createDirectories(Paths.get(imagesDir));
            Files.createDirectories(Paths.get(documentsDir));
            Files.createDirectories(Paths.get(audioDir));
        } catch (ApiException | ClientException e) {
            System.out.println("error using VK API:");
            e.printStackTrace();
            throw new SetupException(e);
        } catch (IOException e) {
            System.out.println("error creating folders");
            throw new SetupException(e);
        }
    }

    public void startDownloading() throws DownloadingException {
        String jsonFileName = outputDir + "messages_backup.json";
        String jsonUsersFileName = outputDir + "users_data.json";
        String jsonGroupsFileName = outputDir + "groups_data.json";
        if (Files.exists(Paths.get(jsonFileName))) {
            System.out.println("messages_backup.json exists already; override is cancelled");
            throw new DownloadingException("backup exists already");
        }

        List<Message> allMessages = new ArrayList<>();
        List<UserFull> allUsers = new ArrayList<>();
        List<GroupFull> allGroups = new ArrayList<>();

        int offset = 0;
        int count = 150; // лимит для сообщений в 1 запросе
        boolean printTotalMessagesFlag = true;
        int totalMessages = 1;

        try {
            while (offset < totalMessages) {
                GetHistoryExtendedResponse response = vk.messages()
                        .getHistoryExtended(actor)
                        .peerId(peerId)
                        .offset(offset)
                        .count(count)
                        .execute();

                if (printTotalMessagesFlag) {
                    totalMessages = response.getCount();
                    System.out.println("Total messages: " + totalMessages);
                    printTotalMessagesFlag = false;
                }

                List<Message> messages = response.getItems();
                if (messages.isEmpty()) break;
                allMessages.addAll(messages);

                if (response.getProfiles() != null) {
                    allUsers.addAll(response.getProfiles());
                }
                if (response.getGroups() != null) {
                    allGroups.addAll(response.getGroups());
                }

                System.out.println("loading messages... " + allMessages.size());
                offset += count;
                Thread.sleep(350);
            }

            Collections.reverse(allMessages);
            try (Writer writer = new FileWriter(jsonFileName)) {
                System.out.println("writing JSON...");
                gson.toJson(allMessages, writer);
            } catch (IOException e) {
                System.out.println("error writing messages JSON:");
                e.printStackTrace();
                throw new DownloadingException(e);
            }

            Map<Long, UserFull> uniqueUsers = allUsers.stream()
                    .collect(Collectors.toMap(UserFull::getId, Function.identity()));

            try (Writer writer = new FileWriter(jsonUsersFileName)) {
                System.out.println("writing users data JSON...");
                gson.toJson(uniqueUsers.values(), writer);
            } catch (IOException e) {
                System.out.println("error writing JSON::");
                e.printStackTrace();
                throw new DownloadingException(e);
            }

            Map<Long, GroupFull> uniqueGroups = new HashMap<>();
            if (!allGroups.isEmpty()) {
                uniqueGroups = allGroups.stream()
                        .collect(Collectors.toMap(GroupFull::getId, Function.identity()));
                try (Writer writer = new FileWriter(jsonGroupsFileName)) {
                    System.out.println("writing communities data JSON...");
                    gson.toJson(uniqueGroups.values(), writer);
                } catch (IOException e) {
                    System.out.println("error writing JSON::");
                    e.printStackTrace();
                    throw new DownloadingException(e);
                }
            }

            generateHtmlArchive(allMessages, uniqueUsers, uniqueGroups);

        } catch (ApiException | ClientException e) {
            throw new DownloadingException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void generateHtmlArchive(List<Message> messages, Map<Long, UserFull> users, Map<Long, GroupFull> groups) throws DownloadingException {
        System.out.println("starting writing HTML...");

        String localSelfAva = imagesDir + "user_" + selfId + ".jpg";
        if (selfAvatarUrl != null && !selfAvatarUrl.isEmpty()) {
            DownloadUtils.downloadFile(selfAvatarUrl, localSelfAva);
        }
        String localPeerAva = imagesDir + "user_" + peerId + ".jpg";
        if (peerAvatarUrl != null && !peerAvatarUrl.isEmpty()) {
            DownloadUtils.downloadFile(peerAvatarUrl, localPeerAva);
        }

        users.values().forEach(u -> {
            if (u.getId() != selfId && u.getId() != peerId && u.getPhoto100() != null) {
                DownloadUtils.downloadFile(u.getPhoto100().toString(), imagesDir + "user_" + u.getId() + ".jpg");
            }
        });
        groups.values().forEach(g -> {
            if (g.getPhoto100() != null) {
                DownloadUtils.downloadFile(g.getPhoto100().toString(), imagesDir + "public_" + g.getId() + ".jpg");
            }
        });

        int messagesPerPage = 500;
        int totalMessagesCount = messages.size();
        int totalPages = (int) Math.ceil((double) totalMessagesCount / messagesPerPage);
        if (totalPages == 0) totalPages = 1;

        String cssStyles = "<style>"
                + "body { font-family: Arial, sans-serif; background-color: #edeef0; padding: 20px; color: #000; }"
                + ".dialog-container { background: white; max-width: 900px; margin: 0 auto 20px auto; box-shadow: 0 1px 3px rgba(0,0,0,0.1); padding: 20px; }"
                + ".dialog-title { font-size: 20px; margin: 0 0 20px 0; border-bottom: 1px solid #eee; padding-bottom: 10px; }"
                + ".msg-block { display: flex; margin-bottom: 20px; align-items: flex-start; }"
                + ".msg-avatar { width: 40px; height: 40px; border-radius: 50%; margin-right: 12px; object-fit: cover; flex-shrink: 0; }"
                + ".msg-body { flex-grow: 1; min-width: 0; }"
                + ".msg-header { margin-bottom: 6px; }"
                + ".msg-author { font-weight: bold; color: #2a5885; text-decoration: none; font-size: 15px; }"
                + ".msg-item { display: flex; justify-content: space-between; align-items: flex-start; font-size: 15px; line-height: 1.4; margin-bottom: 8px; gap: 20px; }"
                + ".msg-content { flex-grow: 1; min-width: 0; }"
                + ".msg-meta { flex-shrink: 0; text-align: right; font-size: 13px; color: #818c99; white-space: nowrap; padding-top: 2px; }"
                + ".msg-statuses { margin-right: 4px; font-size: 12px; }"
                + ".reply-container { margin-bottom: 6px; background: #f0f2f5; padding: 6px 10px; border-left: 2px solid #5181b8; }"
                + ".reply-link { display: inline-block; font-size: 13px; color: #2a5885; text-decoration: none; font-weight: bold; margin-bottom: 2px; }"
                + ".reply-preview { font-size: 13px; color: #555; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }"
                + ".attachments { margin-top: 8px; }"
                + ".img-attach { max-width: 100%; display: block; margin: 8px 0 0 0; border-radius: 0; }"
                + ".sticker-attach { width: 128px; height: 128px; display: block; margin: 4px 0; border-radius: 0; }"
                + ".audio-msg-player { display: block; margin-bottom: 5px; width: 100%; max-width: 300px; }"
                + ".other-attach { margin-top: 6px; font-size: 13px; color: #444; }"
                + ".link-attach { background: #f0f2f5; padding: 10px; border-left: 2px solid #adb5bd; }"
                + ".poll-attach { border: 1px solid #ddd; padding: 10px; }"
                + ".origin-link { word-break: break-all; display: block; margin-top: 3px; font-size: 13px; color: #2a5885; }"
                + ".repost-block { border-left: 3px solid #5181b8; padding-left: 15px; margin-top: 10px; }"
                + ".repost-header-container { display: flex; align-items: center; margin-bottom: 6px; }"
                + ".repost-header { font-size: 12px; color: #818c99; margin-bottom: 5px; }"
                + ".repost-avatar { width: 24px; height: 24px; border-radius: 50%; margin-right: 8px; object-fit: cover; }"
                + ".repost-author { font-weight: bold; color: #2a5885; text-decoration: none; font-size: 13px; }"
                + ".repost-meta { font-size: 13px; color: #818c99; margin-left: 8px; }"
                + ".nav-bar { display: flex; justify-content: space-between; max-width: 900px; margin: 20px auto; }"
                + ".nav-btn { padding: 10px 20px; background: white; border: 1px solid #ddd; text-decoration: none; color: #2a5885; font-size: 14px; font-weight: bold; }"
                + ".text { font-size: 15px; margin-bottom: 5px; white-space: pre-wrap; word-wrap: break-word; }"
                + "</style>";

        for (int page = 1; page <= totalPages; page++) {
            int fromIndex = (page - 1) * messagesPerPage;
            int toIndex = Math.min(page * messagesPerPage, totalMessagesCount);
            List<Message> pageMessages = messages.subList(fromIndex, toIndex);

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>")
                    .append(HtmlUtils.escapeHtml(peerName)).append(" - Сообщения</title>")
                    .append(cssStyles).append("</head><body>");

            if (totalPages > 1) {
                html.append("<div class='nav-bar'>");
                if (page > 1) {
                    String prevFile = (page == 2) ? "index.html" : "index_" + (page - 1) + ".html";
                    html.append("<a class='nav-btn' href='./").append(prevFile).append("'>Предыдущие сообщения</a>");
                } else {
                    html.append("<div></div>"); // Заглушка для сохранения flex-позиционирования кнопки "Вперед"
                }
                if (page < totalPages) {
                    html.append("<a class='nav-btn' href='./index_").append(page + 1).append(".html'>Следующие сообщения</a>");
                } else {
                    html.append("<div></div>");
                }
                html.append("</div>");
            }

            // Основной контейнер
            html.append("<div class='dialog-container'>");
            html.append("<div class='dialog-title'>").append(HtmlUtils.escapeHtml(selfName))
                    .append(" - ").append(HtmlUtils.escapeHtml(peerName)).append("</div>");


            long lastFromId = Long.MIN_VALUE;
            int lastTimestamp = 0;
            boolean isBlockOpen = false;

            for (Message msg : pageMessages) {
                long currentFromId = msg.getFromId();
                int currentTimestamp = msg.getDate();

                boolean shouldCombine = (currentFromId == lastFromId) && (currentTimestamp - lastTimestamp < 28800);

                if (!shouldCombine) {
                    if (isBlockOpen) {
                        html.append("</div></div>"); // Закрываем .msg-body и .msg-block
                    }

                    // Определяем автора блока сообщений
                    String authorName;
                    String authorAvatar;
                    String authorLink;
                    if (currentFromId == selfId) {
                        authorName = selfName;
                        authorAvatar = "user_" + selfId + ".jpg";
                        authorLink = "https://vk.com/id" + selfId;
                    } else if (currentFromId == peerId) {
                        authorName = peerName;
                        authorAvatar = "user_" + peerId + ".jpg";
                        authorLink = "https://vk.com/id" + peerId;
                    } else {
                        UserFull u = users.get(currentFromId);
                        authorName = (u != null) ? (u.getFirstName() + " " + u.getLastName()) : "ID " + currentFromId;
                        authorAvatar = "user_" + currentFromId + ".jpg";
                        authorLink = "https://vk.com/id" + currentFromId;
                    }

                    // Открываем новый структурный блок группы сообщений
                    html.append("<div class='msg-block'>");
                    // Аватарка стоит слева, независимо от контента
                    html.append("<img class='msg-avatar' src='./images/").append(authorAvatar).append("' />");

                    // Открываемmsg-body, где будет имя и список сообщений
                    html.append("<div class='msg-body'>");

                    // Выводим имя автора один раз в начале блока
                    html.append("<div class='msg-header'>");
                    html.append("<a class='msg-author' href='").append(authorLink).append("' target='_blank'>")
                            .append(HtmlUtils.escapeHtml(authorName)).append("</a>");
                    html.append("</div>");

                    isBlockOpen = true;
                }

                // Внутреннее наполнение конкретного сообщения в блоке
                html.append("<div class='msg-item' id='msg_").append(msg.getId()).append("'>");

                // ОТКРЫВАЕМ ЛЕВУЮ ЧАСТЬ (Контент сообщения)
                html.append("<div class='msg-content'>");

                // Если это ответ на другое сообщение
                if (msg.getReplyMessage() != null) {
                    ForeignMessage replyMsg = msg.getReplyMessage();
                    int replyToId = replyMsg.getId();

                    String
                            previewText = getReplyMessagePreview(replyMsg);
                    html.append("<div class='reply-container'>");
                    html.append("<a class='reply-link' href='#msg_").append(replyToId).append("'>Ответ на сообщение ").append(replyMsg.getId()).append("</a>");
                    if (!previewText.isEmpty()) {
                        html.append("<div class='reply-preview'>").append(HtmlUtils.escapeHtml(previewText)).append("</div>");
                    }
                    html.append("</div>");
                }

                // Текст сообщения
                if (msg.getText() != null && !msg.getText().isEmpty()) {
                    html.append("<div class='text'>").append(HtmlUtils.escapeHtml(msg.getText())).append("</div>");
                }

                // Вложения и пересланные сообщения
                if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                    boolean hasExtraAttachments = msg.getAttachments().stream().anyMatch(attach -> {
                        MessageAttachmentType type = attach.getType();
                        return type == MessageAttachmentType.LINK || type == MessageAttachmentType.VIDEO || type == MessageAttachmentType.WALL || type == MessageAttachmentType.VIDEO_PLAYLIST;
                    });
                    if (hasExtraAttachments) {
                        html.append(processMessageAttachmentsManualParsing(msg.getId()));
                    } else {
                        html.append(processMessageAttachments(msg.getAttachments(), msg.getId()));
                    }
                }
                if (msg.getFwdMessages() != null && !msg.getFwdMessages().isEmpty()) {
                    html.append(processForwardedMessages(msg.getFwdMessages(), msg.getId(), users, groups));
                }

                html.append("</div>"); // Закрытие .msg-content (теперь строго на своем месте)

                // ОТКРЫВАЕМ ПРАВУЮ ЧАСТЬ (Время и свойства)
                LocalDateTime itemDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(msg.getDate()), ZoneId.systemDefault());
                String itemTimeStr = itemDateTime.format(dateTimeFormatter);

                html.append("<div class='msg-meta'>");

                if (msg.getImportant() != null && msg.getImportant()) {
                    html.append("<span class='msg-statuses'>⭐</span>");
                }
                if (msg.getUpdateTime() != null && msg.getUpdateTime() > 0) {
                    LocalDateTime editDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(msg.getUpdateTime()), ZoneId.systemDefault());
                    html.append("<span class='msg-statuses' title='Изменено: ").append(editDateTime.format(dateTimeFormatter)).append("'>✏️</span>");
                }
                if (msg.isDeleted()) {
                    html.append("<span class='msg-statuses'>🗑️</span>");
                }

                html.append(itemTimeStr);
                html.append("</div>"); // Конец .msg-meta

                html.append("</div>"); // Конец .msg-item (Флекс-контейнер закрывается штатно)
//                html.append("<hr>");

                lastFromId = currentFromId;
                lastTimestamp = currentTimestamp;
            }

            if (isBlockOpen) {
                html.append("</div></div>");
            }

            html.append("</div>"); // .dialog-container

            // Нижняя навигация
            if (totalPages > 1) {
                html.append("<div class='nav-bar'>");
                if (page > 1) {
                    String prevFile = (page == 2) ? "index.html" : "index_" + (page - 1) + ".html";
                    html.append("<a class='nav-btn' href='./").append(prevFile).append("'>Предыдущие сообщения</a>");
                } else {
                    html.append("<div></div>");
                }
                if (page < totalPages) {
                    html.append("<a class='nav-btn' href='./index_").append(page + 1).append(".html'>Следующие сообщения</a>");
                } else {
                    html.append("<div></div>");
                }
                html.append(
                        "</div>");
            }

            html.append("</body></html>");


            String pageFileName = (page == 1) ? "index.html" : "index_" + page + ".html";
            try (PrintWriter out = new PrintWriter(outputDir + pageFileName, "UTF-8")) {
                out.print(html.toString());
            } catch (FileNotFoundException | UnsupportedEncodingException e) {
                System.out.println("Error processing HTML page " + page);
                e.printStackTrace();
                throw new DownloadingException(e);
            }
        }
    }

    private String processMessageAttachmentsManualParsing(Integer messageId) {
        JsonArray rawAttachments = getRawAttachmentsByMsgId(messageId);
        if (rawAttachments == null || rawAttachments.isEmpty()) return "";


//        return "<div class='attachments'>" + rawAttachments.toString() + "</div>";


        StringBuilder html = new StringBuilder();
        List<MessageAttachment> standardAttachments = new ArrayList<>();
        StringBuilder manualHtml = new StringBuilder();

        for (int i = 0; i < rawAttachments.size(); i++) {
            JsonObject attachObj = rawAttachments.get(i).getAsJsonObject();
            if (!attachObj.has("type")) continue;

            String type = attachObj.get("type").getAsString().toLowerCase();

            // Если тип проблемный — парсим вручную прямо из JSON
            if ("video".equals(type) && attachObj.has("video")) {
                JsonObject videoObj = attachObj.getAsJsonObject("video");
                long ownerId = videoObj.has("owner_id") ? videoObj.get("owner_id").getAsLong() : 0;
                long videoId = videoObj.has("id") ? videoObj.get("id").getAsLong() : 0;
                String title = videoObj.has("title") ? videoObj.get("title").getAsString() : "Видеозапись";
                int duration = videoObj.has("duration") ? videoObj.get("duration").getAsInt() : 0;

                String vkVideoUrl = "https://vk.com" + ownerId + "_" + videoId;

                // Пытаемся вытащить обложку видео из массива image
                String videoThumbTag = "";
                if (videoObj.has("image") && !videoObj.getAsJsonArray("image").isEmpty()) {
                    JsonArray imageArray = videoObj.getAsJsonArray("image");
                    // Ищем картинку с максимальной шириной
                    JsonObject maxImage = imageArray.get(imageArray.size() - 1).getAsJsonObject();
                    for (int j = 0; j < imageArray.size(); j++) {
                        JsonObject img = imageArray.get(j).getAsJsonObject();
                        if (img.has("width") && maxImage.has("width") && img.get("width").getAsInt() > maxImage.get("width").getAsInt()) {
                            maxImage = img;
                        }
                    }
                    if (maxImage.has("url")) {
                        String localVideoImg = "video_thumb_" + videoId + ".jpg";
                        DownloadUtils.downloadFile(maxImage.get("url").getAsString(), imagesDir + localVideoImg);
                        videoThumbTag = "<img class='img-attach' src='./images/" + localVideoImg + "' /><br>";
                    }
                }

                manualHtml.append("<div class='other-attach'>")
                        .append(HtmlUtils.escapeHtml(title)).append(" (").append(HtmlUtils.formatDuration(duration)).append(")<br>")
                        .append(videoThumbTag)
                        .append("<a class='origin-link' href='").append(vkVideoUrl).append("' target='_blank'>").append(vkVideoUrl).append("</a>")
                        .append("</div>");

            } else if ("video_playlist".equals(type) && attachObj.has("video_playlist")) {
                com.google.gson.JsonObject playlistObj = attachObj.getAsJsonObject("video_playlist");
                long ownerId = playlistObj.has("owner_id") ? playlistObj.get("owner_id").getAsLong() : 0;
                long playlistId = playlistObj.has("id") ? playlistObj.get("id").getAsLong() : 0;
                String title = playlistObj.has("title") ? playlistObj.get("title").getAsString() : "Плейлист видео";

                String playlistUrl = "https://vk.com/playlist/" + ownerId + "_" + playlistId;
                manualHtml.append("<div class='other-attach'>")
                        .append(HtmlUtils.escapeHtml(title)).append("<br>")
                        .append("<a class='origin-link' href='").append(playlistUrl).append("' target='_blank'>").append(playlistUrl).append("</a>")
                        .append("</div>");

            } else if ("link".equals(type) && attachObj.has("link")) {
                com.google.gson.JsonObject linkObj = attachObj.getAsJsonObject("link");
                String url = linkObj.has("url") ? linkObj.get("url").getAsString() : "#";
                String title = linkObj.has("title") ? linkObj.get("title").getAsString() : "Ссылка";
                String desc = linkObj.has("description") ? linkObj.get("description").getAsString() : "";

                manualHtml.append("<div class='other-attach link-attach'>")
                        .append("<a href='").append(url).append("' target='_blank'>").append(HtmlUtils.escapeHtml(title)).append("</a><br>");
                if (!desc.isEmpty()) {
                    manualHtml.append("<small style='color:gray;'>").append(HtmlUtils.escapeHtml(desc)).append("</small><br>");
                }
                manualHtml.append("<a class='origin-link' href='").append(url).append("' target='_blank'>").append(url).append("</a>")
                        .append("</div>");

            } else if ("wall".equals(type) && attachObj.has("wall")) {
                com.google.gson.JsonObject wallObj = attachObj.getAsJsonObject("wall");
                long toId = wallObj.has("to_id") ? wallObj.get("to_id").getAsLong() : 0;
                long wallId = wallObj.has("id") ? wallObj.get("id").getAsLong() : 0;
                String text = wallObj.has("text") ? wallObj.get("text").getAsString() : "";

                String wallPostUrl = "https://vk.com/wall" + toId + "_" + wallId;
                manualHtml.append("<div class='repost-block'>");
                manualHtml.append("<div class='repost-header'>Пост на стене</div>");
                if (!text.isEmpty()) {
                    manualHtml.append("<div class='text' style='font-size:14px;'>").append(HtmlUtils.escapeHtml(text)).append("</div>");
                }
                manualHtml.append("<a class='origin-link' href='").append(wallPostUrl).append("' target='_blank'>").append(wallPostUrl).append("</a>");
                manualHtml.append("</div>");

            } else {
                // Если вложение обычное (photo, doc, audio_message и т.д.) — превращаем JSON обратно в объект SDK
                try {
                    MessageAttachment parsedAttach = gson.fromJson(attachObj, MessageAttachment.class);
                    if (parsedAttach != null) {
                        standardAttachments.add(parsedAttach);
                    }
                } catch (Exception e) {
                    System.out.println("manual attachments data deserialization error!");
                    e.printStackTrace();
                }
            }
        }

        if (!standardAttachments.isEmpty()) {
            html.append(processMessageAttachments(standardAttachments, messageId));
        }

        if (manualHtml.length() > 0) {
            // Оборачиваем ручные вложения в контейнер, так как processMessageAttachments для них не вызывался
            html.append("<div class='attachments'>").append(manualHtml).append("</div>");
        }

//        return html.toString();
        return "<div class='attachments'>" + rawAttachments.toString() + "</div>";
    }

    private String getReplyMessagePreview(ForeignMessage replyMsg) {
        String previewText = "";
        if (replyMsg.getText() != null && !replyMsg.getText().isEmpty()) {
            String rawText = replyMsg.getText();
            if (rawText.length() > 70) {
                previewText = rawText.substring(0, 70) + "...";
            } else {
                previewText = rawText;
            }
        } else if (replyMsg.getAttachments() != null && !replyMsg.getAttachments().isEmpty()) {
            previewText = "Вложение: " + replyMsg.getAttachments().get(0).getType().toString();
        }
        return previewText;
    }

    private String processForwardedMessages(List<ForeignMessage> fwdMessages, Integer messageId, Map<Long, UserFull> users, Map<Long, GroupFull> groups) {
        if (fwdMessages == null || fwdMessages.isEmpty()) return "";

        StringBuilder html = new StringBuilder();

        for (com.vk.api.sdk.objects.messages.ForeignMessage fwd : fwdMessages) {
            long authorId = fwd.getFromId();
            String authorName = "ID " + authorId;
            String authorAvatar = "user_0.jpg"; // Заглушка по умолчанию

            if (authorId > 0) {
                com.vk.api.sdk.objects.users.UserFull u = users.get(authorId);
                if (u != null) {
                    authorName = u.getFirstName() + " " + u.getLastName();
                    authorAvatar = "user_" + u.getId() + ".jpg";
                }
            } else {
                long absoluteGroupId = Math.abs(authorId);
                com.vk.api.sdk.objects.groups.GroupFull g = groups.get(absoluteGroupId);
                if (g != null) {
                    authorName = g.getName();
                    authorAvatar = "public_" + g.getId() + ".jpg";
                }
            }


            LocalDateTime fwdDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(fwd.getDate()), ZoneId.systemDefault());
            String formattedFwdDate = fwdDateTime.format(dateTimeFormatter);
            String vkProfileUrl = (authorId > 0) ? "https://vk.com/id" + authorId : "https://vk.com/public" + Math.abs(authorId);

            html.append("<div class='repost-block'>");

            // Шапка пересланного сообщения: маленькая аватарка автора и имя-ссылка
            html.append("<div class='repost-header-container'>");
            html.append("<img class='repost-avatar' src='./images/").append(authorAvatar).append("' />");
            html.append("<a class='repost-author' href='").append(vkProfileUrl).append("' target='_blank'>")
                    .append(HtmlUtils.escapeHtml(authorName)).append("</a>");
            html.append("<span class='repost-meta'>").append(formattedFwdDate).append("</span>");
            html.append("</div>");

            // Текст пересланного сообщения
            if (fwd.getText() != null && !fwd.getText().isEmpty()) {
                html.append("<div class='text' style='font-size:14px; margin-bottom: 5px;'>")
                        .append(HtmlUtils.escapeHtml(fwd.getText())).append("</div>");
            }

            // Важно: пересланные сообщения могут содержать вложения.
            // Передаем их во временный обработчик вложений (класс WallpostAttachment и MessageAttachment имеют разный тип,
            // поэтому для сообщений мы напишем отдельный processMessageAttachments на следующем шаге)
            if (fwd.getAttachments() != null && !fwd.getAttachments().isEmpty()) {
                html.append(processMessageAttachments(fwd.getAttachments(), messageId + fwd.getId()));
            }

            // Рекурсивный вызов: если внутри пересланного сообщения есть еще пересланные
            if (fwd.getFwdMessages() != null && !fwd.getFwdMessages().isEmpty()) {
                html.append(processForwardedMessages(fwd.getFwdMessages(), messageId, users, groups));
            }

            html.append("</div>"); // Конец .repost-block
        }

        return html.toString();


    }

    private String processMessageAttachments(List<MessageAttachment> attachments, Integer messageId) {
        if (attachments == null || attachments.isEmpty()) return "";

        StringBuilder html = new StringBuilder();
        html.append("<div class='attachments'>"); // Открываем общий контейнер для вложений сообщения

        attachments.forEach(attach -> {
            try {
                switch (attach.getType()) {
                    case PHOTO:
                        URL maxPhotoUrl = attach.getPhoto().getSizes().stream()
                                .max(Comparator.comparingInt(PhotoSizes::getHeight))
                                .get().getUrl().toURL();

                        String localImgName = "photo_" + messageId + "_" + attach.getPhoto().getId() + ".jpg";
                        DownloadUtils.downloadFile(maxPhotoUrl.toString(), imagesDir + localImgName);

                        html.append("<img class='img-attach' src='./images/").append(localImgName).append("' />");
                        html.append("<a class='origin-link' href='").append(maxPhotoUrl.toString()).append("' target='_blank'>").append(maxPhotoUrl.toString()).append("</a>");
                        break;

                    case STICKER:
                        Sticker sticker = attach.getSticker();

                        Integer stickerId = sticker.getStickerId();
                        if (stickerId == null || stickerId <= 0) {
                            html.append("<div class='other-attach'>Ошибка загрузки стикера</div>");
                            break;
                        }
                        String localStickerName = "sticker_" + stickerId + ".png";
                        String stickerUrl = "https://vk.ru/sticker/" + stickerId + "/256.png";
                        DownloadUtils.downloadFile(stickerUrl, imagesDir + localStickerName);
                        html.append("<img class='sticker-attach' src='./images/").append(localStickerName).append("' />");
                        break;

                    case AUDIO_MESSAGE:
                        com.vk.api.sdk.objects.messages.AudioMessage audioMsg = attach.getAudioMessage();
                        String voiceUrl = audioMsg.getLinkMp3().toString();
                        String localVoiceName = "voice_" + messageId + "_" + audioMsg.getId() + ".mp3";

                        DownloadUtils.downloadFile(voiceUrl, audioDir + localVoiceName);

                        html.append("<div class='other-attach'>")
                                .append("<audio class='audio-msg-player' controls src='./audio/").append(localVoiceName).append("'></audio>")
                                .append("<a class='origin-link' href='").append(voiceUrl).append("' target='_blank'>").append(voiceUrl).append("</a>")
                                .append("</div>");
                        break;

                    case DOC:
                        Doc doc = attach.getDoc();
                        String docUrl = doc.getUrl().toString();
                        String origTitle = doc.getTitle() != null ? doc.getTitle() : "document_" + doc.getId();
                        String localDocName = doc.getId() + "_" + origTitle;

                        DownloadUtils.downloadFile(docUrl, documentsDir + localDocName);

                        html.append("<div class='other-attach'>Файл: ")
                                .append("<a href='./documents/").append(localDocName).append("' download>").append(HtmlUtils.escapeHtml(origTitle)).append("</a><br>")
                                .append("<a class='origin-link' href='").append(docUrl).append("' target='_blank'>").append(docUrl).append("</a>")
                                .append("</div>");
                        break;

                    case AUDIO:
                        Audio audio = attach.getAudio();
                        String audioUrlStr = (audio.getUrl() != null) ? audio.getUrl().toString() : "#";

                        String localAudioName = "";
                        if (!audioUrlStr.equals("#")) {
                            String cleanArtist = audio.getArtist().replaceAll("[^\\p{L}\\p{Nd}]", "");
                            String cleanTitle = audio.getTitle().replaceAll("[^\\p{L}\\p{Nd}]", "");
                            localAudioName = audio.getId() + "_" + cleanArtist + "_" + cleanTitle + ".mp3";

                            DownloadUtils.downloadFile(audioUrlStr, audioDir + localAudioName);
                        }

                        html.append("<div class='other-attach'>")
                                .append(HtmlUtils.escapeHtml(audio.getArtist())).append(" — ").append(HtmlUtils.escapeHtml(audio.getTitle()))
                                .append(" (").append(HtmlUtils.formatDuration(audio.getDuration())).append(")<br>");
                        if (!localAudioName.isEmpty()) {
                            html.append("<a href='./audio/").append(localAudioName).append("' download>Аудиофайл</a><br>");
                        }
                        if (!audioUrlStr.equals("#")) {
                            html.append("<a class='origin-link' href='").append(audioUrlStr).append("' target='_blank'>").append(audioUrlStr).append("</a>");
                        }
                        html.append("</div>");
                        break;

//                    case VIDEO:
//                        Video video = attach.get();
//                        String vkVideoUrl = "https://vk.com" + video.getOwnerId() + "_" + video.getId();
//
//                        String videoThumbTag = "";
//                        if (video.getImage() != null && !video.getImage().isEmpty()) {
//                            VideoImage maxImage = video.getImage().stream()
//                                    .max(Comparator.comparingInt(VideoImage::getWidth))
//                                    .orElse(null);
//                            if (maxImage != null) {
//                                String localVideoImg = "msg_video_thumb_" + video.getId() + ".jpg";
//                                DownloadUtils.downloadFile(maxImage.getUrl().toString(), imagesDir + localVideoImg);
//                                videoThumbTag = "<img class='img-attach' src='./images/" + localVideoImg + "' style='max-height:200px; margin-bottom:5px;'/><br>";
//                            }
//                        }
//
//                        html.append("<div class='other-attach'>")
//                                .append(HtmlUtils.escapeHtml(video.getTitle())).append(" (").append(HtmlUtils.formatDuration(video.getDuration())).append(")<br>")
//                                .append(videoThumbTag)
//                                .append("<a class='origin-link' href='").append(vkVideoUrl).append("' target='_blank'>").append(vkVideoUrl).append("</a>")
//                                .append("</div>");
//                        break;

//                    case LINK:
//                        Link link = attach.get();
//                        html.append("<div class='other-attach link-attach'>")
//                                .append("<a href='").append(link.getUrl()).append("' target='_blank'>").append(HtmlUtils.escapeHtml(link.getTitle())).append("</a><br>");
//                        if (link.getDescription() != null && !link.getDescription().isEmpty()) {
//                            html.append("<small style='color:gray;'>").append(HtmlUtils.escapeHtml(link.getDescription())).append("</small><br>");
//                        }
//                        html.append("<a class='origin-link' href='").append(link.getUrl()).append("' target='_blank'>").append(link.getUrl()).append("</a>")
//                                .append("</div>");
//                        break;
//

//                    case WALL:
//                        com.vk.api.sdk.objects.wall.Wallpost wallPost = attach.get();
//                        String wallPostUrl = "https://vk.com" + wallPost.getToId() + "_" + wallPost.getId();
//
//                        html.append("<div class='repost-block'>");
//                        html.append("<div class='repost-header'>Пост на стене</div>");
//                        if (wallPost.getText() != null && !wallPost.getText().isEmpty()) {
//                            html.append("<div class='text' style='font-size:14px;'>").append(HtmlUtils.escapeHtml(wallPost.getText())).append("</div>");
//                        }
//                        html.append("<a class='origin-link' href='").append(wallPostUrl).append("' target='_blank'>").append(wallPostUrl).append("</a>");
//                        html.append("</div>");
//                        break;

                    case WALL_REPLY:
                        WallComment wallReply = attach.getWallReply();
                        String replyUrl = "https://vk.com/" + (wallReply.getOwnerId() > 0 ? "id" + wallReply.getOwnerId() : "public" + wallReply.getOwnerId()) + "_" + wallReply.getPostId() + "?reply=" + wallReply.getId();

                        html.append("<div class='repost-block'>");
                        html.append("<div class='repost-header'>Комментарий к записи</div>");
                        if (wallReply.getText() != null && !wallReply.getText().isEmpty()) {
                            html.append("<div class='text' style='font-size:14px;'>").append(HtmlUtils.escapeHtml(wallReply.getText())).append("</div>");
                        }
                        html.append("<a class='origin-link' href='").append(replyUrl).append("' target='_blank'>").append(replyUrl).append("</a>");
                        html.append("</div>");
                        break;
                    case GRAFFITI:
                        Graffiti graffiti = attach.getGraffiti();
                        URI graffitiUrl = graffiti.getUrl();
                        if (graffitiUrl == null) {
                            html.append("<div class='other-attach'>Ошибка загрузки граффити</div>");
                            break;
                        }
                        String localGraffitiName = "graffiti_" + messageId + "_" + graffiti.getId() + ".jpg";
                        DownloadUtils.downloadFile(graffitiUrl.toURL().toString(), imagesDir + localGraffitiName);

                        html.append("<img class='img-attach' src='./images/").append(localGraffitiName).append("' />");
                        html.append("<a class='origin-link' href='").append(graffitiUrl.toString()).append("' target='_blank'>").append(graffitiUrl.toString()).append("</a>");
                        break;

                    case POLL:
                        Poll poll = attach.getPoll();
                        html.append("<div class='other-attach poll-attach'>")
                                .append(HtmlUtils.escapeHtml(poll.getQuestion())).append("<br><ul style='margin:5px 0; padding-left:20px;'>");
                        if (poll.getAnswers() != null) {
                            poll.getAnswers().forEach(ans -> html.append("<li>").append(HtmlUtils.escapeHtml(ans.getText()))
                                    .append(" — <em>").append(ans.getVotes()).append("</em></li>"));
                        }
                        html.append("</ul><small style='color:gray;'>Всего голосов: ").append(poll.getVotes()).append("</small></div>");
                        break;

                    case CALL:
                        Call call = attach.getCall();
                        String callState;
                        switch (call.getState()) {
                            case CANCELED_BY_INITIATOR:
                                callState = "отменён инициатором";
                                break;
                            case CANCELED_BY_RECEIVER:
                                callState = "отменён получателем";
                                break;
                            case REACHED:
                                callState = " ";
                                break;
                            default:
                                callState = ": неизвестно";
                                break;
                        }

                        html.append("<div class='other-attach'>")
                                .append(call.getVideo() ? "<b>Видеозвонок</b> - " : "<b>Аудиозвонок</b> - ").append(callState);
                        if (call.getDuration() != null && call.getDuration() > 0) {
                            html.append(" (").append(HtmlUtils.formatDuration(call.getDuration())).append(")");
                        }
                        html.append("</div>");
                        break;

                    case GIFT:
                        Layout gift = attach.getGift();
                        html.append("<div class='other-attach'> <b>Подарок </b>").append(gift.getId()).append("<br>").append(gift.getKeywords()).append("<br>");
                        if (gift.getThumb256() != null) {
                            String localGiftImg = "gift_" + messageId + "_" + gift.getId() + ".jpg";
                            DownloadUtils.downloadFile(gift.getThumb256().toString(), imagesDir + localGiftImg);
                            html.append("<img class='img-attach' src='./images/").append(localGiftImg).append("' />");
                        }
                        html.append("</div>");
                        break;

                    case MARKET:
                        MarketItem marketItem = attach.getMarket();
                        String marketUrl = "https://vk.com/market" + marketItem.getOwnerId() + "?w=product" + marketItem.getOwnerId() + "_" + marketItem.getId();
                        html.append("<div class='other-attach link-attach'> Маркетплейс: ")
                                .append(HtmlUtils.escapeHtml(marketItem.getTitle())).append("<br>");
                        if (marketItem.getPrice() != null) {
                            html.append("<div>Цена: ")
                                    .append(marketItem.getPrice().getAmount())
                                    .append(" ").append(HtmlUtils.escapeHtml(marketItem.getPrice().getCurrency().getName()))
                                    .append("</div>");
                        }
                        if (marketItem.getDescription() != null && !marketItem.getDescription().isEmpty()) {
                            html.append("<div><small style='color:gray;'>")
                                    .append(HtmlUtils.escapeHtml(marketItem.getDescription()))
                                    .append("</small></div>");
                        }
                        if (marketItem.getThumbPhoto() != null) {
                            String localMarketImg = "market_" + messageId + "_" + marketItem.getId() + ".jpg";
                            html.append("<img class='img-attach' style='max-width:200px;' src='./images/").append(localMarketImg).append("' />");

                        }
                        html.append("<a class='origin-link' href='").append(marketUrl)
                                .append("' target='_blank'>").append(marketUrl).append("</a>")
                                .append("</div>");
                        break;

                    case MARKET_ALBUM:
                        com.vk.api.sdk.objects.market.MarketAlbum marketAlbum = attach.getMarketMarketAlbum();
                        String albumUrl = "https://vk.com/market" + marketAlbum.getOwnerId()
                                + "?w=product" + marketAlbum.getOwnerId() + "_" + marketAlbum.getId();

                        String albumThumbTag = "";
                        if (marketAlbum.getPhoto() != null
                                && marketAlbum.getPhoto().getSizes() != null
                                && !marketAlbum.getPhoto().getSizes().isEmpty()) {
                            PhotoSizes maxAlbumSize = marketAlbum.getPhoto().getSizes().stream()
                                    .max(Comparator.comparingInt(PhotoSizes::getHeight))
                                    .orElse(null);
                            if (maxAlbumSize != null && maxAlbumSize.getUrl() != null) {
                                String localAlbumImg = "market_album_" + messageId + "_" + marketAlbum.getId() + ".jpg";
                                DownloadUtils.downloadFile(maxAlbumSize.getUrl().toString(), imagesDir + localAlbumImg);
                                albumThumbTag = "<img class='img-attach' style='max-width:200px;' src='./images/"
                                        + localAlbumImg + "' />";
                            }
                        }

                        html.append("<div class='other-attach link-attach'>")
                                .append("Маркетплейс подборка: ").append(HtmlUtils.escapeHtml(marketAlbum.getTitle())).append("<br>")
                                .append(albumThumbTag);

                        if (marketAlbum.getCount() != null) {
                            html.append("<div>Товаров: ").append(marketAlbum.getCount()).append("</div>");
                        }
                        html.append("<a class='origin-link' href='").append(albumUrl)
                                .append("' target='_blank'>").append(albumUrl).append("</a>")
                                .append("</div>");
                        break;

                    default:
                        Story story = attach.getStory();
                        if (story != null) {
                            String storyUrl = "https://vk.com/story" + story.getOwnerId() + "_" + story.getId();
                            html.append("<div class='other-attach'>").append("<b>История | </b>").append(story.getFirstNarrativeTitle()).append(" | ").append(story.getViews()).append("<br>");
                            // Пытаемся получить превью из photo-вложений истории (если доступны)
                            if (story.getPhoto() != null && story.getPhoto().getSizes() != null && !story.getPhoto().getSizes().isEmpty()) {
                                PhotoSizes maxStorySize = story.getPhoto().getSizes().stream()
                                        .max(Comparator.comparingInt(PhotoSizes::getHeight))
                                        .orElse(null);
                                if (maxStorySize != null && maxStorySize.getUrl() != null) {
                                    String localStoryImg = "story_" + messageId + "_" + story.getId() + ".jpg";
                                    DownloadUtils.downloadFile(maxStorySize.getUrl().toString(), imagesDir + localStoryImg);
                                    html.append("<img class='img-attach' src='./images/").append(localStoryImg).append("' />");
                                }
                            }
                            html.append("<a class='origin-link' href='").append(story.getVideo().getFiles().getMp4720().toURL().toString())
                                    .append("' target='_blank'>").append(story.getVideo().getFiles().getMp4720().toURL().toString()).append("</a>")
                                    .append("</div>");
                            break;
                        }


                        html.append("<div class='other-attach'>Вложение: ").append(attach.getType().toString()).append("</div>");
                        break;
                }
            } catch (Exception e) {
                html.append("<div class='other-attach'>Ошибка загрузки вложения</div>");
            }
        });

        html.append("</div>");
        return html.toString();
    }


    private JsonArray getRawAttachmentsByMsgId(int msgId) {
        try {
            // Делаем прямой запрос к API ВКонтакте
            String rawJson = vk.getTransportClient().post(vk.getApiEndpoint() + "messages.getById",
                            "access_token=" + URLEncoder.encode(actor.getAccessToken(), "UTF-8")
                                    + "&v=" + URLEncoder.encode(vk.getVersion(), "UTF-8")
                                    + "&message_ids=" + msgId + "&extended=1",
                            "application/x-www-form-urlencoded")
                    .getContent();
            System.out.println(rawJson);

            JsonObject fullResponse = gson.fromJson(rawJson, JsonObject.class);            // Парсим строку в дерево элементов Gson
            if (fullResponse.has("response")) {
                JsonObject responseBody = fullResponse.getAsJsonObject("response");
                JsonArray items = responseBody.getAsJsonArray("items");

                if (items != null && !items.isEmpty()) {
                    JsonObject messageObj = items.get(0).getAsJsonObject();
                    if (messageObj.has("attachments")) {
                        return messageObj.getAsJsonArray("attachments");
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("error while fetching raw message for message ID: " + msgId);
            e.printStackTrace();
        }
        return null;
    }

}

