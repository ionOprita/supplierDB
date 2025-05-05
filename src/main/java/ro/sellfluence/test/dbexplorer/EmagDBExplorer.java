package ro.sellfluence.test.dbexplorer;

import ch.claudio.db.DB;
import org.jetbrains.annotations.NotNull;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.EmagMirrorDB.POInfo;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumnModel;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

import static javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS;
import static javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS;

public class EmagDBExplorer {
    private static final String database = "emagLocal";

    private static final DB emagDB;
    private static final EmagMirrorDB emagMirrorDB;
    public static final int tableWidth = 1800;
    public static final int tableHeight = 200;

    static {
        try {
            emagDB = new DB(database);
            emagMirrorDB = EmagMirrorDB.getEmagMirrorDB(database);
        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        initGUI();
    }

    private static final JTextField orderField = new JTextField(20);
    private static final JButton orderButton = new JButton("Get Order By Order ID");
    private static final JTextField customerField = new JTextField(20);
    //private static final JComboBox<Integer> customerSelection = new JComboBox<>(new Integer[0]);
    private static final JButton customerButton = new JButton("Get Orders By Customer ID");
    private static final JTextField productField = new JTextField(20);
    private static final JButton productButton = new JButton("Get Product");

    private static final OrderTableModel orderTableModel = new OrderTableModel();
    private static final JTable orderTable = new JTable(orderTableModel);
    private static final CustomerTableModel customerTableModel = new CustomerTableModel();
    private static final JTable customerTable = new JTable(customerTableModel);
    private static final POInfoTableModel poInfoTableModel = new POInfoTableModel();

    private static void initGUI() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Emag DB Browser");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            var outerBox = Box.createVerticalBox();
            frame.setContentPane(outerBox);

            // Line 1: Input Field + Get Order Button
            var line1 = Box.createHorizontalBox();
            line1.add(orderField);
            line1.add(orderButton);
            orderButton.addActionListener(EmagDBExplorer::lookupByOrderId);
            outerBox.add(line1);

            // Line 2: Input Field + Get Customer Button
            var line2 = Box.createHorizontalBox();
            line2.add(customerField);
            //line2.add(customerSelection);
            line2.add(customerButton);
            customerButton.addActionListener(EmagDBExplorer::lookupByCustomerId);
            outerBox.add(line2);

            // Line 3: Input Field + Get Product Button
            var line3 = Box.createHorizontalBox();
            line3.add(productField);
            line3.add(productButton);
//            outerBox.add(line3);

            // Order table
            outerBox.add(setupAndReturnOrderTable());
            // Customer table
            outerBox.add(setupAndReturnCustomerTable());

            var buttonBox = Box.createHorizontalBox();

            var histogramButton = new JButton("Show fetch histogram");
            buttonBox.add(histogramButton);
            histogramButton.addActionListener(EmagDBExplorer::showFetchHistogram);

            var gmvButton = new JButton("Show GMV table");
            buttonBox.add(gmvButton);
            gmvButton.addActionListener(EmagDBExplorer::showGMVTable);

            outerBox.add(buttonBox);

            frame.pack();
            frame.setVisible(true);
        });
    }

    private static @NotNull JScrollPane setupAndReturnCustomerTable() {
        customerTable.setFillsViewportHeight(true);  // Ensure headers are always visible
        // Set preferred column widths
        TableColumnModel columnModel = customerTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(70);  // Customer ID
        columnModel.getColumn(1).setPreferredWidth(180);  // Name
        columnModel.getColumn(2).setPreferredWidth(120);  // Email
        columnModel.getColumn(3).setPreferredWidth(100);  // Phone
        columnModel.getColumn(4).setPreferredWidth(400);  // Billing Info
        columnModel.getColumn(5).setPreferredWidth(500);  // Shipping Info
        columnModel.getColumn(6).setPreferredWidth(150);  // Created
        columnModel.getColumn(7).setPreferredWidth(150);  // Modified

        // Apply custom cell renderer for text wrapping
        TextAreaRenderer textAreaRenderer = new TextAreaRenderer();
        customerTable.getColumnModel().getColumn(4).setCellRenderer(textAreaRenderer);  // Billing Info
        customerTable.getColumnModel().getColumn(5).setCellRenderer(textAreaRenderer);  // Shipping Info

        JScrollPane scrollPane = new JScrollPane(customerTable);
        scrollPane.setPreferredSize(new Dimension(tableWidth, tableHeight));  // Set preferred size for scrolling
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_ALWAYS);
        return scrollPane;
    }

    private static @NotNull JScrollPane setupAndReturnOrderTable() {
        orderTable.setFillsViewportHeight(true);  // Ensure headers are always visible
        // Add mouse listener to the table
        orderTable.addMouseListener(getOrderIdMouseAdapter());
        // Set preferred column widths
        TableColumnModel columnModel = orderTable.getColumnModel();
        columnModel.getColumn(0).setPreferredWidth(100);  // Order ID
        columnModel.getColumn(1).setPreferredWidth(200);  // Vendor ID
        columnModel.getColumn(2).setPreferredWidth(150);  // Vendor Name
        columnModel.getColumn(3).setPreferredWidth(100);  // Customer ID
        columnModel.getColumn(4).setPreferredWidth(50);  // Status
        columnModel.getColumn(5).setPreferredWidth(150);  // Date
        columnModel.getColumn(6).setPreferredWidth(150);  // Created
        columnModel.getColumn(7).setPreferredWidth(150);  // Modified


        JScrollPane scrollPane = new JScrollPane(orderTable);
        scrollPane.setPreferredSize(new Dimension(tableWidth, tableHeight));  // Set preferred size for scrolling
        scrollPane.setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_ALWAYS);
        return scrollPane;
    }

    private static @NotNull MouseAdapter getOrderIdMouseAdapter() {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = orderTable.rowAtPoint(e.getPoint());
                int column = orderTable.columnAtPoint(e.getPoint());
                if (column == 0) { // Order ID column index
                    var orderId = (String) orderTable.getValueAt(row, column);
                    orderField.setText(orderId);
                }
            }
        };
    }

    private static void lookupByOrderId(ActionEvent actionEvent) {
        var orderId = orderField.getText();
        try {
            var orders = emagDB.readTX(db -> OrderRecord.getOrdersById(db, orderId));
            updateTables(orders);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void lookupByCustomerId(ActionEvent actionEvent) {
        var customerId = Integer.parseInt(customerField.getText());
        try {
            var orders = emagDB.readTX(db -> OrderRecord.getOrdersByCustomerId(db, customerId));
            updateTables(orders);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    static JFrame histogramWindow = null;
    static HistogramPanel histogramPanel = null;
    static GMVTable gmvPanel = null;
    static JFrame gmvWindow;
    static POInfoTable poinfoPanel;
    static JFrame poinfoWindow;

    private static void showFetchHistogram(ActionEvent actionEvent) {
        try {
            var data = emagDB.readTX(EmagFetchHistogram::getHistogram);
            if (histogramPanel == null) {
                histogramPanel = new HistogramPanel(data);
            } else {
                histogramPanel.setData(data);
            }
            if (histogramWindow == null) {
                histogramWindow = new JFrame("Fetch log histogram");
                histogramWindow.getContentPane().add(histogramPanel);
            }
            histogramWindow.setVisible(true);
            histogramWindow.pack();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void showGMVTable(ActionEvent actionEvent) {
        try {
            var data = emagDB.readTX(emagMirrorDB::getGMV);
            if (gmvPanel == null) {
                gmvPanel = new GMVTable();
            }
            gmvPanel.updateData(data);
            gmvPanel.setCellListener((productWithID, yearMonth) -> {
                try {
                    var orders = emagMirrorDB.readProductInOrderByProductAndMonth(productWithID.id(), yearMonth);
                    SwingUtilities.invokeLater(() -> showPOInfoTable(productWithID.product().name(), yearMonth, orders));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            if (gmvWindow == null) {
                gmvWindow = new JFrame("GMV Table");
                gmvWindow.getContentPane().add(gmvPanel);
            }
            gmvWindow.setVisible(true);
            gmvWindow.pack();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
    private static void showPOInfoTable(String productName, YearMonth month, List<POInfo> productOrders) {
        if (poinfoPanel == null) {
            poinfoPanel = new POInfoTable();
        }
        var title = "Orders with product %s in month %s".formatted(productName, month);
        poinfoPanel.updateTable(productOrders);
        if (poinfoWindow == null) {
            poinfoWindow = new JFrame(title);
            poinfoWindow.getContentPane().add(poinfoPanel);
        }
        poinfoWindow.setTitle(title);
        poinfoWindow.setVisible(true);
        poinfoWindow.invalidate();
        poinfoWindow.repaint();
        poinfoWindow.pack();
    }


    private static void updateTables(List<OrderRecord> orders) {
        orderTableModel.updateOrders(orders);
        var customerIds = orders.stream().map(OrderRecord::customerId).collect(Collectors.toSet());
        var customers = customerIds.stream().flatMap(customerId -> {
            try {
                return emagDB.readTX(db -> CustomerRecord.getCustomerById(db, customerId)).stream();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).toList();
        customerTableModel.updateCustomers(customers);
        if (customerIds.size() == 1) {
            customerField.setText(customerIds.iterator().next().toString());
        }
    }

    private static void lookupByProductId(ActionEvent actionEvent) {
    }
}