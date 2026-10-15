package com.iflytek.skillhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SkillAvatarImageProcessorTest {
    private final SkillAvatarImageProcessor processor = new SkillAvatarImageProcessor();

    @Test
    void acceptsSquareJpegAndReencodesAsPng() throws Exception {
        byte[] jpeg = image("jpeg", 48, 48);
        byte[] result = processor.normalize(jpeg);
        assertThat(result).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(decoded.getWidth()).isEqualTo(48);
        assertThat(decoded.getHeight()).isEqualTo(48);
    }

    @Test
    void rejectsOversizedMalformedAndNonSquareInput() throws Exception {
        assertThatThrownBy(() -> processor.normalize(new byte[SkillAvatarImageProcessor.MAX_BYTES + 1]))
                .isInstanceOf(DomainBadRequestException.class);
        assertThatThrownBy(() -> processor.normalize(new byte[] {(byte) 0x89, 'P', 'N', 'G'}))
                .isInstanceOf(DomainBadRequestException.class);
        assertThatThrownBy(() -> processor.normalize(image("png", 48, 32)))
                .isInstanceOf(DomainBadRequestException.class);
        assertThatThrownBy(() -> processor.normalize(image("png", 1025, 1025)))
                .isInstanceOf(DomainBadRequestException.class);
    }

    private byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
