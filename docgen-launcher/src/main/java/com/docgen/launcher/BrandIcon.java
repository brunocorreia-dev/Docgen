package com.docgen.launcher;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The DocGen mark (the gradient "D" of docgen-web's favicon.svg) drawn with
 * Java2D, so the window icon and the packaged app icon come from one source
 * and no binary image has to live in the repository.
 */
public final class BrandIcon {
    static final List<Integer> WINDOW_SIZES = List.of(16, 24, 32, 48, 64, 128, 256);

    private BrandIcon() {
    }

    static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(size / 48.0, size / 48.0);
            g.setPaint(new LinearGradientPaint(0, 0, 48, 48, new float[]{0f, 0.55f, 1f},
                    new Color[]{LauncherTheme.BLUE, LauncherTheme.PURPLE, LauncherTheme.CYAN}));
            g.fill(new RoundRectangle2D.Float(2, 2, 44, 44, 26, 26));
            // Same geometry as favicon.svg: an outlined "D" with a solid inner block.
            GeneralPath outer = new GeneralPath();
            outer.moveTo(15, 14);
            outer.lineTo(24, 14);
            outer.curveTo(29.5f, 14, 34, 18.5f, 34, 24);
            outer.curveTo(34, 29.5f, 29.5f, 34, 24, 34);
            outer.lineTo(15, 34);
            outer.closePath();
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(outer);
            GeneralPath inner = new GeneralPath();
            inner.moveTo(21, 21);
            inner.lineTo(24.5f, 21);
            inner.curveTo(26.2f, 21, 27.5f, 22.3f, 27.5f, 24);
            inner.curveTo(27.5f, 25.7f, 26.2f, 27, 24.5f, 27);
            inner.lineTo(21, 27);
            inner.closePath();
            g.fill(inner);
        } finally {
            g.dispose();
        }
        return image;
    }

    static List<BufferedImage> windowIcons() {
        return WINDOW_SIZES.stream().map(BrandIcon::render).toList();
    }

    /** Writes the icon as PNG; used by the packaging script ({@code BrandIcon <file.png> [size]}). */
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: BrandIcon <arquivo.png> [tamanho]");
            System.exit(2);
        }
        int size = args.length == 2 ? Integer.parseInt(args[1]) : 512;
        if (size < 16 || size > 4096) {
            throw new IllegalArgumentException("size must be between 16 and 4096");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(render(size), "png", output.toFile())) {
            throw new IOException("PNG encoding is unavailable");
        }
    }
}
