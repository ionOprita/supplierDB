package ro.koppel.supplierDB;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Currency;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.logging.Level.WARNING;
import static ro.koppel.supplierDB.HelperMethods.buildScrapingAntURL;

@Component
public class FetchSupplierDetails {

    private static final Logger logger = Logger.getLogger(FetchSupplierDetails.class.getName());

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
        var profileUrl = supplierUrl.replace("homepage_", "company-profile_");
        Document document = Jsoup.connect(buildScrapingAntURL(profileUrl)).timeout(2 * 60 * 1000).get();
        Elements names = document.getElementsByClass("supplier-name");
        if (names.size() == 0) {
            logger.log(WARNING, "Supplier name was not found on %s. Ignoring this entry.".formatted(profileUrl));
            return;
        }
        if (names.size() > 1) {
            logger.log(WARNING, "More than one supplier name found on %s. Only the first is used.".formatted(profileUrl));
        }
        var supplierName = names.get(0).text();
        Supplier supplier = supplierRepository.findByName(supplierName);
        if (supplier == null) {
            supplier = new Supplier();
            supplier.name = supplierName;
        }
        Elements verifiedTags = document.getElementsByClass("vImg");
        var tableLabels = document.getElementsByClass("table-label");
        supplier.link = URI.create(supplierUrl).toURL();
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
                        supplier.minRnDStuff = range.min;
                        supplier.maxRnDStuff = range.max;
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
        Search search = searchRepository.findBySearchTerms(searchTerm);
        if (search == null) {
            search = new Search();
            search.searchTerms = searchTerm;
            searchRepository.save(search);
        }
        supplier.searchList.add(search);
        search.supplierList.add(supplier);
        supplierRepository.save(supplier);
        searchRepository.save(search);
    }
}
