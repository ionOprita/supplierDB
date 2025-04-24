package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmagMirrorDB.ProductWithID;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.Component;
import java.awt.Dimension;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class POInfoTable extends JPanel {

    private JTable mainTable;
    private JScrollPane scrollPane;

    private POInfoTableModel mainModel;

    private List<YearMonth> sortedMonths = new ArrayList<>();
    private List<ProductWithID> sortedProducts = new ArrayList<>();
    private BiFunction<ProductWithID, YearMonth, Void> cellListener;

    public POInfoTable() {
        initializeComponents();
        setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
    }

    public void updateTable(List<EmagMirrorDB.POInfo> data) {
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
        // Set preferred column widths
        TableColumnModel columnModel = mainTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(100);  // Order ID
        columnModel.getColumn(1).setPreferredWidth(100);  // Order date
        columnModel.getColumn(2).setPreferredWidth(100);  // Order status
        columnModel.getColumn(3).setPreferredWidth(200);  // Product name
        columnModel.getColumn(4).setPreferredWidth(100);  // Quantity
        columnModel.getColumn(5).setPreferredWidth(100);  // Initial quantity
        columnModel.getColumn(6).setPreferredWidth(100);  // Storno quantity
        columnModel.getColumn(7).setPreferredWidth(100);  // Price
        columnModel.getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                if (value instanceof Integer status) {
                    value = switch (status) {
                        case 4 -> "Finalized";
                        case 5 -> "Storno";
                        default -> "%d".formatted(status);
                    };
                }
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
        });
        scrollPane = new JScrollPane(mainTable);
        scrollPane.setPreferredSize(new Dimension(900,600));
        add(scrollPane);
    }
}