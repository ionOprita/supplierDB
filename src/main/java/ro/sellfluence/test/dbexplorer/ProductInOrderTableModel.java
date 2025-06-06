package ro.sellfluence.test.dbexplorer;

import ro.sellfluence.db.EmagMirrorDB.POInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class ProductInOrderTableModel extends AbstractTableModel {
    private final String[] columnNames = {
            "Product ID", "PNK", "Product Name",
            "Quantity", "Initial Quantity", "Storno Quantity", "Price"
    };
    private List<ProductInOrderRecord> poInfos = new ArrayList<>();

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
        ProductInOrderRecord poInfo = poInfos.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> poInfo.externalId();
            case 1 -> poInfo.initialQuantity();
            case 2 -> poInfo.initialQuantity();
            case 3 -> poInfo.initialQuantity();
            case 4 -> poInfo.quantity();
            case 5 -> poInfo.initialQuantity();
            case 6 -> poInfo.stornoQuantity();
            case 7 -> poInfo.initialQuantity();
            default -> null;
        };
    }

    @Override
    public String getColumnName(int columnIndex) {
        return columnNames[columnIndex];
    }

    public void updatePOInfos(List<POInfo> newPOInfos) {
      //  this.poInfos = newPOInfos;
        fireTableDataChanged();  // Notify the table that the data has changed
    }
}