package edu.vkParser.actions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.objects.groups.GroupFull;
import com.vk.api.sdk.objects.messages.ForeignMessage;
import com.vk.api.sdk.objects.messages.Message;
import com.vk.api.sdk.objects.messages.MessageAttachment;
import com.vk.api.sdk.objects.messages.responses.GetHistoryExtendedResponse;
import com.vk.api.sdk.objects.users.Fields;
import com.vk.api.sdk.objects.users.UserFull;
import com.vk.api.sdk.objects.users.responses.GetResponse;
import edu.vkParser.exception.DownloadingException;
import edu.vkParser.exception.SetupException;
import edu.vkParser.util.DownloadUtils;
import edu.vkParser.util.HtmlUtils;

import java.io.*;
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
                + ".dialog-container { background: white; max-width: 800px; margin: 0 auto 20px auto; box-shadow: 0 1px 3px rgba(0,0,0,0.1); padding: 20px; }"
                + ".dialog-title { font-size: 20px; margin: 0 0 20px 0; border-bottom: 1px solid #eee; padding-bottom: 10px; }"
                + ".msg-block { display: flex; margin-bottom: 15px; padding-top: 5px; }"
                + ".msg-avatar { width: 40px; height: 40px; border-radius: 50%; margin-right: 12px; object-fit: cover; flex-shrink: 0; }"
                + ".msg-body { flex-grow: 1; }"
                + ".msg-header { font-size: 14px; margin-bottom: 4px; color: #555; }"
                + ".msg-author { font-weight: bold; color: #2a5885; text-decoration: none; }"
                + ".msg-time { font-size: 12px; color: #818c99; margin-left: 8px; }"
                + ".msg-item { font-size: 15px; line-height: 1.4; white-space: pre-wrap; word-wrap: break-word; margin-bottom: 6px; position: relative; }"
                + ".reply-link { display: block; font-size: 13px; color: #2a5885; text-decoration: none; margin-bottom: 4px; background: #f0f2f5; padding: 4px 8px; border-left: 2px solid #5181b8; }"
                + ".attachments { margin-top: 8px; }"
                + ".img-attach { max-width: 100%; display: block; margin: 8px 0 0 0; }"
                + ".other-attach { margin-top: 6px; font-size: 13px; color: #444; }"
                + ".link-attach { background: #f0f2f5; padding: 10px; border-left: 2px solid #adb5bd; }"
                + ".poll-attach { border: 1px solid #ddd; padding: 10px; }"
                + ".origin-link { word-break: break-all; display: block; margin-top: 3px; font-size: 13px; color: #2a5885; }"
                + ".repost-block { border-left: 3px solid #5181b8; padding-left: 15px; margin-top: 10px; }"
                + ".repost-header { font-size: 13px; color: #555; margin-bottom: 5px; }"
                + ".nav-bar { display: flex; justify-content: space-between; max-width: 800px; margin: 20px auto; }"
                + ".nav-btn { padding: 10px 20px; background: white; border: 1px solid #ddd; text-decoration: none; color: #2a5885; font-size: 14px; font-weight: bold; }"
                + "</style>";

        for (int page = 1; page <= totalPages; page++) {
            int fromIndex = (page - 1) * messagesPerPage;
            int toIndex = Math.min(page * messagesPerPage, totalMessagesCount);
            List<Message> pageMessages = messages.subList(fromIndex, toIndex);

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>")
                    .append(HtmlUtils.escapeHtml(peerName)).append(" - Сообщения").append(page).append("</title>")
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

            long lastFromId = Long.MIN_VALUE;
            int lastTimestamp = 0;
            boolean isBlockOpen = false;

            for (Message msg : pageMessages) {
                long currentFromId = msg.getFromId();
                int currentTimestamp = msg.getDate();

                boolean shouldCombine = (currentFromId == lastFromId) && (currentTimestamp - lastTimestamp < 28800);

                if (!shouldCombine) {
                    if (isBlockOpen) {
                        html.append("</div></div>"); // Закрываем предыдущее тело и контейнер msg-block
                    }

                    // Определяем имя и аватарку автора блока сообщений
                    String authorName;
                    String authorAvatar;
                    if (currentFromId == selfId) {
                        authorName = selfName;
                        authorAvatar = "user_" + selfId + ".jpg";
                    } else if (currentFromId == peerId) {
                        authorName = peerName;
                        authorAvatar = "user_" + peerId + ".jpg";
                    } else {
                        // Если сообщение от стороннего автора (например, пересланное, хотя в ЛС это редкость для корневого уровня)
                        UserFull u = users.get(currentFromId);
                        authorName = (u != null) ? (u.getFirstName() + " " + u.getLastName()) : "ID " + currentFromId;
                        authorAvatar = "images/user_" + currentFromId + ".jpg";
                    }

                    LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(currentTimestamp), ZoneId.systemDefault());
                    String formattedDate = dateTime.format(dateTimeFormatter);

                    // Открываем новый структурный блок
                    html.append("<div class='msg-block'>");
                    html.append("<img class='msg-avatar' src='./images/").append(authorAvatar).append("' />");
                    html.append("<div class='msg-body'>");
                    html.append("<div class='msg-header'>");
                    html.append("<span class='msg-author'>").append(HtmlUtils.escapeHtml(authorName)).append("</span>");
                    html.append("<span class='msg-time'>").append(formattedDate).append("</span>");
                    html.append("</div>");

                    isBlockOpen = true;
                }

                // Внутреннее наполнение конкретного сообщения в блоке
                html.append("<div class='msg-item' id='msg_").append(msg.getId()).append("'>");

                // Если это ответ на другое сообщение, строим локальную якорь-ссылку #msg_ID
                if (msg.getReplyMessage() != null) {
                    int replyToId = msg.getReplyMessage().getId();
                    html.append("<a class='reply-link' href='#msg_").append(replyToId).append("'>Ответ на сообщение</a>");
                }

                // Текст сообщения
                if (msg.getText() != null && !msg.getText().isEmpty()) {
                    html.append("<div>").append(HtmlUtils.escapeHtml(msg.getText())).append("</div>");
                }

                html.append(processMessageAttachments(msg.getAttachments(), msg.getId()));
                html.append(processMessageReposts(msg.getFwdMessages(), msg.getId(), users, groups));

                html.append("</div>"); // Закрываем .msg-item

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

    private String processMessageReposts(List<ForeignMessage> fwdMessages, Integer messageId, Map<Long, UserFull> users, Map<Long, GroupFull> groups) {
        return "";
    }

    private String processMessageAttachments(List<MessageAttachment> attachments, Integer messageId) {
        return "";
    }


}

