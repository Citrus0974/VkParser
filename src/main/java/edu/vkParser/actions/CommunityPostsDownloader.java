package edu.vkParser.actions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.objects.groups.Fields;
import com.vk.api.sdk.objects.groups.GroupFull;
import com.vk.api.sdk.objects.photos.PhotoSizes;
import com.vk.api.sdk.objects.wall.WallItem;
import com.vk.api.sdk.objects.wall.responses.GetResponse;
import edu.vkParser.exception.DownloadingException;
import edu.vkParser.exception.SetupException;
import edu.vkParser.util.HtmlUtils;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CommunityPostsDownloader {


    private final VkApiClient vk;
    private final String communityId;
    private final UserActor actor;

    private String communityName;
    private String communityDesc;
    private String communityAvatarUrl;
    private String outputDir;
    private String imagesDir;
    private String documentsDir;

    private final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public CommunityPostsDownloader(long userId, String apiKey, VkApiClient vk, String communityId) {
        this.communityId = communityId;
        this.vk = vk;

        actor = new UserActor(userId, apiKey);

    }

    public void setupSettings() throws SetupException {
        try {
            List<GroupFull> groups = vk.groups().getByIdObject(actor).groupId(communityId).fields(Fields.PHOTO_400, Fields.DESCRIPTION, Fields.NAME).execute().getGroups();
            if (!groups.isEmpty()) {
                GroupFull group = groups.get(0);
                String groupName = group.getName();
                System.out.println("Community group: \"" + groupName + "\", id" + communityId);
                String trimmedName = groupName.replaceAll("[^\\p{L}\\p{Nd}]", "");
                communityName = groupName;
                communityDesc = group.getDescription();
                communityAvatarUrl = group.getPhoto400().toString();
                outputDir = "./vk_backup/" + trimmedName + "/";
                imagesDir = outputDir + "images/";
                documentsDir = outputDir + "documents/";
                Files.createDirectories(Paths.get(imagesDir));
                Files.createDirectories(Paths.get(documentsDir));
            }
        } catch (ApiException | ClientException e) {
            System.out.println("error using VK API:");
            e.printStackTrace();
            throw new SetupException(e);
        } catch (IOException e) {
            System.out.println("error creating folders");
        }
    }

    public void startDownloading() throws DownloadingException {

        String jsonFileName = outputDir + "posts_backup.json";
        if (Files.exists(Paths.get(jsonFileName))) {
            System.out.println("posts_backup.json exists already; override is cancelled");
            throw new DownloadingException("backup exists already");
        }

        List<WallItem> allPosts = new ArrayList<>();
        int offset = 0;
        int count = 90; // ограничение за 1 запрос
        boolean printTotalPostsFlag = true; //нужно напечатать общее число постов
        int totalPosts = 1; // Динамически обновится при первом запросе

        long communityOwnerId = Long.parseLong(communityId);
        try {
            while (offset < totalPosts) {
                GetResponse response = vk.wall()
                        .get(actor)
                        .ownerId(-communityOwnerId) // Минус обязателен для сообществ
                        .offset(offset)
                        .count(count)
                        .execute();

                totalPosts = response.getCount();
                if (printTotalPostsFlag) {
                    System.out.println("Total posts: " + totalPosts);
                    printTotalPostsFlag = false;
                }

                List<WallItem> posts = response.getItems();
                if (posts.isEmpty()) break;
                allPosts.addAll(posts);
                System.out.println("loading posts... " + allPosts.size());
                offset += count;
                Thread.sleep(350);
            }

            try (Writer writer = new FileWriter(jsonFileName)) {
                System.out.println("writing JSON...");
                gson.toJson(allPosts, writer);
            } catch (IOException e) {
                System.out.println("error writing JSON:");
                e.printStackTrace();
                throw new DownloadingException(e);
            }

            generateHtmlArchive(allPosts);

        } catch (ClientException | ApiException e) {
            System.out.println("error using VK API:");
            e.printStackTrace();
            throw new DownloadingException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void generateHtmlArchive(List<WallItem> posts) throws DownloadingException {
        System.out.println("downloading images and writing HTML...");
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>").append(communityName).append(" (archived)</title>")
                .append("<style>")
                .append("body { font-family: Arial, sans-serif; background-color: #edeef0; padding: 20px; }")
                .append(".post { background: white; padding: 20px; margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); max-width: 600px; margin-left: auto; margin-right: auto; }")
                .append(".header { font-size: 14px; color: #555; margin-bottom: 10px; border-bottom: 1px solid #eee; padding-bottom: 5px; }")
                .append(".text { font-size: 15px; line-height: 1.5; white-space: pre-wrap; word-wrap: break-word; }")
                .append(".attachments { margin-top: 5px; }")
                .append(".img-attach { max-width: 100%; display: block; margin: 0 auto;}")
                .append(".other-attach { background: #f0f2f5; padding: 8px; margin-top: 5px; font-size: 13px; color: #444; }")
                .append("</style></head><body>");

        html.append("<h1 style='text-align:center;'>").append(HtmlUtils.escapeHtml(communityName)).append("</h1>");
        for (WallItem post : posts) {

            LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(post.getDate()), ZoneId.systemDefault());
            String formattedDate = dateTime.format(DATE_FORMATTER);

            html.append("<div class='post'>");
            html.append("<div class='header'>").append("<strong>").append(HtmlUtils.escapeHtml(communityName)).append("</strong>   |   ").append(formattedDate).append(" | (ID: ").append(post.getId()).append(")</div>");
            html.append("<div class='text'>").append(post.getText() != null ? HtmlUtils.escapeHtml(post.getText()) : "").append("</div>");

            // Обработка вложений
            if (post.getAttachments() != null && !post.getAttachments().isEmpty()) {
                html.append("<div class='attachments'>");

                post.getAttachments().forEach(attach -> {
                    try {
                        switch (attach.getType()) {
                            case PHOTO:
                                URL maxPhotoUrl = attach.getPhoto().getSizes().stream()
                                        .max(Comparator.comparingInt(PhotoSizes::getHeight))
                                        .get().getUrl().toURL();

                                String localImgName = "photo_" + post.getId() + "_" + attach.getPhoto().getId() + ".jpg";
                                downloadFile(maxPhotoUrl.toString(), imagesDir + localImgName);
                                html.append("<img class='img-attach' src='./images/").append(localImgName).append("' />");
                                break;

                            case AUDIO:
                                html.append("<div class='other-attach'>Аудио: ")
                                        .append(HtmlUtils.escapeHtml(attach.getAudio().getArtist())).append(" — ")
                                        .append(HtmlUtils.escapeHtml(attach.getAudio().getTitle())).append("<br>")
                                        .append(HtmlUtils.escapeHtml(attach.getAudio().getUrl().toString())).append("</div>");
                                break;

                            case VIDEO:
                                html.append("<div class='other-attach'>Видео: ")
                                        .append(HtmlUtils.escapeHtml(attach.getVideo().getTitle())).append("<br>")
                                        .append(attach.getVideo().getPlayer().toString()).append("</div>");
                                break;

                            case LINK:
                                html.append("<div class='other-attach'>Ссылка: <a href='")
                                        .append(attach.getLink().getUrl()).append("' target='_blank'>")
                                        .append(HtmlUtils.escapeHtml(attach.getLink().getTitle())).append("<br>")
                                        .append(attach.getLink().getUrl()).append("</a></div>");
                                break;

                            default:
                                html.append("<div class='other-attach'>Вложение: ")
                                        .append(attach.getType().toString()).append("<br>")
                                        .append(attach).append("</div>");
                                break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        html.append("<div class='other-attach'>Ошибка загрузки вложения</div></div>");
                    }
                });
                html.append("</div>");
            }

            // Проверка на репост
            if (post.getCopyHistory() != null && !post.getCopyHistory().isEmpty()) {
                html.append("<div class='other-attach' style='border-left: 3px solid #5181b8; padding-left: 10px;'>Репост другой записи</div>");
            }

            html.append("</div>");
        }

        html.append("</body></html>");

        try (PrintWriter out = new PrintWriter(outputDir + "index.html", "UTF-8")) {
            out.print(html.toString());
        } catch (FileNotFoundException | UnsupportedEncodingException e) {
            System.out.println("Error processing custom HTML:");
            e.printStackTrace();
            throw new DownloadingException(e);
        }
    }


    private static void downloadFile(String urlStr, String destinationPath) {
        File file = new File(destinationPath);
        if (file.exists()) return; // Пропускаем, если уже скачали ранее

        try (InputStream in = new URL(urlStr).openStream();
             OutputStream out = Files.newOutputStream(Paths.get(destinationPath))) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            System.err.println("failed to download: " + urlStr);
        }
    }

}
