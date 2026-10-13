package edu.vkParser;


import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.httpclient.HttpTransportClient;
import edu.vkParser.actions.CommunityPostsDownloader;
import edu.vkParser.actions.DialogMessagesDownloader;
import edu.vkParser.exception.DownloadingException;
import edu.vkParser.exception.SetupException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {

        System.out.println("vk parser");


        while (true) {
            System.out.println();
            System.out.println("! user api key should be the first line in api-keys.txt");
            System.out.println("! user ID number should be the second line in api-keys.txt");
            System.out.println("Select option:");
            System.out.println("1.  Download posts from community group");
            System.out.println("2.  Download messages from dialog");
            System.out.println("3.  Download messages from chat");
            System.out.println("0.  Exit");

            Scanner scanner = new Scanner(System.in);
            String input = scanner.nextLine();

            try {
                int decision = Integer.parseInt(input);
                if (decision == 0) {
                    System.exit(0);
                }
                if (decision < 0 || decision > 3) {
                    continue;
                }


                try (BufferedReader reader = new BufferedReader(new FileReader("api-keys.txt"))) {
                    String apiKey = reader.readLine();
                    String userIdString = reader.readLine();
                    long userId;
                    if (apiKey == null || apiKey.length() < 200) {
                        System.out.println("api key is invalid");
                        continue;
                    }
                    if (userIdString == null || userIdString.isEmpty()) {
                        System.out.println("user id is invalid");
                        continue;
                    } else {
                        try {
                            userId = Long.parseLong(userIdString);
                        } catch (NumberFormatException e) {
                            System.out.println("user id is invalid");
                            continue;
                        }
                    }
                    if (decision == 1) {
                        System.out.println("enter community id:");
                        String communityId = scanner.nextLine();
                        if (communityId == null || communityId.isEmpty()) {
                            System.out.println("community id is invalid");
                            continue;
                        }
                        CommunityPostsDownloader downloader = new CommunityPostsDownloader(userId, apiKey, new VkApiClient(new HttpTransportClient()), communityId);
                        downloader.setupSettings();
                        downloader.startDownloading();
                        System.out.println("Success!");
                        System.out.println("press enter to continue...");
                        scanner.nextLine();
                    }
                    if (decision == 2) {
                        System.out.println("enter peer profile id:");
                        String peerIdString = scanner.nextLine();
                        if (peerIdString == null || peerIdString.isEmpty()) {
                            System.out.println("peer id is invalid");
                            continue;
                        }
                        try {
                            long peerId = Long.parseLong(peerIdString);
                            DialogMessagesDownloader downloader = new DialogMessagesDownloader(userId, apiKey, new VkApiClient(new HttpTransportClient()), peerId);
                            downloader.setupSettings();
                            downloader.startDownloading();
                            System.out.println("Success!");
                            System.out.println("press enter to continue...");
                            scanner.nextLine();
                        } catch (NumberFormatException e) {
                            System.out.println("peer id is invalid");
                            continue;
                        }
                    }

                    if(decision==3) {
                        System.out.println("3");
                    }


                } catch (IOException e) {
                    System.out.println("error reading file api-keys.txt");
                    continue;
                } catch (SetupException e) {
                    System.out.println("error while setting up downloader. try again.");
                    continue;
                } catch (DownloadingException e) {
                    System.out.println("error while trying to download. try again.");
                    continue;
                }
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
    }
}