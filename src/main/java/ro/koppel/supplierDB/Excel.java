package ro.koppel.supplierDB;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class Excel {

    private final Logger logger = LoggerFactory.getLogger(Excel.class);
    @Autowired
    SearchRepository searchRepository;

    private static final String[] headers = {
            "Name", "URL", "Verified"
            , "Year established", "Factory Size", "Production Lines"
            , "Min Employees", "Max Employees"
            , "Min QC Staff", "Max QC Staff"
            , "Min R&D Staff", "Max R&D Staff"
            , "Min Annual Sales", "Max Annual Sales"
    };

    public void createExcel(Path path) {
        // Create a new workbook
        try (Workbook workbook = new XSSFWorkbook()) {
            addOrUpdateSheetToExcel(workbook);
            // Save to a file
            try (OutputStream outputStream = Files.newOutputStream(path)) {
                workbook.write(outputStream);
                logger.atWarn().log("Excel file {} created successfully.", path);
            }
        } catch (IOException e) {
            logger.atError().log("Error creating Excel file {}: {}", new Object[]{path, e.getMessage()});
        }
    }

    public void updateExcel(Path path) {
        // Create workbook from Excel file.
        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
            addOrUpdateSheetToExcel(workbook);
            // Save to a file
            try (OutputStream outputStream = Files.newOutputStream(path)) {
                workbook.write(outputStream);
                logger.atWarn().log("Excel file {} created successfully.", path);
            }
        } catch (IOException e) {
            logger.atError().log("Error creating Excel file {}: {}", new Object[]{path, e.getMessage()});
        }
    }

    private void addOrUpdateSheetToExcel(Workbook workbook) {
        var searchTerms = searchRepository.findAll();
        for (Search searchTerm : searchTerms) {
            var sheetName = searchTerm.searchTerms;
            var sheet = workbook.getSheet(sheetName);
            int rowIndex;
            if (sheet == null) {
                sheet = workbook.createSheet(sheetName);
                createHeader(sheet);
                rowIndex = 0;
            } else {
                rowIndex = sheet.getLastRowNum();
            }
//            var suppliers = searchTerm.supplierList;
//            //TODO: check if supplier already on sheet
//            for (Supplier supplier : suppliers) {
//                rowIndex++;
//                addRow(sheet, rowIndex, supplier);
//            }
        }
    }


    private static void addRow(Sheet sheet, int rowIndex, Supplier supplier) {
        Row row = sheet.createRow(rowIndex + 1);
        var columnIndex = 0;
        var cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.name);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.link);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.verified);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.yearEstablished);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.factorySize);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.productionLines);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.minEmployees);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.maxEmployees);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.minQcStaff);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.maxQcStaff);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.minRnDStaff);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.maxRnDStaff);
        cell = row.createCell(columnIndex++);
        cell.setCellValue(supplier.minAnnualSales);
        cell = row.createCell(columnIndex);
        cell.setCellValue(supplier.maxAnnualSales);
    }

    private static void createHeader(Sheet sheet) {
        Row headerRow = sheet.createRow(0);

        // Fill in the header row with column names
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }
    }
}
