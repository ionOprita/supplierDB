package ro.sellfluence.test.dbexplorer;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.List;

public class HistogramPanel extends JPanel {

    private List<EmagFetchHistogram> histogram;

    public HistogramPanel(List<EmagFetchHistogram> histogram) {
        this.histogram = histogram;
    }

    public void setData(List<EmagFetchHistogram> histogram) {
        this.histogram = histogram;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(800, 600);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (histogram == null || histogram.isEmpty()) {
            return; // Nothing to paint
        }
        int panelWidth = getWidth();
        int panelHeight = getHeight();
        int maxCount = histogram.stream().mapToInt(EmagFetchHistogram::count).max().orElse(1); // Find max count for scaling
        int barWidth = panelWidth / histogram.size();
        for (int i = 0; i < histogram.size(); i++) {
            EmagFetchHistogram item = histogram.get(i);
            g.setColor(Color.ORANGE);
            int barHeight = (int) ((double) item.count() / maxCount * panelHeight * 0.8); // Scale to 80% of panel height
            int x = i * barWidth;
            int y = panelHeight - barHeight;
            g.fillRect(x, y, barWidth, barHeight); // Draw the bar
            g.setColor(Color.BLACK);
            g.drawString(String.valueOf(item.days()), x + barWidth / 4, panelHeight - 10); //Label underneath
        }
    }
}
