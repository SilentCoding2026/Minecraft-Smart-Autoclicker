package com.ra.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class SmartClicksCommand {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("smartclicks")
                .then(literal("mode")
                    .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                        .executes(ctx -> {
                            String mode = StringArgumentType.getString(ctx, "mode");
                            sendHttpRequest("http://127.0.0.1:4321/set_mode?mode=" + mode);
                            return 1;
                        })))
                .then(literal("toggle")
                    .then(ClientCommandManager.argument("feature", StringArgumentType.word())
                        .executes(ctx -> {
                            String feature = StringArgumentType.getString(ctx, "feature");
                            sendHttpRequest("http://127.0.0.1:4321/toggle?feature=" + feature);
                            return 1;
                        })))
            );
        });
    }

    private static void sendHttpRequest(String urlString) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                System.out.println("SmartClicks: HTTP error: " + e.getMessage());
            }
        }).start();
    }
}