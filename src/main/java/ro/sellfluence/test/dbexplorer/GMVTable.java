package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.EmagMirrorDB;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;

public class GMVTable extends JPanel {

    private JTable mainTable;
    private JTable rowHeaderTable;
    private JTable columnHeaderTable;
    private JLabel cornerLabel;
    private JTable cornerTable;
    private JScrollPane scrollPane;
    private JPanel cornerPanel;

    private DefaultTableModel mainModel;

    private List<YearMonth> sortedMonths = new ArrayList<>();
    private List<String> sortedProducts = new ArrayList<>();

    public GMVTable() {
        setLayout(new BorderLayout());
        initializeComponents();
    }

    private void initializeComponents() {
        mainModel = new DefaultTableModel();
        mainTable = new JTable(mainModel) {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        mainTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        mainTable.setDefaultRenderer(Object.class, new NumberCellRenderer());

        scrollPane = new JScrollPane(mainTable);

        // Row header table
        rowHeaderTable = new JTable() {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        rowHeaderTable.setModel(new DefaultTableModel());
        rowHeaderTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        rowHeaderTable.setSelectionModel(mainTable.getSelectionModel());
        rowHeaderTable.setDefaultRenderer(Object.class, new HeaderRenderer());
        scrollPane.setRowHeaderView(rowHeaderTable);

        // Column header table (simulating frozen top row)
        columnHeaderTable = new JTable() {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        columnHeaderTable.setModel(new DefaultTableModel());
        columnHeaderTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        columnHeaderTable.setDefaultRenderer(Object.class, new HeaderRenderer());
        scrollPane.setColumnHeaderView(columnHeaderTable);

        // Top-left corner: static label
        cornerLabel = new JLabel("Product", SwingConstants.LEFT);
        cornerLabel.setOpaque(true);
        cornerLabel.setFont(cornerLabel.getFont().deriveFont(Font.BOLD));
        cornerTable = new JTable(1, 1) {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        cornerTable.setValueAt("Product", 0, 0);
        cornerTable.setPreferredScrollableViewportSize(new Dimension(0, 0));
        cornerTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        cornerTable.setDefaultRenderer(Object.class, new HeaderRenderer());
        cornerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        cornerPanel.add(cornerLabel);
        //cornerPanel.setBackground(cornerLabel.getBackground());
        scrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, cornerTable);

        add(scrollPane, BorderLayout.CENTER);
    }

    public void updateData(SortedMap<String, SortedMap<YearMonth, BigDecimal>> data) {
        // Collect months and products
        TreeSet<YearMonth> monthsSet = new TreeSet<>();
        sortedProducts = new ArrayList<>(data.keySet());
        Collections.sort(sortedProducts);

        for (Map<YearMonth, BigDecimal> monthData : data.values()) {
            monthsSet.addAll(monthData.keySet());
        }

        sortedMonths = new ArrayList<>(monthsSet);

        // Set column headers for main table
        Object[] columnNames = sortedMonths.stream().map(YearMonth::toString).toArray();
        Object[][] tableData = new Object[sortedProducts.size()][sortedMonths.size()];

        for (int r = 0; r < sortedProducts.size(); r++) {
            String product = sortedProducts.get(r);
            Map<YearMonth, BigDecimal> gmvData = data.get(product);
            for (int c = 0; c < sortedMonths.size(); c++) {
                tableData[r][c] = gmvData.getOrDefault(sortedMonths.get(c), BigDecimal.ZERO);
            }
        }

        mainModel.setDataVector(tableData, columnNames);

        // Set row header table (1 column: product names)
        Object[][] rowHeaderData = new Object[sortedProducts.size()][1];
        for (int i = 0; i < sortedProducts.size(); i++) {
            rowHeaderData[i][0] = sortedProducts.get(i);
        }
        DefaultTableModel rowHeaderModel = new DefaultTableModel(rowHeaderData, new Object[]{"Product"});
        rowHeaderTable.setModel(rowHeaderModel);

        // Set column header table (1 row: months)
        Object[][] columnHeaderData = new Object[1][sortedMonths.size()];
        for (int i = 0; i < sortedMonths.size(); i++) {
            columnHeaderData[0][i] = sortedMonths.get(i).toString();
        }
        DefaultTableModel columnHeaderModel = new DefaultTableModel(columnHeaderData, columnNames);
        columnHeaderTable.setModel(columnHeaderModel);

        // Match column widths
        for (int i = 0; i < sortedMonths.size(); i++) {
            int width = 100;
            mainTable.getColumnModel().getColumn(i).setPreferredWidth(width);
            columnHeaderTable.getColumnModel().getColumn(i).setPreferredWidth(width);
        }

        FontMetrics fm = rowHeaderTable.getFontMetrics(rowHeaderTable.getFont());
        int rowHeaderWidth = sortedProducts.stream()
                .mapToInt(name -> fm.stringWidth(name) + 20) // 20px padding
                .max()
                .orElse(150);
        rowHeaderTable.getColumnModel().getColumn(0).setPreferredWidth(rowHeaderWidth);

        // Set the same width on the corner label
        cornerTable.getColumnModel().getColumn(0).setPreferredWidth(rowHeaderWidth);
        cornerTable.setRowHeight(columnHeaderTable.getRowHeight());

//        Dimension cornerSize = new Dimension(rowHeaderWidth, columnHeaderTable.getRowHeight());
//        cornerLabel.setPreferredSize(cornerSize);
//        cornerLabel.setMinimumSize(cornerSize);
//        cornerLabel.setMaximumSize(cornerSize);
//        cornerLabel.setSize(cornerSize);
//        cornerPanel.setPreferredSize(cornerSize);
//        cornerPanel.setMinimumSize(cornerSize);
//        cornerPanel.setMaximumSize(cornerSize);
//        cornerPanel.setSize(cornerSize);
//        cornerPanel.revalidate();
//        cornerPanel.repaint();
    }

    private static class HeaderRenderer extends DefaultTableCellRenderer {
        public HeaderRenderer() {
            setOpaque(true);
            setFont(getFont().deriveFont(Font.BOLD));
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            return this;
        }
    }

    private static class NumberCellRenderer extends DefaultTableCellRenderer {
        private final NumberFormat format = NumberFormat.getNumberInstance();

        public NumberCellRenderer() {
            format.setMinimumFractionDigits(2);
            format.setMaximumFractionDigits(2);
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            if (value instanceof BigDecimal) {
                value = format.format(value);
            }
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }


    // Main method for demo/testing
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("GMV Table");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            GMVTable gmvTable = new GMVTable();
            try {
                var data = EmagMirrorDB.getEmagMirrorDB("emagLocal").getGMV();
                gmvTable.updateData(data);
            } catch (SQLException | IOException e) {
                throw new RuntimeException(e);
            }
            frame.add(gmvTable);
            frame.setSize(800, 400);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}