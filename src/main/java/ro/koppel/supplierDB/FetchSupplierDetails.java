package ro.koppel.supplierDB;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;

public class FetchSupplierDetails {
    public static void main(String[] args) throws IOException {
        var supplierUrl = "https://smartwatch-n.manufacturer.globalsources.com/homepage_6008850873897.htm";
        var profileUrl = supplierUrl.replace("homepage_","company-profile_");
        Document document = Jsoup.connect(profileUrl).timeout(2 * 60 * 1000).get();
        var tableLabels =document.getElementsByClass("table-label");
        for (Element label:tableLabels) {
            var isThisTheValue = label.nextElementSibling();
            System.out.println("==>"+label.text().trim()+"<==");
            System.out.println("==>"+isThisTheValue.text().trim()+"<==");
        }
        //, "table-value"
    }
}
