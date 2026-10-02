import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Draws the TxRay icon: a database cylinder crossed by a glowing scan line.
 * <p>
 * {@code java tools/MakeIcon.java 16 bundles/org.eclipse.txray/icons/txray.png}
 */
public class MakeIcon {

    public static void main(String[] args) throws Exception {
        int size = Integer.parseInt(args[0]);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 16.0, size / 16.0);

        double x = 2.5, w = 11, top = 2, bottom = 14, eh = 3.6;
        Color body = new Color(0x2B, 0x5B, 0x84);
        Color light = new Color(0x4F, 0x8F, 0xC0);
        Color edge = new Color(0x1B, 0x3A, 0x55);

        // body
        g.setPaint(new GradientPaint(0, 0, light, 16, 0, body));
        g.fill(new Rectangle2D.Double(x, top + eh / 2, w, bottom - top - eh));
        g.fill(new Ellipse2D.Double(x, bottom - eh, w, eh));
        // top
        g.setColor(new Color(0x9C, 0xC8, 0xE8));
        g.fill(new Ellipse2D.Double(x, top, w, eh));

        g.setStroke(new BasicStroke(0.8f));
        g.setColor(edge);
        g.draw(new Ellipse2D.Double(x, top, w, eh));
        g.draw(new Line2D.Double(x, top + eh / 2, x, bottom - eh / 2));
        g.draw(new Line2D.Double(x + w, top + eh / 2, x + w, bottom - eh / 2));
        g.draw(new java.awt.geom.Arc2D.Double(x, bottom - eh, w, eh, 180, 180, java.awt.geom.Arc2D.OPEN));

        // scan line
        double y = 9.2;
        g.setColor(new Color(0x39, 0xFF, 0x9C, 90));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(0.8, y, 15.2, y));
        g.setColor(new Color(0x39, 0xFF, 0x9C));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(0.8, y, 15.2, y));

        g.dispose();
        File out = new File(args[1]);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }
}
