package org.levimc.launcher.core.discord;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class DiscordAuthStore {

    private static final String FILE_NAME = "discord_rpc_tokens.json";
    private static final Gson GSON = new Gson();

    private DiscordAuthStore() {
    }

    public static DiscordTokens load(File dir) {
        File file = new File(dir, FILE_NAME);
        if (!file.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, DiscordTokens.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(File dir, DiscordTokens tokens) throws IOException {
        dir.mkdirs();
        File file = new File(dir, FILE_NAME);
        File temp = new File(dir, FILE_NAME + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(tokens));
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void clear(File dir) {
        new File(dir, FILE_NAME).delete();
    }
}
