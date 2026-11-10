package teacommontea.kawe.tool;

import net.kyori.adventure.text.Component;
import teacommontea.kawe.Sched;
import teacommontea.kawe.command.CommandError;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;

public final class HeightImages {

    private static final Set<String> HOSTS = Set.of("i.imgur.com", "imgur.com");
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HeightImages() {
    }

    public static BufferedImage load(String name) throws CommandError {
        Path folder = Sched.plugin().getDataFolder().toPath().resolve("heightmaps").toAbsolutePath().normalize();
        String file = name.toLowerCase(Locale.ROOT).endsWith(".png") ? name : name + ".png";
        Path local = null;
        if (!name.contains("://")) {
            try {
                local = folder.resolve(file).normalize();
            } catch (InvalidPathException e) {
                throw new CommandError("kawe.brush.brush.height.invalid", name);
            }
        }
        try {
            if (local != null && local.startsWith(folder) && Files.isRegularFile(local)) {
                try (InputStream in = Files.newInputStream(local)) {
                    return decode(in, name);
                }
            }
            URI uri;
            try {
                uri = URI.create(name);
            } catch (IllegalArgumentException e) {
                throw new CommandError("kawe.brush.brush.height.invalid", name);
            }
            if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https") || uri.getHost() == null
                    || !HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw new CommandError(Component.text("Heightmaps load from plugins/" + Sched.plugin().getName() + "/heightmaps or https://i.imgur.com links"));
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new CommandError("kawe.brush.brush.height.invalid", "HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new CommandError(Component.text("The image is larger than 16 MB"));
                }
                return decode(new ByteArrayInputStream(bytes), name);
            }
        } catch (IOException e) {
            throw new CommandError("kawe.brush.brush.height.invalid", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandError("kawe.brush.brush.height.invalid", "interrupted");
        }
    }

    private static BufferedImage decode(InputStream in, String name) throws IOException, CommandError {
        BufferedImage image = ImageIO.read(in);
        if (image == null) {
            throw new CommandError("kawe.brush.brush.height.invalid", name);
        }
        return image;
    }
}
