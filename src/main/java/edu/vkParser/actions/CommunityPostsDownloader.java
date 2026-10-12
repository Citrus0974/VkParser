package edu.vkParser.actions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.objects.audio.Audio;
import com.vk.api.sdk.objects.base.Image;
import com.vk.api.sdk.objects.base.Link;
import com.vk.api.sdk.objects.base.OwnerCover;
import com.vk.api.sdk.objects.docs.Doc;
import com.vk.api.sdk.objects.groups.Fields;
import com.vk.api.sdk.objects.groups.GroupFull;
import com.vk.api.sdk.objects.photos.PhotoAlbum;
import com.vk.api.sdk.objects.photos.PhotoSizes;
import com.vk.api.sdk.objects.video.Video;
import com.vk.api.sdk.objects.video.VideoImage;
import com.vk.api.sdk.objects.wall.WallItem;
import com.vk.api.sdk.objects.wall.WallpostAttachment;
import com.vk.api.sdk.objects.wall.WallpostFull;
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
    private String communityCoverUrl;
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
            List<GroupFull> groups = vk.groups().getByIdObject(actor).groupId(communityId).fields(Fields.PHOTO_400, Fields.DESCRIPTION, Fields.NAME, Fields.COVER).execute().getGroups();
            if (!groups.isEmpty()) {
                GroupFull group = groups.get(0);
                String groupName = group.getName();

                OwnerCover cover = group.getCover();
                if (cover!= null && cover.getEnabled().getValue().equals("1")){
                    List<Image> images = group.getCover().getImages();
                    if (images != null && !images.isEmpty()) {
                        Image bestImage = images.stream()
                                .max(Comparator.comparingInt(Image::getWidth))
                                .orElse(images.get(0));
                        communityCoverUrl = bestImage.getUrl().toString();
                    }
                }

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
        System.out.println("starting writing HTML...");

        String localAvatarName = "images/ava_" + communityId + ".jpg";
        if (communityAvatarUrl != null && !communityAvatarUrl.isEmpty()) {
            downloadFile(communityAvatarUrl, outputDir + localAvatarName);
        }

        String localCoverName = null;
        if (communityCoverUrl != null && !communityCoverUrl.isEmpty()) {
            localCoverName = "images/cover_" + communityId + ".jpg";
            downloadFile(communityCoverUrl, outputDir + localCoverName);
        }

        int postsPerPage = 50;
        int totalPostsCount = posts.size();
        int totalPages = (int) Math.ceil((double) totalPostsCount / postsPerPage);
        if (totalPages == 0) totalPages = 1;

        String cssStyles = "<style>"
                + "body { font-family: Arial, sans-serif; background-color: #edeef0; padding: 20px; color: #000; }"
                + ".community-container { background: white; max-width: 600px; margin: 0 auto 20px auto; box-shadow: 0 1px 3px rgba(0,0,0,0.1);  overflow: hidden; }"
                + ".community-cover { width: 100%; max-height: 200px; object-fit: cover; display: block; }"
                + ".community-header { padding: 20px; display: flex; align-items: center; }"
                + ".community-avatar { width: 80px; height: 80px; margin-right: 20px; object-fit: cover; }"
                + ".community-info h1 { margin: 0 0 5px 0; font-size: 20px; }"
                + ".community-info p { margin: 0; font-size: 14px; color: #666; white-space: pre-wrap; }"
                + ".post { background: white; padding: 20px; margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); max-width: 600px; margin-left: auto; margin-right: auto;  }"
                + ".post-author-block { display: flex; align-items: center; margin-bottom: 10px; border-bottom: 1px solid #eee; padding-bottom: 10px; }"
                + ".post-avatar { width: 40px; height: 40px; border-radius: 50%; margin-right: 12px; }"
                + ".header-meta { font-size: 14px; color: #555; }"
                + ".text { font-size: 15px; line-height: 1.5; white-space: pre-wrap; word-wrap: break-word; }"
                + ".attachments { margin-top: 12px; }"
                + ".img-attach { width: 100%; height: auto; max-width: 100%; display: block; margin: 8px auto 0 auto;  }"
                + ".other-attach { margin-top: 8px; font-size: 13px; color: #444; }"
                + ".link-attach { background: #f0f2f5; padding: 10px;  border-left: 2px solid #adb5bd; }"
                + ".poll-attach { border: 1px solid #ddd; padding: 10px;  }"
                + ".origin-link { word-break: break-all; display: block; margin-top: 5px; font-size: 13px; }"
                + ".repost-block { border-left: 3px solid #5181b8; padding-left: 15px; margin-top: 15px; padding-top: 5px; padding-bottom: 5px; }"
                + ".repost-header { font-size: 13px; color: #555; margin-bottom: 5px; }"
                + ".post-footer { display: flex; justify-content: space-between; margin-top: 15px; padding-top: 10px; border-top: 1px solid #f0f2f5; font-size: 13px; color: #65676b; }"
                + ".pagination { text-align: center; margin-top: 30px; margin-bottom: 30px; }"
                + ".pagination a, .pagination span { padding: 8px 12px; margin: 0 3px; background: white; border: 1px solid #ddd; text-decoration: none; color: #2a5885;  }"
                + ".pagination .current { background: #2a5885; color: white; border-color: #2a5885; font-weight: bold; }"
                + "</style>";

        for (int page = 1; page <= totalPages; page++) {
            int fromIndex = (page - 1) * postsPerPage;
            int toIndex = Math.min(page * postsPerPage, totalPostsCount);
            List<WallItem> pagePosts = posts.subList(fromIndex, toIndex);

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>")
                    .append(HtmlUtils.escapeHtml(communityName)).append(" — Страница ").append(page).append("</title>")
                    .append(cssStyles).append("</head><body>");

            html.append("<div class='community-container'>");
            if (localCoverName != null) {
                html.append("<img class='community-cover' src='./").append(localCoverName).append("' />");
            }
            html.append("<div class='community-header'>");
            html.append("<img class='community-avatar' src='./").append(localAvatarName).append("' />");
            html.append("<div class='community-info'>");
            html.append("<h1>").append(HtmlUtils.escapeHtml(communityName)).append("</h1>");
            if (communityDesc != null && !communityDesc.isEmpty()) {
                html.append("<p>").append(HtmlUtils.escapeHtml(communityDesc)).append("</p>");
            }
            html.append("</div></div></div>");

            for (WallItem post : pagePosts) {
                LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(post.getDate()), ZoneId.systemDefault());
                String formattedDate = dateTime.format(DATE_FORMATTER);

                html.append("<div class='post'>");

                html.append("<div class='post-author-block'>");
                html.append("<img class='post-avatar' src='./").append(localAvatarName).append("' />");
                html.append("<div class='header-meta'>");
                html.append("<strong>").append(HtmlUtils.escapeHtml(communityName)).append("</strong><br>");
                html.append("<span style='font-size:12px; color:#818c99;'>").append(formattedDate).append(" | ID: ").append(post.getId()).append("</span>");
                html.append("</div></div>");

                html.append("<div class='text'>").append(post.getText() != null ? HtmlUtils.escapeHtml(post.getText()) : "").append("</div>");

                html.append(processAttachments(post.getAttachments(), post.getId()));

                html.append(processRepostHistory(post.getCopyHistory(), post.getId()));

                int likes = post.getLikes() != null ? post.getLikes().getCount() : 0;
                int comments = post.getComments() != null ? post.getComments().getCount() : 0;
                int reposts = post.getReposts() != null ? post.getReposts().getCount() : 0;
                int views = post.getViews() != null ? post.getViews().getCount() : 0;

                html.append("<div class='post-footer'>");
                html.append("<span>❤️ ").append(likes).append("</span>");
                html.append("<span>💬 ").append(comments).append("</span>");
                html.append("<span>🔄 ").append(reposts).append("</span>");
                html.append("<span>👁️ ").append(views).append("</span>");
                html.append("</div>");

                html.append("</div>");
            }

            if (totalPages > 1) {
                html.append("<div class='pagination'>");
                for (int i = 1; i <= totalPages; i++) {
                    String fileName = (i == 1) ? "index.html" : "index_" + i + ".html";
                    if (i == page) {
                        html.append("<span class='current'>").append(i).append("</span>");
                    } else {
                        html.append("<a href='./").append(fileName).append("'>").append(i).append("</a>");
                    }
                }
                html.append("</div>");
            }

            html.append("</body></html>");

            String pageFileName = (page == 1) ? "index.html" : "index_" + page + ".html";
            try (PrintWriter out = new PrintWriter(outputDir + pageFileName, "UTF-8")) {
                out.print(html.toString());
            } catch (FileNotFoundException | UnsupportedEncodingException e) {
                System.out.println("Error processing custom HTML page " + page);
                e.printStackTrace();
                throw new DownloadingException(e);
            }
        }
    }

    private String processAttachments(List<WallpostAttachment> attachments, int postId) {
        if (attachments == null || attachments.isEmpty()) return "";

        StringBuilder html = new StringBuilder();
        html.append("<div class='attachments'>");

        attachments.forEach(attach -> {
            try {
                switch (attach.getType()) {
                    case PHOTO:
                        URL maxPhotoUrl = attach.getPhoto().getSizes().stream()
                                .max(Comparator.comparingInt(PhotoSizes::getHeight))
                                .get().getUrl().toURL();

                        String localImgName = "photo_" + postId + "_" + attach.getPhoto().getId() + ".jpg";
                        downloadFile(maxPhotoUrl.toString(), imagesDir + localImgName);
                        html.append("<img class='img-attach' src='./images/").append(localImgName).append("' />");
                        html.append("<a class='origin-link' href='").append(maxPhotoUrl.toString()).append("' target='_blank'>").append(maxPhotoUrl.toString()).append("</a>");
                        break;

                    case DOC:
                    case GRAFFITI:
                        Doc doc = attach.getDoc();
                        String docUrl = doc.getUrl().toString();
                        String origTitle = doc.getTitle() != null ? doc.getTitle() : "document_" + doc.getId();
                        String localDocName = doc.getId() + "_" + origTitle;

                        downloadFile(docUrl, documentsDir + localDocName);

                        html.append("<div class='other-attach'>")
                                .append("Файл: ")
                                .append("<a href='./documents/").append(localDocName).append("' download>").append(HtmlUtils.escapeHtml(origTitle)).append("</a><br>")
                                .append("<a class='origin-link' href='").append(docUrl).append("' target='_blank'>").append(docUrl).append("</a>")
                                .append("</div>");
                        break;

                    case AUDIO:
                        Audio audio = attach.getAudio();
                        String audioUrlStr = (audio.getUrl() != null) ? audio.getUrl().toString() : "#";
                        html.append("<div class='other-attach'>")
                                .append(HtmlUtils.escapeHtml(audio.getArtist())).append(" — ").append(HtmlUtils.escapeHtml(audio.getTitle()))
                                .append(" (").append(HtmlUtils.formatDuration(audio.getDuration())).append(")<br>")
                                .append("<a class='origin-link' href='").append(audioUrlStr).append("' target='_blank'>").append(audioUrlStr).append("</a>")
                                .append("</div>");
                        break;

                    case VIDEO:
                        Video video = attach.getVideo();
                        String vkVideoUrl = "https://vk.ru/video" + video.getOwnerId() + "_" + video.getId();

                        String videoThumbTag = "";
                        if (video.getImage() != null && !video.getImage().isEmpty()) {
                            VideoImage maxImage = video.getImage().stream()
                                    .max(Comparator.comparingInt(VideoImage::getWidth))
                                    .orElse(null);
                            if (maxImage != null) {
                                String localVideoImg = "video_thumb_" + video.getId() + ".jpg";
                                downloadFile(maxImage.getUrl().toString(), imagesDir + localVideoImg);
                                videoThumbTag = "<img class='img-attach' src='./images/" + localVideoImg + "'/>";
                            }
                        }

                        html.append("<div class='other-attach'>")
                                .append(HtmlUtils.escapeHtml(video.getTitle())).append(" (").append(HtmlUtils.formatDuration(video.getDuration())).append(")<br>")
                                .append("<a class='origin-link' href='").append(vkVideoUrl).append("' target='_blank'>")
                                .append(videoThumbTag)
                                .append(vkVideoUrl).append("</a>")
                                .append("</div>");
                        break;

                    case LINK:
                        Link link = attach.getLink();
                        html.append("<div class='other-attach link-attach'>")
                                .append("<a href='").append(link.getUrl()).append("' target='_blank'>").append(HtmlUtils.escapeHtml(link.getTitle())).append("</a><br>");
                        if (link.getDescription() != null && !link.getDescription().isEmpty()) {
                            html.append("<small style='color:gray;'>").append(HtmlUtils.escapeHtml(link.getDescription())).append("</small><br>");
                        }
                        html.append("<a class='origin-link' href='").append(link.getUrl()).append("' target='_blank'>").append(link.getUrl()).append("</a>")
                                .append("</div>");
                        break;

                    case POLL:
                        com.vk.api.sdk.objects.polls.Poll poll = attach.getPoll();
                        html.append("<div class='other-attach poll-attach'>")
                                .append(HtmlUtils.escapeHtml(poll.getQuestion())).append("<br><ul style='margin:5px 0; padding-left:20px;'>");
                        if (poll.getAnswers() != null) {
                            poll.getAnswers().forEach(ans -> html.append("<li>").append(HtmlUtils.escapeHtml(ans.getText()))
                                    .append(" — <em>").append(ans.getVotes()).append("</em></li>"));
                        }
                        html.append("</ul><small style='color:gray;'>Всего голосов: ").append(poll.getVotes()).append("</small></div>");
                        break;

                    case ALBUM:
                        PhotoAlbum album = attach.getAlbum();
                        String albumUrl = "https://vk.com" + album.getOwnerId() + "_" + album.getId();
                        String albumThumbTag = "";
                        if (album.getThumb() != null) {
                            PhotoSizes maxAlbumSize = album.getThumb().getSizes().stream()
                                    .max(Comparator.comparingInt(PhotoSizes::getHeight))
                                    .orElse(null);
                            if (maxAlbumSize != null) {
                                String localAlbumImg = "album_thumb_" + album.getId() + ".jpg";
                                downloadFile(maxAlbumSize.getUrl().toURL().toString(), imagesDir + localAlbumImg);
                                albumThumbTag = "<img class='img-attach' src='./images/" + localAlbumImg + "' style='max-height:150px; margin-bottom:5px;'/>";
                            }
                        }
                        html.append("<div class='other-attach'>")
                                .append(HtmlUtils.escapeHtml(album.getTitle())).append("<br>").append(albumThumbTag)
                                .append("<a class='origin-link' href='").append(albumUrl).append("' target='_blank'>").append(albumUrl).append("</a>")
                                .append("</div>");
                        break;

                    case MARKET:
                        com.vk.api.sdk.objects.market.MarketItem market = attach.getMarket();
                        html.append("<div class='other-attach'>")
                                .append(HtmlUtils.escapeHtml(market.getTitle())).append("<br>")
                                .append("<span style='color:#4bb34b; font-weight:bold;'>Цена: ").append(market.getPrice().getText()).append("</span>")
                                .append("</div>");
                        break;

                    default:
                        html.append("<div class='other-attach'>📎 Вложение: ").append(attach.getType().toString()).append("</div>");
                        break;
                }
            } catch (Exception e) {
                html.append("<div class='other-attach'>Ошибка загрузки вложения</div>");
            }
        });

        html.append("</div>");
        return html.toString();
    }

    private String processRepostHistory(List<WallpostFull> history, int parentPostId) {
        if (history == null || history.isEmpty()) return "";

        StringBuilder html = new StringBuilder();
        for (WallpostFull subPost : history) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(subPost.getDate()), ZoneId.systemDefault());
            String formattedDate = dateTime.format(DATE_FORMATTER);

            html.append("<div class='repost-block' style='border-left: 3px solid #5181b8; padding-left: 15px; margin-top: 15px; background: #fafbfc; padding-top: 5px; padding-bottom: 5px;'>");
            html.append("<div class='header' style='border:none; font-size:13px;'>🔄 <strong>Репост</strong> | ").append(formattedDate).append(" | ID: ").append(subPost.getId()).append("-").append(subPost.getOwnerId()).append("</div>");
            html.append("<div class='text' style='font-size:14px;'>").append(subPost.getText() != null ? HtmlUtils.escapeHtml(subPost.getText()) : "").append("</div>");

            // Передаем уникальный ID, складывая ID родителя и репоста, чтобы избежать конфликтов имен файлов картинок
            html.append(processAttachments(subPost.getAttachments(), parentPostId + subPost.getId()));
            html.append("</div>");
        }
        return html.toString();
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
            System.out.println("failed to download: " + urlStr);
        }
    }

}
