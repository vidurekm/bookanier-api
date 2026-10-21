package online.bookanear.api.service;

import online.bookanear.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/** Decodes and re-encodes images, discarding original metadata (including EXIF/GPS). */
@Component
public class MediaImageProcessor {
    private static final int MAX_PIXELS = 20_000_000;
    private static final int MAX_EDGE = 1920;

    public record ProcessedImage(byte[] bytes, String contentType, int width, int height) {}

    public ProcessedImage process(byte[] original) {
        if (original == null || original.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Empty image");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg") && !format.equals("png")) throw invalid();
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long) w * h > MAX_PIXELS) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Image dimensions exceed limit");
                }
                BufferedImage source = reader.read(0);
                if (source == null) throw invalid();
                double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
                int outW = Math.max(1, (int) Math.round(w * scale));
                int outH = Math.max(1, (int) Math.round(h * scale));
                boolean jpeg = !format.equals("png");
                BufferedImage clean = new BufferedImage(outW, outH,
                        jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = clean.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(source, 0, 0, outW, outH, null);
                } finally {
                    g.dispose();
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(clean, jpeg ? "jpeg" : "png", output)) throw invalid();
                return new ProcessedImage(output.toByteArray(), jpeg ? "image/jpeg" : "image/png", outW, outH);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw invalid();
        }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Only valid JPEG and PNG images are supported");
    }
}
