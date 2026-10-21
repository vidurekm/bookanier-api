package online.bookanear.api;

import online.bookanear.api.exception.ApiException;
import online.bookanear.api.service.MediaImageProcessor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MediaImageProcessorTest {
    private final MediaImageProcessor processor = new MediaImageProcessor();

    @Test
    void pngIsDecodedAndReencoded() throws Exception {
        BufferedImage image = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        var result = processor.process(bytes.toByteArray());
        assertEquals("image/png", result.contentType());
        assertEquals(100, result.width());
        assertEquals(50, result.height());
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(result.bytes())));
    }

    @Test
    void rejectsInvalidImages() {
        assertThrows(ApiException.class, () -> processor.process("not an image".getBytes()));
    }

    @Test
    void largeImageIsDownscaled() throws Exception {
        BufferedImage image = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpeg", bytes));
        var result = processor.process(bytes.toByteArray());
        assertEquals(1920, result.width());
        assertEquals(960, result.height());
    }
}
