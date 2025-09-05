package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.POInfo;
import ro.sellfluence.db.ProductTable.ProductInfo;
import ro.sellfluence.emagapi.OrderResult;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static ro.sellfluence.test.dbexplorer.EmagDBExplorer.setColumnWidths;

public class POInfoTable extends JPanel {

    private JTable mainTable;
    private JScrollPane scrollPane;

    private POInfoTableModel mainModel;

    private List<YearMonth> sortedMonths = new ArrayList<>();
    private List<ProductInfo> sortedProducts = new ArrayList<>();
    private BiFunction<ProductInfo, YearMonth, Void> cellListener;

    public POInfoTable() {
        initializeComponents();
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
    }

    public void updateTable(List<POInfo> data) {
        mainModel.updatePOInfos(data);
        mainTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        mainTable.invalidate();
    }

    private void initializeComponents() {
        mainModel = new POInfoTableModel();
        mainTable = new JTable(mainModel) {
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        // Order ID, Surrogate ID, Order date, Status, Product name, Product In Order ID, Quantity, Initial quantity, Storno quantity, Price
        setColumnWidths(mainTable, 100, 70, 100, 70, 200, 70, 70, 70, 70, 100);
        mainTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                if (value instanceof Integer status) {
                    value = OrderResult.statusToString(status);
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        });
        scrollPane = new JScrollPane(mainTable);
        scrollPane.setPreferredSize(new Dimension(900,600));
        add(scrollPane);
    }
}