package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.ProductTable.ProductInfo;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.function.BiFunction;

public class GMVTable extends JPanel {

    private JTable mainTable;
    private JTable rowHeaderTable;
    private JTable columnHeaderTable;

    private DefaultTableModel mainModel;

    private BiFunction<ProductInfo, YearMonth, Void> cellListener;

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
        // Add a mouse listener to the table
        mainTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = mainTable.rowAtPoint(e.getPoint());
                int column = mainTable.columnAtPoint(e.getPoint());
                var product = rowHeaderTable.getValueAt(row, 0);
                var month = columnHeaderTable.getValueAt(0, column);
                System.out.println(product + " " + month);
                if (cellListener!=null) cellListener.apply((ProductInfo) product, (YearMonth) month);
            }
        });

        JScrollPane scrollPane = new JScrollPane(mainTable);

        // Row header table
        rowHeaderTable = new JTable() {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        rowHeaderTable.setModel(new DefaultTableModel());
        rowHeaderTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        rowHeaderTable.setSelectionModel(mainTable.getSelectionModel());
        rowHeaderTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer(){
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                if (value instanceof ProductInfo p) {
                    value = p.name();
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        });
        scrollPane.setRowHeaderView(rowHeaderTable);

        // Column header table (simulating frozen top row)
        columnHeaderTable = new JTable() {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        columnHeaderTable.setModel(new DefaultTableModel());
        columnHeaderTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        columnHeaderTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer(){
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                if (value instanceof YearMonth ym) {
                    value = ym.toString();
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        });
        scrollPane.setColumnHeaderView(columnHeaderTable);

        add(scrollPane, BorderLayout.CENTER);
    }

    public void updateData(SortedMap<ProductInfo, SortedMap<YearMonth, BigDecimal>> data) {
        // Collect months and products
        TreeSet<YearMonth> monthsSet = new TreeSet<>();
        List<ProductInfo> sortedProducts = data.keySet().stream().sorted(ProductInfo.nameComparator).toList();

        for (Map<YearMonth, BigDecimal> monthData : data.values()) {
            monthsSet.addAll(monthData.keySet());
        }

        List<YearMonth> sortedMonths = new ArrayList<>(monthsSet);

        // Set column headers for the main table
        Object[] columnNames = sortedMonths.stream().map(YearMonth::toString).toArray();
        Object[][] tableData = new Object[sortedProducts.size()][sortedMonths.size()];

        for (int r = 0; r < sortedProducts.size(); r++) {
            var product = sortedProducts.get(r);
            Map<YearMonth, BigDecimal> gmvData = data.get(product);
            for (int c = 0; c < sortedMonths.size(); c++) {
                tableData[r][c] = gmvData.getOrDefault(sortedMonths.get(c), BigDecimal.ZERO);
            }
        }

        mainModel.setDataVector(tableData, columnNames);

        // Set the row header table (1 column: product names)
        Object[][] rowHeaderData = new Object[sortedProducts.size()][1];
        for (int i = 0; i < sortedProducts.size(); i++) {
            rowHeaderData[i][0] = sortedProducts.get(i);
        }
        DefaultTableModel rowHeaderModel = new DefaultTableModel(rowHeaderData, new Object[]{"Product"});
        rowHeaderTable.setModel(rowHeaderModel);

        // Set column header table (1 row: months)
        Object[][] columnHeaderData = new Object[1][sortedMonths.size()];
        for (int i = 0; i < sortedMonths.size(); i++) {
            columnHeaderData[0][i] = sortedMonths.get(i);
        }
        DefaultTableModel columnHeaderModel = new DefaultTableModel(columnHeaderData, columnNames);
        columnHeaderTable.setModel(columnHeaderModel);

        // Match column widths
        for (int i = 0; i < sortedMonths.size(); i++) {
            int width = 100;
            mainTable.getColumnModel().getColumn(i).setPreferredWidth(width);
            columnHeaderTable.getColumnModel().getColumn(i).setPreferredWidth(width);
        }

        // --- BEGIN: add totals for K, J, S, Z -----------------
        // prefixes to group by:
        List<String> groups = List.of("K", "J", "S", "Z");

        // retrieve the models so we can append to them
        DefaultTableModel mainM = (DefaultTableModel) mainTable.getModel();
        DefaultTableModel rowHeaderM = (DefaultTableModel) rowHeaderTable.getModel();

        for (String prefix : groups) {
            // accumulate per-month sums
            BigDecimal[] sums = new BigDecimal[sortedMonths.size()];
            Arrays.fill(sums, BigDecimal.ZERO);

            for (int r = 0; r < sortedProducts.size(); r++) {
                String prodName = sortedProducts.get(r).name();
                if (prodName.startsWith(prefix)) {
                    // add each column's value
                    for (int c = 0; c < sortedMonths.size(); c++) {
                        Object cell = mainM.getValueAt(r, c);
                        if (cell instanceof BigDecimal bd) {
                            sums[c] = sums[c].add(bd);
                        }
                    }
                }
            }

            // append row to the main table
            mainM.addRow(sums);
            // append corresponding label to row‐header table
            rowHeaderM.addRow(new Object[]{ prefix + " total" });
        }

        // recalc row‐header width in case an "X total" label needs more room
        FontMetrics fm = rowHeaderTable.getFontMetrics(rowHeaderTable.getFont());
        int rowHeaderWidth = 0;
        for (int i = 0; i < rowHeaderM.getRowCount(); i++) {
            String label = rowHeaderM.getValueAt(i, 0).toString();
            rowHeaderWidth = Math.max(rowHeaderWidth, fm.stringWidth(label) + 20);
        }
        rowHeaderTable.getColumnModel().getColumn(0).setPreferredWidth(rowHeaderWidth);
        // --- END: add totals -----------------
    }

    public void setCellListener(BiFunction<ProductInfo, YearMonth, Void> listener) {
        cellListener = listener;
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
}