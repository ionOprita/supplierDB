package ro.koppel.supplierDB;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Currency;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ro.koppel.supplierDB.HelperMethods.buildScrapingAntURL;

@Component
public class FetchSupplierDetails {

    private final Logger logger = LoggerFactory.getLogger(FetchSupplierDetails.class);

    @Autowired
    SearchRepository searchRepository;
    @Autowired
    SupplierRepository supplierRepository;

    record Range(int min, int max, String unit, Currency currency) {

        private static final Pattern rangePattern = Pattern.compile("(.*?)\\s*([\\d,]+)\\s+to\\s+([\\d,]+)\\s*(.*?)");
        private static final Pattern singleValuePattern = Pattern.compile("(.*?)\\s*([\\d,]+)\\s*(.*?)");

        /**
         * Tries to convert the string into the structured Range type.
         *
         * @param toBeParsed string that has to be parsed. It is assumed the string was already trimmed.
         * @return Range object or null if parsing was not possible.
         */
        static Range fromString(String toBeParsed) {
            Matcher matcher = rangePattern.matcher(toBeParsed);
            if (matcher.matches()) {
                String cur = matcher.group(1);
                String min = matcher.group(2);
                String max = matcher.group(3);
                String unit = matcher.group(4);
                return new Range(toInteger(min), toInteger(max), toUnit(unit), toCurrency(cur));
            } else {
                matcher = singleValuePattern.matcher(toBeParsed);
                if (matcher.matches()) {
                    String cur = matcher.group(1);
                    String val = matcher.group(2);
                    String unit = matcher.group(3);
                    return new Range(toInteger(val), toInteger(val), toUnit(unit), toCurrency(cur));
                } else {
                    return null;
                }
            }
        }

        static Currency toCurrency(String currency) {
            if (currency.isBlank()) return null;
            if ("US$".equals(currency)) return Currency.getInstance("USD");
            return Currency.getInstance(currency);
        }

        static int toInteger(String number) {
            return Integer.parseInt(number.replace(",", ""));
        }

        static String toUnit(String unit) {
            if (unit.isBlank()) return null;
            if (unit.equalsIgnoreCase("square metre")) return "m²";
            return unit;
        }
    }


    public void retrieveAndStoreSupplier(String supplierUrl, String searchTerm) throws IOException {
        var search = searchRepository.findBySearchTerms(searchTerm);
        if (search == null) {
            search = new Search();
            search.searchTerms = searchTerm;
            searchRepository.save(search);
        }
        var supplier = supplierRepository.findByLink(supplierUrl);
        if (supplier != null) {
            logger.atDebug().log("Supplier with the link {} is already in the database, thus ignoring it.", supplierUrl);
            if (supplier.searchList.add(search)) {
                logger.atDebug().log("But not yet with search '{}', thus adding search and updating in DB", searchTerm);
                supplierRepository.save(supplier);
                search.supplierList.add(supplier);
                searchRepository.save(search);
            }
        } else {
            addSupplier(supplierUrl, search);
        }
    }

    private void addSupplier(String supplierUrl, Search search) throws IOException {
        var profileUrl = supplierUrl.replace("homepage_", "company-profile_");
        Document document = Jsoup.connect(buildScrapingAntURL(profileUrl)).timeout(2 * 60 * 1000).get();
        Elements names = document.getElementsByClass("supplier-name");
        if (names.size() == 0) {
            logger.atWarn().log("The supplier name was not found on {}. Ignoring this entry.", profileUrl);
            return;
        }
        if (names.size() > 1) {
            logger.atWarn().log("More than one supplier name found on {}. Only the first is used.", profileUrl);
        }
        var supplierName = names.get(0).text();
        Supplier supplier = supplierRepository.findByName(supplierName);
        if (supplier == null) {
            supplier = new Supplier();
            supplier.name = supplierName;
        }
        Elements verifiedTags = document.getElementsByClass("vImg");
        var tableLabels = document.getElementsByClass("table-label");
        supplier.link = supplierUrl;
        supplier.verified = verifiedTags.size() > 0;  // TODO: Maybe needs improvement
        for (Element label : tableLabels) {
            var value = label.nextElementSibling();
            switch (label.text()) {
                case "Total Annual Sales" -> {
                    var range = Range.fromString(value.text().trim());
                    if (range != null) {
                        supplier.minAnnualSales = range.min;
                        supplier.maxAnnualSales = range.max;
                    }
                }
                case "Total Employees" -> {
                    var range = Range.fromString(value.text().trim());
                    if (range != null) {
                        supplier.minEmployees = range.min;
                        supplier.maxEmployees = range.max;
                    }
                }
                case "Number of QC Staff" -> {
                    var range = Range.fromString(value.text().trim());
                    if (range != null) {
                        supplier.minQcStaff = range.min;
                        supplier.maxQcStaff = range.max;
                    }
                }
                case "Number of R & D Staff" -> {
                    var range = Range.fromString(value.text().trim());
                    if (range != null) {
                        supplier.minRnDStaff = range.min;
                        supplier.maxRnDStaff = range.max;
                    }
                }
                case "Year Established" -> {
                    var range = Range.fromString(value.text().trim());
                    if (range != null) {
                        supplier.yearEstablished = range.min;
                    }
                }
                case "Factory Size" -> {
                    var range = Range.fromString(value.text().trim());
                    if (range != null) {
                        supplier.factorySize = range.min;
                    }
                }
                case "No. of Production Lines" -> {
                    var range = Range.fromString(value.text().trim());
                    if (range != null) {
                        supplier.productionLines = range.min;
                    }
                }
            }
        }
        supplier.searchList.add(search);
        search.supplierList.add(supplier);
        supplierRepository.save(supplier);
        searchRepository.save(search);
    }
}
