package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.POInfo;
import ro.sellfluence.emagapi.OrderResult;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class POInfoTableModel extends AbstractTableModel {
    private final String[] columnNames = {
            "Order ID", "Surrogate ID", "Order Date", "Order Status", "Product Name", "Product In Order ID",
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
            case 1 -> poInfo.surrogateId();
            case 2 -> poInfo.orderDate();
            case 3 -> OrderResult.statusToString(poInfo.orderStatus());
            case 4 -> poInfo.productName();
            case 5 -> poInfo.productInOrderId();
            case 6 -> poInfo.quantity();
            case 7 -> poInfo.initialQuantity();
            case 8 -> poInfo.stornoQuantity();
            case 9 -> poInfo.price();
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