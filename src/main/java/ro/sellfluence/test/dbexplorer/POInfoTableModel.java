package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.EmagMirrorDB.POInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class POInfoTableModel extends AbstractTableModel {
    private final String[] columnNames = {
            "Order ID", "Order Date", "Order Status", "Product Name",
            "Quantity", "Initial Quantity", "Storno Quantity", "Price"
    };
    private List<POInfo> poInfos = new ArrayList<>();

    @Override
    public int getRowCount() {
        return poInfos.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= poInfos.size()) {
            return null;
        }
        POInfo poInfo = poInfos.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> poInfo.orderId();
            case 1 -> poInfo.orderDate();
            case 2 -> poInfo.orderStatus();
            case 3 -> poInfo.productName();
            case 4 -> poInfo.quantity();
            case 5 -> poInfo.initialQuantity();
            case 6 -> poInfo.stornoQuantity();
            case 7 -> poInfo.price();
            default -> null;
        };
    }

    @Override
    public String getColumnName(int columnIndex) {
        return columnNames[columnIndex];
    }

    public void updatePOInfos(List<POInfo> newPOInfos) {
        this.poInfos = newPOInfos;
        fireTableDataChanged();  // Notify the table that the data has changed
    }
}